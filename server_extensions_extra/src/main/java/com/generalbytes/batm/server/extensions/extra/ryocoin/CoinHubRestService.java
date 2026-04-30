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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.generalbytes.batm.server.extensions.IExtensionContext;
import com.generalbytes.batm.server.extensions.IBanknoteCounts;
import com.generalbytes.batm.server.extensions.ILocation;
import com.generalbytes.batm.server.extensions.IRestService;
import com.generalbytes.batm.server.extensions.ITerminal;
import com.generalbytes.batm.server.extensions.ITransactionCashbackInfo;
import com.generalbytes.batm.server.extensions.exceptions.CashbackException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;

/**
 * CoinHub REST API mounted on CAS.
 * Base URL: https://&lt;cas-host&gt;:7743/extensions/coinhub/
 * <p>
 * Cashback parameters mirror the CAS &quot;Create Cashback&quot; dialog (amount, currency, send-to email).
 * Operator 2FA in that dialog is enforced by the CAS UI session; this REST endpoint does not accept a 2FA code —
 * secure it at the network/API layer instead (HTTPS, VPN, internal callers only).
 * </p>
 * <p>
 * QR delivery by email: set {@code cashback_mail_from} in the {@code coinhub} extension config (sender address).
 * CAS must have SMTP configured. If {@code cashback_mail_from} is unset, cashback still succeeds but no email is sent.
 * </p>
 * <p>
 * <strong>Translating the email:</strong> add plain-text files under {@code mail_contents/} in the CAS config
 * directory (typically {@code /batm/config/mail_contents/}). Body:
 * {@code mail_contents/coinhub_cashback_email_&lt;lang&gt;.txt} (e.g. {@code coinhub_cashback_email_ja.txt} in that folder).
 * Subject: {@code coinhub_cashback_subject_&lt;lang&gt;.txt} (single line; line breaks are flattened).
 * Pass {@code language} on the cashback request (e.g. {@code ja} or {@code ja-JP}). Placeholders:
 * {@code {terminal}}, {@code {amount}}, {@code {currency}}, {@code {validity_minutes}}, {@code {qr_payload}}.
 * Lookup order: requested tag, then primary subtag (e.g. {@code ja-JP} → {@code ja}). {@code en.txt} is tried only
 * when the language is English ({@code en}, {@code en-GB}, …), so {@code language=ja} does not pick up
 * {@code coinhub_cashback_email_en.txt} if Japanese files are missing. If no file matches, built-in English defaults.
 * If a path with {@code mail_contents/} is not readable by CAS, the same file name in the config root is tried
 * (e.g. {@code coinhub_cashback_email_ja.txt} in {@code /batm/config/}).
 * </p>
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CoinHubRestService implements IRestService {

    private static final Logger log = LoggerFactory.getLogger(CoinHubRestService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String getPrefixPath() {
        return "coinhub";
    }

    @Override
    public Class getImplementation() {
        return CoinHubRestService.class;
    }

    /**
     * Sample cashback payload for integration tests.
     * https://localhost:7743/extensions/coinhub/cashback-test
     */
    @GET
    @Path("/cashback-test")
    public Map<String, Object> cashbackTest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", "CH-CASHBACK-TEST-0001");
        body.put("terminal", "BT401469");
        body.put("amount", 10000);
        body.put("currency", "JPY");
        body.put("identity_public_id", "I39OJWLCNPLZGRXK");
        body.put("email", "customer@example.com");
        body.put("language", "en");
        return body;
    }

    /**
     * Creates a cashback transaction on the given terminal. The customer can then complete withdrawal at the terminal
     * using the returned {@code transaction_uuid} in the redeem flow.
     * <p>
     * Example:
     * {@code GET /extensions/coinhub/cashback?serial_number=BT401469&fiat_amount=10000&fiat_currency=JPY&identity_public_id=I39OJWLCNPLZGRXK&email=user@example.com&language=ja}
     * </p>
     */
    @GET
    @Path("/cashback")
    public Map<String, Object> createCashback(
            @Context HttpServletRequest servletRequest,
            @Context UriInfo uriInfo) {
        return runCashback(
                firstParam(servletRequest, uriInfo, "serial_number", "terminal"),
                firstParam(servletRequest, uriInfo, "fiat_amount", "amount"),
                firstParam(servletRequest, uriInfo, "fiat_currency", "currency"),
                firstParam(servletRequest, uriInfo, "identity_public_id"),
                firstParam(servletRequest, uriInfo, "email"),
                firstParam(servletRequest, uriInfo, "language", "lang", "locale"),
                uriInfo,
                "GET");
    }

    /**
     * Create cashback from a JSON object. Many clients send {@code POST} with
     * {@code Content-Type: application/json} and no query string, which is why GET parameters appear "missing".
     * Body example:
     * {@code {"serial_number":"BT401469","fiat_amount":10000,"fiat_currency":"JPY","identity_public_id":"...","email":"user@example.com","language":"ja"} }
     */
    @POST
    @Path("/cashback")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> createCashbackJson(
            @Context UriInfo uriInfo,
            InputStream entity) {
        String serial = null;
        String fiatAmountRaw = null;
        String fiatCurrency = null;
        String identityPublicId = null;
        String email = null;
        String language = null;
        try {
            if (entity != null) {
                JsonNode n = JSON.readTree(entity);
                if (n != null && n.isObject()) {
                    serial = jsonField(n, "serial_number", "terminal");
                    fiatAmountRaw = jsonField(n, "fiat_amount", "amount");
                    fiatCurrency = jsonField(n, "fiat_currency", "currency");
                    identityPublicId = jsonField(n, "identity_public_id", "identityPublicId");
                    email = jsonField(n, "email", "e_mail");
                    language = jsonField(n, "language", "lang", "locale");
                }
            }
        } catch (Exception e) {
            log.warn("cashback JSON body parse failed", e);
        }
        return runCashback(serial, fiatAmountRaw, fiatCurrency, identityPublicId, email, language, uriInfo, "JSON");
    }

    /**
     * Same as GET {@link #createCashback} but for {@code application/x-www-form-urlencoded} body.
     */
    @POST
    @Path("/cashback")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> createCashbackForm(
            @Context HttpServletRequest servletRequest,
            @Context UriInfo uriInfo) {
        return runCashback(
                firstParam(servletRequest, uriInfo, "serial_number", "terminal"),
                firstParam(servletRequest, uriInfo, "fiat_amount", "amount"),
                firstParam(servletRequest, uriInfo, "fiat_currency", "currency"),
                firstParam(servletRequest, uriInfo, "identity_public_id"),
                firstParam(servletRequest, uriInfo, "email"),
                firstParam(servletRequest, uriInfo, "language", "lang", "locale"),
                uriInfo,
                "FORM");
    }

    private Map<String, Object> runCashback(
            String serialNumber,
            String fiatAmountRaw,
            String fiatCurrency,
            String identityPublicId,
            String email,
            String language,
            UriInfo uriInfo,
            String source) {
        Map<String, Object> body = new LinkedHashMap<>();
        BigDecimal fiatAmount = null;
        if (fiatAmountRaw != null && !fiatAmountRaw.trim().isEmpty()) {
            try {
                fiatAmount = new BigDecimal(fiatAmountRaw.trim());
            } catch (NumberFormatException e) {
                body.put("ok", false);
                body.put("error", "invalid_parameters");
                body.put("detail", "fiat_amount must be a decimal number");
                return body;
            }
        }
        if (serialNumber == null || serialNumber.trim().isEmpty()
                || fiatAmount == null
                || fiatCurrency == null || fiatCurrency.trim().isEmpty()) {
            String rawQ = null;
            if (uriInfo != null && uriInfo.getRequestUri() != null) {
                rawQ = uriInfo.getRequestUri().getRawQuery();
            }
            log.warn(
                    "cashback missing_parameters: source={} queryPresent={}",
                    source,
                    rawQ != null);
            body.put("ok", false);
            body.put("error", "missing_parameters");
            body.put("detail", "serial_number, fiat_amount, fiat_currency, and email are required");
            body.put(
                    "hint",
                    "Use GET with a query string, or POST the same fields as "
                            + "application/x-www-form-urlencoded, or as application/json "
                            + "(field names: serial_number, fiat_amount, fiat_currency, email, optional identity_public_id, optional language).");
            body.put("source", source);
            body.put("query_string_present", rawQ != null && !rawQ.isEmpty());
            body.put("query_param_names_seen", listRawQueryParamNames(rawQ));
            body.put("build_marker", "CoinHub-cashback-v7");
            return body;
        }
        String emailTrimmed = email != null ? email.trim() : "";
        if (emailTrimmed.isEmpty()) {
            body.put("ok", false);
            body.put("error", "missing_email");
            body.put("detail", "email is required (same as Send to E-mail in CAS Create Cashback)");
            return body;
        }
        if (!looksLikeEmail(emailTrimmed)) {
            body.put("ok", false);
            body.put("error", "invalid_email");
            body.put("detail", "email must look like a valid address");
            return body;
        }
        IExtensionContext ctx = RYOExtension.getExtensionContext();
        if (ctx == null) {
            body.put("ok", false);
            body.put("error", "extension_context_unavailable");
            return body;
        }
        try {
            ITransactionCashbackInfo cashback = ctx.cashback(
                    serialNumber.trim(),
                    fiatAmount,
                    fiatCurrency.trim(),
                    identityPublicId);
            String qrPayload =
                    "cashback:jackpot?amount="
                            + cashback.getCashAmount().toPlainString()
                            + "&label="
                            + cashback.getRemoteTransactionId()
                            + "&uuid="
                            + cashback.getTransactionUUID();
            cashback.getCustomData().put("qrcode", qrPayload);
            cashback.getCustomData().put("email", emailTrimmed);
            String languageTrimmed = language != null ? language.trim() : "";
            if (!languageTrimmed.isEmpty()) {
                cashback.getCustomData().put("language", languageTrimmed);
            }
            body.put("email", emailTrimmed);
            if (!languageTrimmed.isEmpty()) {
                body.put("language", languageTrimmed);
            }
            body.put("ok", true);
            body.put("remote_transaction_id", cashback.getRemoteTransactionId());
            body.put("local_transaction_id", cashback.getLocalTransactionId());
            body.put("status", cashback.getStatus());
            body.put("cash_amount", cashback.getCashAmount());
            body.put("cash_currency", cashback.getCashCurrency());
            body.put("transaction_uuid", cashback.getTransactionUUID());
            body.put("validity_in_minutes", cashback.getValidityInMinutes());
            body.put("custom_data", cashback.getCustomData());
            tryQueueCashbackQrEmail(ctx, emailTrimmed, serialNumber.trim(), cashback, qrPayload, body, languageTrimmed);
        } catch (CashbackException e) {
            log.error("cashback creation failed", e);
            body.put("ok", false);
            body.put("error", "cashback_failed");
            body.put("detail", e.getMessage());
        } catch (Throwable e) {
            log.error("cashback endpoint error", e);
            body.put("ok", false);
            body.put("error", "unexpected");
            body.put("detail", e.getMessage());
        }
        return body;
    }

    /**
     * https://localhost:7743/extensions/coinhub/ping
     */
    @GET
    @Path("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("extension", "CoinHub");
        return body;
    }

    /**
     * https://localhost:7743/extensions/coinhub/version
     */
    @GET
    @Path("/version")
    public Map<String, Object> version() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            if (RYOExtension.getExtensionContext() != null) {
                body.put("server_version", RYOExtension.getExtensionContext().getServerVersion());
            } else {
                body.put("server_version", null);
                body.put("warning", "extension context not initialized");
            }
        } catch (Throwable e) {
            log.error("version endpoint error", e);
            body.put("error", e.getMessage());
        }
        return body;
    }

    /**
     * Returns information about an ATM (terminal) including cash boxes/cassettes and banknote counts.
     *
     * <p>Example:</p>
     * {@code GET /extensions/coinhub/atm?serial_number=BT401469}
     */
    @GET
    @Path("/atm")
    public Map<String, Object> getAtmInfo(
            @Context HttpServletRequest servletRequest,
            @Context UriInfo uriInfo) {
        Map<String, Object> body = new LinkedHashMap<>();

        String serialNumber = firstParam(servletRequest, uriInfo, "serial_number", "terminal", "serial", "sn");
        if (serialNumber == null || serialNumber.trim().isEmpty()) {
            body.put("ok", false);
            body.put("error", "missing_parameters");
            body.put("detail", "serial_number is required");
            return body;
        }

        IExtensionContext ctx = RYOExtension.getExtensionContext();
        if (ctx == null) {
            body.put("ok", false);
            body.put("error", "extension_context_unavailable");
            return body;
        }

        String sn = serialNumber.trim();
        try {
            ITerminal terminal = ctx.findTerminalBySerialNumber(sn);
            if (terminal == null) {
                body.put("ok", false);
                body.put("error", "terminal_not_found");
                body.put("serial_number", sn);
                return body;
            }

            body.put("ok", true);
            body.put("serial_number", sn);
            body.put("terminal", terminalToMap(terminal));

            List<IBanknoteCounts> cashBoxes = safeList(ctx.getCashBoxes(sn));
            body.put("cash_boxes", banknoteCountsToList(cashBoxes));
            List<IBanknoteCounts> cassettes = filterByCashboxNamePrefix(cashBoxes, "dispenser_cassette_");
            List<IBanknoteCounts> acceptorCashbox = filterByCashboxNameExact(cashBoxes, IBanknoteCounts.CN_ACCEPTOR_CASHBOX);
            List<IBanknoteCounts> reject = filterByCashboxNameExact(cashBoxes, IBanknoteCounts.CN_DISPENSER_REJECT);
            List<IBanknoteCounts> recycler = filterByCashboxNamePrefix(cashBoxes, "recycler_");

            body.put("cassettes", banknoteCountsToList(cassettes));
            body.put("cashboxes", banknoteCountsToList(acceptorCashbox));
            body.put("reject", banknoteCountsToList(reject));
            body.put("recycler", banknoteCountsToList(recycler));

            body.put("cassette_summary", summarizeByCashboxName(cassettes));
            body.put("cashbox_summary", summarizeByCashboxName(acceptorCashbox));
            body.put("reject_summary", summarizeByCashboxName(reject));
            body.put("recycler_summary", summarizeByCashboxName(recycler));

            body.put("banknotes", aggregateBanknotesByCurrencyAndDenomination(cashBoxes));
        } catch (IllegalArgumentException e) {
            body.put("ok", false);
            body.put("error", "invalid_parameters");
            body.put("detail", e.getMessage());
        } catch (Throwable e) {
            log.error("atm endpoint error", e);
            body.put("ok", false);
            body.put("error", "unexpected");
            body.put("detail", e.getMessage());
        }

        return body;
    }

    private static Map<String, Object> terminalToMap(ITerminal t) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) {
            return m;
        }
        m.put("type", t.getType());
        m.put("serial_number", t.getSerialNumber());
        m.put("name", t.getName());
        m.put("active", t.isActive());
        m.put("locked", t.isLocked());
        m.put("deleted", t.isDeleted());
        m.put("organization_id", t.getOrganizationId());
        m.put("connected_at", t.getConnectedAt());
        m.put("last_ping_at", t.getLastPingAt());
        m.put("last_ping_duration_ms", t.getLastPingDuration());
        m.put("exchange_rate_updated_at", t.getExchangeRateUpdatedAt());
        m.put("exchange_rates_buy", t.getExchangeRatesBuy());
        m.put("exchange_rates_sell", t.getExchangeRatesSell());
        m.put("errors", t.getErrors());
        m.put("operational_mode", t.getOperationalMode());
        m.put("rejected_reason", t.getRejectedReason());
        m.put("allowed_cash_currencies", safeList(t.getAllowedCashCurrencies()));
        m.put("allowed_crypto_currencies", safeList(t.getAllowedCryptoCurrencies()));
        m.put("tags", t.getTags() != null ? new ArrayList<>(t.getTags()) : Collections.emptyList());
        m.put("location", locationToMap(t.getLocation()));
        return m;
    }

    private static Map<String, Object> locationToMap(ILocation loc) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (loc == null) {
            return m;
        }
        m.put("public_id", loc.getPublicId());
        m.put("external_location_id", loc.getExternalLocationId());
        m.put("name", loc.getName());
        m.put("contact_address", loc.getContactAddress());
        m.put("city", loc.getCity());
        m.put("province", loc.getProvince());
        m.put("zip", loc.getZip());
        m.put("country", loc.getCountry());
        m.put("country_iso2", loc.getCountryIso2());
        m.put("gps_lat", loc.getGpsLat());
        m.put("gps_lon", loc.getGpsLon());
        m.put("time_zone", loc.getTimeZone());
        m.put("description", loc.getDescription());
        return m;
    }

    private static List<Map<String, Object>> banknoteCountsToList(List<IBanknoteCounts> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cashbox_name", bc.getCashboxName());
            row.put("currency", bc.getCurrency());
            row.put("denomination", bc.getDenomination());
            row.put("count", bc.getCount());
            row.put("capacity", bc.getCapacity());
            out.add(row);
        }
        out.sort(Comparator
                .comparing((Map<String, Object> r) -> Objects.toString(r.get("cashbox_name"), ""))
                .thenComparing(r -> Objects.toString(r.get("currency"), ""))
                .thenComparing(r -> Objects.toString(r.get("denomination"), "")));
        return out;
    }

    private static List<Map<String, Object>> aggregateBanknotesByCurrencyAndDenomination(List<IBanknoteCounts> cashBoxes) {
        if (cashBoxes == null || cashBoxes.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Map<String, Integer>> agg = new LinkedHashMap<>();
        for (IBanknoteCounts bc : cashBoxes) {
            if (bc == null) {
                continue;
            }
            String currency = bc.getCurrency() != null ? bc.getCurrency() : "";
            String denom = bc.getDenomination() != null ? bc.getDenomination().toPlainString() : "0";
            int count = bc.getCount();
            if (count == 0) {
                continue;
            }
            Map<String, Integer> byDenom = agg.computeIfAbsent(currency, k -> new LinkedHashMap<>());
            byDenom.put(denom, byDenom.getOrDefault(denom, 0) + count);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> c : agg.entrySet()) {
            for (Map.Entry<String, Integer> d : c.getValue().entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("currency", c.getKey());
                row.put("denomination", d.getKey());
                row.put("count", d.getValue());
                out.add(row);
            }
        }
        out.sort(Comparator
                .comparing((Map<String, Object> r) -> Objects.toString(r.get("currency"), ""))
                .thenComparing(r -> Objects.toString(r.get("denomination"), "")));
        return out;
    }

    private static List<Map<String, Object>> summarizeByCashboxName(List<IBanknoteCounts> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<IBanknoteCounts>> grouped = new LinkedHashMap<>();
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            String name = bc.getCashboxName() != null ? bc.getCashboxName() : "";
            grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(bc);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<IBanknoteCounts>> e : grouped.entrySet()) {
            String cashboxName = e.getKey();
            List<IBanknoteCounts> rows = e.getValue();

            int totalCount = 0;
            Integer capacity = null;
            BigDecimal totalValue = BigDecimal.ZERO;
            String currencySingle = null;
            boolean currencyMixed = false;

            List<Map<String, Object>> banknotes = new ArrayList<>();
            for (IBanknoteCounts bc : rows) {
                if (bc == null) {
                    continue;
                }
                totalCount += bc.getCount();
                if (bc.getCapacity() != null) {
                    capacity = capacity == null ? bc.getCapacity() : Math.max(capacity, bc.getCapacity());
                }
                String currency = bc.getCurrency();
                if (currencySingle == null) {
                    currencySingle = currency;
                } else if (!Objects.equals(currencySingle, currency)) {
                    currencyMixed = true;
                }
                BigDecimal denom = bc.getDenomination();
                BigDecimal value = denom != null ? denom.multiply(BigDecimal.valueOf(bc.getCount())) : null;
                if (value != null) {
                    totalValue = totalValue.add(value);
                }

                Map<String, Object> bn = new LinkedHashMap<>();
                bn.put("currency", currency);
                bn.put("denomination", denom);
                bn.put("count", bc.getCount());
                bn.put("value", value);
                banknotes.add(bn);
            }
            banknotes.sort(Comparator
                    .comparing((Map<String, Object> r) -> Objects.toString(r.get("currency"), ""))
                    .thenComparing(r -> Objects.toString(r.get("denomination"), "")));

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("cashbox_name", cashboxName);
            summary.put("total_count", totalCount);
            summary.put("capacity", capacity);
            if (capacity != null && capacity > 0) {
                BigDecimal ratio =
                        BigDecimal.valueOf(totalCount)
                                .divide(BigDecimal.valueOf(capacity), 4, RoundingMode.HALF_UP);
                summary.put("fill_ratio", ratio);
            } else {
                summary.put("fill_ratio", null);
            }
            summary.put("currency", currencyMixed ? null : currencySingle);
            summary.put("total_value", totalValue);
            summary.put("banknotes", banknotes);
            out.add(summary);
        }

        out.sort(Comparator.comparing((Map<String, Object> r) -> Objects.toString(r.get("cashbox_name"), "")));
        return out;
    }

    private static List<IBanknoteCounts> filterByCashboxNamePrefix(List<IBanknoteCounts> items, String prefix) {
        if (items == null || items.isEmpty() || prefix == null) {
            return Collections.emptyList();
        }
        List<IBanknoteCounts> out = new ArrayList<>();
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            String name = bc.getCashboxName();
            if (name != null && name.startsWith(prefix)) {
                out.add(bc);
            }
        }
        return out;
    }

    private static List<IBanknoteCounts> filterByCashboxNameExact(List<IBanknoteCounts> items, String exact) {
        if (items == null || items.isEmpty() || exact == null) {
            return Collections.emptyList();
        }
        List<IBanknoteCounts> out = new ArrayList<>();
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            if (exact.equals(bc.getCashboxName())) {
                out.add(bc);
            }
        }
        return out;
    }

    private static <T> List<T> safeList(List<T> in) {
        return in != null ? in : Collections.emptyList();
    }

    /**
     * Resolves a query/body parameter. Case-insensitive on names. Tries servlet parameters, JAX-RS query map, raw query.
     */
    private static String firstParam(HttpServletRequest request, UriInfo uriInfo, String... names) {
        if (request != null) {
            for (String n : names) {
                String v = request.getParameter(n);
                if (v != null && !v.trim().isEmpty()) {
                    return v;
                }
            }
            for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
                for (String want : names) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase(want)) {
                        if (e.getValue() != null && e.getValue().length > 0) {
                            String v = e.getValue()[0];
                            if (v != null && !v.trim().isEmpty()) {
                                return v;
                            }
                        }
                    }
                }
            }
        }
        if (uriInfo != null) {
            MultivaluedMap<String, String> q = uriInfo.getQueryParameters();
            for (String n : names) {
                String v = q.getFirst(n);
                if (v != null && !v.trim().isEmpty()) {
                    return v;
                }
            }
            for (String n : names) {
                for (String key : q.keySet()) {
                    if (key != null && key.equalsIgnoreCase(n)) {
                        String v = q.getFirst(key);
                        if (v != null && !v.trim().isEmpty()) {
                            return v;
                        }
                    }
                }
            }
            if (uriInfo.getRequestUri() != null) {
                Map<String, String> raw = parseRawQueryString(uriInfo.getRequestUri().getRawQuery());
                for (String n : names) {
                    for (Map.Entry<String, String> e : raw.entrySet()) {
                        if (e.getKey() != null && e.getKey().equalsIgnoreCase(n)) {
                            String v = e.getValue();
                            if (v != null && !v.trim().isEmpty()) {
                                return v;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Splits a query string into key/value segments. Both {@code &} (RFC) and {@code ;} (HTML/legacy) are valid
     * parameter separators; splitting on {@code &} only leaves a single fake "key" when {@code ;} is used.
     */
    private static String[] splitQuerySegments(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return new String[0];
        }
        return rawQuery.split("[&;]");
    }

    private static List<String> listRawQueryParamNames(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>();
        for (String pair : splitQuerySegments(rawQuery)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            try {
                k = URLDecoder.decode(k, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                // keep k as-is
            }
            keys.add(k);
        }
        return keys;
    }

    /** Minimal sanity check; not a full RFC 5322 validator. */
    private static boolean looksLikeEmail(String s) {
        if (s == null) {
            return false;
        }
        s = s.trim();
        int at = s.indexOf('@');
        if (at <= 0 || at >= s.length() - 1) {
            return false;
        }
        if (s.indexOf('@', at + 1) >= 0) {
            return false;
        }
        String domain = s.substring(at + 1);
        return domain.indexOf('.') >= 0 && !domain.startsWith(".") && !domain.endsWith(".");
    }

    private static String jsonField(JsonNode node, String... possibleNames) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String want : possibleNames) {
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                if (key != null && key.equalsIgnoreCase(want)) {
                    return jsonNodeToScalarString(node.get(key));
                }
            }
        }
        return null;
    }

    private static String jsonNodeToScalarString(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.toString();
        }
        if (n.isTextual()) {
            String t = n.asText();
            return t != null && t.trim().isEmpty() ? null : t;
        }
        if (n.isValueNode()) {
            return n.toString();
        }
        return null;
    }

    private static Map<String, String> parseRawQueryString(String rawQuery) {
        Map<String, String> out = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return out;
        }
        for (String pair : splitQuerySegments(rawQuery)) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 && eq + 1 < pair.length() ? pair.substring(eq + 1) : "";
            try {
                k = URLDecoder.decode(k, StandardCharsets.UTF_8.name());
                v = URLDecoder.decode(v, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                // keep undecoded fragment
            }
            out.putIfAbsent(k, v);
        }
        return out;
    }

    private static final int CASHBACK_QR_SIZE_PX = 400;

    /** Safe subset of BCP 47 for config filenames (see {@link IExtensionContext#getConfigFileContent}). */
    private static final Pattern ALLOWED_LANGUAGE_TAG = Pattern.compile("^[a-z]{2,3}(-[a-z0-9]{2,8})?$");

    /** Relative to the CAS config directory; {@link IExtensionContext#getConfigFileContent} path. */
    private static final String CASHBACK_MAIL_CONTENTS_DIR = "mail_contents/";

    private static final String CASHBACK_EMAIL_BODY_TEMPLATE_PREFIX =
            CASHBACK_MAIL_CONTENTS_DIR + "coinhub_cashback_email_";

    private static final String CASHBACK_EMAIL_SUBJECT_TEMPLATE_PREFIX =
            CASHBACK_MAIL_CONTENTS_DIR + "coinhub_cashback_subject_";

    /**
     * Sends the cashback redeem QR as {@code cashback-qr.png} via CAS mail (SMTP must be configured on the server).
     * Sender address: {@code cashback_mail_from} in the {@code coinhub} extension config file.
     */
    private void tryQueueCashbackQrEmail(
            IExtensionContext ctx,
            String toEmail,
            String terminalSerial,
            ITransactionCashbackInfo cashback,
            String qrPayload,
            Map<String, Object> responseBody,
            String languageRaw) {
        responseBody.put("email_locale_resolved", sanitizeLanguageTagForConfig(languageRaw));
        String from = ctx.getConfigProperty("coinhub", "cashback_mail_from", null);
        if (from == null || from.trim().isEmpty()) {
            log.warn(
                    "Cashback QR email not sent: add cashback_mail_from to coinhub extension config (sender From: address)");
            responseBody.put("email_queued", false);
            responseBody.put(
                    "email_send_skipped",
                    "configure coinhub.cashback_mail_from and ensure CAS SMTP is set up; cashback was still created.");
            return;
        }
        final byte[] png;
        try {
            png = encodeCashbackQrPng(qrPayload, CASHBACK_QR_SIZE_PX);
        } catch (Exception e) {
            log.error("cashback QR image encoding failed", e);
            responseBody.put("email_queued", false);
            responseBody.put("email_send_error", "qr_encode_failed: " + e.getMessage());
            return;
        }
        if (png == null || png.length == 0) {
            responseBody.put("email_queued", false);
            responseBody.put("email_send_error", "qr_encode_empty");
            return;
        }
        String subject =
                resolveCashbackEmailSubjectText(
                        ctx, terminalSerial, cashback, qrPayload, languageRaw, responseBody);
        String text =
                resolveCashbackEmailBodyText(
                        ctx, terminalSerial, cashback, qrPayload, languageRaw, responseBody);
        try {
            ctx.sendMailAsyncWithAttachment(
                    from.trim(),
                    toEmail,
                    subject,
                    text,
                    "cashback-qr.png",
                    png,
                    "image/png",
                    null);
            responseBody.put("email_queued", true);
        } catch (Exception e) {
            log.error("cashback email send failed", e);
            responseBody.put("email_queued", false);
            responseBody.put("email_send_error", e.getMessage() != null ? e.getMessage() : "send_failed");
        }
    }

    /**
     * Loads {@code mail_contents/coinhub_cashback_subject_*.txt} when present; else
     * {@link #buildDefaultCashbackEmailSubject}.
     */
    private static String resolveCashbackEmailSubjectText(
            IExtensionContext ctx,
            String terminalSerial,
            ITransactionCashbackInfo cashback,
            String qrPayload,
            String languageRaw,
            Map<String, Object> responseBody) {
        String tag = sanitizeLanguageTagForConfig(languageRaw);
        CashbackTemplateFile template = loadCashbackConfigTemplate(ctx, CASHBACK_EMAIL_SUBJECT_TEMPLATE_PREFIX, tag);
        if (template != null && !template.content.trim().isEmpty()) {
            if (responseBody != null) {
                responseBody.put("email_subject_template", template.loadedFromPath);
            }
            return applyCashbackEmailTemplate(
                    normalizeSingleLineForEmailSubject(template.content),
                    terminalSerial,
                    cashback,
                    qrPayload);
        }
        if (responseBody != null) {
            responseBody.put("email_subject_template", "default");
        }
        return buildDefaultCashbackEmailSubject(terminalSerial, cashback);
    }

    /**
     * Loads {@code mail_contents/coinhub_cashback_email_*.txt} from the CAS config directory when present; else
     * {@link #buildDefaultCashbackPlainEmailBody}.
     */
    private static String resolveCashbackEmailBodyText(
            IExtensionContext ctx,
            String terminalSerial,
            ITransactionCashbackInfo cashback,
            String qrPayload,
            String languageRaw,
            Map<String, Object> responseBody) {
        String tag = sanitizeLanguageTagForConfig(languageRaw);
        CashbackTemplateFile template = loadCashbackConfigTemplate(ctx, CASHBACK_EMAIL_BODY_TEMPLATE_PREFIX, tag);
        if (template != null && !template.content.trim().isEmpty()) {
            if (responseBody != null) {
                responseBody.put("email_body_template", template.loadedFromPath);
            }
            return applyCashbackEmailTemplate(template.content, terminalSerial, cashback, qrPayload);
        }
        if (responseBody != null) {
            responseBody.put("email_body_template", "default");
        }
        return buildDefaultCashbackPlainEmailBody(terminalSerial, cashback, qrPayload);
    }

    private static String sanitizeLanguageTagForConfig(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "en";
        }
        String t = raw.trim().toLowerCase();
        if (!ALLOWED_LANGUAGE_TAG.matcher(t).matches()) {
            log.warn("cashback email language ignored (invalid tag, using en): {}", raw);
            return "en";
        }
        return t;
    }

    /**
     * Non-null content plus which relative path under the CAS config directory was used (for API diagnostics).
     */
    private static final class CashbackTemplateFile {
        final String content;
        final String loadedFromPath;

        CashbackTemplateFile(String content, String loadedFromPath) {
            this.content = content;
            this.loadedFromPath = loadedFromPath;
        }
    }

    /**
     * Tries {@code &lt;prefix&gt;&lt;lang&gt;.txt} (prefix includes {@code mail_contents/}). If the request is for a
     * non-English locale, does not fall back to {@code *_en.txt} (so {@code language=ja} will not load English
     * templates when {@code *_ja.txt} is absent). English requests still try {@code *_en.txt} after tag variants.
     * <p>
     * If {@code getConfigFileContent} returns empty for a subpath, the same file name in the config root is tried
     * (some CAS builds only resolve flat names).
     */
    private static CashbackTemplateFile loadCashbackConfigTemplate(
            IExtensionContext ctx, String basenamePrefix, String sanitizedTag) {
        String primary =
                sanitizedTag.contains("-") ? sanitizedTag.substring(0, sanitizedTag.indexOf('-')) : sanitizedTag;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(basenamePrefix + sanitizedTag + ".txt");
        if (!primary.equals(sanitizedTag)) {
            names.add(basenamePrefix + primary + ".txt");
        }
        if ("en".equals(primary)) {
            names.add(basenamePrefix + "en.txt");
        }
        for (String name : names) {
            CashbackTemplateFile loaded = readCashbackTemplateFile(ctx, name);
            if (loaded != null) {
                log.debug("cashback email template from config: {}", loaded.loadedFromPath);
                return loaded;
            }
        }
        return null;
    }

    /**
     * Reads template text; if {@code mail_contents/foo.txt} is empty, tries {@code foo.txt} at config root.
     */
    private static CashbackTemplateFile readCashbackTemplateFile(IExtensionContext ctx, String relativePath) {
        String content = ctx.getConfigFileContent(relativePath);
        if (content != null && !content.trim().isEmpty()) {
            return new CashbackTemplateFile(content, relativePath);
        }
        int slash = relativePath.lastIndexOf('/');
        if (slash >= 0) {
            String flatName = relativePath.substring(slash + 1);
            content = ctx.getConfigFileContent(flatName);
            if (content != null && !content.trim().isEmpty()) {
                log.info(
                        "cashback template loaded from config root {} (path {} was missing or empty)",
                        flatName,
                        relativePath);
                return new CashbackTemplateFile(content, flatName);
            }
        }
        return null;
    }

    /** Collapses newlines in a subject template (file may be multi-line for editing). */
    private static String normalizeSingleLineForEmailSubject(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("[\r\n]+", " ");
    }

    private static String applyCashbackEmailTemplate(
            String template,
            String terminalSerial,
            ITransactionCashbackInfo cashback,
            String qrPayload) {
        long validity = cashback.getValidityInMinutes();
        return template
                .replace("{terminal}", terminalSerial)
                .replace("{amount}", cashback.getCashAmount().toPlainString())
                .replace("{currency}", cashback.getCashCurrency())
                .replace("{validity_minutes}", Long.toString(validity))
                .replace("{qr_payload}", qrPayload);
    }

    /** Default English subject when no {@code mail_contents/coinhub_cashback_subject_*.txt} exists. */
    private static String buildDefaultCashbackEmailSubject(
            String terminalSerial, ITransactionCashbackInfo cashback) {
        return "Cashback — "
                + cashback.getCashAmount().toPlainString()
                + " "
                + cashback.getCashCurrency()
                + " ("
                + terminalSerial
                + ")";
    }

    /** Default English body when no template file exists (same text as before localization support). */
    private static String buildDefaultCashbackPlainEmailBody(
            String terminalSerial, ITransactionCashbackInfo cashback, String qrPayload) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your cashback is ready.\r\n\r\n");
        sb.append("Terminal: ").append(terminalSerial).append("\r\n");
        sb.append("Amount: ")
                .append(cashback.getCashAmount().toPlainString())
                .append(" ")
                .append(cashback.getCashCurrency())
                .append("\r\n");
        long validity = cashback.getValidityInMinutes();
        if (validity > 0) {
            sb.append("Please use the QR at the terminal within about ")
                    .append(validity)
                    .append(" minutes.\r\n");
        }
        sb.append("\r\nThe attached image (cashback-qr.png) is your redeem QR code.\r\n\r\n");
        sb.append("Reference string:\r\n").append(qrPayload).append("\r\n");
        return sb.toString();
    }

    private static byte[] encodeCashbackQrPng(String contents, int sizePx) throws WriterException, IOException {
        Map<EncodeHintType, Object> hintMap = new HashMap<>();
        hintMap.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix byteMatrix = qrCodeWriter.encode(contents, BarcodeFormat.QR_CODE, sizePx, sizePx, hintMap);
        int matrixWidth = byteMatrix.getWidth();
        BufferedImage image = new BufferedImage(matrixWidth, matrixWidth, BufferedImage.TYPE_INT_RGB);
        image.createGraphics();
        Graphics2D graphics = (Graphics2D) image.getGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, matrixWidth, matrixWidth);
        graphics.setColor(Color.BLACK);
        for (int i = 0; i < matrixWidth; i++) {
            for (int j = 0; j < matrixWidth; j++) {
                if (byteMatrix.get(i, j)) {
                    graphics.fillRect(i, j, 1, 1);
                }
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageOutputStream stream = new MemoryCacheImageOutputStream(baos);
        ImageIO.write(image, "png", stream);
        stream.close();
        return baos.toByteArray();
    }
}
