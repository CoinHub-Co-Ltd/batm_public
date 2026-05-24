package com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response;

import java.math.BigDecimal;

public class TransactionFeesResponse {
    /**
     * CoinHub fee in percent (e.g. 10.05 means 10.05%).
     */
    public BigDecimal ch_fee;

    /**
     * Trading fee in fraction (e.g. 0.0005 means 0.05%).
     */
    public BigDecimal trade_fee;

    /**
     * Withdrawal fee in fraction (e.g. 5.0e-5).
     */
    public BigDecimal withdrawal_fee;
}

