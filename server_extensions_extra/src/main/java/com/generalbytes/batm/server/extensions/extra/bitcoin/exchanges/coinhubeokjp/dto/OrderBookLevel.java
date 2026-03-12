package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto;

import java.math.BigDecimal;

public class OrderBookLevel {
    private BigDecimal pricePerUnit;
    private BigDecimal quantity;

    public OrderBookLevel(BigDecimal pricePerUnit, BigDecimal quantity) {
        this.pricePerUnit = pricePerUnit;
        this.quantity = quantity;
    }

    public BigDecimal getPrice() {
        return pricePerUnit;
    }

    public BigDecimal getAmount() {
        return quantity;
    }
}
