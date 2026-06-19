package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.dto.spottrading.request;

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
    public String fee_discount;
    public String crypto_discount_amount;
    public String discount_quotient;
    public String rate_source_price;
    public String expected_profit;
    public String profit_percent;
    public String fixed_fee_percent_of_cash;
    public String customer_effective_rate;
    public String markup_percent_vs_rate_source;
    public String estimated_profit_fiat;
    public String configured_profit_buy_percent;
    public String configured_profit_sell_percent;
    public String discount_code;
    public String note;
    public String server_time;
    public String terminal_time;
    public String market_rate;
    public String markup_rate;
    public String fiat_money;
    public String fiat_currency;
    public String crypto_code;
    public String type;
    public String markup_coinhub_fee_percentage;
    public String markup_trade_fee_percentage;
    public String markup_fx_spread_percentage;
    public String hot_wallet_markup_subtotal_percentage;
    public String markup_cas_buffer;
    public String markup_total_percentage;

    public String liquidity_type;
    public String amount_insert;
    public String withdrawal_fee_jpy;
    public String jpy_to_usd_rate;
    public String trading_amount_jpy;
    public String trading_amount_usd;
    public String coinhub_fee_amount;
    public String coinhub_fee_percentage;
    public String trader_fee;
    public String trader_fee_jpy;
    public String btc_amount;
    public String btc_gas_fee;
}