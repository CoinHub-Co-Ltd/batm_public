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

import com.generalbytes.batm.server.extensions.*;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.ICoinHubEokjpAPI;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.request.TransactionDetailsRequest;
import com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp.dto.spottrading.response.TransactionDetailsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.mazi.rescu.ClientConfig;
import si.mazi.rescu.ClientConfigUtil;
import si.mazi.rescu.RestProxyFactory;

import javax.net.ssl.SSLContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transaction listener that applies fees to transactions.
 * This listener modifies transaction amounts by applying a fee percentage
 * and stores the fee information in the transaction's custom data.
 */
public class CoinHubFeeTransactionListener implements ITransactionListener {
    private static final Logger log = LoggerFactory.getLogger(CoinHubFeeTransactionListener.class);
    
    // Fixed fee configuration - exactly 0.00018 BTC deducted from all transactions
    private static final BigDecimal FIXED_FEE_BTC = new BigDecimal("0.00018"); // Fixed fee of 0.00018 BTC
    private static final BigDecimal MIN_CRYPTO_AMOUNT = new BigDecimal("0.00000001"); // Minimum crypto amount to prevent negative
    
    private IExtensionContext ctx;
    private ICoinHubEokjpAPI apiClient;
    private String apiKey;

    public CoinHubFeeTransactionListener(IExtensionContext ctx, String apiKey, String apiEndpoint) {
        this.ctx = ctx;
        this.apiKey = apiKey;
        initializeApiClient(apiEndpoint);
    }
    
    private void initializeApiClient(String apiEndpoint) {
        try {
            ClientConfig config = new ClientConfig();
            SSLContext sslcontext = SSLContext.getInstance("TLS");
            sslcontext.init(null, null, null);
            config.setSslSocketFactory(sslcontext.getSocketFactory());
            config.setIgnoreHttpErrorCodes(true);
            this.apiClient = RestProxyFactory.createProxy(ICoinHubEokjpAPI.class, apiEndpoint, config);
            log.info("[SEIKI] API client initialized successfully");
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.error("[SEIKI] Failed to initialize API client", e);
            this.apiClient = null;
        }
    }

    @Override
    public boolean isTransactionApproved(ITransactionRequest transactionRequest) {
        try {
            log.info("[SEIKI] Transaction approval check for transaction: {}", transactionRequest.getRemoteTransactionId());
            
            // Populate cache early so createPurchaseCoinsTask can access it
            TransactionFiatCache.put(transactionRequest);
            
            if (transactionRequest.getType() == ITransactionRequest.TYPE_BUY_CRYPTO || 
                transactionRequest.getType() == ITransactionRequest.TYPE_SELL_CRYPTO) {
                
                BigDecimal originalAmount = transactionRequest.getCashAmount();
                BigDecimal feeAmount = FIXED_FEE_BTC;
                BigDecimal netAmount = originalAmount.subtract(feeAmount);
                
                log.info("[SEIKI] Fixed fee calculation: original={}, fee={}, net={}", originalAmount, feeAmount, netAmount);
                // Do NOT call ctx.updateTransaction here!
            }
            
            return true; // Always approve the transaction
        } catch (Exception e) {
            log.error("Error in transaction approval check", e);
            return true; // Approve even if fee calculation fails
        }
    }

    @Override
    public boolean isTransactionPreparationApproved(ITransactionPreparation preparation) {
        log.info("[SEIKI] isTransactionPreparationApproved called: {}", preparation);
        return true;
    }

    @Override
    public Map<String, String> onTransactionCreated(ITransactionDetails transactionDetails) {
        log.info("[SEIKI] onTransactionCreated called: {}", transactionDetails);
        Map<String, String> customData = new HashMap<>();
        try {
            log.info("Transaction created: {} (Type: {})", 
                transactionDetails.getRemoteTransactionId(), 
                getTransactionTypeName(transactionDetails.getType()));

            // Send transaction details to API
            sendTransactionToApi(transactionDetails, "CREATED");

            return customData;
        } catch (Exception e) {
            log.error("Error in onTransactionCreated", e);
            return customData;
        }
    }

