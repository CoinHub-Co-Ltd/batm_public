package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response;

import java.math.BigDecimal;

public class TransactionDetailsResponse {
    // Original fields for trade details
    public String ledger_id;
    public String trade_id;
    public String instrument_id;
    public String price;
    public String size;
    public String order_id;
    public String timestamp;
    public String exec_type;
    public String fee;
    public String side;
    public String currency;
    
    // Response fields for save operation
    public String result;
    public String message;
    public String transaction_id;
    public String status;
    public String created_at;
    public String updated_at;
}