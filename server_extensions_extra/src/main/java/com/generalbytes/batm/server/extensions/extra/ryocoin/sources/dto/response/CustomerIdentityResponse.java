package com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response;

public class CustomerIdentityResponse {
    public String status;
    public CustomerIdentity data;

    public static class CustomerIdentity {
        public String firstName;
        public String lastName;
        public String birthOfDate;
    }
}