    @Override
    public Map<String, String> onTransactionUpdated(ITransactionDetails transactionDetails) {
        try {
            log.info("[SEIKI] onTransactionUpdated called: {}", transactionDetails);
            log.info("Transaction updated: {} (Status: {})", 
                transactionDetails.getRemoteTransactionId(), 
                transactionDetails.getStatus());

            // Check if fee has already been applied using custom data
            Map<String, String> existingCustomData = transactionDetails.getCustomData();
            boolean feeAlreadyApplied = false;
            if (existingCustomData != null) {
                String feeAppliedFlag = existingCustomData.get("fee.applied");
                feeAlreadyApplied = "true".equalsIgnoreCase(feeAppliedFlag);
            }

            log.info("[SEIKI][DEBUG] feeAlreadyApplied: {}", feeAlreadyApplied);
            log.info("[SEIKI][DEBUG] transaction type: {}", transactionDetails.getType());
            log.info("[SEIKI][DEBUG] TYPE_BUY_CRYPTO: {}, TYPE_SELL_CRYPTO: {}", ITransactionDetails.TYPE_BUY_CRYPTO, ITransactionDetails.TYPE_SELL_CRYPTO);
            log.info("[SEIKI][DEBUG] existingCustomData: {}", existingCustomData);

            if (!feeAlreadyApplied &&
                (transactionDetails.getType() == ITransactionDetails.TYPE_BUY_CRYPTO ||
                 transactionDetails.getType() == ITransactionDetails.TYPE_SELL_CRYPTO)) {

                Map<String, IAmountWithDiscount> cryptoAmounts = ctx.calculateCryptoAmounts(
                    transactionDetails.getTerminalSerialNumber(),
                    java.util.Collections.singletonList(transactionDetails.getCryptoCurrency()),
                    transactionDetails.getCashAmount(),
                    transactionDetails.getCashCurrency(),
                    transactionDetails.getType() == ITransactionDetails.TYPE_BUY_CRYPTO
                        ? IExtensionContext.DIRECTION_BUY_CRYPTO
                        : IExtensionContext.DIRECTION_SELL_CRYPTO,
                    null,
                    transactionDetails.getIdentityPublicId()
                );

                IAmountWithDiscount amountWithDiscount = cryptoAmounts.get(transactionDetails.getCryptoCurrency());
                BigDecimal netCryptoAmount = amountWithDiscount != null
                    ? amountWithDiscount.getCryptoAmount()
                    : transactionDetails.getCryptoAmount();

                // Ensure net crypto is not negative
                if (netCryptoAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    log.warn("Net crypto amount would be negative. Setting to minimum.");
                    netCryptoAmount = MIN_CRYPTO_AMOUNT;
                }

                Map<String, String> customData = new HashMap<>();
                customData.put("fee.applied", "true");
                customData.put("net.crypto.amount", netCryptoAmount.toPlainString());

                if (transactionDetails.getRemoteTransactionId() != null) {
                    BigDecimal netCryptoAmountScaled = netCryptoAmount.setScale(8, RoundingMode.DOWN);
                    ctx.updateTransaction(
                        transactionDetails.getRemoteTransactionId(),
                        null, // Do not update fiat amount
                        netCryptoAmountScaled.toPlainString(), // Update crypto amount as string
                        customData
                    );
                    log.info("[SEIKI] Transaction updated with net crypto amount and fee data for transaction: {}", transactionDetails.getRemoteTransactionId());
                } else {
                    log.warn("[SEIKI][DEBUG] remoteTransactionId is null, skipping update.");
                }
                return customData;
            } else {
                log.info("[SEIKI] Fee already applied or not a crypto transaction. Skipping update for transaction: {}", transactionDetails.getRemoteTransactionId());
            }
            
            // Send transaction details to API
            sendTransactionToApi(transactionDetails, "UPDATED");
            
            // Optionally, update a timestamp or other info
            Map<String, String> updateData = new HashMap<>();
            updateData.put("last.fee.update", String.valueOf(System.currentTimeMillis()));
            return updateData;
        } catch (Exception e) {
            log.error("Error in onTransactionUpdated", e);
            return new HashMap<>();
        }
    }

    /**
     * Get the fixed fee amount (0.00018 BTC for all transactions)
     */
    private BigDecimal getFixedFeeAmount() {
        return FIXED_FEE_BTC;
    }

