package com.generalbytes.batm.server.extensions.extra.bitcoin.exchanges.coinhubeokjp;

import com.generalbytes.batm.server.extensions.ITransactionDetails;
import com.generalbytes.batm.server.extensions.ITransactionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory bridge between {@link com.generalbytes.batm.server.extensions.ITransactionListener}
 * (has full transaction details) and {@link com.generalbytes.batm.server.extensions.IExchangeAdvanced}
 * (createPurchaseCoinsTask has no transaction details).
 *
 * Keyed by remoteTransactionId.
 */
final class TransactionFiatCache {
    private static final Logger log = LoggerFactory.getLogger(TransactionFiatCache.class);
    private static final ConcurrentHashMap<String, BigDecimal> CASH_AMOUNT_BY_REMOTE_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ITransactionDetails> TRANSACTION_DETAILS_BY_REMOTE_ID = new ConcurrentHashMap<>();

    private TransactionFiatCache() {}

    static void put(ITransactionDetails td) {
        if (td == null) {
            log.debug("[SEIKI] TransactionFiatCache.put called with null transaction");
            return;
        }
        String id = td.getRemoteTransactionId();
        if (id == null || id.trim().isEmpty()) {
            log.warn("[SEIKI] TransactionFiatCache.put: transaction has no remoteTransactionId");
            return;
        }
        BigDecimal cash = td.getCashAmount();
        if (cash == null) {
            log.warn("[SEIKI] TransactionFiatCache.put: transaction {} has no cashAmount", id);
            return;
        }
        CASH_AMOUNT_BY_REMOTE_ID.put(id, cash);
        TRANSACTION_DETAILS_BY_REMOTE_ID.put(id, td);
        log.info("[SEIKI] TransactionFiatCache.put: cached remoteTransactionId={}, cashAmount={} {}, transactionDetails stored", id, cash, td.getCashCurrency());
    }
    
    /**
     * Populate cache from ITransactionRequest (called earlier in transaction lifecycle, before createPurchaseCoinsTask)
     */
    static void put(ITransactionRequest tr) {
        if (tr == null) {
            log.debug("[SEIKI] TransactionFiatCache.put(ITransactionRequest) called with null transaction");
            return;
        }
        String id = tr.getRemoteTransactionId();
        if (id == null || id.trim().isEmpty()) {
            log.warn("[SEIKI] TransactionFiatCache.put(ITransactionRequest): transaction has no remoteTransactionId");
            return;
        }
        BigDecimal cash = tr.getCashAmount();
        if (cash == null) {
            log.warn("[SEIKI] TransactionFiatCache.put(ITransactionRequest): transaction {} has no cashAmount", id);
            return;
        }
        CASH_AMOUNT_BY_REMOTE_ID.put(id, cash);
        log.info("[SEIKI] TransactionFiatCache.put(ITransactionRequest): cached remoteTransactionId={}, cashAmount={} {}", id, cash, tr.getCashCurrency());
    }

    static BigDecimal getCashAmount(String remoteTransactionId) {
        if (remoteTransactionId == null || remoteTransactionId.trim().isEmpty()) return null;
        return CASH_AMOUNT_BY_REMOTE_ID.get(remoteTransactionId);
    }
    
    /**
     * Get full transaction details from cache
     */
    static ITransactionDetails getTransactionDetails(String remoteTransactionId) {
        if (remoteTransactionId == null || remoteTransactionId.trim().isEmpty()) return null;
        return TRANSACTION_DETAILS_BY_REMOTE_ID.get(remoteTransactionId);
    }
    
    /**
     * Debug method to get all cached transaction IDs (for logging/debugging)
     */
    static java.util.Set<String> getAllCachedIds() {
        return CASH_AMOUNT_BY_REMOTE_ID.keySet();
    }
    
    /**
     * Debug method to get cache size
     */
    static int getCacheSize() {
        return CASH_AMOUNT_BY_REMOTE_ID.size();
    }
}



