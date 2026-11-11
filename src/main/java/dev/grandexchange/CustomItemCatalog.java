package dev.grandexchange;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class CustomItemCatalog {
    private final File file;
    private FileConfiguration config;

    public CustomItemCatalog(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "custom-items.yml");
        if (!file.exists()) {
            plugin.saveResource("custom-items.yml", false);
        }
        reload();
    }

    public List<CustomItem> items() {
        ConfigurationSection section = config.getConfigurationSection("items");
        if (section == null) {
            return List.of();
        }
        List<CustomItem> result = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ItemStack item = get(key);
            if (item != null) {
                result.add(new CustomItem(key, item, BigDecimal.valueOf(config.getDouble(
                        "items." + key + ".baseline-price", 0))));
            }
        }
        return result;
    }

    public ItemStack get(String key) {
        String data = config.getString("items." + key + ".data");
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public BigDecimal baselineFor(ItemStack item) {
        for (CustomItem customItem : items()) {
            if (customItem.item().isSimilar(item)) {
                return customItem.baselinePrice();
            }
        }
        return null;
    }

    public boolean contains(String key) {
        return config.contains("items." + key);
    }

    public List<String> keys() {
        ConfigurationSection section = config.getConfigurationSection("items");
        return section == null ? List.of() : section.getKeys(false).stream().sorted().toList();
    }

    public void add(String key, ItemStack item, BigDecimal baselinePrice) throws IOException {
        String path = "items." + key + ".";
        config.set(path + "data", Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        config.set(path + "baseline-price", baselinePrice);
        config.save(file);
    }

    public void remove(String key) throws IOException {
        config.set("items." + key, null);
        config.save(file);
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
    }

    public record CustomItem(String key, ItemStack item, BigDecimal baselinePrice) {
    }
}
