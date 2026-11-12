package com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.request;

public class FingerprintIdentityCheckRequest {
    public String identityPublicId;
    public String address;
    public String fingerprint;
    public String firstName;
    public String lastName;
    public String birthOfDate;

    public FingerprintIdentityCheckRequest() {
    }

    public FingerprintIdentityCheckRequest(String identityPublicId, String address, String fingerprint,
                                            String firstName, String lastName, String birthOfDate) {
        this.identityPublicId = identityPublicId;
        this.address = address;
        this.fingerprint = fingerprint;
        this.firstName = firstName;
        this.lastName = lastName;
        this.birthOfDate = birthOfDate;
    }
}
