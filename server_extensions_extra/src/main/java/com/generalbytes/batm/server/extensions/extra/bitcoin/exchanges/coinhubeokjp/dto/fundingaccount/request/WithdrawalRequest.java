package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.request;

import java.math.BigDecimal;

public class WithdrawalRequest {
    public String currency;
    public BigDecimal amount;
    public String destination;
    public String to_address;
    public String trade_pwd;
    public String fee;
    public String chain;
    public String usage_agreement;
    public String reason;
}