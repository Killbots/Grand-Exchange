package dev.grandexchange;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;

public final class BaselinePrices {
    private final JavaPlugin plugin;
    private final File overrideFile;
    private final File defaultsFile;
    private FileConfiguration overrides;
    private FileConfiguration defaults;

    public BaselinePrices(JavaPlugin plugin) {
        this.plugin = plugin;
        this.overrideFile = new File(plugin.getDataFolder(), "baseline-prices.yml");
        this.defaultsFile = new File(plugin.getDataFolder(), "baseline-defaults.yml");
        if (!overrideFile.exists()) {
            plugin.saveResource("baseline-prices.yml", false);
        }
        if (!defaultsFile.exists()) {
            plugin.saveResource("baseline-defaults.yml", false);
        }
        reload();
    }

    public BigDecimal get(Material material) {
        String path = "prices." + material.name();
        if (overrides.contains(path)) {
            BigDecimal override = readPrice(overrides, path);
            if (override != null) {
                return override;
            }
        }
        return readPrice(defaults, path);
    }

    private BigDecimal readPrice(FileConfiguration source, String path) {
        if (!source.contains(path)) {
            return null;
        }
        double value = source.getDouble(path);
        if (!Double.isFinite(value) || value <= 0) {
            return null;
        }
        return BigDecimal.valueOf(value);
    }

    public void set(Material material, BigDecimal value) throws IOException {
        overrides.set("prices." + material.name(), value);
        overrides.save(overrideFile);
    }

    public void reset(Material material) throws IOException {
        overrides.set("prices." + material.name(), null);
        overrides.save(overrideFile);
    }

    public void reload() {
        overrides = YamlConfiguration.loadConfiguration(overrideFile);
        defaults = YamlConfiguration.loadConfiguration(defaultsFile);
    }
}