    /**
     * Get human-readable transaction type name
     */
    private String getTransactionTypeName(int transactionType) {
        switch (transactionType) {
            case ITransactionRequest.TYPE_BUY_CRYPTO:
                return "BUY_CRYPTO";
            case ITransactionRequest.TYPE_SELL_CRYPTO:
                return "SELL_CRYPTO";
            case ITransactionRequest.TYPE_WITHDRAW_CASH:
                return "WITHDRAW_CASH";
            default:
                return "UNKNOWN";
        }
    }

    @Override
    public void receiptSent(IReceiptDetails receiptDetails) {
        log.info("Receipt sent for transaction: {} to {} ({})", 
            receiptDetails.getRemoteTransactionId(),
            receiptDetails.getCellphone() != null ? receiptDetails.getCellphone() : receiptDetails.getEmail(),
            receiptDetails.getCellphone() != null ? "SMS" : "EMAIL");
    }
    
    /**
     * Send transaction details to the API
     */
    private void sendTransactionToApi(ITransactionDetails transactionDetails, String eventType) {
        try {
            if (apiClient != null) {
                TransactionDetailsRequest request = buildTransactionDetailsRequest(transactionDetails, eventType);
                TransactionDetailsResponse response = apiClient.saveTransactionDetails(apiKey, request);
                
                if (response != null) {
                    log.info("[SEIKI] Successfully sent transaction data to API for event: {}. Response: result={}, message={}, transactionId={}", 
                        eventType, response.result, response.message, response.transaction_id);
                } else {
                    log.warn("[SEIKI] API returned null response for event: {}", eventType);
                }
            } else {
                log.warn("[SEIKI] API client not initialized, skipping API call for event: {}", eventType);
            }
        } catch (Exception e) {
            log.error("[SEIKI] Error sending transaction data to API for event: {}", eventType, e);
        }
    }
    
    /**
     * Build the transaction details request from transaction details
     */
    private TransactionDetailsRequest buildTransactionDetailsRequest(ITransactionDetails transactionDetails, String eventType) {
        TransactionDetailsRequest request = new TransactionDetailsRequest();
        
        // Set basic transaction information
        request.order_id = transactionDetails.getRemoteTransactionId();
        request.instrument_id = transactionDetails.getCryptoCurrency() + "-" + transactionDetails.getCashCurrency();
        request.after = String.valueOf(transactionDetails.getServerTime().getTime());
        request.before = String.valueOf(System.currentTimeMillis());
        request.limit = "1";
        
        // Set transaction amounts and fees
        if (transactionDetails.getCashAmount() != null) {
            request.cash_amount = transactionDetails.getCashAmount().toPlainString();
        }
        request.cash_currency = transactionDetails.getCashCurrency();
        
        if (transactionDetails.getCryptoAmount() != null) {
            request.crypto_amount = transactionDetails.getCryptoAmount().toPlainString();
        }
        request.crypto_currency = transactionDetails.getCryptoCurrency();
        
        // Set fixed fee information
        if (transactionDetails.getFixedTransactionFee() != null) {
            request.fixed_fee = transactionDetails.getFixedTransactionFee().toPlainString();
            request.fee_currency = transactionDetails.getCashCurrency();
        } else {
            // Use our fixed fee if not set in transaction
            request.fixed_fee = FIXED_FEE_BTC.toPlainString();
            request.fee_currency = "BTC";
        }
        
        // Set net crypto amount from custom data if available
        Map<String, String> customData = transactionDetails.getCustomData();
        if (customData != null) {
            String netCryptoAmount = customData.get("net.crypto.amount");
            if (netCryptoAmount != null) {
                request.net_crypto_amount = netCryptoAmount;
            }
        }
        
        // Set transaction metadata
        request.transaction_type = getTransactionTypeName(transactionDetails.getType());
        request.transaction_status = String.valueOf(transactionDetails.getStatus());
        request.terminal_serial_number = transactionDetails.getTerminalSerialNumber();
        request.identity_public_id = transactionDetails.getIdentityPublicId();
        request.cellphone_used = transactionDetails.getCellPhoneUsed();
        request.event_type = eventType;
        
        // Set additional fee and discount information
        if (transactionDetails.getFeeDiscount() != null) {
            request.fee_discount = transactionDetails.getFeeDiscount().toPlainString();
        }
        if (transactionDetails.getCryptoDiscountAmount() != null) {
            request.crypto_discount_amount = transactionDetails.getCryptoDiscountAmount().toPlainString();
        }
        if (transactionDetails.getDiscountQuotient() != null) {
            request.discount_quotient = transactionDetails.getDiscountQuotient().toPlainString();
        }
        if (transactionDetails.getRateSourcePrice() != null) {
            request.rate_source_price = transactionDetails.getRateSourcePrice().toPlainString();
        }
        if (transactionDetails.getExpectedProfit() != null) {
            request.expected_profit = transactionDetails.getExpectedProfit().toPlainString();
            request.profit_percent = request.expected_profit;
        }
        if (transactionDetails.getDiscountCode() != null) {
            request.discount_code = transactionDetails.getDiscountCode();
        }
        if (transactionDetails.getNote() != null) {
            request.note = transactionDetails.getNote();
        }
        
        // Set timestamps
        if (transactionDetails.getServerTime() != null) {
            request.server_time = String.valueOf(transactionDetails.getServerTime().getTime());
        }
        if (transactionDetails.getTerminalTime() != null) {
            request.terminal_time = String.valueOf(transactionDetails.getTerminalTime().getTime());
        }

        populateDerivedPricing(request, transactionDetails);
        populateConfiguredProfitPercents(request, transactionDetails);

        log.info("[SEIKI] Built transaction request: orderId={}, instrumentId={}, cashAmount={}, cryptoAmount={}, fixedFee={}, feeDiscount={}, expectedProfit={}, rateSource={}, effectiveRate={}, eventType={}",
            request.order_id, request.instrument_id, request.cash_amount, request.crypto_amount, request.fixed_fee, request.fee_discount, request.expected_profit, request.rate_source_price, request.customer_effective_rate, eventType);
        
        return request;
    }

