package com.generalbytes.batm.server.extensions.extra.watchlists.ch;


public final class CoinHubAtmErrors {

    private CoinHubAtmErrors() {
    }

    public static String msg(String code, String text) {
        return "[" + code + "] " + text;
    }

    // Security 1 — initial-security / watchlist
    public static final String S1_DENIED = msg("00001", "Transaction denied.");
    public static final String S1_BLACKLISTED = msg("00002", "Black Listed. Please contact support.");
    public static final String S1_UNAVAILABLE = msg("00003", "CoinHub watchlist unavailable (empty response)");
    public static final String S1_CHECK_FAILED = msg("00004", "Transaction denied.");

    // Security 2 — fingerprint / limits
    public static final String S2_MISSING_ADDRESS = msg("00005", "Crypto currency or destination address is missing.");
    public static final String S2_FINGERPRINT_DENIED = msg("00006", "Transaction denied.");
    public static final String S2_FINGERPRINT_UNAVAILABLE = msg("0007", "Transaction limit check unavailable. Please try again later.");
    public static final String S2_LEDGER_UNAVAILABLE = msg("00008", "Transaction limit check unavailable. Please try again later.");
    public static final String S2_LIMIT_TRY_LATER = msg("00009", "Transaction limit exceeded. Please try again later.");
    public static final String S2_LIMIT_EXCEEDED = msg("00010", "Transaction limit exceeded.");

    public static String s1UnavailableDetail(String detail) {
        return msg("00002", "CoinHub watchlist unavailable: " + detail);
    }

    public static String s2MaxPerTx(String maxPerTx) {
        return msg("00016", "Maximum " + maxPerTx + " JPY per transaction exceeded.");
    }
}
