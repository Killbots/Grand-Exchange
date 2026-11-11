package dev.grandexchange;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class MarketSchema {
    private MarketSchema() {
    }

    static void initialize(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ge_offers (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      owner_uuid CHAR(36) NOT NULL,
                      owner_name VARCHAR(16) NOT NULL,
                      side VARCHAR(4) NOT NULL,
                      material VARCHAR(64) NOT NULL,
                      item_data BLOB NOT NULL,
                      quantity INT NOT NULL,
                      remaining INT NOT NULL,
                      unit_price DECIMAL(20, 4) NOT NULL,
                      status VARCHAR(12) NOT NULL DEFAULT 'OPEN',
                      origin_server VARCHAR(32) NOT NULL DEFAULT 'unknown',
                      created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                      updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ge_trades (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      offer_id BIGINT NOT NULL,
                      buyer_uuid CHAR(36) NOT NULL,
                      seller_uuid CHAR(36) NOT NULL,
                      material VARCHAR(64) NOT NULL,
                      quantity INT NOT NULL,
                      unit_price DECIMAL(20, 4) NOT NULL,
                      transaction_server VARCHAR(32) NOT NULL DEFAULT 'unknown',
                      traded_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ge_delivery_queue (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      player_uuid CHAR(36) NOT NULL,
                      item_data BLOB NOT NULL,
                      quantity INT NOT NULL,
                      source_trade BIGINT NOT NULL,
                      created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ge_payout_queue (
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      player_uuid CHAR(36) NOT NULL,
                      offer_id BIGINT NOT NULL,
                      amount DECIMAL(20, 4) NOT NULL,
                      created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ge_servers (
                      server_id VARCHAR(32) NOT NULL PRIMARY KEY,
                      plugin_version VARCHAR(32) NOT NULL,
                      last_seen_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    )
                    """);
            ensureColumn(connection, "ge_offers", "origin_server");
            ensureColumn(connection, "ge_trades", "transaction_server");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS ge_offers_book "
                    + "ON ge_offers (status, material, side, unit_price, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS ge_offers_server "
                    + "ON ge_offers (origin_server, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS ge_offers_owner "
                    + "ON ge_offers (owner_uuid, status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS ge_trades_market "
                    + "ON ge_trades (material, traded_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS ge_trades_server "
                    + "ON ge_trades (transaction_server, traded_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS ge_trades_players "
                    + "ON ge_trades (buyer_uuid, traded_at, seller_uuid)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS ge_delivery_player "
                    + "ON ge_delivery_queue (player_uuid, id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS ge_payout_player "
                    + "ON ge_payout_queue (player_uuid, id)");
        }
    }

    static void registerServer(Connection connection, String serverId, String version) throws SQLException {
        try (var update = connection.prepareStatement(
                "UPDATE ge_servers SET plugin_version = ?, last_seen_at = CURRENT_TIMESTAMP(3) WHERE server_id = ?")) {
            update.setString(1, version);
            update.setString(2, serverId);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (var insert = connection.prepareStatement(
                "INSERT INTO ge_servers (server_id, plugin_version, last_seen_at) VALUES (?, ?, CURRENT_TIMESTAMP(3))")) {
            insert.setString(1, serverId);
            insert.setString(2, version);
            insert.executeUpdate();
        }
    }

    private static void ensureColumn(Connection connection, String tableName, String columnName)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, null, null)) {
            while (columns.next()) {
                if (tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName
                    + " VARCHAR(32) NOT NULL DEFAULT 'unknown'");
        }
    }
}
