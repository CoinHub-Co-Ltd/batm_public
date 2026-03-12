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
package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp;

import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.response.Balance;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.response.Currency;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.response.FundTransfer;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.response.Withdrawal;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.request.FundTransferRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.request.WithdrawalRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.request.DepositAddressRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.response.DepositAddress;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request.PlaceOrderRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response.PlaceOrderResponse;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request.OrderBookRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request.TransactionDetailsRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response.OrderBookResponse;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response.TransactionDetailsResponse;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.RateResponse;

import com.generalbytes.batm.server.extensions.util.OrderBookPriceCalculator;
import com.generalbytes.batm.server.extensions.util.net.RateLimitingInterceptor;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.List;
import java.math.BigDecimal;

@Path("/v1/api")
@Produces(MediaType.APPLICATION_JSON)
public interface ICoinHubEokjpAPI {

    @GET
    @Path("/account/v3/wallet")
    Balance getBalance() throws IOException;

    @GET
    @Path("/account/v3/wallet/{currency}")
    @Produces("application/json")
    Currency getCurrency(@HeaderParam("X-API-SECRET") String apiKey, @PathParam("currency") String currency) throws IOException;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/account/v3/transfer")
    FundTransfer transferFund(@HeaderParam("X-API-SECRET") String apiKey, FundTransferRequest fundTransferRequest) throws IOException;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/account/v3/withdrawal")
    Withdrawal withdraw(@HeaderParam("X-API-SECRET") String apiKey, WithdrawalRequest withdrawalRequest) throws IOException;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/spot/v3/orders")
    PlaceOrderResponse placeOrder(@HeaderParam("X-API-SECRET") String apiKey, PlaceOrderRequest placeOrderRequest) throws IOException;

    @GET
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/spot/v3/instruments/{instrument_id}/book")
    OrderBookResponse getOrderBook(@HeaderParam("X-API-SECRET") String apiKey, @PathParam("instrument_id") String instrument_id,
                        @QueryParam("size") int size,
                        @QueryParam("depth") double depth) throws IOException;
    @GET
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/account/v3/deposit/address/{currency}")
    @Produces("application/json")
    DepositAddress getDepositAddress(@HeaderParam("X-API-SECRET") String apiKey, @PathParam("currency") String currency) throws IOException;

     @GET
    @Path("/rate/buy/{crypto_currency}/{fiat_currency}/")
    RateResponse getBuyRate(@HeaderParam("X-API-SECRET") String apiKey, @PathParam("crypto_currency") String cryptoCurrency, @PathParam("fiat_currency") String fiatCurrency);

    @GET
    @Path("/rate/sell/{crypto_currency}/{fiat_currency}/")
    RateResponse getSellRate(@HeaderParam("X-API-SECRET") String apiKey, @PathParam("crypto_currency") String cryptoCurrency, @PathParam("fiat_currency") String fiatCurrency);
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/buy/transaction-details")
    TransactionDetailsResponse saveTransactionDetails(@HeaderParam("X-API-SECRET") String apiKey, TransactionDetailsRequest request);
}
