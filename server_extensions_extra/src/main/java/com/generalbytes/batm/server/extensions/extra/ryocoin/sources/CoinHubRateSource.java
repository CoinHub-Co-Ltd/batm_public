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

import com.generalbytes.batm.common.currencies.CryptoCurrency;
import com.generalbytes.batm.common.currencies.FiatCurrency;
import com.generalbytes.batm.server.extensions.IRateSourceAdvanced;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.CoinHubFeeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.mazi.rescu.RestProxyFactory;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.RateResponse;


public class CoinHubRateSource implements IRateSourceAdvanced {
    private static final Logger log = LoggerFactory.getLogger(CoinHubRateSource.class);
    private final ICoinHubAPI api;
    private String preferredFiatCurrency = FiatCurrency.JPY.getCode();
    private String apiKey;
    private String liquidityProvider = CoinHubFeeConfig.DEFAULT_LIQUIDITY_PROVIDER;

    public CoinHubRateSource(String preferedFiatCurrency, String apiKey, String apiEndpoint) {
        this(preferedFiatCurrency, apiKey, apiEndpoint, CoinHubFeeConfig.DEFAULT_LIQUIDITY_PROVIDER);
    }

    public CoinHubRateSource(String preferedFiatCurrency, String apiKey, String apiEndpoint, String liquidityProvider) {
        this.apiKey = apiKey;
        if (liquidityProvider != null && !liquidityProvider.trim().isEmpty()) {
            String normalized = liquidityProvider.trim().toLowerCase();
            if ("okj".equals(normalized) || "mexc".equals(normalized)) {
                this.liquidityProvider = normalized;
            }
        }
        api = RestProxyFactory.createProxy(ICoinHubAPI.class, apiEndpoint);
    }

    @Override
    public Set<String> getCryptoCurrencies() {
        Set<String> result = new HashSet<String>();
        result.add(CryptoCurrency.BTC.getCode());
        result.add(CryptoCurrency.DOGE.getCode());
        result.add(CryptoCurrency.ETH.getCode());
        result.add(CryptoCurrency.SHIB.getCode());
        result.add(CryptoCurrency.SOL.getCode());
        return result;
    }

    @Override
    public Set<String> getFiatCurrencies() {
        Set<String> result = new HashSet<String>();
        result.add(FiatCurrency.JPY.getCode());
        return result;
    } 

    @Override
    public BigDecimal getExchangeRateLast(String cryptoCurrency, String fiatCurrency) {
        return getExchangeRateForBuy(cryptoCurrency,fiatCurrency);
    }

    @Override
    public String getPreferredFiatCurrency() {
        return preferredFiatCurrency;
    }


    @Override
    public BigDecimal getExchangeRateForBuy(String cryptoCurrency, String fiatCurrency) {
        final RateResponse rate = api.getBuyRate(apiKey, cryptoCurrency, fiatCurrency, liquidityProvider);
        if (rate != null) {
            return rate.best_ask;
        }
        return null;
    }

    @Override
    public BigDecimal getExchangeRateForSell(String cryptoCurrency, String fiatCurrency) {
        final RateResponse rate = api.getSellRate(apiKey, cryptoCurrency, fiatCurrency, liquidityProvider);
        if (rate != null) {
            return rate.best_bid;
        }
        return null;
    }

    @Override
    public BigDecimal calculateBuyPrice(String cryptoCurrency, String fiatCurrency, BigDecimal cryptoAmount) {
        log.info("calculateBuyPrice executed");
        final BigDecimal rate = getExchangeRateForBuy(cryptoCurrency, fiatCurrency);
        if (rate != null) {
            return rate.multiply(cryptoAmount);
        }
        return null;
    }

    @Override
    public BigDecimal calculateSellPrice(String cryptoCurrency, String fiatCurrency, BigDecimal cryptoAmount) {
        final BigDecimal rate = getExchangeRateForSell(cryptoCurrency, fiatCurrency);
        if (rate != null) {
            return rate.multiply(cryptoAmount);
        }
        return null;
    }

}
