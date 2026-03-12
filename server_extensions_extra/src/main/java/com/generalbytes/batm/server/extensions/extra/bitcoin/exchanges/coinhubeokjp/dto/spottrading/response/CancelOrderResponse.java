package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response;

import java.math.BigDecimal;

/** 
 * Note: @type enum value options (transfer,trade, rebate)
 */
public class CancelOrderResponse {
    public String order_id;
    public String client_oid;
    public String result;
    public String error_code;
    public String error_message;
}