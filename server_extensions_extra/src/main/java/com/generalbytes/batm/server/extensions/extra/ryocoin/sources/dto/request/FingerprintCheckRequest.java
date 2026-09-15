package com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.request;

public class FingerprintCheckRequest {
    public String address;
    public String fingerprint;

    public FingerprintCheckRequest() {
    }

    public FingerprintCheckRequest(String address, String fingerprint) {
        this.address = address;
        this.fingerprint = fingerprint;
    }
}
