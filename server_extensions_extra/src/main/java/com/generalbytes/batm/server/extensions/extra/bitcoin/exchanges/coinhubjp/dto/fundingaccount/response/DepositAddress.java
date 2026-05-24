package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.dto.fundingaccount.response;

public class DepositAddress {
    public String address;
    public String memo;
    public Raw raw;

    public static class Raw {
        public String coin;
        public String network;
        public String address;
        public String memo;
        public String chainName;
        public String chainDisplayName;
        public String netWork;
    }
}