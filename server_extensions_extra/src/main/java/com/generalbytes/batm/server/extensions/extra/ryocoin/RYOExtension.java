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

import com.generalbytes.batm.common.currencies.FiatCurrency;
import com.generalbytes.batm.server.extensions.*;
import com.generalbytes.batm.server.extensions.FixPriceRateSource;
import com.generalbytes.batm.server.extensions.ExtensionsUtil;
import com.generalbytes.batm.server.extensions.extra.ryocoin.wallets.ryocoind.RYOAPIWallet;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.CoinHubRateSource;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.ICoinHubAPI;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.request.TransactionFeesRequest;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.RateResponse;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.TransactionFeesResponse;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.CoinHubJPExchange;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubjp.CoinHubJPFeeTransactionListener;
import com.generalbytes.batm.server.extensions.IExtensionContext;
import com.generalbytes.batm.server.extensions.ITerminal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.mazi.rescu.RestProxyFactory;

public class RYOExtension extends AbstractExtension implements ITerminalListener {
    private IExtensionContext ctx;
    private static IExtensionContext extensionContext;
    private static final Logger log = LoggerFactory.getLogger(RYOExtension.class);
    private String chEndpoint = "http://api.coinhubportal.test";
    private String apiKey = "apitest";
    private ICoinHubAPI coinHubApi;
    /**
     * Used by {@link CoinHubRestService} and other integration points that need CAS APIs without an instance reference.
     */
    public static IExtensionContext getExtensionContext() {
        return extensionContext;
    }

    @Override
    public void init(IExtensionContext ctx) {
        this.ctx = ctx;
        extensionContext = ctx;
        // check if production and get the api key and endpoint from the config file
        if (ctx.configFileExists("coinhub")) {
            apiKey = ctx.getConfigProperty("coinhub", "api_key", null);
            chEndpoint = ctx.getConfigProperty("coinhub", "api_endpoint", null);
        }
        coinHubApi = RestProxyFactory.createProxy(ICoinHubAPI.class, chEndpoint);
        CoinHubJPFeeTransactionListener feeListener = new CoinHubJPFeeTransactionListener(ctx, apiKey, chEndpoint);
        ctx.addTransactionListener(feeListener);
        ctx.addTerminalListener(this);
    }

    @Override
    public void deinit() {
        if (ctx != null) {
            ctx.removeTerminalListener(this);
        }
        extensionContext = null;
        super.deinit();
    }

