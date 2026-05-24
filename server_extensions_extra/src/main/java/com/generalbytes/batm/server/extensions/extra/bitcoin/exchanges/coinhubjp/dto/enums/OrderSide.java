package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.dto.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OrderSide {
    BUY("buy"),
    SELL("sell");

    private final String apiValue;

    OrderSide(String apiValue) {
        this.apiValue = apiValue;
    }

    @JsonValue
    public String getApiValue() {
        return apiValue;
    }

    @JsonCreator
    public static OrderSide fromValue(String value) {
        if (value == null) {
            return null;
        }
        if ("buy".equalsIgnoreCase(value)) {
            return BUY;
        }
        if ("sell".equalsIgnoreCase(value)) {
            return SELL;
        }
        throw new IllegalArgumentException("Unsupported side: " + value);
    }
}