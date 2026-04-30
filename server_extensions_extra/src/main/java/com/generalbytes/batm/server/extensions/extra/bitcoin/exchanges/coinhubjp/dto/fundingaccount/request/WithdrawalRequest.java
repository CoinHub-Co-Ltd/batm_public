package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.dto.fundingaccount.request;

import java.math.BigDecimal;

public class WithdrawalRequest {
    public String crypto;
    public BigDecimal amount;
    public String destination;
    /** API expects "recipient" (note: historical misspelling kept for compatibility). */
    public String recipient;
    /** @deprecated Typo: kept so older servers/clients that expect it still work. */
    @Deprecated
    public String recepient;
    public String trade_pwd;
    public String fee;
    public String chain;
    public String usage_agreement;
    public String reason;
}