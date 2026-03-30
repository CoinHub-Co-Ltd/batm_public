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

import com.generalbytes.batm.common.currencies.CryptoCurrency;
import com.generalbytes.batm.common.currencies.FiatCurrency;
import com.generalbytes.batm.server.extensions.IExchangeAdvanced;
import com.generalbytes.batm.server.extensions.IRateSourceAdvanced;
import com.generalbytes.batm.server.extensions.ITask;
import com.generalbytes.batm.server.extensions.ICryptoConfiguration ;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.request.FundTransferRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.request.WithdrawalRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.response.Withdrawal;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.fundingaccount.response.Currency;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response.PlaceOrderResponse;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request.PlaceOrderRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request.OrderBookRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response.OrderBookResponse;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request.LimitOrderRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request.MarketOrderRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.enums.OrderSide;
import com.generalbytes.batm.server.extensions.util.OrderBookPriceCalculator;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.OrderBookLevel;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.mazi.rescu.HttpStatusIOException;
import si.mazi.rescu.ClientConfig;
import si.mazi.rescu.ClientConfigUtil;
import si.mazi.rescu.Interceptor;
import si.mazi.rescu.RestProxyFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.UUID;
import java.util.Date;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import com.generalbytes.batm.server.extensions.util.net.CompatSSLSocketFactory;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.RateResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.generalbytes.batm.server.extensions.payment.PaymentReceipt;
import com.generalbytes.batm.server.extensions.payment.ReceivedAmount;
import com.generalbytes.batm.server.extensions.ITransactionDetails;
import com.generalbytes.batm.server.extensions.IBanknoteCounts;
import com.generalbytes.batm.server.extensions.IExtensionContext;
import com.generalbytes.batm.server.extensions.IGeneratesNewDepositCryptoAddress;
import com.generalbytes.batm.server.extensions.IQueryableWallet;
import com.generalbytes.batm.server.extensions.IWallet;


public class CoinHubEokjpExchange implements IExchangeAdvanced, IRateSourceAdvanced, IWallet, IGeneratesNewDepositCryptoAddress, IQueryableWallet {
    private static final Logger log = LoggerFactory.getLogger(CoinHubEokjpExchange.class);
    private static final OrderBookPriceCalculator<OrderBookLevel> orderBookPriceCalculator =
    new OrderBookPriceCalculator<>(OrderBookLevel::getPrice, OrderBookLevel::getAmount);
    private static final double orderBookDepth = 0.1;
    private static final int orderSize = 5;
    private static final String preferredFiatCurrency = FiatCurrency.JPY.getCode();
    private static final Set<String> fiatCurrencies = ImmutableSet.of(FiatCurrency.JPY.getCode());
    private static final Set<String> cryptoCurrencies = ImmutableSet.of(
        CryptoCurrency.BTC.getCode(),
        CryptoCurrency.ETH.getCode(),
        CryptoCurrency.DOGE.getCode(),
        CryptoCurrency.SHIB.getCode(),
        CryptoCurrency.RYO.getCode());
    
    private ICoinHubEokjpAPI api;
    private IExtensionContext extensionContext;
    private String terminalSerialNumber;
    private String apiKey;
    private String secretKey;

    private ITransactionDetails currentTransaction;

    private static final Pattern UUID_PATTERN = Pattern.compile(
        "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"
    );

    public CoinHubEokjpExchange(String apiEndpoint) {
         try {
            ClientConfig config = new ClientConfig();
            SSLContext sslcontext = SSLContext.getInstance("TLS");
            sslcontext.init(null, null, null);
            CompatSSLSocketFactory socketFactory = new CompatSSLSocketFactory(sslcontext.getSocketFactory());
            config.setSslSocketFactory(socketFactory);
            config.setIgnoreHttpErrorCodes(true);
            api = RestProxyFactory.createProxy(ICoinHubEokjpAPI.class, apiEndpoint, config);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.error("constructor - Cannot create instance.", e);
            throw new RuntimeException("Failed to initialize CoinHubEokjpExchange", e);
        }
    }

