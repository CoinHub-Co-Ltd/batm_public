package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.dto.spottrading.request;

import java.math.BigDecimal;

/** 
 * Note: @type enum value options (transfer,trade, rebate)
 */
public class BillsDetailsRequest {
    public String currency;
    public String after;
    public String before;
    public String limit;
    public String type;
}