package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request;

import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.enums.OrderSide;
import java.math.BigDecimal;
import java.util.UUID;

public class PlaceOrderRequest {
    public String client_oid;
    public String type;
    public OrderSide side;
    public String instrument_id;
    public String order_type;
    public String size;
    public BigDecimal fiat_amount;
}