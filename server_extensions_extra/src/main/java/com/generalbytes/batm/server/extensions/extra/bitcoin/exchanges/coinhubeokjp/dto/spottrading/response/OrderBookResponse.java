package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response;

import java.math.BigDecimal;
import java.util.List;

public class OrderBookResponse {
     public List<List<String>> asks;
    public List<List<String>> bids;
    public String timestamp;

    @Override
    public String toString() {
        return "OrderBookResponse{" +
            "asks=" + asks +
            ", bids=" + bids +
            ", timestamp='" + timestamp + '\'' +
            '}';
    }
}