    /**
     * Derives fee % of cash, customer fiat-per-crypto rate, markup vs {@link ITransactionDetails#getRateSourcePrice()},
     * and approximate fiat profit vs rate source for buy transactions.
     */
    private void populateDerivedPricing(TransactionDetailsRequest request, ITransactionDetails td) {
        BigDecimal cash = td.getCashAmount();
        BigDecimal crypto = td.getCryptoAmount();
        BigDecimal rateSource = td.getRateSourcePrice();
        BigDecimal fiatFee = td.getFixedTransactionFee();

        if (cash != null && cash.compareTo(BigDecimal.ZERO) > 0 && fiatFee != null && fiatFee.compareTo(BigDecimal.ZERO) >= 0) {
            request.fixed_fee_percent_of_cash = fiatFee
                .multiply(new BigDecimal("100"))
                .divide(cash, 8, RoundingMode.HALF_UP)
                .toPlainString();
        }

        if (cash != null && crypto != null && crypto.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal effective = cash.divide(crypto, 16, RoundingMode.HALF_UP);
            request.customer_effective_rate = effective.toPlainString();

            if (rateSource != null && rateSource.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal markupPct = effective
                    .divide(rateSource, 16, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE)
                    .multiply(new BigDecimal("100"));
                request.markup_percent_vs_rate_source = markupPct.toPlainString();
            }
        }

        if (td.getType() == ITransactionDetails.TYPE_BUY_CRYPTO
            && cash != null && crypto != null && rateSource != null
            && cash.compareTo(BigDecimal.ZERO) > 0 && crypto.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal fiatAtSource = crypto.multiply(rateSource);
            request.estimated_profit_fiat = cash.subtract(fiatAtSource).setScale(8, RoundingMode.HALF_UP).toPlainString();
        }
    }

    private void populateConfiguredProfitPercents(TransactionDetailsRequest request, ITransactionDetails td) {
        if (ctx == null) {
            return;
        }
        String serial = td.getTerminalSerialNumber();
        String coin = td.getCryptoCurrency();
        if (serial == null || coin == null) {
            return;
        }
        try {
            List<ICryptoConfiguration> configs = ctx.findCryptoConfigurationsByTerminalSerialNumbers(
                Collections.singletonList(serial));
            for (ICryptoConfiguration c : configs) {
                if (coin.equals(c.getCryptoCurrency())) {
                    if (c.getProfitBuy() != null) {
                        request.configured_profit_buy_percent = c.getProfitBuy().toPlainString();
                    }
                    if (c.getProfitSell() != null) {
                        request.configured_profit_sell_percent = c.getProfitSell().toPlainString();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("[SEIKI] Could not resolve ICryptoConfiguration for terminal {} coin {}", serial, coin, e);
        }
    }
}