    public CoinHubEokjpExchange(String apiKey, String secretKey, String terminalSerialNumber, String apiEndpoint) {
        this(apiEndpoint); // call the default constructor for initialization
        this.terminalSerialNumber = terminalSerialNumber;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }

    public void setCurrentTransaction(ITransactionDetails tx) {
        this.currentTransaction = tx;
    }

    public void setExtensionContext(IExtensionContext extensionContext) {
        this.extensionContext = extensionContext;
    }

    // private BigDecimal getAmount(ITransactionDetails td) {
    //     if (td == null) {
    //         return null;
    //     }
    //     BigDecimal amount = td.getCashAmount();
    //     if (amount != null) {
    //         return amount;
    //     }
    //     BigDecimal total = BigDecimal.ZERO;
    //     if (td.getBanknotes() != null) {
    //         for (IBanknoteCounts b : td.getBanknotes()) {
    //             if (b.getDenomination() != null) {
    //                 total = total.add(b.getDenomination().multiply(BigDecimal.valueOf(b.getCount())));
    //             }
    //         }
    //     }
    //     return total;
    // }

    private String getMarketSymbol(String cryptoCurrency, String fiatCurrency) {
        return cryptoCurrency + "-" + fiatCurrency;
    }

    private boolean isCryptoCurrencySupported(String currency) {
        if (currency == null || !getCryptoCurrencies().contains(currency)) {
            log.debug("doesn't support cryptocurrency '{}'", currency);
            return false;
        }
        return true;
    }

    private boolean isFiatCurrencySupported(String currency) {
        if (currency == null || !getFiatCurrencies().contains(currency)) {
            log.debug("doesn't support fiat currency '{}'", currency);
            return false;
        }
        return true;
    }

    @Override
    public BigDecimal getCryptoBalance(String cryptoCurrency) {
        log.debug("Seiki Crypto Balance '{}'", cryptoCurrency);
        if (!isCryptoCurrencySupported(cryptoCurrency)) {
            return BigDecimal.TEN;
        }
        return getBalance(cryptoCurrency);
    }

    @Override
    public BigDecimal getFiatBalance(String fiatCurrency) {
        log.debug("Seiki Fiat Balance '{}'", fiatCurrency);
        if (!isFiatCurrencySupported(fiatCurrency)) {
            return BigDecimal.TEN;
        }
        return getBalance(fiatCurrency);
    }

    private BigDecimal getBalance(String currency) {
        return this.call(currency + " balance", () -> {
            String available = api.getCurrency(apiKey, currency).available;
            log.debug("Seiki available Balance '{}'", available);
            if (available == null) {
                log.warn("Balance for currency {} is null", currency);
                return BigDecimal.TEN;
            }
            return new BigDecimal(available);
        });
    }

    private String checkCryptoCurrency(String cryptoCurrency) {
        if (cryptoCurrency == null || !cryptoCurrencies.contains(cryptoCurrency)) {
            log.error("checkCryptoCurrency - Crypto currency \"" + cryptoCurrency + "\" is not supported. CoinZix supports " + Arrays.toString(cryptoCurrencies.toArray()) + ".");
            return null;
        }
        return cryptoCurrency.toUpperCase();
    }

    @Override
    public String getDepositAddress(String cryptoCurrency) {
        if (!isCryptoCurrencySupported(cryptoCurrency)) {
            return null;
        }

        return call("deposit address", () -> api.getDepositAddress(apiKey, cryptoCurrency).address);
    }

    // IWallet interface methods
    @Override
    public String getCryptoAddress(String cryptoCurrency) {
        // For IWallet interface, use getDepositAddress
        return getDepositAddress(cryptoCurrency);
    }

    @Override
    public String getPreferredCryptoCurrency() {
        // Return BTC as preferred crypto currency
        return CryptoCurrency.BTC.getCode();
    }

