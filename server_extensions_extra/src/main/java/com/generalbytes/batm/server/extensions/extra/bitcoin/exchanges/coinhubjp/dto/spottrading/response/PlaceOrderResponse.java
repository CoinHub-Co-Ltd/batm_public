package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.dto.spottrading.response;

public class PlaceOrderResponse {
    public String order_id;
    public boolean result;
    public String timestamp;
    public String message;
    public Raw raw;

    public static class Raw {
        public String msg;
        public int code;
    }
}