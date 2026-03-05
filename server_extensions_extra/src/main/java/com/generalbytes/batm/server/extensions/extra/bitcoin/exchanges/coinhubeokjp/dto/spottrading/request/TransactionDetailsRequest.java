package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request;

import java.math.BigDecimal;

public class TransactionDetailsRequest {
    public String order_id;
    public String instrument_id;
    public String after;
    public String before;
    public String limit;
    
    // Transaction amounts and fees
    public String cash_amount;
    public String cash_currency;
    public String crypto_amount;
    public String crypto_currency;
    public String fixed_fee;
    public String fee_currency;
    public String net_crypto_amount;
    public String transaction_type;
    public String transaction_status;
    public String terminal_serial_number;
    public String identity_public_id;
    public String cellphone_used;
    public String event_type;
    
    // Additional fee and discount information
    public String fee_discount;
    public String crypto_discount_amount;
    public String discount_quotient;
    public String rate_source_price;
    public String expected_profit;
    public String discount_code;
    public String note;
    public String server_time;
    public String terminal_time;
}