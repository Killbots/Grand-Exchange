package dev.grandexchange;

import java.math.BigDecimal;

public record MarketQuote(
        BigDecimal bestBid,
        BigDecimal bestAsk,
        BigDecimal lastPrice,
        BigDecimal averagePrice24h,
        long volume24h
) {
}
