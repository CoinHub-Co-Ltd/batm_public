package com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response;

import java.math.BigDecimal;

public class LedgerEntry {
    public Long id;
    public String tx_id;
    public String identity_id;
    public Integer transaction_id;
    public BigDecimal amount;
    public String transaction_type;
    public String status;
    public String created_at;
    public String updated_at;
}
