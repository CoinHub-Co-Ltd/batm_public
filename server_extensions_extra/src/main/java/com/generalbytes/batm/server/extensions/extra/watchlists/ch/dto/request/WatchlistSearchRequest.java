package com.generalbytes.batm.server.extensions.extra.watchlists.ch.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WatchlistSearchRequest {
    public String firstName;
    public String lastName;
    public String country;
    public String birthOfDate;
    public String name;
    public String address;
    public String occupation;
    public String purpose;
    public String identityPublicId;
    public String email;
    public String phone;
    public String fingerprint;
}

