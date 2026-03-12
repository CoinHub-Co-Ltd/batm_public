package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response;

import java.math.BigDecimal;

/** 
 * Note: @type enum value options (transfer,trade, rebate)
 */
public class BillsDetailsResponse {
    public String ledger_id;
    public String balance;
    public String currency;
    public String amount;
    public String type;
    public String timestamp;
    public String details;
    public String order_id;
    public String instrument_id;
}