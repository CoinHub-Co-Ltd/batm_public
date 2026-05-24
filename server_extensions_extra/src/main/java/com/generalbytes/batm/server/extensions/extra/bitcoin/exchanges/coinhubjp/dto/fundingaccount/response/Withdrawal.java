package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.dto.fundingaccount.response;

public class Withdrawal {
    public String message;
    public Data data;

    public static class Data {
        public boolean result;
        public String withdrawOrderId;
        public String timestamp;
        public Raw raw;
    }

    public static class Raw {
        public int code;
        public String msg;
        public long timestamp;
    }
}