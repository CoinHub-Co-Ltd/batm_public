package com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateCustomerRequest {
    public String firstName;
    public String lastName;
    public String birthOfDate;
    public String country;
    public String occupation;
    public String address;
    public String purpose;
    public String email;
    public String phone;
}
