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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
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
 * <strong>Translating the email:</strong> add template files under {@code mail_contents/} in the CAS config
 * directory (typically {@code /batm/config/mail_contents/}). Body:
 * {@code mail_contents/coinhub_cashback_email_&lt;lang&gt;.html} (HTML, preferred) or
 * {@code mail_contents/coinhub_cashback_email_&lt;lang&gt;.txt} (plain-text fallback).
 * Subject: {@code coinhub_cashback_subject_&lt;lang&gt;.txt} (single line; line breaks are flattened).
 * Pass {@code language} on the cashback request (e.g. {@code ja} or {@code ja-JP}). Placeholders:
 * {@code {terminal}}, {@code {amount}}, {@code {currency}}, {@code {validity_minutes}}, {@code {qr_payload}},
 * {@code {transaction_id}}, {@code {time}}, {@code {logo}} (text), plus for HTML
 * {@code {logo_html}} and {@code {logo_url}}. Configure {@code coinhub.cashback_logo_url} for the logo image URL.
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
     * Returns cassette summaries (per {@code dispenser_cassette_*}: {@code denomination}, {@code total_remaining_count},
     * {@code total_remaining_amount}) and acceptor {@code cashbox_summary} with fixed JPY slots ¥1000 / ¥5000 / ¥10,000.
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

            List<IBanknoteCounts> cashBoxes = safeList(ctx.getCashBoxes(sn));
            List<IBanknoteCounts> cassettes = filterByCashboxNamePrefix(cashBoxes, "dispenser_cassette_");
            List<IBanknoteCounts> acceptorCashbox = filterAcceptorCashboxRows(cashBoxes);

            body.put("cassette_summary", summarizeCassetteBoxes(cassettes));
            TreeSet<BigDecimal> fallbackDenoms = collectJpyDenominationsSorted(cassettes);
            List<Map<String, Object>> cashboxSummary = summarizeAcceptorCashboxJpyFlat(acceptorCashbox, fallbackDenoms);
            if (cashboxSummary.isEmpty()) {
                cashboxSummary = Collections.singletonList(emptyAcceptorCashboxSummary(fallbackDenoms));
            }
            body.put("cashbox_summary", cashboxSummary);
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

    /**
     * Returns cassette + acceptor cashbox summaries for all terminals.
     *
     * <p>Example:</p>
     * {@code GET /extensions/coinhub/atm/all}
     */
    @GET
    @Path("/atm/all")
    public Map<String, Object> getAtmInfoAll() {
        Map<String, Object> body = new LinkedHashMap<>();

        IExtensionContext ctx = RYOExtension.getExtensionContext();
        if (ctx == null) {
            body.put("ok", false);
            body.put("error", "extension_context_unavailable");
            return body;
        }

        List<ITerminal> terminals = safeList(ctx.findAllTerminals());
        List<Map<String, Object>> out = new ArrayList<>();

        for (ITerminal t : terminals) {
            if (t == null) {
                continue;
            }
            String sn = t.getSerialNumber() != null ? t.getSerialNumber().trim() : "";
            if (sn.isEmpty()) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serial_number", sn);
            row.put("terminal_name", t.getName());
            row.put("terminal_active", t.isActive());
            row.put("terminal_locked", t.isLocked());
            row.put("terminal_deleted", t.isDeleted());

            try {
                List<IBanknoteCounts> cashBoxes = safeList(ctx.getCashBoxes(sn));
                List<IBanknoteCounts> cassettes = filterByCashboxNamePrefix(cashBoxes, "dispenser_cassette_");
                List<IBanknoteCounts> acceptorCashbox = filterAcceptorCashboxRows(cashBoxes);

                row.put("cassette_summary", summarizeCassetteBoxes(cassettes));
                TreeSet<BigDecimal> fallbackDenoms = collectJpyDenominationsSorted(cassettes);
                List<Map<String, Object>> cashboxSummary = summarizeAcceptorCashboxJpyFlat(acceptorCashbox, fallbackDenoms);
                if (cashboxSummary.isEmpty()) {
                    cashboxSummary = Collections.singletonList(emptyAcceptorCashboxSummary(fallbackDenoms));
                }
                row.put("cashbox_summary", cashboxSummary);
                row.put("ok", true);
            } catch (IllegalArgumentException e) {
                row.put("ok", false);
                row.put("error", "invalid_parameters");
                row.put("detail", e.getMessage());
            } catch (Throwable e) {
                log.error("atm/all terminal summary failed: {}", sn, e);
                row.put("ok", false);
                row.put("error", "unexpected");
                row.put("detail", e.getMessage());
            }

            out.add(row);
        }

        out.sort(Comparator.comparing(r -> Objects.toString(r.get("serial_number"), "")));
        body.put("ok", true);
        body.put("count", out.size());
        body.put("terminals", out);
        return body;
    }

    private static List<Map<String, Object>> summarizeCassetteBoxes(List<IBanknoteCounts> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, List<IBanknoteCounts>> grouped = groupBanknoteCountsByCashboxName(items);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<IBanknoteCounts>> e : grouped.entrySet()) {
            List<IBanknoteCounts> group = e.getValue();
            Map<BigDecimal, Integer> countsByDenom = new HashMap<>();
            for (IBanknoteCounts bc : group) {
                if (bc == null) {
                    continue;
                }
                int cnt = bc.getCount();
                String currency = bc.getCurrency();
                boolean jpy = currency == null || "JPY".equalsIgnoreCase(currency);
                BigDecimal denom = bc.getDenomination();
                if (jpy && denom != null) {
                    BigDecimal d = denom.stripTrailingZeros();
                    countsByDenom.merge(d, cnt, Integer::sum);
                }
            }

            // One row per denomination so total_remaining_amount is "by denomination"
            TreeSet<BigDecimal> denomsSorted = new TreeSet<>(countsByDenom.keySet());
            for (BigDecimal d : denomsSorted) {
                int remaining = countsByDenom.getOrDefault(d, 0);
                BigDecimal totalAmount = d.multiply(BigDecimal.valueOf(remaining));

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cashbox_name", e.getKey());
                row.put("denomination", denominationToJson(d));
                row.put("total_remaining_count", remaining);
                row.put("total_remaining_amount", amountToJson(totalAmount));
                out.add(row);
            }
        }
        out.sort(Comparator
                .comparing((Map<String, Object> r) -> Objects.toString(r.get("cashbox_name"), ""))
                .thenComparing(r -> Objects.toString(r.get("denomination"), "")));
        return out;
    }

    /**
     * Acceptor cashbox: all note counts, total JPY value (JPY rows only; null currency treated as JPY), and three
     * dynamic denomination slots populated from {@link IBanknoteCounts#getDenomination()} (lowest 3 denominations).
     */
    private static List<Map<String, Object>> summarizeAcceptorCashboxJpyFlat(List<IBanknoteCounts> items, TreeSet<BigDecimal> fallbackDenoms) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        int totalCount = 0;
        BigDecimal totalValueJpy = BigDecimal.ZERO;
        Map<BigDecimal, Integer> countsByDenom = new HashMap<>();

        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            int cnt = bc.getCount();
            totalCount += cnt;
            String currency = bc.getCurrency();
            boolean jpy = currency == null || "JPY".equalsIgnoreCase(currency);
            BigDecimal denom = bc.getDenomination();
            if (jpy && denom != null) {
                BigDecimal d = denom.stripTrailingZeros();
                totalValueJpy = totalValueJpy.add(d.multiply(BigDecimal.valueOf(cnt)));
                countsByDenom.merge(d, cnt, Integer::sum);
            }
        }

        TreeSet<BigDecimal> denomsSorted = new TreeSet<>(countsByDenom.keySet());
        if (denomsSorted.isEmpty() && fallbackDenoms != null && !fallbackDenoms.isEmpty()) {
            denomsSorted = new TreeSet<>(fallbackDenoms);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cashbox_name", IBanknoteCounts.CN_ACCEPTOR_CASHBOX);
        summary.put("total_count", totalCount);
        summary.put("total_value", amountToJson(totalValueJpy));
        int slot = 1;
        for (BigDecimal d : denomsSorted) {
            if (slot > 3) {
                break;
            }
            int c = countsByDenom.getOrDefault(d, 0);
            BigDecimal lineValue = d.multiply(BigDecimal.valueOf(c));
            summary.put("banknote_" + slot + "_denomication", denominationToJson(d));
            summary.put("banknote_" + slot + "_count", c);
            summary.put("banknote_" + slot + "_value", amountToJson(lineValue));
            slot++;
        }
        while (slot <= 3) {
            summary.put("banknote_" + slot + "_denomication", null);
            summary.put("banknote_" + slot + "_count", 0);
            summary.put("banknote_" + slot + "_value", 0L);
            slot++;
        }
        return Collections.singletonList(summary);
    }

    /** JSON-friendly number: integral denominations as Long, otherwise plain {@link BigDecimal}. */
    private static Object denominationToJson(BigDecimal d) {
        if (d == null) {
            return null;
        }
        BigDecimal s = d.stripTrailingZeros();
        if (s.scale() <= 0) {
            return s.longValue();
        }
        return s;
    }

    private static Object amountToJson(BigDecimal v) {
        if (v == null) {
            return 0L;
        }
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() <= 0) {
            return s.longValue();
        }
        return s;
    }

    /**
     * Rows the server reports for the bill acceptor / stacker: canonical {@code acceptor_cashbox}, names containing
     * {@code acceptor}, or any cashbox that is not a dispenser cassette, recycler, or reject slot (CAS-specific names).
     */
    private static List<IBanknoteCounts> filterAcceptorCashboxRows(List<IBanknoteCounts> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        List<IBanknoteCounts> out = new ArrayList<>();
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            if (isAcceptorCashboxName(bc.getCashboxName())) {
                out.add(bc);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        return filterResidualNonDispenserCashboxRows(items);
    }

    /** Dispenser cassettes, recycler drums, and reject — not used for deposit / acceptor summary. */
    private static boolean isKnownNonAcceptorHardwareCashboxName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String n = name.trim();
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.startsWith("dispenser_")) {
            return true;
        }
        if (lower.startsWith("recycler")) {
            return true;
        }
        return false;
    }

    private static List<IBanknoteCounts> filterResidualNonDispenserCashboxRows(List<IBanknoteCounts> items) {
        List<IBanknoteCounts> out = new ArrayList<>();
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            String name = bc.getCashboxName();
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            if (!isKnownNonAcceptorHardwareCashboxName(name)) {
                out.add(bc);
            }
        }
        return out;
    }

    private static Map<String, Object> emptyAcceptorCashboxSummary(TreeSet<BigDecimal> fallbackDenoms) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("cashbox_name", IBanknoteCounts.CN_ACCEPTOR_CASHBOX);
        summary.put("total_count", 0);
        summary.put("total_value", 0L);
        TreeSet<BigDecimal> denomsSorted = (fallbackDenoms != null) ? new TreeSet<>(fallbackDenoms) : new TreeSet<>();
        int slot = 1;
        for (BigDecimal d : denomsSorted) {
            if (slot > 3) {
                break;
            }
            summary.put("banknote_" + slot + "_denomication", denominationToJson(d));
            summary.put("banknote_" + slot + "_count", 0);
            summary.put("banknote_" + slot + "_value", 0L);
            slot++;
        }
        while (slot <= 3) {
            // Ensure non-null even when we have no fallback info.
            summary.put("banknote_" + slot + "_denomication", 0);
            summary.put("banknote_" + slot + "_count", 0);
            summary.put("banknote_" + slot + "_value", 0L);
            slot++;
        }
        return summary;
    }

    private static TreeSet<BigDecimal> collectJpyDenominationsSorted(List<IBanknoteCounts> items) {
        TreeSet<BigDecimal> out = new TreeSet<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            String currency = bc.getCurrency();
            boolean jpy = currency == null || "JPY".equalsIgnoreCase(currency);
            if (!jpy) {
                continue;
            }
            BigDecimal denom = bc.getDenomination();
            if (denom != null) {
                out.add(denom.stripTrailingZeros());
            }
        }
        return out;
    }

    private static boolean isAcceptorCashboxName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String n = name.trim();
        if (IBanknoteCounts.CN_ACCEPTOR_CASHBOX.equalsIgnoreCase(n)) {
            return true;
        }
        String lower = n.toLowerCase(Locale.ROOT);
        if (!lower.contains("acceptor")) {
            return false;
        }
        if (lower.startsWith("dispenser_") || lower.startsWith("recycler")) {
            return false;
        }
        return !IBanknoteCounts.CN_DISPENSER_REJECT.equalsIgnoreCase(n);
    }

    private static Map<String, List<IBanknoteCounts>> groupBanknoteCountsByCashboxName(List<IBanknoteCounts> items) {
        Map<String, List<IBanknoteCounts>> grouped = new LinkedHashMap<>();
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            String name = bc.getCashboxName() != null ? bc.getCashboxName() : "";
            grouped.computeIfAbsent(name, k -> new ArrayList<>()).add(bc);
        }
        return grouped;
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

    private static final String CASHBACK_EMAIL_BODY_HTML_TEMPLATE_PREFIX =
            CASHBACK_MAIL_CONTENTS_DIR + "coinhub_cashback_email_";

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
        String logoUrl = ctx.getConfigProperty("coinhub", "cashback_logo_url", null);
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
        try {
            String html =
                    resolveCashbackEmailBodyHtml(
                            ctx, terminalSerial, cashback, qrPayload, languageRaw, responseBody, logoUrl);
            if (html != null && !html.trim().isEmpty()) {
                ctx.sendHTMLMailAsyncWithAttachment(
                        from.trim(),
                        toEmail,
                        subject,
                        html,
                        "cashback-qr.png",
                        png,
                        "image/png",
                        null);
            } else {
                String text =
                        resolveCashbackEmailBodyText(
                                ctx, terminalSerial, cashback, qrPayload, languageRaw, responseBody);
                ctx.sendMailAsyncWithAttachment(
                        from.trim(),
                        toEmail,
                        subject,
                        text,
                        "cashback-qr.png",
                        png,
                        "image/png",
                        null);
            }
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
        CashbackTemplateFile template = loadCashbackConfigTemplate(ctx, CASHBACK_EMAIL_SUBJECT_TEMPLATE_PREFIX, tag, "txt");
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
        CashbackTemplateFile template = loadCashbackConfigTemplate(ctx, CASHBACK_EMAIL_BODY_TEMPLATE_PREFIX, tag, "txt");
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

    /**
     * Loads {@code mail_contents/coinhub_cashback_email_*.html} from CAS config directory when present.
     * If absent, returns {@code null} and caller should use plain-text fallback.
     */
    private static String resolveCashbackEmailBodyHtml(
            IExtensionContext ctx,
            String terminalSerial,
            ITransactionCashbackInfo cashback,
            String qrPayload,
            String languageRaw,
            Map<String, Object> responseBody,
            String logoUrl) {
        String tag = sanitizeLanguageTagForConfig(languageRaw);
        CashbackTemplateFile template =
                loadCashbackConfigTemplate(ctx, CASHBACK_EMAIL_BODY_HTML_TEMPLATE_PREFIX, tag, "html");
        if (template != null && !template.content.trim().isEmpty()) {
            if (responseBody != null) {
                responseBody.put("email_body_template_html", template.loadedFromPath);
            }
            return applyCashbackEmailTemplateHtml(template.content, terminalSerial, cashback, qrPayload, logoUrl);
        }
        if (responseBody != null) {
            responseBody.put("email_body_template_html", "missing");
        }
        return null;
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
            IExtensionContext ctx, String basenamePrefix, String sanitizedTag, String extension) {
        String primary =
                sanitizedTag.contains("-") ? sanitizedTag.substring(0, sanitizedTag.indexOf('-')) : sanitizedTag;
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String ext = extension != null && !extension.trim().isEmpty() ? extension.trim() : "txt";
        names.add(basenamePrefix + sanitizedTag + "." + ext);
        if (!primary.equals(sanitizedTag)) {
            names.add(basenamePrefix + primary + "." + ext);
        }
        if ("en".equals(primary)) {
            names.add(basenamePrefix + "en." + ext);
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
        String time = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        return template
                .replace("{terminal}", terminalSerial)
                .replace("{amount}", cashback.getCashAmount().toPlainString())
                .replace("{currency}", cashback.getCashCurrency())
                .replace("{validity_minutes}", Long.toString(validity))
                .replace("{transaction_id}", Objects.toString(cashback.getRemoteTransactionId(), ""))
                .replace("{time}", time)
                .replace("{logo}", "CoinHub")
                .replace("{qr_payload}", qrPayload);
    }

    private static String applyCashbackEmailTemplateHtml(
            String template,
            String terminalSerial,
            ITransactionCashbackInfo cashback,
            String qrPayload,
            String logoUrl) {
        String logoHtml = buildLogoHtml(logoUrl);
        return applyCashbackEmailTemplate(template, terminalSerial, cashback, qrPayload)
                .replace("{logo_url}", logoUrl != null ? logoUrl : "")
                .replace("{logo_html}", logoHtml);
    }

    private static String buildLogoHtml(String logoUrl) {
        String url = logoUrl != null ? logoUrl.trim() : "";
        if (url.isEmpty()) {
            return "CoinHub";
        }
        // Keep markup minimal for broad email-client compatibility.
        return "<img src=\"" + escapeHtmlAttr(url) + "\" alt=\"CoinHub\" style=\"height:32px;max-width:100%;\" />";
    }

    private static String escapeHtmlAttr(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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
