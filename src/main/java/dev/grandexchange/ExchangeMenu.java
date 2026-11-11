package dev.grandexchange;

import dev.grandexchange.ShopCategoryCatalog.Category;
import net.milkbowl.vault.economy.Economy;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExchangeMenu implements Listener {
    private static final int INVENTORY_SIZE = 54;
    private static final int SELL_DRAFT_SIZE = 45;
    private static final int SELL_DRAFT_INPUT_SLOTS = 36;
    private static final Pattern HEX_COLOR = Pattern.compile("(?i)&#([0-9a-f]{6})");
    private static final int ITEMS_PER_PAGE = 45;
    private static final int[] CATEGORY_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
    private static final int[] LISTING_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44};
    private static final int[] GE_SLOT_POSITIONS = {10, 12, 14, 16, 28, 30, 32, 34};

    private final ExchangePlugin plugin;
    private final OfferService offers;
    private final Map<UUID, PricePrompt> pendingPricePrompts = new ConcurrentHashMap<>();

    public ExchangeMenu(ExchangePlugin plugin) {
        this.plugin = plugin;
        this.offers = new OfferService(plugin);
    }

    public void open(Player player) {
        openMain(player);
    }

    public void openMain(Player player) {
        List<Category> categories = plugin.shopCategories().categories();
        MenuHolder holder = new MenuHolder(MenuType.MAIN);
        Inventory inventory = createInventory(holder, "");
        for (int i = 0; i < Math.min(categories.size(), CATEGORY_SLOTS.length); i++) {
            Category category = categories.get(i);
            ItemStack icon = namedItem(category.icon(), ChatColor.GOLD + category.name(),
                    List.of(ChatColor.GRAY.toString() + category.items().size() + " items", "", ChatColor.YELLOW + "Click to browse"));
            inventory.setItem(CATEGORY_SLOTS[i], icon);
        }
        int customSlot = CATEGORY_SLOTS[Math.min(categories.size(), CATEGORY_SLOTS.length - 1)];
        inventory.setItem(customSlot, namedItem(Material.AMETHYST_SHARD, ChatColor.LIGHT_PURPLE + "Custom Items",
                List.of(ChatColor.GRAY.toString() + plugin.customItems().items().size() + " custom items", "",
                        ChatColor.YELLOW + "Click to browse")));
        fillFrame(inventory, 45, 53);
        inventory.setItem(49, namedItem(Material.BOOK, ChatColor.YELLOW + "How to use",
                List.of(ChatColor.GRAY + "/exchange sell <price> [amount]", ChatColor.GRAY + "/exchange orders",
                        ChatColor.GRAY + "/exchange buy <listing-id>")));
        inventory.setItem(47, namedItem(Material.CHEST, ChatColor.GREEN + "My Sell Slots",
                List.of(ChatColor.GRAY + "Manage multiple sell offers", "",
                        ChatColor.YELLOW + "Click to open your GE slots")));
        player.openInventory(inventory);
    }

    private void openCategory(Player player, String key, int page) {
        Category category = plugin.shopCategories().categories().stream()
                .filter(value -> value.key().equalsIgnoreCase(key)).findFirst().orElse(null);
        if (category == null) {
            openMain(player);
            return;
        }
        MenuHolder holder = new MenuHolder(MenuType.CATEGORY);
        holder.categoryKey = category.key();
        holder.page = page;
        Inventory inventory = createInventory(holder, "Shop: " + category.name() + " (" + (page + 1) + ")");
        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, category.items().size());
        for (int index = start; index < end; index++) {
            Material material = category.items().get(index);
            BigDecimalPrice baseline = new BigDecimalPrice(plugin.baselinePrices().get(material));
            List<String> lore = new ArrayList<>();
            if (baseline.value != null) {
                lore.add(ChatColor.GRAY + "Baseline: " + format(baseline.value.doubleValue()));
            }
            lore.add(ChatColor.GRAY + "Click to view sell listings");
            inventory.setItem(index - start, namedItem(material, ChatColor.WHITE + pretty(material), lore));
        }
        addNavigation(inventory, holder, page, end < category.items().size());
        player.openInventory(inventory);
    }

    private void openCustomItems(Player player, int page) {
        List<CustomItemCatalog.CustomItem> items = plugin.customItems().items();
        MenuHolder holder = new MenuHolder(MenuType.CUSTOM_ITEMS);
        holder.page = page;
        Inventory inventory = createInventory(holder, "Custom Items (" + (page + 1) + ")");
        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, items.size());
        if (items.isEmpty()) {
            inventory.setItem(22, namedItem(Material.PAPER, ChatColor.LIGHT_PURPLE + "No custom items yet",
                    List.of(ChatColor.GRAY + "Hold a custom item and use:",
                            ChatColor.YELLOW + "/exchange custom add <key> <baseline-price>")));
        } else {
            for (int index = start; index < end; index++) {
                CustomItemCatalog.CustomItem custom = items.get(index);
                ItemStack icon = custom.item().clone();
                icon.setAmount(Math.min(icon.getAmount(), Math.max(1, icon.getMaxStackSize())));
                ItemMeta meta = icon.getItemMeta();
                List<String> lore = meta != null && meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                if (!lore.isEmpty()) {
                    lore.add("");
                }
                lore.add(ChatColor.GRAY + "Catalog key: " + custom.key());
                lore.add(ChatColor.GRAY + "Baseline: " + format(custom.baselinePrice().doubleValue()));
                lore.add(ChatColor.YELLOW + "Click to view matching listings");
                if (meta != null) {
                    meta.setLore(lore);
                    icon.setItemMeta(meta);
                }
                inventory.setItem(index - start, icon);
            }
        }
        addNavigation(inventory, holder, page, end < items.size());
        player.openInventory(inventory);
    }

    private void openItemMarket(Player player, ItemStack item, String itemName, MenuType parentType,
                                String parentKey, int parentPage) {
        ItemStack template = item.clone();
        template.setAmount(1);
        MenuHolder holder = new MenuHolder(MenuType.ITEM_MARKET);
        holder.item = template;
        holder.itemName = itemName;
        holder.parentType = parentType;
        holder.parentKey = parentKey;
        holder.page = parentPage;
        Inventory inventory = createInventory(holder, "Listings: " + itemName);
        ItemStack preview = template.clone();
        preview.setAmount(1);
        BigDecimalPrice baseline = new BigDecimalPrice(plugin.customItems().baselineFor(template));
        if (baseline.value == null) {
            baseline = new BigDecimalPrice(plugin.baselinePrices().get(template.getType()));
        }
        List<String> previewLore = new ArrayList<>();
        if (baseline.value != null) {
            previewLore.add(ChatColor.GRAY + "Baseline: " + format(baseline.value.doubleValue()));
        }
        previewLore.add(ChatColor.GRAY + "Select a listing to buy it.");
        setLore(preview, previewLore);
        inventory.setItem(49, preview);
        try {
            List<OfferService.OfferListing> listings = offers.listMatching(template, ITEMS_PER_PAGE);
            for (int index = 0; index < listings.size(); index++) {
                OfferService.OfferListing listing = listings.get(index);
                ItemStack icon = template.clone();
                icon.setAmount(Math.min(listing.quantity(), Math.max(1, icon.getMaxStackSize())));
                setLore(icon, List.of(ChatColor.GRAY + "Listing: #" + listing.id(),
                        ChatColor.GRAY + "Seller: " + listing.sellerName(),
                        ChatColor.GRAY + "Server: " + listing.originServer(),
                        ChatColor.GRAY + "Quantity: " + listing.quantity(),
                        ChatColor.GRAY + "Unit price: " + format(listing.unitPrice().doubleValue()),
                        ChatColor.GRAY + "Total: " + format(listing.unitPrice().doubleValue() * listing.quantity()),
                        "", ChatColor.YELLOW + "Click to buy the full listing"));
                inventory.setItem(LISTING_SLOTS[index], icon);
                holder.listingSlots.put(LISTING_SLOTS[index], listing.id());
            }
            if (listings.isEmpty()) {
                inventory.setItem(22, namedItem(Material.MAP, ChatColor.GRAY + "No current listings",
                        List.of(ChatColor.GRAY + "Use /exchange sell <price> [amount] to list this item.")));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not load GUI listings: " + exception.getMessage());
            inventory.setItem(22, namedItem(Material.BARRIER, ChatColor.RED + "Market unavailable",
                    List.of(ChatColor.GRAY + "Try again in a moment.")));
        }
        inventory.setItem(45, namedItem(Material.ARROW, ChatColor.YELLOW + "Back", List.of()));
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!held.getType().isAir() && held.isSimilar(template)) {
            BigDecimalPrice sellPrice = new BigDecimalPrice(plugin.customItems().baselineFor(held));
            if (sellPrice.value == null) {
                sellPrice = new BigDecimalPrice(plugin.baselinePrices().get(held.getType()));
            }
            List<String> sellLore = new ArrayList<>();
            sellLore.add(ChatColor.GRAY + "Held amount: " + held.getAmount());
            if (sellPrice.value == null) {
                sellLore.add(ChatColor.RED + "No baseline price is configured");
            } else {
                sellLore.add(ChatColor.GRAY + "Unit price: " + format(sellPrice.value.doubleValue()));
                sellLore.add(ChatColor.GRAY + "Total: "
                        + format(sellPrice.value.doubleValue() * held.getAmount()));
                sellLore.add("");
                sellLore.add(ChatColor.YELLOW + "Click to list the full stack");
            }
            inventory.setItem(53, namedItem(sellPrice.value == null ? Material.BARRIER : Material.EMERALD,
                    ChatColor.GREEN + "Sell held item", sellLore));
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder rawHolder = event.getView().getTopInventory().getHolder();
        if (!(rawHolder instanceof MenuHolder holder)) {
            return;
        }
        if (holder.type == MenuType.SELL_DRAFT) {
            clickSellDraft(player, holder, event);
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        switch (holder.type) {
            case MAIN -> clickMain(player, slot);
            case CATEGORY -> clickCategory(player, holder, slot);
            case CUSTOM_ITEMS -> clickCustomItems(player, holder, slot);
            case ITEM_MARKET -> clickItemMarket(player, holder, slot);
            case SELL_SLOTS -> clickSellSlots(player, holder, slot);
            case SELL_DRAFT -> { }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        if (holder.type != MenuType.SELL_DRAFT) {
            event.setCancelled(true);
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize && rawSlot >= holder.draftInputSlots) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)
                || holder.type != MenuType.SELL_DRAFT) {
            return;
        }
        pendingPricePrompts.entrySet().removeIf(entry -> entry.getValue().draft() == holder);
        if (!holder.draftSettled && event.getPlayer() instanceof Player player) {
            returnDraftItems(player, holder);
            player.sendMessage(ChatColor.YELLOW + "Sell draft closed; your items were returned.");
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        PricePrompt prompt = pendingPricePrompts.get(playerId);
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> handlePriceInput(event.getPlayer(), prompt, input));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        pendingPricePrompts.remove(event.getPlayer().getUniqueId());
    }

    private void clickMain(Player player, int slot) {
        if (slot == 47) {
            openSellSlots(player, 0);
            return;
        }
        List<Category> categories = plugin.shopCategories().categories();
        for (int index = 0; index < Math.min(categories.size(), CATEGORY_SLOTS.length); index++) {
            if (CATEGORY_SLOTS[index] == slot) {
                openCategory(player, categories.get(index).key(), 0);
                return;
            }
        }
        int customSlot = CATEGORY_SLOTS[Math.min(categories.size(), CATEGORY_SLOTS.length - 1)];
        if (slot == customSlot) {
            openCustomItems(player, 0);
        }
    }

    private void clickCategory(Player player, MenuHolder holder, int slot) {
        if (slot == 49) {
            openMain(player);
            return;
        }
        if (slot == 48 && holder.page > 0) {
            openCategory(player, holder.categoryKey, holder.page - 1);
            return;
        }
        if (slot == 50 && holder.hasNext) {
            openCategory(player, holder.categoryKey, holder.page + 1);
            return;
        }
        ItemStack clicked = player.getOpenInventory().getTopInventory().getItem(slot);
        if (clicked != null && clicked.getType().isItem() && clicked.getType() != Material.GRAY_STAINED_GLASS_PANE) {
            openItemMarket(player, new ItemStack(clicked.getType()), pretty(clicked.getType()),
                    MenuType.CATEGORY, holder.categoryKey, holder.page);
        }
    }

    private void clickCustomItems(Player player, MenuHolder holder, int slot) {
        if (slot == 49) {
            openMain(player);
            return;
        }
        if (slot == 48 && holder.page > 0) {
            openCustomItems(player, holder.page - 1);
            return;
        }
        if (slot == 50 && holder.hasNext) {
            openCustomItems(player, holder.page + 1);
            return;
        }
        List<CustomItemCatalog.CustomItem> items = plugin.customItems().items();
        int index = holder.page * ITEMS_PER_PAGE + slot;
        if (slot >= 0 && slot < ITEMS_PER_PAGE && index < items.size()) {
            CustomItemCatalog.CustomItem custom = items.get(index);
            openItemMarket(player, custom.item(), custom.key(), MenuType.CUSTOM_ITEMS, "", holder.page);
        }
    }

    private void clickItemMarket(Player player, MenuHolder holder, int slot) {
        if (slot == 45) {
            if (holder.parentType == MenuType.CUSTOM_ITEMS) {
                openCustomItems(player, holder.page);
            } else if (holder.parentType == MenuType.MAIN) {
                openMain(player);
            } else {
                openCategory(player, holder.parentKey, holder.page);
            }
            return;
        }
        Long listingId = holder.listingSlots.get(slot);
        if (listingId == null) {
            if (slot == 53) {
                createListingFromHand(player, holder);
            }
            return;
        }
        try {
            OfferService.PurchaseResult result = offers.buy(player, listingId);
            player.sendMessage(ChatColor.GREEN + "Bought " + result.quantity() + " x "
                    + result.material().name() + " for " + format(result.total().doubleValue()) + ".");
            openItemMarket(player, holder.item, holder.itemName, holder.parentType,
                    holder.parentKey, holder.page);
        } catch (OfferService.OfferException exception) {
            player.sendMessage(ChatColor.RED + exception.getMessage());
        } catch (SQLException exception) {
            plugin.getLogger().warning("GUI purchase failed: " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "The market could not complete that purchase.");
        }
    }

    private void createListingFromHand(Player player, MenuHolder holder) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || !held.isSimilar(holder.item)) {
            player.sendMessage(ChatColor.RED + "Hold the matching item in your main hand to list it.");
            return;
        }
        java.math.BigDecimal unitPrice = plugin.customItems().baselineFor(held);
        if (unitPrice == null) {
            unitPrice = plugin.baselinePrices().get(held.getType());
        }
        if (unitPrice == null || unitPrice.signum() <= 0) {
            player.sendMessage(ChatColor.RED + "There is no baseline price for that item yet.");
            return;
        }
        ItemStack original = held.clone();
        ItemStack offered = held.clone();
        player.getInventory().setItemInMainHand(null);
        try {
            long id = offers.createSellOffer(player, offered, unitPrice);
            player.sendMessage(ChatColor.GREEN + "Listed " + offered.getAmount() + " x "
                    + holder.itemName + " as offer #" + id + " for "
                    + format(unitPrice.doubleValue()) + " each.");
            openItemMarket(player, holder.item, holder.itemName, holder.parentType,
                    holder.parentKey, holder.page);
        } catch (OfferService.OfferException exception) {
            player.getInventory().setItemInMainHand(original);
            player.sendMessage(ChatColor.RED + exception.getMessage());
        } catch (SQLException exception) {
            player.getInventory().setItemInMainHand(original);
            plugin.getLogger().warning("GUI could not create sell listing: " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "The market could not save your listing; your item was restored.");
        }
    }

    private String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? meta.getDisplayName() : pretty(item.getType());
    }

    private void openSellSlots(Player player, int page) {
        List<OfferService.OwnedOffer> owned;
        try {
            owned = offers.listOwned(player);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not load player sell slots: " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "Your sell slots could not be loaded.");
            openMain(player);
            return;
        }
        int slotLimit = offers.sellSlotLimit(player);
        int shownSlots = Math.max(slotLimit, owned.size());
        int pages = Math.max(1, (shownSlots + GE_SLOT_POSITIONS.length - 1) / GE_SLOT_POSITIONS.length);
        int selectedPage = Math.max(0, Math.min(page, pages - 1));
        MenuHolder holder = new MenuHolder(MenuType.SELL_SLOTS);
        holder.page = selectedPage;
        holder.hasNext = selectedPage + 1 < pages;
        Inventory inventory = createInventory(holder, "My Sell Slots (" + owned.size() + "/" + slotLimit + ")");
        int pageStart = selectedPage * GE_SLOT_POSITIONS.length;
        for (int index = 0; index < GE_SLOT_POSITIONS.length; index++) {
            int absoluteIndex = pageStart + index;
            int menuSlot = GE_SLOT_POSITIONS[index];
            if (absoluteIndex < owned.size()) {
                OfferService.OwnedOffer offer = owned.get(absoluteIndex);
                ItemStack icon = offer.item().clone();
                icon.setAmount(Math.min(offer.quantity(), Math.max(1, icon.getMaxStackSize())));
                List<String> lore = new ArrayList<>();
                ItemMeta meta = icon.getItemMeta();
                if (meta != null && meta.hasLore()) {
                    lore.addAll(meta.getLore());
                    if (!lore.isEmpty()) {
                        lore.add("");
                    }
                }
                lore.add(ChatColor.GRAY + "Offer: #" + offer.id());
                lore.add(ChatColor.GRAY + "Quantity: " + offer.quantity());
                lore.add(ChatColor.GRAY + "Unit price: " + format(offer.unitPrice().doubleValue()));
                lore.add(ChatColor.GRAY + "Total: " + format(offer.unitPrice().doubleValue() * offer.quantity()));
                lore.add("");
                lore.add(ChatColor.RED + "Click to cancel and return the items");
                setLore(icon, lore);
                inventory.setItem(menuSlot, icon);
                holder.offerSlots.put(menuSlot, offer.id());
            } else if (absoluteIndex < slotLimit) {
                inventory.setItem(menuSlot, namedItem(Material.LIME_STAINED_GLASS_PANE,
                        ChatColor.GREEN + "Empty sell slot #" + (absoluteIndex + 1),
                        List.of(ChatColor.GRAY + "Click to open the sell chest", "",
                                ChatColor.YELLOW + "Drag in multiple item stacks")));
            } else {
                inventory.setItem(menuSlot, namedItem(Material.GRAY_STAINED_GLASS_PANE,
                        ChatColor.GRAY + "Locked slot #" + (absoluteIndex + 1),
                        List.of(ChatColor.GRAY + "Your current limit is " + slotLimit + " sell slots.")));
            }
        }
        fillFrame(inventory, 45, 53);
        inventory.setItem(49, namedItem(Material.BARRIER, ChatColor.YELLOW + "Back", List.of()));
        if (selectedPage > 0) {
            inventory.setItem(48, namedItem(Material.ARROW, ChatColor.YELLOW + "Previous page", List.of()));
        }
        if (holder.hasNext) {
            inventory.setItem(50, namedItem(Material.ARROW, ChatColor.YELLOW + "Next page", List.of()));
        }
        inventory.setItem(52, namedItem(Material.BOOK, ChatColor.YELLOW + "Slot rewards",
                List.of(ChatColor.GRAY + "Base: " + plugin.getConfig().getInt("market.default-sell-slots", 8),
                        ChatColor.GRAY + "Higher ranks can unlock more slots.")));
        player.openInventory(inventory);
    }

    private void clickSellSlots(Player player, MenuHolder holder, int slot) {
        if (slot == 49) {
            openMain(player);
            return;
        }
        if (slot == 48 && holder.page > 0) {
            openSellSlots(player, holder.page - 1);
            return;
        }
        if (slot == 50 && holder.hasNext) {
            openSellSlots(player, holder.page + 1);
            return;
        }
        Long offerId = holder.offerSlots.get(slot);
        if (offerId != null) {
            try {
                ItemStack returned = offers.cancel(player, offerId);
                player.getInventory().addItem(returned).values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                player.sendMessage(ChatColor.YELLOW + "Cancelled offer #" + offerId + " and returned the items.");
                openSellSlots(player, holder.page);
            } catch (OfferService.OfferException exception) {
                player.sendMessage(ChatColor.RED + exception.getMessage());
            } catch (SQLException exception) {
                plugin.getLogger().warning("Could not cancel GUI offer " + offerId + ": " + exception.getMessage());
                player.sendMessage(ChatColor.RED + "The market could not cancel that offer.");
            }
            return;
        }
        for (int position : GE_SLOT_POSITIONS) {
            if (position == slot) {
                openSellDraft(player, holder.page);
                return;
            }
        }
    }

    private void openSellDraft(Player player, int returnPage) {
        List<OfferService.OwnedOffer> owned;
        try {
            owned = offers.listOwned(player);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not check sell slots before opening a draft: " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "Your sell slots could not be loaded.");
            return;
        }
        int slotLimit = offers.sellSlotLimit(player);
        int availableSlots = slotLimit - owned.size();
        if (availableSlots <= 0) {
            player.sendMessage(ChatColor.RED + "All " + slotLimit
                    + " sell slots are in use. Cancel an offer or unlock more slots first.");
            return;
        }
        MenuHolder holder = new MenuHolder(MenuType.SELL_DRAFT);
        holder.page = returnPage;
        holder.draftInputSlots = SELL_DRAFT_INPUT_SLOTS;
        Inventory inventory = createInventory(holder, "Add Items to Sell Offers", SELL_DRAFT_SIZE);
        fillFrame(inventory, 36, 44);
        inventory.setItem(36, namedItem(Material.EMERALD, ChatColor.GREEN + "Confirm sell offers",
                List.of(ChatColor.GRAY + "Each item stack becomes one offer", "",
                        ChatColor.GRAY + "Free offer slots: " + availableSlots,
                        ChatColor.YELLOW + "Price each stack, then confirm")));
        inventory.setItem(37, namedItem(Material.RED_DYE, ChatColor.RED + "Reduce selected price by 5%",
                List.of(ChatColor.GRAY + "Right-click an item stack above to select it.")));
        inventory.setItem(38, namedItem(Material.WRITABLE_BOOK, ChatColor.YELLOW + "Set custom unit price",
                List.of(ChatColor.GRAY + "Right-click an item stack, then click here.")));
        inventory.setItem(39, namedItem(Material.LIME_DYE, ChatColor.GREEN + "Increase selected price by 5%",
                List.of(ChatColor.GRAY + "Right-click an item stack above to select it.")));
        inventory.setItem(40, namedItem(Material.BARRIER, ChatColor.RED + "Cancel", List.of()));
        inventory.setItem(44, namedItem(Material.BOOK, ChatColor.YELLOW + "How this works",
                List.of(ChatColor.GRAY + "Drag stacks from your inventory", ChatColor.GRAY + "into the chest above.",
                        ChatColor.GRAY + "36 item spaces; " + availableSlots + " sell slots available.")));
        player.openInventory(inventory);
    }

    private void clickSellDraft(Player player, MenuHolder holder, InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (rawSlot < 0) {
            event.setCancelled(true);
            return;
        }
        if (rawSlot >= topSize) {
            return;
        }
        event.setCancelled(true);
        if (rawSlot == 36) {
            confirmSellDraft(player, holder);
            return;
        }
        if (rawSlot == 40) {
            returnDraftItems(player, holder);
            player.closeInventory();
            openSellSlots(player, holder.page);
            return;
        }
        if (rawSlot == 37 || rawSlot == 38 || rawSlot == 39) {
            if (holder.selectedItem == null) {
                player.sendMessage(ChatColor.RED + "Right-click an item stack in the chest to select its price.");
            } else if (rawSlot == 37) {
                adjustSelectedPrice(player, holder, new BigDecimal("0.95"));
            } else if (rawSlot == 39) {
                adjustSelectedPrice(player, holder, new BigDecimal("1.05"));
            } else {
                pendingPricePrompts.put(player.getUniqueId(),
                        new PricePrompt(holder, holder.selectedItemKey, holder.selectedItem.clone()));
                player.sendMessage(ChatColor.YELLOW + "Enter a custom unit price in chat, or type cancel.");
            }
            return;
        }
        if (rawSlot >= holder.draftInputSlots) {
            return;
        }
        if (event.getClick().isRightClick()) {
            ItemStack selected = holder.inventory.getItem(rawSlot);
            if (selected != null && !selected.getType().isAir()) {
                holder.selectedItem = selected.clone();
                holder.selectedItemKey = itemKey(selected);
                refreshDraftControls(player, holder);
                player.sendMessage(ChatColor.YELLOW + itemName(selected) + " selected. Current unit price: "
                        + draftPrice(holder, selected).map(price -> format(price.doubleValue()))
                        .orElse("not set"));
            }
            return;
        }
        event.setCancelled(false);
    }

    private void confirmSellDraft(Player player, MenuHolder holder) {
        if (pendingPricePrompts.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Finish or cancel your custom price entry before confirming.");
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        List<java.math.BigDecimal> prices = new ArrayList<>();
        Inventory inventory = holder.inventory;
        for (int slot = 0; slot < holder.draftInputSlots; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            BigDecimal unitPrice = draftPrice(holder, item).orElse(null);
            if (unitPrice == null || unitPrice.signum() <= 0) {
                player.sendMessage(ChatColor.RED + "No baseline price is configured for "
                        + itemName(item) + ". Select it and enter a custom price first.");
                return;
            }
            items.add(item.clone());
            prices.add(unitPrice);
        }
        if (items.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Drag at least one item stack into the chest first.");
            return;
        }
        try {
            List<Long> offerIds = offers.createSellOffers(player, items, prices);
            holder.draftSettled = true;
            for (int slot = 0; slot < holder.draftInputSlots; slot++) {
                inventory.clear(slot);
            }
            player.sendMessage(ChatColor.GREEN + "Created " + offerIds.size()
                    + " sell offers with your selected prices.");
            player.closeInventory();
            openSellSlots(player, 0);
        } catch (OfferService.OfferException exception) {
            player.sendMessage(ChatColor.RED + exception.getMessage());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not create sell offer batch: " + exception.getMessage());
            player.sendMessage(ChatColor.RED + "The market could not save these offers. Your draft is unchanged.");
        }
    }

    private void returnDraftItems(Player player, MenuHolder holder) {
        if (holder.draftSettled) {
            return;
        }
        holder.draftSettled = true;
        for (int slot = 0; slot < holder.draftInputSlots; slot++) {
            ItemStack item = holder.inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            holder.inventory.clear(slot);
            player.getInventory().addItem(item).values().forEach(leftover ->
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    private void adjustSelectedPrice(Player player, MenuHolder holder, BigDecimal multiplier) {
        BigDecimal current = draftPrice(holder, holder.selectedItem).orElse(null);
        if (current == null) {
            player.sendMessage(ChatColor.RED + "This item has no baseline. Set a custom unit price first.");
            return;
        }
        BigDecimal adjusted = current.multiply(multiplier).setScale(4, RoundingMode.HALF_UP);
        if (adjusted.signum() <= 0) {
            adjusted = new BigDecimal("0.0001");
        }
        holder.draftPrices.put(holder.selectedItemKey, adjusted);
        refreshDraftControls(player, holder);
        player.sendMessage(ChatColor.GREEN + "Unit price set to " + format(adjusted.doubleValue()) + ".");
    }

    private void handlePriceInput(Player player, PricePrompt prompt, String input) {
        if (pendingPricePrompts.get(player.getUniqueId()) != prompt) {
            return;
        }
        if (prompt.draft().draftSettled) {
            pendingPricePrompts.remove(player.getUniqueId(), prompt);
            return;
        }
        if (input.equalsIgnoreCase("cancel")) {
            pendingPricePrompts.remove(player.getUniqueId(), prompt);
            player.sendMessage(ChatColor.GRAY + "Custom price entry cancelled.");
            return;
        }
        BigDecimal price;
        try {
            price = new BigDecimal(input).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            player.sendMessage(ChatColor.RED + "Enter a valid number greater than zero, or type cancel.");
            return;
        }
        if (price.signum() <= 0 || price.precision() > 16) {
            player.sendMessage(ChatColor.RED + "Price must be greater than zero and no larger than 999999999999.9999.");
            return;
        }
        pendingPricePrompts.remove(player.getUniqueId(), prompt);
        MenuHolder holder = prompt.draft();
        holder.draftPrices.put(prompt.itemKey(), price);
        holder.selectedItem = prompt.item().clone();
        holder.selectedItemKey = prompt.itemKey();
        if (player.getOpenInventory().getTopInventory().getHolder() == holder) {
            refreshDraftControls(player, holder);
        }
        player.sendMessage(ChatColor.GREEN + "Custom unit price set to " + format(price.doubleValue()) + ".");
    }

    private void refreshDraftControls(Player player, MenuHolder holder) {
        ItemStack selected = holder.selectedItem;
        BigDecimal current = selected == null ? null : draftPrice(holder, selected).orElse(null);
        List<String> selectionLore = selected == null
                ? List.of(ChatColor.GRAY + "Right-click an item stack above to select it.")
                : List.of(ChatColor.GRAY + itemName(selected),
                        current == null ? ChatColor.RED + "No baseline or custom price set"
                                : ChatColor.GRAY + "Unit price: " + format(current.doubleValue()),
                        current == null ? ChatColor.YELLOW + "Set a custom price first"
                                : ChatColor.YELLOW + "Use +5%, -5%, or set a custom price");
        holder.inventory.setItem(37, namedItem(Material.RED_DYE, ChatColor.RED + "Reduce selected price by 5%",
                selectionLore));
        holder.inventory.setItem(38, namedItem(Material.WRITABLE_BOOK, ChatColor.YELLOW + "Set custom unit price",
                selectionLore));
        holder.inventory.setItem(39, namedItem(Material.LIME_DYE, ChatColor.GREEN + "Increase selected price by 5%",
                selectionLore));
        holder.inventory.setItem(44, namedItem(Material.BOOK, ChatColor.YELLOW + "How this works",
                List.of(ChatColor.GRAY + "Right-click a stack to select its price.",
                        ChatColor.GRAY + "Baseline prices can be adjusted by 5%.",
                        ChatColor.GRAY + "Items without a baseline need a custom price.")));
    }

    private java.util.Optional<BigDecimal> draftPrice(MenuHolder holder, ItemStack item) {
        String key = itemKey(item);
        BigDecimal selectedPrice = holder.draftPrices.get(key);
        if (selectedPrice != null) {
            return java.util.Optional.of(selectedPrice);
        }
        BigDecimal baseline = plugin.customItems().baselineFor(item);
        if (baseline == null) {
            baseline = plugin.baselinePrices().get(item.getType());
        }
        return java.util.Optional.ofNullable(baseline);
    }

    private String itemKey(ItemStack item) {
        ItemStack normalized = item.clone();
        normalized.setAmount(1);
        return Base64.getEncoder().encodeToString(normalized.serializeAsBytes());
    }

    private void addNavigation(Inventory inventory, MenuHolder holder, int page, boolean hasNext) {
        holder.hasNext = hasNext;
        fillFrame(inventory, 45, 53);
        if (page > 0) {
            inventory.setItem(48, namedItem(Material.ARROW, ChatColor.YELLOW + "Previous page", List.of()));
        }
        inventory.setItem(49, namedItem(Material.BARRIER, ChatColor.YELLOW + "Back", List.of()));
        if (hasNext) {
            inventory.setItem(50, namedItem(Material.ARROW, ChatColor.YELLOW + "Next page", List.of()));
        }
    }

    private Inventory createInventory(MenuHolder holder, String title) {
        return createInventory(holder, title, INVENTORY_SIZE);
    }

    private Inventory createInventory(MenuHolder holder, String title, int size) {
        String menuName = plugin.getConfig().getString("menu.name", "&bGrand &1Exchange");
        if (menuName == null || menuName.isBlank()) {
            menuName = "&bGrand &1Exchange";
        }
        String fullTitle = menuName;
        if (title != null && !title.isBlank()) {
            fullTitle += " " + ChatColor.DARK_GRAY + "» " + ChatColor.WHITE + title;
        }
        String coloredTitle = colorizeTitle(fullTitle);
        Inventory inventory = Bukkit.createInventory(holder, size, coloredTitle);
        holder.inventory = inventory;
        return inventory;
    }

    private String colorizeTitle(String title) {
        Matcher matcher = HEX_COLOR.matcher(title);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder legacyHex = new StringBuilder("\u00a7x");
            for (int index = 0; index < hex.length(); index++) {
                legacyHex.append('\u00a7').append(hex.charAt(index));
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(legacyHex.toString()));
        }
        matcher.appendTail(expanded);
        return ChatColor.translateAlternateColorCodes('&', expanded.toString());
    }

    private void fillFrame(Inventory inventory, int start, int end) {
        ItemStack pane = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = start; slot <= end; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, pane);
            }
        }
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void setLore(ItemStack item, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
    }

    private String format(double amount) {
        Economy economy = plugin.economy();
        return economy == null ? String.format(Locale.ROOT, "%.2f", amount) : economy.format(amount);
    }

    private String pretty(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            words.add(part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        }
        return String.join(" ", words);
    }

    private enum MenuType {
        MAIN,
        CATEGORY,
        CUSTOM_ITEMS,
        ITEM_MARKET,
        SELL_SLOTS,
        SELL_DRAFT
    }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final java.util.Map<Integer, Long> listingSlots = new java.util.HashMap<>();
        private final java.util.Map<Integer, Long> offerSlots = new java.util.HashMap<>();
        private final Map<String, BigDecimal> draftPrices = new java.util.HashMap<>();
        private Inventory inventory;
        private String categoryKey;
        private String parentKey;
        private String itemName;
        private ItemStack item;
        private MenuType parentType;
        private int page;
        private int draftInputSlots;
        private boolean hasNext;
        private boolean draftSettled;
        private ItemStack selectedItem;
        private String selectedItemKey;

        private MenuHolder(MenuType type) {
            this.type = type;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record PricePrompt(MenuHolder draft, String itemKey, ItemStack item) {
    }

    private static final class BigDecimalPrice {
        private final java.math.BigDecimal value;

        private BigDecimalPrice(java.math.BigDecimal value) {
            this.value = value;
        }
    }
}
