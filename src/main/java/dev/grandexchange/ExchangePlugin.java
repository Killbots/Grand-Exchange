package dev.grandexchange;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.grandexchange.command.ExchangeCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ExchangePlugin extends JavaPlugin {
    private static final Pattern SERVER_ID_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");
    private HikariDataSource dataSource;
    private Economy economy;
    private BaselinePrices baselinePrices;
    private ShopCategoryCatalog shopCategories;
    private CustomItemCatalog customItems;
    private ExchangeMenu exchangeMenu;
    private String serverId = "unknown";
    private Map<String, Object> startupStorageSettings = Map.of();

    @Override
    public void onEnable() {
        migrateLegacyDataFolder();
        saveDefaultConfig();
        serverId = getConfig().getString("server-id", "unknown").trim().toLowerCase(Locale.ROOT);
        if (!SERVER_ID_PATTERN.matcher(serverId).matches()) {
            getLogger().warning("Invalid server-id; using 'unknown'. Use 1-32 letters, numbers, _ or -.");
            serverId = "unknown";
        }
        if (getConfig().getConfigurationSection("storage") != null) {
            startupStorageSettings = Map.copyOf(getConfig().getConfigurationSection("storage").getValues(false));
        }
        if (!connectEconomy()) {
            getLogger().severe("No Vault-compatible economy provider found; install Vault and an economy provider, then restart.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!connectStorage()) {
            getLogger().severe("Could not connect to the exchange database; disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        baselinePrices = new BaselinePrices(this);
        shopCategories = new ShopCategoryCatalog(this);
        customItems = new CustomItemCatalog(this);
        try (Connection connection = dataSource.getConnection()) {
            MarketSchema.initialize(connection);
            MarketSchema.registerServer(connection, serverId, getDescription().getVersion());
        } catch (SQLException exception) {
            getLogger().severe("Could not initialize exchange tables: " + exception.getMessage());
            closeStorage();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("GrandExchange connected to "
                + getConfig().getString("storage.type", "mariadb") + " market storage as server '"
                + serverId + "'.");
        ExchangeCommand command = new ExchangeCommand(this);
        getCommand("exchange").setExecutor(command);
        getCommand("exchange").setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new PayoutListener(this), this);
        exchangeMenu = new ExchangeMenu(this);
        getServer().getPluginManager().registerEvents(exchangeMenu, this);
    }

    @Override
    public void onDisable() {
        closeStorage();
    }

    private void migrateLegacyDataFolder() {
        Path target = getDataFolder().toPath();
        Path legacy = target.resolveSibling("ModernMCExchange");
        if (!Files.isDirectory(legacy) || legacy.equals(target)) {
            return;
        }
        try (var paths = Files.walk(legacy)) {
            for (Path source : paths.filter(Files::isRegularFile).toList()) {
                Path destination = target.resolve(legacy.relativize(source));
                if (Files.exists(destination)) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            }
            getLogger().info("Imported existing data from the ModernMCExchange folder.");
        } catch (IOException exception) {
            getLogger().warning("Could not migrate all existing plugin data: " + exception.getMessage());
        }
    }

    private boolean connectStorage() {
        String type = getConfig().getString("storage.type", "mariadb").toLowerCase();
        String jdbcUrl;
        String driverClass;
        if (type.equals("h2")) {
            String file = getConfig().getString("storage.file", "market");
            Path pluginFolder = getDataFolder().toPath().toAbsolutePath().normalize();
            Path databasePath = pluginFolder.resolve(file).normalize();
            if (!databasePath.startsWith(pluginFolder)) {
                getLogger().severe("H2 file path must remain inside the plugin data folder.");
                return false;
            }
            jdbcUrl = "jdbc:h2:file:" + databasePath + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
            driverClass = "org.h2.Driver";
        } else if (type.equals("mariadb")) {
            String host = getConfig().getString("storage.host", "localhost");
            int port = getConfig().getInt("storage.port", 3306);
            String database = getConfig().getString("storage.database", "grand_exchange");
            jdbcUrl = "jdbc:mariadb://" + host + ":" + port + "/" + database
                    + "?connectTimeout=5000&socketTimeout=10000";
            driverClass = "org.mariadb.jdbc.Driver";
        } else {
            getLogger().severe("Unsupported storage.type '" + type + "' (use h2 or mariadb).");
            return false;
        }

        String username = getConfig().getString("storage.username", "grandexchange");
        String password = getConfig().getString("storage.password", "");
        int poolSize = Math.max(2, getConfig().getInt("storage.pool-size", 4));

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setDriverClassName(driverClass);
        if (type.equals("mariadb")) {
            hikari.setUsername(username);
            hikari.setPassword(password);
        }
        hikari.setMaximumPoolSize(poolSize);
        hikari.setPoolName("GrandExchange-" + serverId);
        hikari.setConnectionTimeout(5000);
        hikari.setInitializationFailTimeout(5000);
        try {
            dataSource = new HikariDataSource(hikari);
            return true;
        } catch (RuntimeException exception) {
            getLogger().severe("Exchange database connection failed: " + exception.getMessage());
            return false;
        }
    }

    private boolean connectEconomy() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            return false;
        }
        economy = provider.getProvider();
        return economy != null;
    }

    private void closeStorage() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    public String serverId() {
        return serverId;
    }

    public boolean reloadLiveConfiguration() {
        reloadConfig();
        boolean restartRequired = false;
        for (Map.Entry<String, Object> setting : startupStorageSettings.entrySet()) {
            String path = "storage." + setting.getKey();
            if (!Objects.equals(getConfig().get(path), setting.getValue())) {
                restartRequired = true;
                getConfig().set(path, setting.getValue());
            }
        }
        String configuredServerId = getConfig().getString("server-id", "unknown");
        if (!serverId.equalsIgnoreCase(configuredServerId)) {
            restartRequired = true;
            getConfig().set("server-id", serverId);
        }
        if (baselinePrices != null) {
            baselinePrices.reload();
        }
        if (shopCategories != null) {
            shopCategories.reload();
        }
        if (customItems != null) {
            customItems.reload();
        }
        return restartRequired;
    }

    public Economy economy() {
        return economy;
    }

    public BaselinePrices baselinePrices() {
        return baselinePrices;
    }

    public ShopCategoryCatalog shopCategories() {
        return shopCategories;
    }

    public CustomItemCatalog customItems() {
        return customItems;
    }

    public ExchangeMenu exchangeMenu() {
        return exchangeMenu;
    }
}
