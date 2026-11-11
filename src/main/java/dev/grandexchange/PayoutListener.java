package dev.grandexchange;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class PayoutListener implements Listener {
    private final ExchangePlugin plugin;

    public PayoutListener(ExchangePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> processPayouts(event.getPlayer()), 1L);
    }

    private void processPayouts(Player player) {
        if (plugin.economy() == null || !player.isOnline()) {
            return;
        }
        List<Payout> credited = new ArrayList<>();
        try (Connection connection = plugin.dataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<Payout> pending = loadPending(connection, player);
                for (Payout payout : pending) {
                    EconomyResponse response = plugin.economy().depositPlayer(player, payout.amount().doubleValue());
                    if (!response.transactionSuccess()) {
                        plugin.getLogger().warning("Could not credit offline seller payout #"
                                + payout.offerId() + " for " + player.getUniqueId() + ": " + response.errorMessage);
                        continue;
                    }
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM ge_payout_queue WHERE id = ? AND player_uuid = ?")) {
                        delete.setLong(1, payout.id());
                        delete.setString(2, player.getUniqueId().toString());
                        delete.executeUpdate();
                    }
                    credited.add(payout);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not load offline seller payouts for "
                    + player.getUniqueId() + ": " + exception.getMessage());
            return;
        }

        for (Payout payout : credited) {
            player.sendMessage(ChatColor.GREEN + "Your listing #" + payout.offerId()
                    + " sold while you were offline. "
                    + plugin.economy().format(payout.amount().doubleValue()) + " has been credited.");
        }
    }

    private List<Payout> loadPending(Connection connection, Player player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, offer_id, amount FROM ge_payout_queue
                WHERE player_uuid = ? ORDER BY id FOR UPDATE
                """)) {
            statement.setString(1, player.getUniqueId().toString());
            try (ResultSet result = statement.executeQuery()) {
                List<Payout> payouts = new ArrayList<>();
                while (result.next()) {
                    payouts.add(new Payout(result.getLong("id"), result.getLong("offer_id"),
                            result.getBigDecimal("amount")));
                }
                return payouts;
            }
        }
    }

    private record Payout(long id, long offerId, BigDecimal amount) {
    }
}
