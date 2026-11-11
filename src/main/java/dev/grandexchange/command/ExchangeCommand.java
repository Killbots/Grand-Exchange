package dev.grandexchange.command;

import dev.grandexchange.ExchangePlugin;
import dev.grandexchange.MarketQuote;
import dev.grandexchange.MarketStore;
import dev.grandexchange.OfferService;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExchangeCommand implements CommandExecutor, TabCompleter {
    private final ExchangePlugin plugin;
    private final MarketStore marketStore;
    private final OfferService offerService;

    public ExchangeCommand(ExchangePlugin plugin) {
        this.plugin = plugin;
        this.marketStore = new MarketStore(plugin.dataSource());
        this.offerService = new OfferService(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!hasExchangePermission(sender, "use")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use the exchange.");
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.exchangeMenu().open(player);
            } else {
                sender.sendMessage(ChatColor.RED + "Use /exchange help from console.");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("gui") && args.length == 1) {
            if (sender instanceof Player player) {
                plugin.exchangeMenu().open(player);
            } else {
                sender.sendMessage(ChatColor.RED + "Only players can open the exchange menu.");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            handleReload(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.GOLD + "Grand Exchange");
            sender.sendMessage(ChatColor.YELLOW + "/exchange" + ChatColor.GRAY + " - open the exchange menu");
            sender.sendMessage(ChatColor.YELLOW + "/exchange reload" + ChatColor.GRAY
                    + " - reload live configuration (admin)");
            sender.sendMessage(ChatColor.YELLOW + "/exchange custom add <key> <baseline-price>" + ChatColor.GRAY
                    + " - add the item in your hand to the Custom Items page (admin)");
            sender.sendMessage(ChatColor.YELLOW + "/exchange custom remove <key>" + ChatColor.GRAY
                    + " - remove a custom item (admin)");
            sender.sendMessage(ChatColor.YELLOW + "/exchange price <item>" + ChatColor.GRAY
                    + " - view the market quote and recent trade activity");
            sender.sendMessage(ChatColor.YELLOW + "/exchange baseline <item> [price|reset]" + ChatColor.GRAY
                    + " - view or change an item baseline (admin)");
            sender.sendMessage(ChatColor.YELLOW + "/exchange sell <unit-price> [amount]" + ChatColor.GRAY
                    + " - list the item in your main hand");
            sender.sendMessage(ChatColor.YELLOW + "/exchange orders [item]" + ChatColor.GRAY
                    + " - browse sell listings");
            sender.sendMessage(ChatColor.YELLOW + "/exchange buy <listing-id>" + ChatColor.GRAY
                    + " - buy a complete listing");
            sender.sendMessage(ChatColor.YELLOW + "/exchange cancel <listing-id>" + ChatColor.GRAY
                    + " - cancel your listing");
            return true;
        }
        if (args[0].equalsIgnoreCase("baseline")) {
            handleBaseline(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("custom")) {
            handleCustom(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("sell")) {
            handleSell(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("orders")) {
            handleOrders(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("buy")) {
            handleBuy(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("cancel")) {
            handleCancel(sender, args);
            return true;
        }
        if (args[0].equalsIgnoreCase("price") && (args.length == 1 || args.length == 2)) {
            Material material;
            ItemStack heldItem = null;
            if (args.length == 1) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "Console usage: /exchange price <item>");
                    return true;
                }
                heldItem = player.getInventory().getItemInMainHand();
                material = heldItem.getType();
                if (material.isAir()) {
                    sender.sendMessage(ChatColor.RED + "Hold an item in your main hand first.");
                    return true;
                }
            } else {
                material = parseMaterial(args[1]);
            }
            if (material == null || !material.isItem()) {
                sender.sendMessage(ChatColor.RED + "Unknown item: " + (args.length == 2 ? args[1] : ""));
                return true;
            }
            BigDecimal customBaseline = heldItem == null ? null : plugin.customItems().baselineFor(heldItem);
            sender.sendMessage(ChatColor.GRAY + "Loading market quote for "
                    + ChatColor.WHITE + material.name().toLowerCase(Locale.ROOT) + ChatColor.GRAY + "...");
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    MarketQuote quote = marketStore.getQuote(material);
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> sendQuote(sender, material, quote, customBaseline));
                } catch (SQLException exception) {
                    plugin.getLogger().warning("Market quote query failed: " + exception.getMessage());
                    plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                            ChatColor.RED + "The market is temporarily unavailable."));
                }
            });
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /exchange price <item>");
        return true;
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (!hasExchangePermission(sender, "admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to reload the exchange.");
            return;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /exchange reload");
            return;
        }
        boolean restartRequired = plugin.reloadLiveConfiguration();
        sender.sendMessage(ChatColor.GREEN + "GrandExchange live settings and data files reloaded.");
        if (restartRequired) {
            sender.sendMessage(ChatColor.YELLOW + "Storage or server-id changes need a restart; "
                    + "the running values remain active until then.");
        }
    }

    private void sendQuote(CommandSender sender, Material material, MarketQuote quote, BigDecimal customBaseline) {
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Market: "
                + ChatColor.WHITE + material.name().toLowerCase(Locale.ROOT));
        sender.sendMessage(ChatColor.YELLOW + "Best buy offer: " + price(quote.bestBid()));
        sender.sendMessage(ChatColor.YELLOW + "Best sell offer: " + price(quote.bestAsk()));
        sender.sendMessage(ChatColor.YELLOW + "Last trade: " + price(quote.lastPrice()));
        sender.sendMessage(ChatColor.YELLOW + "24h average: " + price(quote.averagePrice24h())
                + ChatColor.GRAY + " (" + quote.volume24h() + " traded)");

        BigDecimal baseline = customBaseline == null ? plugin.baselinePrices().get(material) : customBaseline;
        if (baseline != null) {
            BigDecimal observed = quote.averagePrice24h() != null ? quote.averagePrice24h() : quote.lastPrice();
            double minimumMultiplier = Math.max(0, plugin.getConfig()
                    .getDouble("market.minimum-price-multiplier", 0.5));
            double maximumMultiplier = Math.max(minimumMultiplier, plugin.getConfig()
                    .getDouble("market.maximum-price-multiplier", 2.0));
            BigDecimal floor = baseline.multiply(BigDecimal.valueOf(minimumMultiplier));
            BigDecimal ceiling = baseline.multiply(BigDecimal.valueOf(maximumMultiplier));
            BigDecimal indicative = observed == null ? baseline : observed.max(floor).min(ceiling);
            sender.sendMessage(ChatColor.YELLOW + "Baseline: " + price(baseline));
            sender.sendMessage(ChatColor.YELLOW + "Allowed price band: " + price(floor)
                    + ChatColor.GRAY + " - " + price(ceiling));
            sender.sendMessage(ChatColor.YELLOW + "Indicative price: " + price(indicative)
                    + (observed == null ? ChatColor.GRAY + " (baseline; no trades yet)" : ""));
        }
    }

    private void handleBaseline(CommandSender sender, String[] args) {
        if (!hasExchangePermission(sender, "admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to change market baselines.");
            return;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /exchange baseline <item> [price|reset]");
            return;
        }
        Material material = parseMaterial(args[1]);
        if (material == null || !material.isItem()) {
            sender.sendMessage(ChatColor.RED + "Unknown item: " + args[1]);
            return;
        }
        if (args.length == 2) {
            sender.sendMessage(ChatColor.YELLOW + material.name() + " baseline: "
                    + price(plugin.baselinePrices().get(material)));
            return;
        }
        try {
            if (args[2].equalsIgnoreCase("reset")) {
                plugin.baselinePrices().reset(material);
                sender.sendMessage(ChatColor.GREEN + "Removed the custom baseline for " + material.name() + ".");
                return;
            }
            BigDecimal value = new BigDecimal(args[2]);
            if (value.signum() <= 0) {
                sender.sendMessage(ChatColor.RED + "Baseline price must be greater than zero.");
                return;
            }
            plugin.baselinePrices().set(material, value);
            sender.sendMessage(ChatColor.GREEN + "Set " + material.name() + " baseline to " + price(value) + ".");
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Enter a valid price or use 'reset'.");
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save baseline for " + material.name() + ": " + exception.getMessage());
            sender.sendMessage(ChatColor.RED + "Could not save that baseline.");
        }
    }

    private void handleCustom(CommandSender sender, String[] args) {
        if (!hasExchangePermission(sender, "admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to manage custom items.");
            return;
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("remove")) {
            String key = args[2].toLowerCase(Locale.ROOT);
            try {
                if (!plugin.customItems().contains(key)) {
                    sender.sendMessage(ChatColor.RED + "No custom item uses the key '" + key + "'.");
                    return;
                }
                plugin.customItems().remove(key);
                sender.sendMessage(ChatColor.GREEN + "Removed custom item '" + key + "'.");
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not remove custom item " + key + ": " + exception.getMessage());
                sender.sendMessage(ChatColor.RED + "Could not save the custom item catalog.");
            }
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Hold the custom item in-game to add it to the catalog.");
            return;
        }
        if (args.length != 4 || !args[1].equalsIgnoreCase("add")) {
            sender.sendMessage(ChatColor.RED + "Usage: /exchange custom add <key> <baseline-price>");
            sender.sendMessage(ChatColor.RED + "       /exchange custom remove <key>");
            return;
        }
        String key = args[2].toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_-]{1,32}")) {
            sender.sendMessage(ChatColor.RED + "Use a 1-32 character key with letters, numbers, _ or -.");
            return;
        }
        if (plugin.customItems().contains(key)) {
            sender.sendMessage(ChatColor.RED + "That custom item key already exists.");
            return;
        }
        BigDecimal baseline;
        try {
            baseline = new BigDecimal(args[3]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Enter a valid baseline price.");
            return;
        }
        if (baseline.signum() <= 0) {
            sender.sendMessage(ChatColor.RED + "Baseline price must be greater than zero.");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "Hold the custom item in your main hand first.");
            return;
        }
        try {
            plugin.customItems().add(key, held.clone(), baseline);
            sender.sendMessage(ChatColor.GREEN + "Added '" + key + "' to the Custom Items page with baseline "
                    + price(baseline) + ". The item in your hand was not consumed.");
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save custom item " + key + ": " + exception.getMessage());
            sender.sendMessage(ChatColor.RED + "Could not save the custom item catalog.");
        }
    }

    private void handleSell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can create listings.");
            return;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /exchange sell <unit-price> [amount]");
            return;
        }
        BigDecimal unitPrice;
        try {
            unitPrice = new BigDecimal(args[1]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Enter a valid unit price.");
            return;
        }
        if (unitPrice.signum() <= 0) {
            sender.sendMessage(ChatColor.RED + "Price must be greater than zero.");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "Hold the item you want to list in your main hand.");
            return;
        }
        int amount = hand.getAmount();
        if (args.length == 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "Amount must be a whole number.");
                return;
            }
        }
        if (amount < 1 || amount > hand.getAmount()) {
            sender.sendMessage(ChatColor.RED + "You can list between 1 and " + hand.getAmount()
                    + " items from your hand.");
            return;
        }
        if (!priceWithinBand(hand, unitPrice)) {
            sender.sendMessage(ChatColor.RED + "That price is outside the allowed baseline range: "
                    + priceFloorAndCeiling(hand) + ChatColor.RED + ".");
            return;
        }

        ItemStack offered = hand.clone();
        offered.setAmount(amount);
        ItemStack originalHand = hand.clone();
        if (amount == hand.getAmount()) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - amount);
        }
        try {
            long id = offerService.createSellOffer(player, offered, unitPrice);
            sender.sendMessage(ChatColor.GREEN + "Listed " + amount + " x " + hand.getType().name()
                    + " as offer #" + id + " for " + price(unitPrice) + " each.");
        } catch (OfferService.OfferException exception) {
            player.getInventory().setItemInMainHand(originalHand);
            sender.sendMessage(ChatColor.RED + exception.getMessage());
        } catch (SQLException exception) {
            player.getInventory().setItemInMainHand(originalHand);
            plugin.getLogger().warning("Could not create sell listing: " + exception.getMessage());
            sender.sendMessage(ChatColor.RED + "The market could not save your listing; your item was restored.");
        }
    }

    private void handleOrders(CommandSender sender, String[] args) {
        if (args.length > 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /exchange orders [item]");
            return;
        }
        Material material = args.length == 2 ? parseMaterial(args[1]) : null;
        if (args.length == 2 && (material == null || !material.isItem())) {
            sender.sendMessage(ChatColor.RED + "Unknown item: " + args[1]);
            return;
        }
        try {
            List<OfferService.OfferListing> listings = offerService.list(material, 10);
            if (listings.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "There are no open sell listings" +
                        (material == null ? "." : " for " + material.name() + "."));
                return;
            }
            sender.sendMessage(ChatColor.GOLD + "Sell listings" +
                    (material == null ? "" : " for " + material.name()) + ChatColor.GRAY + " (top 10):");
            for (OfferService.OfferListing listing : listings) {
                sender.sendMessage(ChatColor.YELLOW + "#" + listing.id() + ChatColor.GRAY + "  "
                        + listing.quantity() + " x " + listing.material().name()
                        + " @ " + price(listing.unitPrice()) + " each"
                        + ChatColor.DARK_GRAY + " — " + listing.sellerName()
                        + ChatColor.GRAY + " @ " + listing.originServer());
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not read sell listings: " + exception.getMessage());
            sender.sendMessage(ChatColor.RED + "The market is temporarily unavailable.");
        }
    }

    private void handleBuy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can buy listings.");
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /exchange buy <listing-id>");
            return;
        }
        long listingId;
        try {
            listingId = Long.parseLong(args[1]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Listing id must be a number.");
            return;
        }
        if (listingId < 1) {
            sender.sendMessage(ChatColor.RED + "Listing id must be positive.");
            return;
        }
        try {
            OfferService.PurchaseResult purchase = offerService.buy(player, listingId);
            sender.sendMessage(ChatColor.GREEN + "Bought " + purchase.quantity() + " x "
                    + purchase.material().name() + " for " + price(purchase.total()) + ".");
        } catch (OfferService.OfferException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not complete purchase for listing " + listingId + ": "
                    + exception.getMessage());
            sender.sendMessage(ChatColor.RED + "The market could not complete that purchase.");
        }
    }

    private void handleCancel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can cancel listings.");
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /exchange cancel <listing-id>");
            return;
        }
        long listingId;
        try {
            listingId = Long.parseLong(args[1]);
        } catch (NumberFormatException exception) {
            sender.sendMessage(ChatColor.RED + "Listing id must be a number.");
            return;
        }
        try {
            ItemStack item = offerService.cancel(player, listingId);
            player.getInventory().addItem(item).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            sender.sendMessage(ChatColor.GREEN + "Cancelled listing #" + listingId + " and returned "
                    + item.getAmount() + " x " + item.getType().name() + ".");
        } catch (OfferService.OfferException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not cancel listing " + listingId + ": " + exception.getMessage());
            sender.sendMessage(ChatColor.RED + "The market could not cancel that listing.");
        }
    }

    private BigDecimal baselineFor(ItemStack item) {
        BigDecimal custom = plugin.customItems().baselineFor(item);
        return custom == null ? plugin.baselinePrices().get(item.getType()) : custom;
    }

    private boolean priceWithinBand(ItemStack item, BigDecimal unitPrice) {
        BigDecimal baseline = baselineFor(item);
        if (baseline == null) {
            return true;
        }
        double minimum = Math.max(0, plugin.getConfig().getDouble("market.minimum-price-multiplier", 0.5));
        double maximum = Math.max(minimum, plugin.getConfig().getDouble("market.maximum-price-multiplier", 2.0));
        return unitPrice.compareTo(baseline.multiply(BigDecimal.valueOf(minimum))) >= 0
                && unitPrice.compareTo(baseline.multiply(BigDecimal.valueOf(maximum))) <= 0;
    }

    private String priceFloorAndCeiling(ItemStack item) {
        BigDecimal baseline = baselineFor(item);
        if (baseline == null) {
            return "no baseline is configured";
        }
        BigDecimal floor = baseline.multiply(BigDecimal.valueOf(Math.max(0,
                plugin.getConfig().getDouble("market.minimum-price-multiplier", 0.5))));
        BigDecimal ceiling = baseline.multiply(BigDecimal.valueOf(Math.max(
                plugin.getConfig().getDouble("market.minimum-price-multiplier", 0.5),
                plugin.getConfig().getDouble("market.maximum-price-multiplier", 2.0))));
        return price(floor) + " to " + price(ceiling);
    }

    private String price(java.math.BigDecimal value) {
        if (value == null) {
            return ChatColor.GRAY + "no data";
        }
        String formatted = plugin.economy() == null
                ? String.format(Locale.ROOT, "%.2f", value.doubleValue())
                : plugin.economy().format(value.doubleValue());
        return ChatColor.GREEN + formatted;
    }

    private Material parseMaterial(String input) {
        String name = input.toUpperCase(Locale.ROOT);
        if (name.startsWith("MINECRAFT:")) {
            name = name.substring("MINECRAFT:".length());
        }
        return Material.matchMaterial(name);
    }

    private boolean hasExchangePermission(CommandSender sender, String suffix) {
        return sender.hasPermission("grandexchange." + suffix);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("gui", "help", "reload", "price", "baseline", "custom", "sell", "orders", "buy", "cancel").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("price")) {
            return itemSuggestions(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("baseline")) {
            return itemSuggestions(args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("baseline")) {
            return List.of("reset").stream()
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("orders")) {
            return itemSuggestions(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("custom")) {
            return List.of("add", "remove").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("custom")
                && args[1].equalsIgnoreCase("remove")) {
            return plugin.customItems().keys().stream()
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private List<String> itemSuggestions(String prefix) {
        List<String> matches = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isItem() && material.name().toLowerCase(Locale.ROOT)
                    .startsWith(prefix.toLowerCase(Locale.ROOT))) {
                matches.add(material.name().toLowerCase(Locale.ROOT));
            }
        }
        return matches;
    }
}
