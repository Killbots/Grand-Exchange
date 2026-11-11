package dev.grandexchange;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OfferService {
    private final ExchangePlugin plugin;

    public OfferService(ExchangePlugin plugin) {
        this.plugin = plugin;
    }

    public long createSellOffer(Player seller, ItemStack escrowedItem, BigDecimal unitPrice) throws SQLException {
        return createSellOffers(seller, List.of(escrowedItem), List.of(unitPrice)).get(0);
    }

    public List<Long> createSellOffers(Player seller, List<ItemStack> items, List<BigDecimal> unitPrices)
            throws SQLException {
        if (items.isEmpty() || items.size() != unitPrices.size()) {
            throw new IllegalArgumentException("Sell offers require one price for every item stack.");
        }
        try (Connection connection = plugin.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                int slotLimit = sellSlotLimit(seller);
                int openOffers = countOpenSellOffers(connection, seller.getUniqueId());
                if (openOffers + items.size() > slotLimit) {
                    throw new OfferException("This batch needs " + items.size() + " sell slots, but you only have "
                            + Math.max(0, slotLimit - openOffers) + " free. Cancel offers or unlock more slots.");
                }
                List<Long> ids = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ge_offers
                          (owner_uuid, owner_name, side, material, item_data, quantity, remaining, unit_price,
                           status, origin_server)
                        VALUES (?, ?, 'SELL', ?, ?, ?, ?, ?, 'OPEN', ?)
                        """, PreparedStatement.RETURN_GENERATED_KEYS)) {
                    for (int index = 0; index < items.size(); index++) {
                        ItemStack item = items.get(index);
                        BigDecimal unitPrice = unitPrices.get(index);
                        if (item == null || item.getType().isAir() || unitPrice == null || unitPrice.signum() <= 0) {
                            throw new IllegalArgumentException("Sell offers require non-empty items and positive prices.");
                        }
                        int quantity = item.getAmount();
                        statement.setString(1, seller.getUniqueId().toString());
                        statement.setString(2, seller.getName());
                        statement.setString(3, item.getType().name());
                        statement.setBytes(4, item.serializeAsBytes());
                        statement.setInt(5, quantity);
                        statement.setInt(6, quantity);
                        statement.setBigDecimal(7, unitPrice);
                        statement.setString(8, plugin.serverId());
                        statement.executeUpdate();
                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new SQLException("No listing id was generated.");
                            }
                            ids.add(keys.getLong(1));
                        }
                    }
                }
                connection.commit();
                return ids;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }
    }

    public List<OwnedOffer> listOwned(Player owner) throws SQLException {
        try (Connection connection = plugin.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, item_data, remaining, unit_price
                     FROM ge_offers
                     WHERE owner_uuid = ? AND side = 'SELL' AND status = 'OPEN' AND remaining > 0
                     ORDER BY created_at ASC, id ASC
                     """)) {
            statement.setString(1, owner.getUniqueId().toString());
            try (ResultSet result = statement.executeQuery()) {
                List<OwnedOffer> offers = new ArrayList<>();
                while (result.next()) {
                    offers.add(new OwnedOffer(result.getLong("id"),
                            ItemStack.deserializeBytes(result.getBytes("item_data")),
                            result.getInt("remaining"), result.getBigDecimal("unit_price")));
                }
                return offers;
            }
        }
    }

    public int sellSlotLimit(Player player) {
        int hardCap = Math.max(1, plugin.getConfig().getInt("market.max-open-offers-per-player", 20));
        int limit = Math.min(hardCap,
                Math.max(1, plugin.getConfig().getInt("market.default-sell-slots", 8)));
        for (int tier : plugin.getConfig().getIntegerList("market.sell-slot-tiers")) {
            if (tier > limit && player.hasPermission("grandexchange.slots." + tier)) {
                limit = Math.min(hardCap, tier);
            }
        }
        return limit;
    }

    private int countOpenSellOffers(Connection connection, UUID ownerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM ge_offers
                WHERE owner_uuid = ? AND side = 'SELL' AND status = 'OPEN' AND remaining > 0
                """)) {
            statement.setString(1, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    public List<OfferListing> list(Material material, int limit) throws SQLException {
        String sql = "SELECT id, owner_uuid, owner_name, material, quantity, remaining, unit_price, "
                + "origin_server, created_at "
                + "FROM ge_offers WHERE side = 'SELL' AND status = 'OPEN' AND remaining > 0 ";
        if (material != null) {
            sql += "AND material = ? ";
        }
        sql += "ORDER BY unit_price ASC, created_at ASC LIMIT ?";
        try (Connection connection = plugin.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (material != null) {
                statement.setString(index++, material.name());
            }
            statement.setInt(index, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<OfferListing> listings = new ArrayList<>();
                while (result.next()) {
                    listings.add(new OfferListing(
                            result.getLong("id"),
                            UUID.fromString(result.getString("owner_uuid")),
                            result.getString("owner_name"),
                            Material.valueOf(result.getString("material")),
                            result.getInt("remaining"),
                            result.getBigDecimal("unit_price"),
                            result.getString("origin_server"),
                            result.getTimestamp("created_at").toInstant()
                    ));
                }
                return listings;
            }
        }
    }

    public List<OfferListing> listMatching(ItemStack item, int limit) throws SQLException {
        String sql = "SELECT id, owner_uuid, owner_name, material, item_data, remaining, unit_price, "
                + "origin_server, created_at "
                + "FROM ge_offers WHERE side = 'SELL' AND status = 'OPEN' AND remaining > 0 "
                + "AND material = ? ORDER BY unit_price ASC, created_at ASC LIMIT 500";
        try (Connection connection = plugin.dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, item.getType().name());
            try (ResultSet result = statement.executeQuery()) {
                List<OfferListing> listings = new ArrayList<>();
                while (result.next() && listings.size() < limit) {
                    ItemStack listedItem = ItemStack.deserializeBytes(result.getBytes("item_data"));
                    if (!listedItem.isSimilar(item)) {
                        continue;
                    }
                    listings.add(new OfferListing(
                            result.getLong("id"),
                            UUID.fromString(result.getString("owner_uuid")),
                            result.getString("owner_name"),
                            Material.valueOf(result.getString("material")),
                            result.getInt("remaining"),
                            result.getBigDecimal("unit_price"),
                            result.getString("origin_server"),
                            result.getTimestamp("created_at").toInstant()
                    ));
                }
                return listings;
            }
        }
    }

    public PurchaseResult buy(Player buyer, long listingId) throws SQLException {
        ItemStack item = null;
        Player seller = null;
        BigDecimal total = BigDecimal.ZERO;
        try (Connection connection = plugin.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            boolean buyerWithdrawn = false;
            boolean sellerPaid = false;
            try {
                OfferRow offer = selectOpenOffer(connection, listingId);
                if (offer == null) {
                    throw new OfferException("That listing is no longer available.");
                }
                if (offer.ownerId().equals(buyer.getUniqueId())) {
                    throw new OfferException("You can't buy your own listing.");
                }
                seller = Bukkit.getPlayer(offer.ownerId());
                item = ItemStack.deserializeBytes(offer.itemData());
                if (item.getAmount() != offer.quantity()) {
                    throw new OfferException("This listing has an invalid item quantity; contact an admin.");
                }
                if (buyer.getInventory().firstEmpty() < 0) {
                    throw new OfferException("Free one inventory slot before buying this listing.");
                }
                total = offer.unitPrice().multiply(BigDecimal.valueOf(offer.quantity()));
                if (plugin.economy() == null || plugin.economy().getBalance(buyer) < total.doubleValue()) {
                    throw new OfferException("You don't have enough money for this listing.");
                }

                EconomyResponse withdraw = plugin.economy().withdrawPlayer(buyer, total.doubleValue());
                if (!withdraw.transactionSuccess()) {
                    throw new OfferException("Payment failed: " + withdraw.errorMessage);
                }
                buyerWithdrawn = true;

                if (seller != null) {
                    EconomyResponse deposit = plugin.economy().depositPlayer(seller, total.doubleValue());
                    if (!deposit.transactionSuccess()) {
                        refundBuyer(buyer, total, "seller deposit failed");
                        buyerWithdrawn = false;
                        throw new OfferException("Payment to the seller failed; no listing was completed.");
                    }
                    sellerPaid = true;
                }

                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE ge_offers SET status = 'SOLD', remaining = 0 "
                                + "WHERE id = ? AND status = 'OPEN'")) {
                    update.setLong(1, listingId);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Listing state changed during purchase.");
                    }
                }
                insertTrade(connection, listingId, buyer.getUniqueId(), offer.ownerId(),
                        offer.material(), offer.quantity(), offer.unitPrice(), plugin.serverId());
                if (seller == null) {
                    insertPendingPayout(connection, offer.ownerId(), listingId, total);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                if (sellerPaid) {
                    EconomyResponse reversal = plugin.economy().withdrawPlayer(seller, total.doubleValue());
                    if (!reversal.transactionSuccess()) {
                        plugin.getLogger().severe("Could not reverse seller payment for listing " + listingId + ".");
                    }
                }
                if (buyerWithdrawn) {
                    refundBuyer(buyer, total, "listing purchase rolled back");
                }
                throw exception;
            }
        } catch (OfferException exception) {
            throw exception;
        }

        if (seller != null) {
            seller.sendMessage(org.bukkit.ChatColor.GREEN + "Your listing #" + listingId + " sold: "
                    + item.getAmount() + " x " + item.getType().name() + " for "
                    + plugin.economy().format(total.doubleValue()) + ".");
        }
        java.util.Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> buyer.getWorld().dropItemNaturally(buyer.getLocation(), leftover));
        return new PurchaseResult(item.getType(), item.getAmount(), total);
    }

    public ItemStack cancel(Player owner, long listingId) throws SQLException {
        try (Connection connection = plugin.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                OfferRow offer = selectOpenOffer(connection, listingId);
                if (offer == null) {
                    throw new OfferException("That listing is no longer available.");
                }
                if (!offer.ownerId().equals(owner.getUniqueId())) {
                    throw new OfferException("You can only cancel your own listings.");
                }
                ItemStack item = ItemStack.deserializeBytes(offer.itemData());
                if (owner.getInventory().firstEmpty() < 0) {
                    throw new OfferException("Free one inventory slot before cancelling this listing.");
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE ge_offers SET status = 'CANCELLED', remaining = 0 "
                                + "WHERE id = ? AND status = 'OPEN'")) {
                    update.setLong(1, listingId);
                    if (update.executeUpdate() != 1) {
                        throw new SQLException("Listing state changed during cancellation.");
                    }
                }
                connection.commit();
                return item;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        }
    }

    private OfferRow selectOpenOffer(Connection connection, long listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuid, material, item_data, quantity, remaining, unit_price "
                        + "FROM ge_offers WHERE id = ? AND side = 'SELL' AND status = 'OPEN' FOR UPDATE")) {
            statement.setLong(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new OfferRow(
                        UUID.fromString(result.getString("owner_uuid")),
                        Material.valueOf(result.getString("material")),
                        result.getBytes("item_data"),
                        result.getInt("remaining"),
                        result.getBigDecimal("unit_price")
                );
            }
        }
    }

    private void insertTrade(Connection connection, long offerId, UUID buyer, UUID seller, Material material,
                             int quantity, BigDecimal unitPrice, String transactionServer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ge_trades
                  (offer_id, buyer_uuid, seller_uuid, material, quantity, unit_price, transaction_server)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, offerId);
            statement.setString(2, buyer.toString());
            statement.setString(3, seller.toString());
            statement.setString(4, material.name());
            statement.setInt(5, quantity);
            statement.setBigDecimal(6, unitPrice);
            statement.setString(7, transactionServer);
            statement.executeUpdate();
        }
    }

    private void insertPendingPayout(Connection connection, UUID playerId, long offerId, BigDecimal amount)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ge_payout_queue (player_uuid, offer_id, amount)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, playerId.toString());
            statement.setLong(2, offerId);
            statement.setBigDecimal(3, amount);
            statement.executeUpdate();
        }
    }

    private void refundBuyer(Player buyer, BigDecimal total, String reason) {
        EconomyResponse refund = plugin.economy().depositPlayer(buyer, total.doubleValue());
        if (!refund.transactionSuccess()) {
            plugin.getLogger().severe("Could not refund buyer after " + reason + ".");
        }
    }

    public record OfferListing(long id, UUID sellerId, String sellerName, Material material,
                               int quantity, BigDecimal unitPrice, String originServer, Instant createdAt) {
    }

    public record OwnedOffer(long id, ItemStack item, int quantity, BigDecimal unitPrice) {
    }

    public record PurchaseResult(Material material, int quantity, BigDecimal total) {
    }

    public static final class OfferException extends RuntimeException {
        public OfferException(String message) {
            super(message);
        }
    }

    private record OfferRow(UUID ownerId, Material material, byte[] itemData, int quantity, BigDecimal unitPrice) {
    }
}
