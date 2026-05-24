/*************************************************************************************
 * Copyright (C) 2014-2020 GENERAL BYTES s.r.o. All rights reserved.
 *
 * This software may be distributed and modified under the terms of the GNU
 * General Public License version 2 (GPL2) as published by the Free Software
 * Foundation and appearing in the file GPL2.TXT included in the packaging of
 * this file. Please note that GPL2 Section 2[b] requires that all works based
 * on this software must also be made publicly available under the terms of
 * the GPL2 ("Copyleft").
 *
 * Contact information
 * -------------------
 *
 * GENERAL BYTES s.r.o.
 * Web      :  http://www.generalbytes.com
 *
 ************************************************************************************/

package com.generalbytes.batm.server.extensions.extra.ryocoin.sources;

import java.math.BigDecimal;

import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.request.TransactionFeesRequest;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.RateResponse;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.TransactionFeesResponse;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.WalletBalanceResponse;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;


@Path("/v2/api")
public interface ICoinHubAPI {

    @GET
    @Path("market/rate/buy/{cryptoCurrency}/{fiatCurrency}/")
    RateResponse getBuyRate(
        @HeaderParam("X-API-SECRET") String apiKey,
        @PathParam("cryptoCurrency") String cryptoCurrency,
        @PathParam("fiatCurrency") String fiatCurrency);

    @GET
    @Path("market/rate/sell/{cryptoCurrency}/{fiatCurrency}/")
    RateResponse getSellRate(
        @HeaderParam("X-API-SECRET") String apiKey,
        @PathParam("cryptoCurrency") String cryptoCurrency,
        @PathParam("fiatCurrency") String fiatCurrency);

    @GET
    @Path("paperwallet/wallet/{address}/{cryptoCurrency}/balance")
    WalletBalanceResponse getWalletBalance(
        @HeaderParam("X-API-SECRET") String apiKey,
        @PathParam("address") String address,
        @PathParam("cryptoCurrency") String cryptoCurrency);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/service/transaction/fees")
    TransactionFeesResponse getTransactionFees(@HeaderParam("X-API-SECRET") String apiKey, TransactionFeesRequest request);
}
