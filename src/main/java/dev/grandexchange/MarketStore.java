package dev.grandexchange;

import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Material;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

public final class MarketStore {
    private final HikariDataSource dataSource;

    public MarketStore(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public MarketQuote getQuote(Material material) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            BigDecimal bestBid = bestOffer(connection, material, "BUY", true);
            BigDecimal bestAsk = bestOffer(connection, material, "SELL", false);
            Optional<TradeSummary> recent = recentTrades(connection, material);
            return new MarketQuote(
                    bestBid,
                    bestAsk,
                    recent.map(TradeSummary::lastPrice).orElse(null),
                    recent.map(TradeSummary::averagePrice).orElse(null),
                    recent.map(TradeSummary::volume).orElse(0L)
            );
        }
    }

    private BigDecimal bestOffer(Connection connection, Material material, String side, boolean descending)
            throws SQLException {
        String direction = descending ? "DESC" : "ASC";
        String sql = "SELECT unit_price FROM ge_offers "
                + "WHERE material = ? AND side = ? AND status = 'OPEN' AND remaining > 0 "
                + "ORDER BY unit_price " + direction + ", created_at ASC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, material.name());
            statement.setString(2, side);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBigDecimal(1) : null;
            }
        }
    }

    private Optional<TradeSummary> recentTrades(Connection connection, Material material) throws SQLException {
        String sql = "SELECT "
                + "(SELECT unit_price FROM ge_trades WHERE material = ? "
                + "ORDER BY traded_at DESC, id DESC LIMIT 1) AS last_price, "
                + "SUM(CASE WHEN traded_at >= ? "
                + "THEN unit_price * quantity ELSE 0 END) / "
                + "NULLIF(SUM(CASE WHEN traded_at >= ? "
                + "THEN quantity ELSE 0 END), 0) AS average_price, "
                + "COALESCE(SUM(CASE WHEN traded_at >= ? "
                + "THEN quantity ELSE 0 END), 0) AS volume "
                + "FROM ge_trades WHERE material = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, material.name());
            Timestamp cutoff = Timestamp.from(Instant.now().minus(24, ChronoUnit.HOURS));
            statement.setTimestamp(2, cutoff);
            statement.setTimestamp(3, cutoff);
            statement.setTimestamp(4, cutoff);
            statement.setString(5, material.name());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                BigDecimal lastPrice = result.getBigDecimal("last_price");
                BigDecimal averagePrice = result.getBigDecimal("average_price");
                long volume = result.getLong("volume");
                if (lastPrice == null && averagePrice == null && volume == 0) {
                    return Optional.empty();
                }
                return Optional.of(new TradeSummary(lastPrice, averagePrice, volume));
            }
        }
    }

    private record TradeSummary(BigDecimal lastPrice, BigDecimal averagePrice, long volume) {
    }
}
