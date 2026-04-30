package com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.request;

public class TransactionFeesRequest {
    public String instrument_id;

    public TransactionFeesRequest() {
    }

    public TransactionFeesRequest(String instrument_id) {
        this.instrument_id = instrument_id;
    }
}

