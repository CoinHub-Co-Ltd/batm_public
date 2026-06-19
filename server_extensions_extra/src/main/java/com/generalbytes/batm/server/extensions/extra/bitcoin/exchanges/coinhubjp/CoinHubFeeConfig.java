package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp;

import com.generalbytes.batm.server.extensions.IExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

public class CoinHubFeeConfig {
    private static final Logger log = LoggerFactory.getLogger(CoinHubFeeConfig.class);

    public static final BigDecimal DEFAULT_WITHDRAWAL_FEE_JPY = new BigDecimal("700");
    public static final BigDecimal DEFAULT_JPY_USD_RATE = new BigDecimal("160");
    public static final BigDecimal DEFAULT_COINHUB_FEE_PERCENT = new BigDecimal("10");
    public static final BigDecimal DEFAULT_FX_SPREAD_PERCENT = new BigDecimal("0.80");
    public static final BigDecimal DEFAULT_CAS_BUFFER_PERCENT = new BigDecimal("0.07");
    public static final String DEFAULT_BTC_WITHDRAWAL_SOURCE = "hotwallet";
    public static final boolean DEFAULT_USE_LIVE_FEES_API = true;

    private final IExtensionContext ctx;

    public CoinHubFeeConfig(IExtensionContext ctx) {
        this.ctx = ctx;
    }

    public BigDecimal getWithdrawalFeeJpy() {
        return readDecimal("withdrawal_fee_jpy", DEFAULT_WITHDRAWAL_FEE_JPY);
    }

    public BigDecimal getJpyUsdRate() {
        return readDecimal("jpy_usd_rate", DEFAULT_JPY_USD_RATE);
    }

    public BigDecimal getCoinhubFeePercent() {
        return readDecimal("coinhub_fee_percent", DEFAULT_COINHUB_FEE_PERCENT);
    }

    public BigDecimal getFxSpreadPercent() {
        return readDecimal("fx_spread_jpy_usd_percent", DEFAULT_FX_SPREAD_PERCENT);
    }

    public BigDecimal getCasBufferPercent() {
        return readDecimal("cas_buffer_percent", DEFAULT_CAS_BUFFER_PERCENT);
    }

    public String getBtcWithdrawalSource() {
        String value = readString("btc_withdrawal_source", DEFAULT_BTC_WITHDRAWAL_SOURCE);
        return value == null ? DEFAULT_BTC_WITHDRAWAL_SOURCE : value.trim().toLowerCase();
    }

    public boolean useLiveFeesApi() {
        if (ctx == null || !ctx.configFileExists("coinhub")) {
            return DEFAULT_USE_LIVE_FEES_API;
        }
        String value = ctx.getConfigProperty("coinhub", "use_live_fees_api", "true");
        return !"false".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private BigDecimal readDecimal(String key, BigDecimal defaultValue) {
        if (ctx == null || !ctx.configFileExists("coinhub")) {
            return defaultValue;
        }
        String raw = ctx.getConfigProperty("coinhub", key, null);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[CoinHubFeeConfig] Invalid {}={} — using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private String readString(String key, String defaultValue) {
        if (ctx == null || !ctx.configFileExists("coinhub")) {
            return defaultValue;
        }
        String raw = ctx.getConfigProperty("coinhub", key, null);
        return raw == null || raw.trim().isEmpty() ? defaultValue : raw.trim();
    }
}
