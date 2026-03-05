package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request;

import java.math.BigDecimal;
import java.util.List;

public class CancelMultipleOrdersRequest {
    public List<String[]> order_ids;
    public String instrument_id;
    public String client_oids;
}