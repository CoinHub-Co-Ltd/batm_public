package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request;

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
    /** Mirrors expected_profit (server-reported expected profit percent). */
    public String profit_percent;
    /** Fixed fee as percent of cash_amount when fixed fee is in fiat. */
    public String fixed_fee_percent_of_cash;
    /** Fiat per 1 unit crypto from the transaction: cash_amount / crypto_amount. */
    public String customer_effective_rate;
    /** (customer_effective_rate / rate_source_price - 1) * 100. */
    public String markup_percent_vs_rate_source;
    /** Buy only: cash_amount minus crypto_amount * rate_source_price (approximate fiat vs source). */
    public String estimated_profit_fiat;
    /** Profit % from crypto settings in admin (buy), when resolvable for this terminal + coin. */
    public String configured_profit_buy_percent;
    /** Profit % from crypto settings in admin (sell), when resolvable for this terminal + coin. */
    public String configured_profit_sell_percent;
    public String discount_code;
    public String note;
    public String server_time;
    public String terminal_time;
}