    @Override
    public String generateNewDepositCryptoAddress(String cryptoCurrency, String label) {
        log.info("[SEIKI] generateNewDepositCryptoAddress called for cryptoCurrency: {}, label: {}", cryptoCurrency, label);
        
        if (!isCryptoCurrencySupported(cryptoCurrency)) {
            log.warn("[SEIKI] generateNewDepositCryptoAddress: Unsupported cryptoCurrency: {}", cryptoCurrency);
            return null;
        }

        // Note: CoinHub API may return the same address for each currency
        // If the API supports unique addresses per label, this should be updated
        String address = getDepositAddress(cryptoCurrency);
        if (address != null) {
            log.info("[SEIKI] Generated deposit address: {} for cryptoCurrency: {}, label: {}", address, cryptoCurrency, label);
        } else {
            log.warn("[SEIKI] Failed to generate deposit address for cryptoCurrency: {}, label: {}", cryptoCurrency, label);
        }
        return address;
    }

    @Override
    public ReceivedAmount getReceivedAmount(String address, String cryptoCurrency) {
        log.info("[SEIKI] getReceivedAmount called for address: {}, cryptoCurrency: {}", address, cryptoCurrency);
        
        if (!isCryptoCurrencySupported(cryptoCurrency)) {
            log.warn("[SEIKI] getReceivedAmount: Unsupported cryptoCurrency: {}", cryptoCurrency);
            return ReceivedAmount.ZERO;
        }

        if (address == null || address.trim().isEmpty()) {
            log.warn("[SEIKI] getReceivedAmount: Address is null or empty");
            return ReceivedAmount.ZERO;
        }

        // TODO: Implement proper balance checking by address if CoinHub API supports it
        // For now, return ZERO - the system will need to rely on other mechanisms
        // to detect incoming deposits (e.g., webhooks, polling deposit history)
        log.debug("[SEIKI] getReceivedAmount: Returning ZERO for address: {} (API endpoint for address balance not available)", address);
        return ReceivedAmount.ZERO;
    }

