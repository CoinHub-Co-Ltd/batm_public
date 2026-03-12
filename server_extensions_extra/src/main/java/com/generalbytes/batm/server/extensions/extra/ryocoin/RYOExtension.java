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
package com.generalbytes.batm.server.extensions.extra.ryocoin;

import com.generalbytes.batm.common.currencies.CryptoCurrency;
import com.generalbytes.batm.common.currencies.FiatCurrency;
import com.generalbytes.batm.server.extensions.*;
import com.generalbytes.batm.server.extensions.FixPriceRateSource;
import com.generalbytes.batm.server.extensions.ExtensionsUtil;
import com.generalbytes.batm.server.extensions.extra.ryocoin.wallets.ryocoind.RYOAPIWallet;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.CoinHubRateSource;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.CoinHubEokjpExchange;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.CoinHubFeeTransactionListener;
import com.generalbytes.batm.server.extensions.IExtensionContext;
import com.generalbytes.batm.server.extensions.ITerminal;
import com.generalbytes.batm.server.extensions.ITerminalListener;

import java.math.BigDecimal;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RYOExtension extends AbstractExtension implements ITerminalListener {
    private IExtensionContext ctx;
    private static final BigDecimal STATIC_BUY_PROFIT = new BigDecimal("11.11"); 
    private static final Logger log = LoggerFactory.getLogger(RYOExtension.class);
    private String chEndpoint = "http://operation.coinhubportal.test";
    private String apiKey = "apitest";
    @Override
    public void init(IExtensionContext ctx) {
        this.ctx = ctx;
        ctx.addTerminalListener(this);
        // check if production and get the api key and endpoint from the config file
        if (ctx.configFileExists("coinhub")) {
            apiKey = ctx.getConfigProperty("coinhub", "api_key", null);
            chEndpoint = ctx.getConfigProperty("coinhub", "api_endpoint", null);
        }
        CoinHubFeeTransactionListener feeListener = new CoinHubFeeTransactionListener(ctx, apiKey, chEndpoint);
        ctx.addTransactionListener(feeListener);
    }

    @Override
    public String getName() {
        return "BATM RYO extension";
    }

    @Override
    public IWallet createWallet(String walletLogin, String tunnelPassword) {
        try {
        if (walletLogin !=null && !walletLogin.trim().isEmpty()) {
            StringTokenizer st = new StringTokenizer(walletLogin,":");
            String walletType = st.nextToken();

            if ("ryoapi".equalsIgnoreCase(walletType)) {
                // Simple API-only wallet: "ryoapi:label"
                String label = "RYO Wallet";
                if (st.hasMoreTokens()) {
                    label = st.nextToken();
                }
                
                log.info("Creating RYO API wallet with label: {}", label);
                return new RYOAPIWallet(label);
            }
            
            if ("coinhubeokjp".equalsIgnoreCase(walletType)) {
                // Create CoinHub exchange as wallet: "coinhubeokjp"
                String secretKey = null;
                String terminalSerialNumber = "COINHUB-ATM";
                if (ctx != null) {
                    List<ITerminal> terminals = ctx.findAllTerminals();
                    if (terminals != null && !terminals.isEmpty()) {
                        terminalSerialNumber = terminals.get(0).getSerialNumber();
                    }
                }
                CoinHubEokjpExchange exchange = new CoinHubEokjpExchange(apiKey, secretKey, terminalSerialNumber, chEndpoint);
                exchange.setExtensionContext(ctx);
                log.info("Creating CoinHub exchange as wallet with terminalSerialNumber: {}", terminalSerialNumber);
                return exchange;
            }
        }
        } catch (Exception e) {
            ExtensionsUtil.logExtensionParamsException("createWallet", getClass().getSimpleName(), walletLogin, e);
        }
        return null;
    }

    @Override
    public ICryptoAddressValidator createAddressValidator(String cryptoCurrency) {
        if (CryptoCurrency.RYO.getCode().equalsIgnoreCase(cryptoCurrency)) {
            return new RYOAddressValidator();
        }
        return null;
    }

    @Override
    public IRateSource createRateSource(String sourceLogin) {
        if (sourceLogin != null && !sourceLogin.trim().isEmpty()) {
            try {
                StringTokenizer st = new StringTokenizer(sourceLogin, ":");
                String exchangeType = st.nextToken();

                if ("coinhubratesource".equalsIgnoreCase(exchangeType)) {
                    String preferedFiatCurrency = FiatCurrency.JPY.getCode();
                    return new CoinHubRateSource(preferedFiatCurrency, apiKey, chEndpoint);
                }
            } catch (Exception e) {
                ExtensionsUtil.logExtensionParamsException("createRateSource", getClass().getSimpleName(), sourceLogin, e);
            }

        }
        return null;
    }

    @Override
    public Set<String> getSupportedCryptoCurrencies() {
        Set<String> result = new HashSet<String>();
        result.add(CryptoCurrency.RYO.getCode());  // Add RYO support
        result.add(CryptoCurrency.SHIB.getCode());
        result.add(CryptoCurrency.BTC.getCode());
        result.add(CryptoCurrency.ETH.getCode());
        result.add(CryptoCurrency.DOGE.getCode());

        return result;
    }

    @Override
    public IExchange createExchange(String exchangeLogin) {
        try {
            if ((exchangeLogin != null) && (!exchangeLogin.trim().isEmpty())) {
                StringTokenizer paramTokenizer = new StringTokenizer(exchangeLogin, ":");
                String prefix = paramTokenizer.nextToken();
                if ("coinhubeokjp".equalsIgnoreCase(prefix)) {
                    // String apiKey = ctx.getConfigProperty("coinhub", "api_key", "default_key");
                    String secretKey = null;
                    // Get the serial number from the first terminal in the context
                    String terminalSerialNumber = "COINHUB-ATM";
                    if (ctx != null) {
                        List<ITerminal> terminals = ctx.findAllTerminals();
                        if (terminals != null && !terminals.isEmpty()) {
                            terminalSerialNumber = terminals.get(0).getSerialNumber();
                        }
                    }
                    CoinHubEokjpExchange exchange = new CoinHubEokjpExchange(apiKey, secretKey, terminalSerialNumber, chEndpoint);
                    exchange.setExtensionContext(ctx);
                    return exchange;
                }
            }
        } catch (Exception e) {
            ExtensionsUtil.logExtensionParamsException("createExchange", getClass().getSimpleName(), exchangeLogin, e);
        }
        return null;
    }

    @Override
    public BigDecimal overrideProfitBuy(String serialNumber, String cryptoCurrency, BigDecimal profitBuy) {
        log.info("[RYOExtension] overrideProfitBuy called: terminal={}, cryptoCurrency={}, adminProfitBuy={}, staticProfit={}",
                serialNumber, cryptoCurrency, profitBuy, STATIC_BUY_PROFIT);
        // Always return static buy profit
        return profitBuy;
    }

    @Override
    public BigDecimal overrideProfitSell(String serialNumber, String cryptoCurrency, BigDecimal profitSell) {
        // Use admin configuration for sell profit
        return null;
    }
}