    @Override
    public Set<IRestService> getRestServices() {
        Set<IRestService> services = new HashSet<>();
        services.add(new CoinHubRestService());
        return services;
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
            
            if ("coinhubjp".equalsIgnoreCase(walletType)) {
                // Create CoinHub exchange as wallet: "coinhubjp"
                String secretKey = null;
                String terminalSerialNumber = "COINHUB-ATM";
                if (ctx != null) {
                    List<ITerminal> terminals = ctx.findAllTerminals();
                    if (terminals != null && !terminals.isEmpty()) {
                        terminalSerialNumber = terminals.get(0).getSerialNumber();
                    }
                }
                CoinHubJPExchange exchange = new CoinHubJPExchange(apiKey, secretKey, terminalSerialNumber, chEndpoint);
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
        if ("RYO".equalsIgnoreCase(cryptoCurrency)) {
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
        // IMPORTANT: do not reference CryptoCurrency enum constants here.
        // This extension can be deployed into servers with older `currencies` jars where some constants
        // (e.g. RYO) may not exist, and direct enum-field access would crash with NoSuchFieldError.
        return new HashSet<>(Arrays.asList(
            "RYO",
            "SHIB",
            "BTC",
            "ETH",
            "DOGE"
        ));
    }

    @Override
    public IExchange createExchange(String exchangeLogin) {
        try {
            if ((exchangeLogin != null) && (!exchangeLogin.trim().isEmpty())) {
                StringTokenizer paramTokenizer = new StringTokenizer(exchangeLogin, ":");
                String prefix = paramTokenizer.nextToken();
                if ("coinhubjp".equalsIgnoreCase(prefix)) {
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
                    CoinHubJPExchange exchange = new CoinHubJPExchange(apiKey, secretKey, terminalSerialNumber, chEndpoint);
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
        return null;
        // BigDecimal percentage = BigDecimal.ZERO;

        // try {
        //     if (coinHubApi != null && apiKey != null && cryptoCurrency != null) {

        //         String fiat = FiatCurrency.JPY.getCode();

        //         // --- Get base market rate ---
        //         RateResponse rate = coinHubApi.getBuyRate(apiKey, cryptoCurrency, fiat);

        //         if (rate != null && rate.best_ask != null) {

        //             BigDecimal base = rate.best_ask;

        //             // Defaults (kept for backward compatibility if fees API is unavailable)
        //             BigDecimal chFeePercent = new BigDecimal("10.00");       // percent
        //             BigDecimal tradeFee = new BigDecimal("0.0005");          // fraction
        //             BigDecimal withdrawalFee = new BigDecimal("0.000005");   // fraction

        //             try {
        //                 TransactionFeesResponse fees = coinHubApi.getTransactionFees(apiKey, new TransactionFeesRequest(cryptoCurrency));
        //                 if (fees != null) {
        //                     if (fees.ch_fee != null) chFeePercent = fees.ch_fee;
        //                     if (fees.trade_fee != null) tradeFee = fees.trade_fee;
        //                     if (fees.withdrawal_fee != null) withdrawalFee = fees.withdrawal_fee;
        //                 }
        //             } catch (Exception e) {
        //                 log.warn("Failed to fetch CoinHub fees; using defaults: terminal={}, crypto={}", serialNumber, cryptoCurrency, e);
        //             }

        //             // --- Step 1: Apply CoinHub fee (percent) ---
        //             BigDecimal markupMultiplier = BigDecimal.ONE.add(
        //                     chFeePercent.divide(new BigDecimal("100"), 20, RoundingMode.HALF_UP)
        //             );
        //             BigDecimal afterMarkup = base.multiply(markupMultiplier);

        //             // --- Step 2: Apply trading fee (fraction) ---
        //             BigDecimal afterTradingFee = afterMarkup.multiply(BigDecimal.ONE.add(tradeFee));

        //             // --- Step 3: Apply withdrawal fee (fraction of adjusted price) ---
        //             BigDecimal withdrawalFeeJPY = afterTradingFee.multiply(withdrawalFee);

        //             // --- Step 4: Final price (NO rounding here) ---
        //             BigDecimal finalValue = afterTradingFee.add(withdrawalFeeJPY);

        //             // ✅ IMPORTANT: Use HIGH precision for percentage (no early rounding)
        //             percentage = finalValue
        //                     .divide(base, 20, RoundingMode.HALF_UP)   // higher precision
        //                     .subtract(BigDecimal.ONE)
        //                     .multiply(new BigDecimal("100"))
        //                     .setScale(12, RoundingMode.HALF_UP);     // keep enough decimals

        //             // --- Optional: round final display value only ---
        //             BigDecimal finalDisplay = finalValue.setScale(2, RoundingMode.HALF_UP);

        //             // Log for verification
        //             log.info("CoinHub BUY CALCULATION:");
        //             log.info(" Base={}", base);
        //             log.info(" Fees: ch_fee%={}, trade_fee={}, withdrawal_fee={}", chFeePercent, tradeFee, withdrawalFee);
        //             log.info(" After ch_fee markup={}", afterMarkup);
        //             log.info(" After trade fee={}", afterTradingFee);
        //             log.info(" Withdrawal Fee (JPY)={}", withdrawalFeeJPY);
        //             log.info(" Final Price (raw)={}", finalValue);
        //             log.info(" Final Price (display)={}", finalDisplay);
        //             log.info(" Percentage={}%", percentage);

        //         } else {
        //             log.warn("CoinHub BUY rate not available: terminal={}, crypto={}, fiat={}, response={}",
        //                     serialNumber, cryptoCurrency, fiat, rate);
        //         }
        //     }
        // } catch (Exception e) {
        //     log.warn("Failed to fetch CoinHub BUY rate: terminal={}, crypto={}",
        //             serialNumber, cryptoCurrency, e);
        // }

        // return percentage;
    }

    @Override
    public BigDecimal overrideProfitSell(String serialNumber, String cryptoCurrency, BigDecimal profitSell){
        // Use admin configuration for-sell-profit
        return null;
    }
}