    @Override
    public String sendCoins(String destinationAddress, BigDecimal amount, String cryptoCurrency, String description) {
        try {
            // Try to get terminal serial number from transaction details if available
            if (this.terminalSerialNumber == null && description != null && extensionContext != null) {
                String remoteTransactionId = (description != null) ? description.trim() : null;
                if (remoteTransactionId != null) {
                    try {
                        ITransactionDetails txDetails = extensionContext.findTransactionByTransactionId(remoteTransactionId);
                        if (txDetails != null) {
                            String txTerminalSN = txDetails.getTerminalSerialNumber();
                            if (txTerminalSN != null && !txTerminalSN.trim().isEmpty()) {
                                this.terminalSerialNumber = txTerminalSN;
                                log.info("[SEIKI] Updated terminalSerialNumber from transaction in sendCoins: {}", txTerminalSN);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[SEIKI] Could not get terminal serial number from transaction: {}", remoteTransactionId, e);
                    }
                }
            }
            
            BigDecimal profit = this.getProfitMargin(true, cryptoCurrency);
            // BigDecimal cashAmount = getAmount(currentTransaction);
            // String cashCurrency = currentTransaction != null ? currentTransaction.getCashCurrency() : null;
            // if (cashAmount != null && cashCurrency != null) {
            //     log.info("sendCoins context - cash inserted: {} {}", cashAmount, cashCurrency);
            // }
            Boolean isCryptoCurrencySupported = isCryptoCurrencySupported(cryptoCurrency);
            if (!isCryptoCurrencySupported) {
                log.error("Crypto currency has no value");
                return null;
            }
            log.info("SendCoins executed");
            BigDecimal rate = getExchangeRateForBuy(cryptoCurrency, getPreferredFiatCurrency());
            if (rate == null) {
                log.error("Failed to get exchange rate for {} to {}", cryptoCurrency, getPreferredFiatCurrency());
                return null;
            }
            log.info("test" + amount.multiply(rate).setScale(2, RoundingMode.HALF_UP));



            WithdrawalRequest request = new WithdrawalRequest();
            request.currency = cryptoCurrency;
            request.amount = amount; //amount.divide(BigDecimal.ONE.add(profit), RoundingMode.HALF_UP);
            request.destination = "1";
            request.usage_agreement = "1";
            request.to_address = destinationAddress;
            request.trade_pwd = "";
            request.reason = "1"; // sending to external wallet
            Withdrawal response = api.withdraw(apiKey, request);
            
            if (response == null) {
                log.error("Withdrawal API returned null response");
                return null;
            }
            
            if ("false".equals(response.result)) {
                log.error("Error sending coins - API returned false result");
                return null;
            }
            
            // Check if response.amount is not null before using it
            if (response.amount == null) {
                log.error("Withdrawal response amount is null");
                return null;
            }
            
            PaymentReceipt receipt = new PaymentReceipt(cryptoCurrency, destinationAddress);
            receipt.setAmount(new BigDecimal(response.amount));
            log.info("Successfully sent {} {} to {}", response.amount, cryptoCurrency, destinationAddress);
            return "success";
            
        } catch (Exception e) {
            log.error("sendCoins failed", e);
            return null;
        }
    }

    @Override
    public ITask createPurchaseCoinsTask(BigDecimal amount, String cryptoCurrency, String fiatCurrency, String description) {
        // if (!getCryptoCurrencies().contains(cryptoCurrency)) {
        //     log.error("coinhubeokjp implementation supports only " + Arrays.toString(getCryptoCurrencies().toArray()));
        //     return null;
        // }
        // if (!fiatCurrencies.JPY.getCode().equalsIgnoreCase(fiatCurrency)) {
        //     log.error("coinhubeokjp supports only " + fiatCurrencies.JPY.getCode() );
        //     return null;
        // }
        
        // Log what description actually contains to verify framework behavior
        log.info("[SEIKI] createPurchaseCoinsTask called - description={}, amount={}, crypto={}, fiat={}", 
                description, amount, cryptoCurrency, fiatCurrency);
        
        BigDecimal cryptoAmount = amount; 
        
        BigDecimal fiatAmount = null;
        // Extract remoteTransactionId from description parameter (description contains the remoteTransactionId like "R2M9RW")
        String remoteTransactionId = (description != null) ? description.trim() : null;
        
        if (remoteTransactionId != null && extensionContext != null) {
            ITransactionDetails transactionDetails = extensionContext.findTransactionByTransactionId(remoteTransactionId);
            if (transactionDetails != null) {
                log.info("[SEIKI] TransactionDetails found: remoteTransactionId={}, cashAmount={}, cashCurrency={}, cryptoAmount={}, cryptoCurrency={}, status={}, terminalSN={}, type={}", 
                        transactionDetails.getRemoteTransactionId(),
                        transactionDetails.getCashAmount(),
                        transactionDetails.getCashCurrency(),
                        transactionDetails.getCryptoAmount(),
                        transactionDetails.getCryptoCurrency(),
                        transactionDetails.getStatus(),
                        transactionDetails.getTerminalSerialNumber(),
                        transactionDetails.getType());
                
                fiatAmount = transactionDetails.getCashAmount();
                if (fiatAmount != null) {
                    log.info("[SEIKI] Using fiat amount from transaction details: remoteTransactionId={}, fiatAmount={} {}", 
                            remoteTransactionId, fiatAmount, fiatCurrency);
                } else {
                    log.warn("[SEIKI] Transaction found but has no cashAmount: remoteTransactionId={}", remoteTransactionId);
                }
            } else {
                log.warn("[SEIKI] Transaction not found by remoteTransactionId: {}", remoteTransactionId);
            }
        }
        
        if (fiatAmount == null && remoteTransactionId != null) {
            fiatAmount = TransactionFiatCache.getCashAmount(remoteTransactionId);
            if (fiatAmount != null) {
                log.info("[SEIKI] fallback Using fiat amount from TransactionFiatCache: remoteTransactionId={}, fiatAmount={} {}", 
                        remoteTransactionId, fiatAmount, fiatCurrency);
            }
        }

        BigDecimal profit = this.getProfitMargin(true, cryptoCurrency);
        UUID client_oid = UUID.randomUUID();
        String type = "market";
        String marketSymbol = getMarketSymbol(cryptoCurrency, fiatCurrency);
        
        BigDecimal price = null; 

        BigDecimal notional;
    
        notional = cryptoAmount; //cryptoAmount.divide(BigDecimal.ONE.add(profit), RoundingMode.HALF_UP);
        log.info("Using crypto-based notional: {} {}", notional, cryptoCurrency);
        
        String order_type = "3";
        
        log.info("Creating purchase task - cryptoAmount: {} {}, fiatAmount: {} {}, marketSymbol: {}, notional: {},  fiatAmount: {}", 
                cryptoAmount, cryptoCurrency, fiatAmount != null ? fiatAmount + " " + fiatCurrency : "N/A", marketSymbol, notional, fiatAmount);
        
        return new OrderCoinsTask(client_oid, OrderSide.BUY, marketSymbol, type, price, cryptoAmount, notional, order_type, fiatAmount);
    }


    @Override
    public ITask createSellCoinsTask(BigDecimal amount, String cryptoCurrency, String fiatCurrency, String description) {
        
    
        BigDecimal cryptoAmount = amount; 
        
        BigDecimal fiatAmount = null;
        // Extract remoteTransactionId from description parameter (description contains the remoteTransactionId like "R2M9RW")
        String remoteTransactionId = (description != null) ? description.trim() : null;
        
        if (remoteTransactionId != null && extensionContext != null) {
            ITransactionDetails transactionDetails = extensionContext.findTransactionByTransactionId(remoteTransactionId);
            if (transactionDetails != null) {
                log.info("[SEIKI] TransactionDetails found: remoteTransactionId={}, cashAmount={}, cashCurrency={}, cryptoAmount={}, cryptoCurrency={}, status={}, terminalSN={}, type={}", 
                        transactionDetails.getRemoteTransactionId(),
                        transactionDetails.getCashAmount(),
                        transactionDetails.getCashCurrency(),
                        transactionDetails.getCryptoAmount(),
                        transactionDetails.getCryptoCurrency(),
                        transactionDetails.getStatus(),
                        transactionDetails.getTerminalSerialNumber(),
                        transactionDetails.getType());
                
                fiatAmount = transactionDetails.getCashAmount();
                if (fiatAmount != null) {
                    log.info("[SEIKI] Using fiat amount from transaction details: remoteTransactionId={}, fiatAmount={} {}", 
                            remoteTransactionId, fiatAmount, fiatCurrency);
                } else {
                    log.warn("[SEIKI] Transaction found but has no cashAmount: remoteTransactionId={}", remoteTransactionId);
                }
            } else {
                log.warn("[SEIKI] Transaction not found by remoteTransactionId: {}", remoteTransactionId);
            }
        }
        
        if (fiatAmount == null && remoteTransactionId != null) {
            fiatAmount = TransactionFiatCache.getCashAmount(remoteTransactionId);
            if (fiatAmount != null) {
                log.info("[SEIKI] fallback Using fiat amount from TransactionFiatCache: remoteTransactionId={}, fiatAmount={} {}", 
                        remoteTransactionId, fiatAmount, fiatCurrency);
            }
        }

        UUID client_oid = UUID.randomUUID();
        String type = "market";
        String marketSymbol = getMarketSymbol(cryptoCurrency, fiatCurrency);
        BigDecimal price = getExchangeRateForSell(cryptoCurrency, fiatCurrency);
        String order_type = "3";
        return new OrderCoinsTask(client_oid,OrderSide.SELL,marketSymbol,type,price,BigDecimal.ZERO,amount,order_type, fiatAmount);
    }

    private BigDecimal getRateSourceCryptoVolume(String cryptoCurrency) {
        if (CryptoCurrency.BTC.getCode().equals(cryptoCurrency)) {
            return new BigDecimal("0.001"); // 0.001 BTC instead of 1.0
        }
        return new BigDecimal("0.01"); // 0.01 instead of 10.0
    }

    @Override
    public BigDecimal getExchangeRateForBuy(String cryptoCurrency, String fiatCurrency) {
        log.info("Exchange File getExchangeRateForBuy executed");
        BigDecimal rateSourceCryptoVolume = getRateSourceCryptoVolume(cryptoCurrency);
        BigDecimal result = calculateBuyPrice(cryptoCurrency, fiatCurrency, rateSourceCryptoVolume);
        if (result != null) {
            return result.divide(rateSourceCryptoVolume, 2, BigDecimal.ROUND_UP);
        }
        return null;
    }

    @Override
    public BigDecimal getExchangeRateForSell(String cryptoCurrency, String fiatCurrency) {
        log.info("Exchange File getExchangeRateForSell executed");
        BigDecimal rateSourceCryptoVolume = getRateSourceCryptoVolume(cryptoCurrency);
        BigDecimal result = calculateSellPrice(cryptoCurrency, fiatCurrency, rateSourceCryptoVolume);
        if (result != null) {
            return result.divide(rateSourceCryptoVolume, 2, RoundingMode.DOWN);
        }
        return null;
    }

    @Override
    public BigDecimal calculateBuyPrice(String cryptoCurrency, String fiatCurrency, BigDecimal cryptoAmount) {
        log.info("Exchange File calculateBuyPrice executed");
        return calculatePrice(cryptoCurrency, fiatCurrency, cryptoAmount, OrderSide.BUY);
    }

    @Override
    public BigDecimal calculateSellPrice(String cryptoCurrency, String fiatCurrency, BigDecimal cryptoAmount) {
        log.info("Exchange File calculateSellPrice executed");
        return calculatePrice(cryptoCurrency, fiatCurrency, cryptoAmount, OrderSide.SELL);
    }

    private BigDecimal calculatePrice(String cryptoCurrency, String fiatCurrency, BigDecimal cryptoAmount, OrderSide orderSide) {
        log.info("Exchange File calculatePrice executed");
        if (!isCryptoCurrencySupported(cryptoCurrency) || !isFiatCurrencySupported(fiatCurrency)) {
            log.warn("Unsupported currency pair: {} / {}", cryptoCurrency, fiatCurrency);
            return null;
        }

        String marketSymbol = getMarketSymbol(cryptoCurrency, fiatCurrency);
        log.info("Requesting order book for market: {} with size: {} and depth: {}", marketSymbol, orderSize, orderBookDepth);
        OrderBookResponse orderBook = call(
            marketSymbol + " orderBook",
            () -> api.getOrderBook(apiKey, marketSymbol, orderSize, orderBookDepth)
        );

        if (orderBook == null) {
            log.error("Failed to retrieve order book for market: {}", marketSymbol);
            return null;
        }

        if (orderSide == OrderSide.SELL) {
            if (orderBook.bids == null || orderBook.bids.isEmpty()) {
                log.error("Order book contains no bids for market: {}", marketSymbol);
                return null;
            }

            List<OrderBookLevel> bids = orderBook.bids.stream()
                .map(level -> {
                    try {
                        return new OrderBookLevel(
                            new BigDecimal(level.get(0)), // price
                            new BigDecimal(level.get(1))  // amount
                        );
                    } catch (Exception ex) {
                        log.warn("Failed to parse bid level {}: {}", level, ex.toString());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            System.out.println("Crypto amount needed: " + cryptoAmount);
            bids.forEach(b -> System.out.println("Bid: price=" + b.getPrice() + ", quantity=" + b.getAmount()));


            return orderBookPriceCalculator.getSellPrice(cryptoAmount, bids);

        } else { // BUY side
            if (orderBook.asks == null || orderBook.asks.isEmpty()) {
                log.error("Order book contains no asks for market: {}", marketSymbol);
                return null;
            }

            List<OrderBookLevel> asks = orderBook.asks.stream()
                .map(level -> {
                    try {
                        return new OrderBookLevel(
                            new BigDecimal(level.get(0)), // price
                            new BigDecimal(level.get(1))  // amount
                        );
                    } catch (Exception ex) {
                        log.warn("Failed to parse ask level {}: {}", level, ex.toString());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

            return orderBookPriceCalculator.getBuyPrice(cryptoAmount, asks);
        }
    }

    class OrderCoinsTask implements ITask {
        private static final long MAXIMUM_TIME_TO_WAIT_FOR_ORDER_TO_FINISH = 5 * 60 * 60 * 1000; //5 hours
        private final long checkTillTime;

        private final UUID client_oid;
        private final OrderSide side;
        private final String instrument_id;  
        private final String type;
        private final BigDecimal price;
        private final BigDecimal size;
        private final BigDecimal notional;
        private final String order_type;
        private final BigDecimal fiatAmount;
        private String orderId;
        private String result;
        private boolean finished;

        OrderCoinsTask(UUID client_oid, OrderSide side, String instrument_id, String type, BigDecimal price, BigDecimal size, BigDecimal notional, String order_type, BigDecimal fiatAmount) {
            this.client_oid = client_oid;
            this.side = side;
            this.instrument_id = instrument_id;
            this.type = type;
            this.price = price;
            this.size = size;
            this.notional = notional;
            this.fiatAmount = fiatAmount;
            this.order_type = order_type;
            this.checkTillTime = System.currentTimeMillis() + MAXIMUM_TIME_TO_WAIT_FOR_ORDER_TO_FINISH;
        }

        @Override
        public boolean onCreate() {
            PlaceOrderRequest request;
            try {
                log.info("OrderCoinsTask.onCreate() - type: {}, side: {}, instrument_id: {}, notional: {}, price: {}", 
                         type, side, instrument_id, notional, price);
                
                if (type.equals("market")) {
                    MarketOrderRequest marketRequest = new MarketOrderRequest();
                    marketRequest.notional = notional;
                    marketRequest.size = "";
                    request = marketRequest;
                    log.info("Created MarketOrderRequest with notional: {}", notional);
                } else {
                    LimitOrderRequest limitRequest = new LimitOrderRequest();
                    limitRequest.price = price;
                    limitRequest.size = size.toString();
                    request = limitRequest;
                    log.info("Created LimitOrderRequest with price: {}", price);
                }

                String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                String clientOid = "CH-" + CoinHubEokjpExchange.this.terminalSerialNumber + "-" + dateTime;
                request.client_oid = clientOid;
                request.type = type;
                request.side = side;
                request.instrument_id = instrument_id;
                request.order_type = order_type;
                request.fiat_amount = fiatAmount;
                
                if (request instanceof MarketOrderRequest) {
                    MarketOrderRequest marketRequest = (MarketOrderRequest) request;
                    log.info("MarketOrderRequest details: notional={}, client_oid={}, type={}, side={}, instrument_id={}, order_type={}, size={}", 
                             marketRequest.notional, marketRequest.client_oid, marketRequest.type, marketRequest.side, 
                             marketRequest.instrument_id, marketRequest.order_type, marketRequest.size);
                } else if (request instanceof LimitOrderRequest) {
                    LimitOrderRequest limitRequest = (LimitOrderRequest) request;
                    log.info("LimitOrderRequest details: price={}, client_oid={}, type={}, side={}, instrument_id={}, order_type={}, size={}", 
                             limitRequest.price, limitRequest.client_oid, limitRequest.type, limitRequest.side, 
                             limitRequest.instrument_id, limitRequest.order_type, limitRequest.size);
                }
                
                orderId = call("task createOrder", () -> api.placeOrder(apiKey, request).order_id);
                
                log.info("API call completed, orderId: {}", orderId);
                return orderId != null;
            } catch (Throwable e) {  
                log.error("coinhubeokjp Exchange (purchaseCoins) failed", e);
            }
            return (orderId != null);
        }

        @Override
        public boolean onDoStep() {
            if (orderId == null || orderId.equals("0")) {
                log.debug("Giving up on waiting for trade to complete. Because it did not happen");
                finished = true;
                result = "Skipped";
                return false;
            }
            if (System.currentTimeMillis() > checkTillTime) {
                log.debug("Giving up on waiting for trade {} to complete", orderId);
                finished = true;
                return false;
            }

            result = orderId;
            finished = true;
            return true;
        }

        @Override
        public boolean isFinished() {
            return finished;
        }

        @Override
        public String getResult() {
            return result;
        }

        @Override
        public boolean isFailed() {
            return finished && result == null;
        }

        @Override
        public void onFinish() {
            log.debug("task finished");
        }

        @Override
        public long getShortestTimeForNexStepInvocation() {
            return 5 * 1000; 
        }
    }

    @Override
    public Set<String> getCryptoCurrencies() {
        return cryptoCurrencies;
    }

    @Override
    public Set<String> getFiatCurrencies() {
        return fiatCurrencies;
    }

    @Override
    public String getPreferredFiatCurrency() {
        return preferredFiatCurrency;
    }

    /**
     * Execute an action and yield its result or {@code null} if there is any error.
     */
    private <T> T call(String label, Callable<T> action) {
        try {
            log.info("Making API call: {}", label);
            T result = action.call();
            log.info("{} result: {}", label, result);
            return result;
        } catch (HttpStatusIOException e) {
            log.error("{} error; HTTP status: {}, body: {}", label, e.getHttpStatusCode(), e.getHttpBody(), e);
            log.error("Full HTTP error details: {}", e.getMessage());
        } catch (Exception e) {
            log.error("{} error: {}", label, e.getMessage(), e);
            log.error("Exception type: {}", e.getClass().getSimpleName());
        }
        return null;
    }

    private BigDecimal getProfitMargin(boolean isBuy, String cryptoCurrency) {
        ICryptoConfiguration config = getCryptoConfiguration(cryptoCurrency);
        
        if (config == null) {
            log.warn("cryptoConfiguration is null for cryptoCurrency {}, using default profit margin of 0.11", cryptoCurrency);
            return new BigDecimal("0.11"); 
        }
        
        try {
            if (isBuy) {
                BigDecimal profitBuy = config.getProfitBuy();
                return profitBuy != null ? profitBuy : new BigDecimal("0.11");
            } else {
                BigDecimal profitSell = config.getProfitSell();
                return profitSell != null ? profitSell : new BigDecimal("0.11");
            }
        } catch (Exception e) {
            log.error("Error getting profit margin from cryptoConfiguration, using default", e);
            return new BigDecimal("0.11");
        }
    }

    private ICryptoConfiguration getCryptoConfiguration(String cryptoCurrency) {
        if (extensionContext == null || cryptoCurrency == null) {
            return null;
        }
        
        try {
            String terminalSN = this.terminalSerialNumber;
            
            // Try to get terminal serial number from currentTransaction
            if (terminalSN == null && currentTransaction != null) {
                terminalSN = currentTransaction.getTerminalSerialNumber();
            }
            
            // If still null, try to get from cached transaction details (if description contains transaction ID)
            if (terminalSN == null && extensionContext != null) {
                // Note: This won't work for sendCoins as description may not contain transaction ID
                // But we can try to get from any available transaction in cache
                // For now, skip if terminalSN is still null
            }
            
            // Validate terminal serial number before using it
            if (terminalSN == null || terminalSN.trim().isEmpty()) {
                log.debug("Cannot get cryptoConfiguration: terminalSerialNumber is null or empty");
                return null;
            }
            
            terminalSN = terminalSN.trim();
            
            // Validate terminal serial number format (must be non-empty and reasonable length)
            if (terminalSN.length() < 3) {
                log.warn("Terminal serial number appears invalid (too short): {}", terminalSN);
                return null;
            }
            
            List<ICryptoConfiguration> configs = extensionContext.findCryptoConfigurationsByTerminalSerialNumbers(
                Collections.singletonList(terminalSN)
            );
            
            if (configs != null) {
                for (ICryptoConfiguration config : configs) {
                    if (config != null && cryptoCurrency.equals(config.getCryptoCurrency())) {
                        return config;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            // Terminal serial number validation failed
            log.warn("Invalid terminal serial number when getting cryptoConfiguration: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Error getting cryptoConfiguration from context", e);
        }
        return null;
    }
}
