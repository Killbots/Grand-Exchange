package dev.grandexchange;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class ShopCategoryCatalog {
    private final File file;
    private FileConfiguration config;

    public ShopCategoryCatalog(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "shop-categories.yml");
        if (!file.exists()) {
            plugin.saveResource("shop-categories.yml", false);
        }
        reload();
    }

    public List<Category> categories() {
        ConfigurationSection section = config.getConfigurationSection("categories");
        if (section == null) {
            return List.of();
        }
        List<Category> categories = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            String path = "categories." + key;
            Material icon = Material.matchMaterial(config.getString(path + ".icon", "CHEST"));
            if (icon == null || !icon.isItem()) {
                icon = Material.CHEST;
            }
            List<Material> items = config.getStringList(path + ".items").stream()
                    .map(Material::matchMaterial)
                    .filter(material -> material != null && material.isItem())
                    .distinct()
                    .toList();
            categories.add(new Category(key, config.getString(path + ".name", key), icon, items));
        }
        return categories;
    }

    public record Category(String key, String name, Material icon, List<Material> items) {
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
    }
}
