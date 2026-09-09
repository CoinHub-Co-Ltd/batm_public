package com.generalbytes.batm.server.extensions.extra.ryocoin;

import com.generalbytes.batm.server.extensions.*;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.ICoinHubAPI;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.request.CreateLedgerRequest;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.request.FingerprintCheckRequest;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.FingerprintCheckResponse;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.LedgerEntry;
import com.generalbytes.batm.server.extensions.extra.ryocoin.sources.dto.response.LedgerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import si.mazi.rescu.RestProxyFactory;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class CoinHubSecurityListener implements ITransactionListener {
    private static final Logger log = LoggerFactory.getLogger(CoinHubSecurityListener.class);

    private final BigDecimal maxPerTx;
    private final BigDecimal max3Hours;
    private final BigDecimal calcDay;
    private final BigDecimal calcWeek;
    private final BigDecimal calcLong;
    private final BigDecimal amlDay;
    private final int amlDayCount;
    private final BigDecimal amlLong;
    private final int amlLongCount;

    private final ICoinHubAPI api;
    private final String apiKey;
    private final IExtensionContext ctx;

    public CoinHubSecurityListener(IExtensionContext ctx, String apiKey, String apiEndpoint) {
        this.apiKey = apiKey;
        this.api = RestProxyFactory.createProxy(ICoinHubAPI.class, apiEndpoint);
        this.maxPerTx = config(ctx, "sec22_max_per_tx", new BigDecimal("100000"));
        this.max3Hours = config(ctx, "sec22_max_3_hours", new BigDecimal("100000"));
        this.calcDay = config(ctx, "sec22_calc_day", new BigDecimal("800000"));
        this.calcWeek = config(ctx, "sec22_calc_week", new BigDecimal("1600000"));
        this.calcLong = config(ctx, "sec22_calc_long", new BigDecimal("3000000"));
        this.amlDay = config(ctx, "sec22_aml_day", new BigDecimal("800000"));
        this.amlDayCount = config(ctx, "sec22_aml_day_count", 8);
        this.amlLong = config(ctx, "sec22_aml_long", new BigDecimal("5600000"));
        this.amlLongCount = config(ctx, "sec22_aml_long_count", 56);
        this.ctx = ctx;
    }

    private BigDecimal config(IExtensionContext ctx, String key, BigDecimal defaultValue) {
        String value = ctx.getConfigProperty("coinhub", key, defaultValue.toPlainString());
        try {
            return new BigDecimal(value.trim());
        } catch (Exception e) {
            log.warn("[Security] Invalid config {}={}, using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    private int config(IExtensionContext ctx, String key, int defaultValue) {
        String value = ctx.getConfigProperty("coinhub", key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            log.warn("[Security] Invalid config {}={}, using default {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public boolean isTransactionPreparationApproved(ITransactionPreparation prep) {
        if (prep.getType() != ITransactionPreparation.TYPE_BUY_CRYPTO) {
            return true;
        }
        
        String currency = prep.getCryptoCurrency();
        String address = prep.getCryptoAddress();
        if (currency == null || address == null || address.trim().isEmpty()) {
            log.warn("[Security] DENY prep localTxId={} missing crypto/address currency={} address={}",
                prep.getLocalTransactionId(), currency, address);
            prep.setErrorMessage("Crypto currency or destination address is missing.");
            return false;
        }

        String identityId = prep.getIdentityPublicId();
        if (identityId != null && api != null && apiKey != null) {
            String fingerprint = null;
            IIdentity identity = ctx.findIdentityByIdentityId(identityId);
            if (identity != null && identity.getIdentityPieces() != null) {
                for (IIdentityPiece piece : identity.getIdentityPieces()) {
                    if (piece.getPieceType() == IIdentityPiece.TYPE_FINGERPRINT && piece.getData() != null) {
                        fingerprint = Base64.getEncoder().encodeToString(piece.getData());
                        break;
                    }
                }
            }
            if (fingerprint != null) {
                try {
                    FingerprintCheckResponse check = api.checkFingerprint(apiKey, new FingerprintCheckRequest(address, fingerprint));
                    if (check != null && Boolean.FALSE.equals(check.allowed)) {
                        log.warn("[Security] DENY prep identity={} address={} fingerprint conflict", identityId, address);
                        prep.setErrorMessage("Transaction denied. Please contact support.");
                        return false;
                    }
                } catch (Exception e) {
                    log.error("[Security] fingerprint check failed identity={}", identityId, e);
                    prep.setErrorMessage("Transaction limit check unavailable. Please try again later.");
                    return false;
                }
            }
        }

        if (identityId == null) {
            return true;
        }
        List<LedgerEntry> ledger = getLedger(identityId);
        if (ledger == null) {
            prep.setErrorMessage("Transaction limit check unavailable. Please try again later.");
            return false;
        }
        if (noBuyInLastYear(ledger)) {
            log.info("[Security] prep PASS identity={} (no buy in ledger last year)", identityId);
            return true;
        }
        if (exceedThreeHours(ledger, BigDecimal.ZERO)) {
            log.warn("[Security] DENY prep identity={} ledger >= {} within 3 hours", identityId, max3Hours);
            prep.setErrorMessage("Transaction limit exceeded. Please try again later.");
            return false;
        }
        if (exceedCalculation(ledger, BigDecimal.ZERO)) {
            log.warn("[Security] DENY prep identity={} ledger over rolling limits", identityId);
            prep.setErrorMessage("Transaction limit exceeded. Please contact support.");
            return false;
        }
        return true;
    }

    @Override
    public boolean isTransactionApproved(ITransactionRequest request) {
        if (request.getType() != ITransactionRequest.TYPE_BUY_CRYPTO) {
            return true;
        }
        BigDecimal amount = request.getCashAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        if (!"JPY".equalsIgnoreCase(request.getCashCurrency())) {
            return true;
        }
        if (amount.compareTo(maxPerTx) > 0) {
            return deny(request, "Maximum " + maxPerTx + " JPY per transaction exceeded.");
        }
        String identityId = request.getIdentityPublicId();
        if (identityId == null) {
            return true;
        }
        List<LedgerEntry> ledger = getLedger(identityId);
        if (ledger == null) {
            return deny(request, "Transaction limit check unavailable. Please try again later.");
        }
        if (noBuyInLastYear(ledger)) {
            return true;
        }
        if (exceedThreeHours(ledger, amount)) {
            return deny(request, "Transaction limit exceeded. Please try again later.");
        }
        if (exceedCalculation(ledger, amount) || exceedsAml(ledger, amount)) {
            return deny(request, "Transaction limit exceeded. Please contact support.");
        }
        return true;
    }

    @Override
    public Map<String, String> onTransactionUpdated(ITransactionDetails td) {
        try {
            if (td == null || td.getIdentityPublicId() == null) {
                return new HashMap<>();
            }
            // if (!isCompletedBuyOrSell(td)) {
            //     return new HashMap<>();
            // }
            createLedgerTransaction(td);
        } catch (Exception e) {
            log.error("[Security] createLedger failed rid={}", td != null ? td.getRemoteTransactionId() : null, e);
        }
        return new HashMap<>();
    }

    private boolean isCompletedBuyOrSell(ITransactionDetails td) {
        if (td.getType() == ITransactionDetails.TYPE_BUY_CRYPTO) {
            return td.getStatus() == ITransactionDetails.STATUS_BUY_COMPLETED;
        }
        if (td.getType() == ITransactionDetails.TYPE_SELL_CRYPTO) {
            return td.getStatus() == ITransactionDetails.STATUS_SELL_PAYMENT_ARRIVED;
        }
        return false;
    }

    private void createLedgerTransaction(ITransactionDetails td) {
        if (api == null || apiKey == null) {
            log.warn("[Security] ledger API not configured, skip create");
            return;
        }
        byte[] fingerPrintBytes = null;
        String fingerPrintFileName = null;
        IIdentity identity = ctx.findIdentityByIdentityId(td.getIdentityPublicId());
        if (identity != null && identity.getIdentityPieces() != null) {
            for (IIdentityPiece piece : identity.getIdentityPieces()) {
                if (piece.getPieceType() == IIdentityPiece.TYPE_FINGERPRINT && piece.getData() != null) {
                    fingerPrintBytes = piece.getData();
                    fingerPrintFileName = piece.getFilename();
                    break;
                }
            }
        }
        CreateLedgerRequest request = new CreateLedgerRequest();
        String rid = td.getRemoteTransactionId() != null ? td.getRemoteTransactionId() : "";
        request.tx_id = rid;
        request.transaction_id = Math.abs(rid.hashCode());
        request.amount = td.getCashAmount() != null ? td.getCashAmount() : BigDecimal.ZERO;
        request.transaction_type = td.getType() == ITransactionDetails.TYPE_BUY_CRYPTO ? "CREDIT" : "DEBIT";
        request.status = String.valueOf(td.getStatus());
        request.identity_id = td.getIdentityPublicId();
        request.fingerprint_data = fingerPrintBytes != null ? Base64.getEncoder().encodeToString(fingerPrintBytes) : null;
        request.fingerprint_filename = fingerPrintFileName;
        request.address = td.getCryptoAddress();
        LedgerEntry created = api.createLedgerTransaction(apiKey, request);
        log.info("[Security] ledger created tx_id={} identity={} entry={}", rid, request.identity_id, created);
    }

    private List<LedgerEntry> getLedger(String identityId) {
        try {
            if (api == null || apiKey == null || identityId == null) {
                return null;
            }
            LedgerResponse response = api.getLedger(apiKey, identityId);
            if (response == null || response.data == null) {
                return null;
            }
            return response.data;
        } catch (Exception e) {
            log.error("[Security] getLedger failed identity={}", identityId, e);
            return null;
        }
    }

    private boolean deny(ITransactionRequest request, String message) {
        log.warn("[Security] DENY rid={} identity={} msg={}",
            request.getRemoteTransactionId(), request.getIdentityPublicId(), message);
        request.setErrorMessage(message);
        return false;
    }

    private boolean noBuyInLastYear(List<LedgerEntry> ledger) {
        Date oneYearAgo = addYear(new Date(), -1);
        for (LedgerEntry entry : ledger) {
            if (!isCredit(entry)) {
                continue;
            }
            Date created = parseDate(entry.created_at);
            if (created != null && created.after(oneYearAgo)) {
                return false;
            }
        }
        return true;
    }

    private boolean exceedThreeHours(List<LedgerEntry> ledger, BigDecimal current) {
        BigDecimal past = sumInHours(ledger, 3);
        if (past.compareTo(max3Hours) >= 0) {
            return true;
        }
        return past.add(current).compareTo(max3Hours) > 0;
    }

    private boolean exceedCalculation(List<LedgerEntry> ledger, BigDecimal current) {
        return sumInWindow(ledger, 1).add(current).compareTo(calcDay) > 0
            || sumInWindow(ledger, 7).add(current).compareTo(calcWeek) > 0
            || sumInWindow(ledger, 90).add(current).compareTo(calcLong) > 0
            || sumInWindow(ledger, 180).add(current).compareTo(calcLong) > 0
            || sumInWindow(ledger, 365).add(current).compareTo(calcLong) > 0;
    }

    private boolean exceedsAml(List<LedgerEntry> ledger, BigDecimal current) {
        boolean dayFail = sumInWindow(ledger, 1).add(current).compareTo(amlDay) > 0
            || countInWindow(ledger, 1) + 1 > amlDayCount;
        boolean longFail = sumInWindow(ledger, 7).add(current).compareTo(amlLong) > 0
            || countInWindow(ledger, 7) + 1 > amlLongCount
            || sumInWindow(ledger, 30).add(current).compareTo(amlLong) > 0
            || countInWindow(ledger, 30) + 1 > amlLongCount
            || sumInWindow(ledger, 180).add(current).compareTo(amlLong) > 0
            || countInWindow(ledger, 180) + 1 > amlLongCount
            || sumInWindow(ledger, 365).add(current).compareTo(amlLong) > 0
            || countInWindow(ledger, 365) + 1 > amlLongCount;
        return dayFail || longFail;
    }

    private BigDecimal sumInHours(List<LedgerEntry> ledger, int hours) {
        Date from = addHours(new Date(), -hours);
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerEntry entry : ledger) {
            if (!isCountable(entry, from)) {
                continue;
            }
            if (entry.amount != null) {
                sum = sum.add(entry.amount);
            }
        }
        return sum;
    }

    private BigDecimal sumInWindow(List<LedgerEntry> ledger, int days) {
        Date from = addDays(new Date(), -days);
        BigDecimal sum = BigDecimal.ZERO;
        for (LedgerEntry entry : ledger) {
            if (!isCountable(entry, from)) {
                continue;
            }
            if (entry.amount != null) {
                sum = sum.add(entry.amount);
            }
        }
        return sum;
    }

    private int countInWindow(List<LedgerEntry> ledger, int days) {
        Date from = addDays(new Date(), -days);
        int n = 0;
        for (LedgerEntry entry : ledger) {
            if (isCountable(entry, from)) {
                n++;
            }
        }
        return n;
    }

    private boolean isCountable(LedgerEntry entry, Date from) {
        if (entry == null || !isCredit(entry)) {
            return false;
        }
        Date created = parseDate(entry.created_at);
        return created != null && !created.before(from);
    }

    private boolean isCredit(LedgerEntry entry) {
        if (entry == null || entry.transaction_type == null) {
            return false;
        }
        String type = entry.transaction_type.trim();
        return "CREDIT".equalsIgnoreCase(type)
            || "BUY_CRYPTO".equalsIgnoreCase(type)
            || "BUY".equalsIgnoreCase(type);
    }

    private Date parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String raw = value.trim();
        String[] patterns = {
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                format.setLenient(true);
                return format.parse(raw);
            } catch (ParseException ignored) {
            }
        }
        log.warn("[Security] Could not parse ledger date: {}", value);
        return null;
    }

    private static Date addHours(Date date, int hours) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.HOUR_OF_DAY, hours);
        return calendar.getTime();
    }

    private static Date addDays(Date date, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.DAY_OF_YEAR, days);
        return calendar.getTime();
    }

    private static Date addYear(Date date, int years) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.add(Calendar.YEAR, years);
        return calendar.getTime();
    }
}
