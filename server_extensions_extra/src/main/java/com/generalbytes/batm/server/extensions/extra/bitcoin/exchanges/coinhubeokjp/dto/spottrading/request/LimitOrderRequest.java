package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request;

import java.math.BigDecimal;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request.PlaceOrderRequest;

public class LimitOrderRequest extends PlaceOrderRequest {
    public BigDecimal price;
}