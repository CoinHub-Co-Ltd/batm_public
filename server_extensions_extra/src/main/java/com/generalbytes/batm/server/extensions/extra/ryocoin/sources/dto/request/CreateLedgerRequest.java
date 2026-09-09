package com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.request;

import java.math.BigDecimal;

public class CreateLedgerRequest {
    public String tx_id;
    public int transaction_id;
    public BigDecimal amount;
    public String transaction_type;
    public String status;
    public String identity_id;
    public String fingerprint_data;
    public String fingerprint_filename;
    public String address;
}
