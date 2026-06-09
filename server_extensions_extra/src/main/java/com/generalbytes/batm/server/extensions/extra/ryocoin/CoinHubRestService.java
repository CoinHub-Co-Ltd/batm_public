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
import com.generalbytes.batm.server.extensions.IOrganization;
import com.generalbytes.batm.server.extensions.IRestService;
import com.generalbytes.batm.server.extensions.ITerminal;
import com.generalbytes.batm.server.extensions.ITransactionCashbackInfo;
import com.generalbytes.batm.server.extensions.ITransactionDetails;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
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
 * {@code {terminal}}, {@code {amount}}, {@code {currency}}, {@code {address}}, {@code {validity_minutes}},
 * {@code {qr_payload}}, {@code {transaction_id}}, {@code {time}}, {@code {logo}} (text), plus for HTML
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

    private static final String SLOT_C4 = "C4";
    private static final int DISPENSER_UI_SLOT_COUNT = 3;
    private static final int CASSETTE_CAPACITY_NOTES = 500;
    private static final int CASSETTE_NEAR_END_THRESHOLD_NOTES = 20;
    private static final int CASSETTE_SEVERE_LOW_THRESHOLD_NOTES = 10;
    private static final int CASHBOX_CAPACITY_NOTES = 1200;
    private static final int CASHBOX_NEAR_FULL_THRESHOLD_NOTES = 1000;

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
     * {@code total_remaining_amount}), acceptor {@code cashbox_summary}, and unified {@code slot_summary} for
     * {@code C1}–{@code C3} (dispenser cassettes) plus {@code C4} (acceptor cashbox).
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
            body.put("slot_summary", buildSlotSummaries(cassettes, acceptorCashbox, fallbackDenoms));
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
     * Returns how many ATMs are included in {@link #getAtmInfoAll()}: active, non-deleted terminals with a non-empty serial number.
     *
     * <p>Example:</p>
     * {@code GET /extensions/coinhub/atm/total}
     */
    @GET
    @Path("/atm/total")
    public Map<String, Object> getAtmTotal() {
        Map<String, Object> body = new LinkedHashMap<>();

        IExtensionContext ctx = RYOExtension.getExtensionContext();
        if (ctx == null) {
            body.put("ok", false);
            body.put("error", "extension_context_unavailable");
            return body;
        }

        try {
            int total = listCoinhubActiveAtmsWithSerial(ctx).size();
            body.put("ok", true);
            body.put("total", total);
        } catch (Throwable e) {
            log.error("atm/total endpoint error", e);
            body.put("ok", false);
            body.put("error", "unexpected");
            body.put("detail", e.getMessage());
        }
        return body;
    }

    /**
     * Returns cassette + acceptor cashbox summaries and {@code slot_summary} ({@code C1}–{@code C4}) for active,
     * non-deleted terminals, plus {@code company} (organization name) and {@code printer_status}
     * (from terminal hardware error flags).
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

        List<ITerminal> terminals = listCoinhubActiveAtmsWithSerial(ctx);
        Map<Long, String> organizationIdToName = buildOrganizationIdToNameMap(ctx);
        List<Map<String, Object>> out = new ArrayList<>();

        for (ITerminal t : terminals) {
            String sn = t.getSerialNumber() != null ? t.getSerialNumber().trim() : "";

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serial_number", sn);
            row.put("terminal_name", t.getName());
            row.put("terminal_active", t.isActive());
            row.put("terminal_locked", t.isLocked());
            row.put("terminal_deleted", t.isDeleted());
            row.put("company", companyNameForTerminal(t, organizationIdToName));
            row.put("printer_status", printerStatusFromErrors(t.getErrors()));

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
                row.put("slot_summary", buildSlotSummaries(cassettes, acceptorCashbox, fallbackDenoms));
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

    /**
     * Returns terminal list with common operational details for active, non-deleted terminals only.
     *
     * <p>Example:</p>
     * {@code GET /extensions/coinhub/terminal/all/details}
     */
    @GET
    @Path("/terminal/all/details")
    public Map<String, Object> getTerminalDetailsAll() {
        Map<String, Object> body = new LinkedHashMap<>();

        IExtensionContext ctx = RYOExtension.getExtensionContext();
        if (ctx == null) {
            body.put("ok", false);
            body.put("error", "extension_context_unavailable");
            return body;
        }

        List<ITerminal> terminals = listCoinhubActiveAtmsWithSerial(ctx);
        Map<Long, String> organizationIdToName = buildOrganizationIdToNameMap(ctx);
        List<Map<String, Object>> out = new ArrayList<>();

        for (ITerminal t : terminals) {
            String sn = t.getSerialNumber() != null ? t.getSerialNumber().trim() : "";

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serial_number", sn);
            row.put("terminal_name", t.getName());
            row.put("company", companyNameForTerminal(t, organizationIdToName));
            row.put("location", locationToJson(t.getLocation()));

            row.put("terminal_active", t.isActive());
            row.put("terminal_locked", t.isLocked());
            row.put("terminal_deleted", t.isDeleted());

            row.put("connected_at", dateToEpochMillis(t.getConnectedAt()));
            row.put("last_ping_at", dateToEpochMillis(t.getLastPingAt()));
            row.put("ping_duration_ms", t.getLastPingDuration());

            row.put("errors", t.getErrors());
            row.put("operational_mode", t.getOperationalMode());
            row.put("status", terminalStatusFromTerminal(t));
            row.put("printer_status", printerStatusFromErrors(t.getErrors()));

            // Terminal "created/added at" is not available via ITerminal in this public API.
            row.put("created_at", null);

            try {
                List<IBanknoteCounts> cashBoxes = safeList(ctx.getCashBoxes(sn));
                List<IBanknoteCounts> cassettes = filterByCashboxNamePrefix(cashBoxes, "dispenser_cassette_");
                if (cassettes.isEmpty()) {
                    cassettes = filterCashboxesByNameContains(cashBoxes, "cassette");
                }
                List<IBanknoteCounts> acceptorCashbox = filterAcceptorCashboxRows(cashBoxes);
                TreeSet<BigDecimal> fallbackDenoms = collectJpyDenominationsSorted(cassettes);
                row.put("cash_status", cashStatusFromCashBoxes(cashBoxes));
                row.put("slot_summary", buildSlotSummaries(cassettes, acceptorCashbox, fallbackDenoms));
                row.put("ok", true);
            } catch (IllegalArgumentException e) {
                row.put("ok", true);
                row.put("cash_status", null);
                row.put("slot_summary", null);
                row.put("cash_status_warning", "unavailable");
                row.put("cash_status_detail", e.getMessage());
            } catch (Throwable e) {
                log.error("terminal/all/details cash status failed: {}", sn, e);
                row.put("ok", true);
                row.put("cash_status", null);
                row.put("slot_summary", null);
                row.put("cash_status_warning", "unexpected");
                row.put("cash_status_detail", e.getMessage());
            }

            out.add(row);
        }

        out.sort(Comparator.comparing(r -> Objects.toString(r.get("serial_number"), "")));
        body.put("ok", true);
        body.put("count", out.size());
        body.put("terminals", out);
        return body;
    }

    /**
     * Returns transactions for a terminal identified by its serial number, optionally bounded by server time.
     *
     * <p>Each row contains: {@code terminal_time}, {@code remote_transaction_id}, {@code type}/{@code type_label},
     * {@code cash_amount} + {@code cash_currency}, {@code crypto_amount} + {@code crypto_currency},
     * {@code destination_address}, {@code identity_public_id}, {@code status}/{@code status_label}.</p>
     *
     * <p>Examples:</p>
     * <ul>
     *     <li>{@code GET /extensions/coinhub/transactions?serial_number=BT401469}</li>
     *     <li>{@code GET /extensions/coinhub/transactions?serial_number=BT401469&from=2025-01-01T00:00:00Z&to=2025-12-31T23:59:59Z}</li>
     *     <li>{@code GET /extensions/coinhub/transactions?serial_number=BT401469&previous_rid=RIDXXXX}</li>
     *     <li>{@code GET /extensions/coinhub/transactions?serial_number=BT401469&type=1&limit=50}</li>
     * </ul>
     *
     * <p>Query parameters (all but {@code serial_number} are optional):</p>
     * <ul>
     *     <li>{@code serial_number} (aliases: {@code terminal}, {@code serial}, {@code sn}) — required</li>
     *     <li>{@code from} (aliases: {@code server_time_from}, {@code start}, {@code since}) — ISO-8601 date/time or epoch millis</li>
     *     <li>{@code to} (aliases: {@code server_time_to}, {@code end}, {@code until}) — ISO-8601 date/time or epoch millis</li>
     *     <li>{@code previous_rid} (aliases: {@code previousRID}, {@code after_rid}) — only transactions NEWER than this remote ID</li>
     *     <li>{@code limit} (alias: {@code max}) — cap the number of returned rows after type filtering</li>
     *     <li>{@code type} — filter by transaction type
     *         (0=BUY_CRYPTO, 1=SELL_CRYPTO, 2=WITHDRAW_CASH, 3=CASHBACK, 4=ORDER_CRYPTO, 5=DEPOSIT_CASH)</li>
     * </ul>
     */
    @GET
    @Path("/transactions")
    public Map<String, Object> getTransactions(
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

        String fromRaw = firstParam(servletRequest, uriInfo, "from", "server_time_from", "start", "since");
        String toRaw = firstParam(servletRequest, uriInfo, "to", "server_time_to", "end", "until");
        String previousRid = firstParam(servletRequest, uriInfo, "previous_rid", "previousRID", "after_rid");
        String limitRaw = firstParam(servletRequest, uriInfo, "limit", "max");
        String typeRaw = firstParam(servletRequest, uriInfo, "type");

        Date fromDate;
        Date toDate;
        try {
            fromDate = parseDateParam(fromRaw);
            toDate = parseDateParam(toRaw);
        } catch (IllegalArgumentException e) {
            body.put("ok", false);
            body.put("error", "invalid_parameters");
            body.put("detail", e.getMessage());
            return body;
        }
        Integer typeFilter = parseIntegerParam(typeRaw);
        Integer limit = parseIntegerParam(limitRaw);

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

            List<ITransactionDetails> transactions = safeList(
                    ctx.findTransactions(sn, fromDate, toDate, previousRid, false));

            List<Map<String, Object>> rows = new ArrayList<>();
            for (ITransactionDetails tx : transactions) {
                if (tx == null) {
                    continue;
                }
                if (typeFilter != null && tx.getType() != typeFilter) {
                    continue;
                }
                rows.add(transactionToJson(tx));
                if (limit != null && limit > 0 && rows.size() >= limit) {
                    break;
                }
            }

            body.put("ok", true);
            body.put("serial_number", sn);
            body.put("count", rows.size());
            body.put("from", dateToEpochMillis(fromDate));
            body.put("to", dateToEpochMillis(toDate));
            body.put("previous_rid", previousRid);
            if (typeFilter != null) {
                body.put("type_filter", typeFilter);
                body.put("type_filter_label", transactionTypeLabel(typeFilter));
            }
            if (limit != null) {
                body.put("limit", limit);
            }
            body.put("transactions", rows);
        } catch (IllegalArgumentException e) {
            body.put("ok", false);
            body.put("error", "invalid_parameters");
            body.put("detail", e.getMessage());
        } catch (Throwable e) {
            log.error("transactions endpoint error", e);
            body.put("ok", false);
            body.put("error", "unexpected");
            body.put("detail", e.getMessage());
        }

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
     * Terminals included in list endpoints: non-null, active, not deleted, non-blank serial.
     */
    private static List<ITerminal> listCoinhubActiveAtmsWithSerial(IExtensionContext ctx) {
        List<ITerminal> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        for (ITerminal t : safeList(ctx.findAllTerminals())) {
            if (t == null || !t.isActive() || t.isDeleted()) {
                continue;
            }
            String sn = t.getSerialNumber() != null ? t.getSerialNumber().trim() : "";
            if (sn.isEmpty()) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private static Map<Long, String> buildOrganizationIdToNameMap(IExtensionContext ctx) {
        Map<Long, String> out = new HashMap<>();
        if (ctx == null) {
            return out;
        }
        for (IOrganization o : safeList(ctx.getOrganizations())) {
            if (o == null || o.getId() == null) {
                continue;
            }
            try {
                out.put(Long.parseLong(o.getId().trim()), o.getName());
            } catch (NumberFormatException e) {
                // ignore non-numeric organization ids
            }
        }
        return out;
    }

    private static String companyNameForTerminal(ITerminal t, Map<Long, String> organizationIdToName) {
        if (t == null || organizationIdToName == null) {
            return null;
        }
        long oid = t.getOrganizationId();
        if (oid == 0L) {
            return null;
        }
        return organizationIdToName.get(oid);
    }

    /**
     * Human-readable printer line for admin-style lists, derived from {@link ITerminal#getErrors()} hardware bits.
     */
    private static String printerStatusFromErrors(long errors) {
        if ((errors & ITerminal.ERROR_PRINTER_DISCONNECTED) != 0) {
            return "Printer disconnected";
        }
        if ((errors & ITerminal.WARNING_NO_PRINTER_PAPER) != 0) {
            return "Paper missing";
        }
        if ((errors & ITerminal.WARNING_PRINTER_PAPER_LOW) != 0) {
            return "Paper low";
        }
        return "Ok";
    }

    private static Long dateToEpochMillis(Date d) {
        return d != null ? d.getTime() : null;
    }

    private static Map<String, Object> locationToJson(ILocation loc) {
        if (loc == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", loc.getName());
        out.put("address", loc.getContactAddress());
        out.put("city", loc.getCity());
        out.put("country", loc.getCountry());
        out.put("country_iso2", loc.getCountryIso2());
        out.put("province", loc.getProvince());
        out.put("zip", loc.getZip());
        out.put("gps_lat", loc.getGpsLat());
        out.put("gps_lon", loc.getGpsLon());
        out.put("time_zone", loc.getTimeZone());
        out.put("public_id", loc.getPublicId());
        out.put("external_location_id", loc.getExternalLocationId());
        return out;
    }

    /**
     * Simple terminal status derived from last ping time and {@link ITerminal#getErrors()}.
     * <ul>
     *     <li>{@code NEVER_PINGED} - no {@link ITerminal#getLastPingAt()}</li>
     *     <li>{@code OFFLINE} - last ping older than 5 minutes</li>
     *     <li>{@code WARNING} - online, but errors bitmask non-zero</li>
     *     <li>{@code OK} - online and no errors</li>
     * </ul>
     */
    private static String terminalStatusFromTerminal(ITerminal t) {
        if (t == null) {
            return "UNKNOWN";
        }
        Date lastPingAt = t.getLastPingAt();
        if (lastPingAt == null) {
            return "NEVER_PINGED";
        }
        long now = System.currentTimeMillis();
        if (lastPingAt.getTime() + (5L * 60L * 1000L) < now) {
            return "OFFLINE";
        }
        return t.getErrors() == 0L ? "OK" : "WARNING";
    }

    /**
     * Cash status derived from cashbox counts.
     *
     * <p>Rules (defaults) aligned with UI legend:</p>
     * <ul>
     *     <li>{@code OK}</li>
     *     <li>{@code LOW_BALANCE}</li>
     *     <li>{@code SEVERE_LOW_BALANCE}</li>
     *     <li>{@code EMPTY}</li>
     * </ul>
     *
     * <p>Thresholds are based on total note counts per cassette (ATM technical parameters):</p>
     * <ul>
     *     <li>{@code EMPTY}: total notes == 0</li>
     *     <li>{@code SEVERE_LOW_BALANCE}: total notes &lt; 10 (half of near-end, conservative default)</li>
     *     <li>{@code LOW_BALANCE}: total notes &lt; 20 (near-end threshold)</li>
     * </ul>
     *
     * <p>Overall is the worst level across dispenser cassettes. If there is no cassette data, overall is
     * {@code UNKNOWN}. {@code slot_levels} and {@code cassette_levels} include {@code C4} for the acceptor cashbox
     * (full/near-full scale); {@code C1}–{@code C3} use the dispenser low-balance scale.</p>
     */
    private static String slotIdForDispenserIndex(int index) {
        return "C" + index;
    }

    private static String dispenserCashboxNameForIndex(int index) {
        switch (index) {
            case 1:
                return IBanknoteCounts.CN_DISPENSER_CASSETTE_1;
            case 2:
                return IBanknoteCounts.CN_DISPENSER_CASSETTE_2;
            case 3:
                return IBanknoteCounts.CN_DISPENSER_CASSETTE_3;
            default:
                throw new IllegalArgumentException("invalid dispenser slot index: " + index);
        }
    }

    private static String dispenserLevelFromNoteCount(int total) {
        if (total == 0) {
            return "EMPTY";
        }
        if (total < CASSETTE_SEVERE_LOW_THRESHOLD_NOTES) {
            return "SEVERE_LOW_BALANCE";
        }
        if (total < CASSETTE_NEAR_END_THRESHOLD_NOTES) {
            return "LOW_BALANCE";
        }
        return "OK";
    }

    private static String acceptorLevelFromNoteCount(int total, boolean hasAcceptorData) {
        if (!hasAcceptorData) {
            return "UNKNOWN";
        }
        if (total == 0) {
            return "EMPTY";
        }
        if (total >= CASHBOX_CAPACITY_NOTES) {
            return "FULL";
        }
        if (total >= CASHBOX_NEAR_FULL_THRESHOLD_NOTES) {
            return "NEAR_FULL";
        }
        return "OK";
    }

    private static String acceptorStatusLabel(String level) {
        if (level == null) {
            return null;
        }
        switch (level) {
            case "OK":
                return "OK";
            case "NEAR_FULL":
                return "Near Full";
            case "FULL":
                return "Full";
            case "EMPTY":
                return "Empty";
            case "UNKNOWN":
            default:
                return "Unknown";
        }
    }

    private static Map<String, Integer> aggregateNoteCountsByCashboxName(List<IBanknoteCounts> items) {
        Map<String, Integer> totals = new HashMap<>();
        for (IBanknoteCounts bc : safeList(items)) {
            if (bc == null || bc.getCashboxName() == null) {
                continue;
            }
            totals.merge(bc.getCashboxName(), bc.getCount(), Integer::sum);
        }
        return totals;
    }

    private static Map<String, BigDecimal> aggregateJpyAmountsByCashboxName(List<IBanknoteCounts> items) {
        Map<String, BigDecimal> amounts = new HashMap<>();
        for (IBanknoteCounts bc : safeList(items)) {
            if (bc == null || bc.getCashboxName() == null) {
                continue;
            }
            String currency = bc.getCurrency();
            boolean jpy = currency == null || "JPY".equalsIgnoreCase(currency);
            BigDecimal denom = bc.getDenomination();
            if (!jpy || denom == null) {
                continue;
            }
            BigDecimal lineAmount = denom.stripTrailingZeros().multiply(BigDecimal.valueOf(bc.getCount()));
            amounts.merge(bc.getCashboxName(), lineAmount, BigDecimal::add);
        }
        return amounts;
    }

    /**
     * Unified C1–C3 dispenser slots plus C4 acceptor cashbox for ATM list/detail endpoints.
     */
    private static List<Map<String, Object>> buildSlotSummaries(
            List<IBanknoteCounts> cassettes,
            List<IBanknoteCounts> acceptor,
            TreeSet<BigDecimal> fallbackDenoms) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> cassetteTotals = aggregateNoteCountsByCashboxName(cassettes);
        Map<String, BigDecimal> cassetteAmountsJpy = aggregateJpyAmountsByCashboxName(cassettes);

        for (int i = 1; i <= DISPENSER_UI_SLOT_COUNT; i++) {
            String slot = slotIdForDispenserIndex(i);
            String cashboxName = dispenserCashboxNameForIndex(i);
            boolean hasData = cassetteTotals.containsKey(cashboxName);
            int total = cassetteTotals.getOrDefault(cashboxName, 0);
            String status = hasData ? dispenserLevelFromNoteCount(total) : "UNKNOWN";

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot);
            row.put("cashbox_name", cashboxName);
            row.put("cashbox_type", "dispenser");
            row.put("status", status);
            row.put("status_label", cashStatusLabel(status));
            row.put("total_remaining_count", hasData ? total : null);
            row.put("total_remaining_amount", hasData ? amountToJson(cassetteAmountsJpy.getOrDefault(cashboxName, BigDecimal.ZERO)) : null);
            out.add(row);
        }

        List<Map<String, Object>> acceptorSummaryRows = summarizeAcceptorCashboxJpyFlat(acceptor, fallbackDenoms);
        Map<String, Object> acceptorSummary = acceptorSummaryRows.isEmpty()
                ? emptyAcceptorCashboxSummary(fallbackDenoms)
                : acceptorSummaryRows.get(0);
        int acceptorTotal = acceptorSummary.get("total_count") instanceof Number
                ? ((Number) acceptorSummary.get("total_count")).intValue()
                : 0;
        boolean hasAcceptorData = acceptor != null && !acceptor.isEmpty();
        String acceptorStatus = acceptorLevelFromNoteCount(acceptorTotal, hasAcceptorData);

        Map<String, Object> c4 = new LinkedHashMap<>();
        c4.put("slot", SLOT_C4);
        c4.put("cashbox_name", IBanknoteCounts.CN_ACCEPTOR_CASHBOX);
        c4.put("cashbox_type", "acceptor");
        c4.put("status", acceptorStatus);
        c4.put("status_label", acceptorStatusLabel(acceptorStatus));
        c4.put("total_remaining_count", hasAcceptorData ? acceptorTotal : null);
        c4.put("total_remaining_amount", acceptorSummary.get("total_value"));
        for (int slot = 1; slot <= 3; slot++) {
            String denomKey = "banknote_" + slot + "_denomication";
            String countKey = "banknote_" + slot + "_count";
            String valueKey = "banknote_" + slot + "_value";
            c4.put(denomKey, acceptorSummary.get(denomKey));
            c4.put(countKey, acceptorSummary.get(countKey));
            c4.put(valueKey, acceptorSummary.get(valueKey));
        }
        out.add(c4);
        return out;
    }

    private static Map<String, Object> cashStatusFromCashBoxes(List<IBanknoteCounts> cashBoxes) {
        final int cashboxRejectFullThresholdNotes = 30;

        // Prefer canonical CAS naming, but fall back to any cashbox containing "cassette"
        // (some deployments expose different cashbox name prefixes).
        List<IBanknoteCounts> cassettes = filterByCashboxNamePrefix(cashBoxes, "dispenser_cassette_");
        if (cassettes.isEmpty()) {
            cassettes = filterCashboxesByNameContains(cashBoxes, "cassette");
        }
        List<IBanknoteCounts> acceptor = filterAcceptorCashboxRows(cashBoxes);

        Map<String, Integer> cassetteTotals = new HashMap<>();
        Map<String, BigDecimal> cassetteDenoms = new HashMap<>();
        Map<String, BigDecimal> cassetteAmountRemainingJpy = new HashMap<>();
        for (IBanknoteCounts bc : safeList(cassettes)) {
            if (bc == null || bc.getCashboxName() == null) {
                continue;
            }
            cassetteTotals.merge(bc.getCashboxName(), bc.getCount(), Integer::sum);
            BigDecimal denom = bc.getDenomination();
            if (denom != null && !cassetteDenoms.containsKey(bc.getCashboxName())) {
                cassetteDenoms.put(bc.getCashboxName(), denom.stripTrailingZeros());
            }
            // Remaining JPY amount for the cassette (only for JPY rows; null currency treated as JPY for parity)
            String currency = bc.getCurrency();
            boolean jpy = currency == null || "JPY".equalsIgnoreCase(currency);
            if (jpy && denom != null) {
                BigDecimal d = denom.stripTrailingZeros();
                BigDecimal lineAmount = d.multiply(BigDecimal.valueOf(bc.getCount()));
                cassetteAmountRemainingJpy.merge(bc.getCashboxName(), lineAmount, BigDecimal::add);
            }
        }

        int emptyCassettes = 0;
        int severeLowCassettes = 0;
        int lowCassettes = 0;
        for (Map.Entry<String, Integer> e : cassetteTotals.entrySet()) {
            int total = e.getValue() != null ? e.getValue() : 0;
            if (total == 0) {
                emptyCassettes++;
            } else if (total < CASSETTE_SEVERE_LOW_THRESHOLD_NOTES) {
                severeLowCassettes++;
            } else if (total < CASSETTE_NEAR_END_THRESHOLD_NOTES) {
                lowCassettes++;
            }
        }

        // Per-cassette level (for C1/C2/C3 UI) plus C4 acceptor cashbox
        Map<String, String> cassetteLevels = new LinkedHashMap<>();
        Map<String, String> cassetteLevelLabels = new LinkedHashMap<>();
        TreeSet<String> cassetteNamesSorted = new TreeSet<>(cassetteTotals.keySet());
        for (String name : cassetteNamesSorted) {
            int total = cassetteTotals.getOrDefault(name, 0);
            String level = dispenserLevelFromNoteCount(total);
            cassetteLevels.put(name, level);
            cassetteLevelLabels.put(name, cashStatusLabel(level));
        }

        String cassettesStatus;
        if (cassetteTotals.isEmpty()) {
            cassettesStatus = "UNKNOWN";
        } else if (emptyCassettes > 0) {
            cassettesStatus = "EMPTY";
        } else if (severeLowCassettes > 0) {
            cassettesStatus = "SEVERE_LOW_BALANCE";
        } else if (lowCassettes > 0) {
            cassettesStatus = "LOW_BALANCE";
        } else {
            cassettesStatus = "OK";
        }

        int acceptorTotalCount = 0;
        for (IBanknoteCounts bc : safeList(acceptor)) {
            if (bc == null) {
                continue;
            }
            acceptorTotalCount += bc.getCount();
        }
        String cashboxStatus = acceptorLevelFromNoteCount(acceptorTotalCount, !acceptor.isEmpty());

        cassetteLevels.put(SLOT_C4, cashboxStatus);
        cassetteLevelLabels.put(SLOT_C4, acceptorStatusLabel(cashboxStatus));

        Map<String, String> slotLevels = new LinkedHashMap<>();
        Map<String, String> slotLevelLabels = new LinkedHashMap<>();
        Map<String, Integer> slotNoteCounts = new LinkedHashMap<>();
        for (int i = 1; i <= DISPENSER_UI_SLOT_COUNT; i++) {
            String slot = slotIdForDispenserIndex(i);
            String cashboxName = dispenserCashboxNameForIndex(i);
            boolean hasData = cassetteTotals.containsKey(cashboxName);
            String level = hasData ? dispenserLevelFromNoteCount(cassetteTotals.getOrDefault(cashboxName, 0)) : "UNKNOWN";
            slotLevels.put(slot, level);
            slotLevelLabels.put(slot, hasData ? cashStatusLabel(level) : cashStatusLabel("UNKNOWN"));
            slotNoteCounts.put(slot, hasData ? cassetteTotals.get(cashboxName) : null);
        }
        slotLevels.put(SLOT_C4, cashboxStatus);
        slotLevelLabels.put(SLOT_C4, acceptorStatusLabel(cashboxStatus));
        slotNoteCounts.put(SLOT_C4, acceptor.isEmpty() ? null : acceptorTotalCount);

        String overall = cassettesStatus;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overall", overall);
        out.put("overall_label", cashStatusLabel(overall));
        out.put("cassettes", cassettesStatus);
        out.put("cashbox", cashboxStatus);
        out.put("cassette_capacity_notes", CASSETTE_CAPACITY_NOTES);
        out.put("cassette_near_end_threshold_notes", CASSETTE_NEAR_END_THRESHOLD_NOTES);
        out.put("cassette_severe_low_threshold_notes", CASSETTE_SEVERE_LOW_THRESHOLD_NOTES);
        out.put("cashbox_capacity_notes", CASHBOX_CAPACITY_NOTES);
        out.put("cashbox_near_full_threshold_notes", CASHBOX_NEAR_FULL_THRESHOLD_NOTES);
        out.put("cashbox_reject_full_threshold_notes", cashboxRejectFullThresholdNotes);
        out.put("cassettes_empty_count", emptyCassettes);
        out.put("cassettes_severe_low_count", severeLowCassettes);
        out.put("cassettes_low_count", lowCassettes);
        out.put("cassette_levels", cassetteLevels);
        out.put("cassette_level_labels", cassetteLevelLabels);
        out.put("slot_levels", slotLevels);
        out.put("slot_level_labels", slotLevelLabels);
        out.put("slot_note_counts", slotNoteCounts);
        out.put("cassette_note_counts", cassetteTotals);
        out.put("cassette_denominations", cassetteDenoms);
        out.put("cassette_remaining_amount_jpy", cassetteAmountRemainingJpy);
        out.put("cashbox_rows_count", cashBoxes != null ? cashBoxes.size() : 0);
        out.put("cashbox_names_seen", listCashboxNames(cashBoxes));
        Map<String, Long> cassetteMaxAmountJpy = new HashMap<>();
        for (Map.Entry<String, BigDecimal> e : cassetteDenoms.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            BigDecimal max = e.getValue().multiply(BigDecimal.valueOf(CASSETTE_CAPACITY_NOTES));
            cassetteMaxAmountJpy.put(e.getKey(), (Long) amountToJson(max));
        }
        out.put("cassette_max_amount_jpy", cassetteMaxAmountJpy);
        out.put("cashbox_total_count", acceptor.isEmpty() ? null : acceptorTotalCount);
        return out;
    }

    private static List<IBanknoteCounts> filterCashboxesByNameContains(List<IBanknoteCounts> items, String token) {
        if (items == null || items.isEmpty() || token == null || token.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        List<IBanknoteCounts> out = new ArrayList<>();
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            String name = bc.getCashboxName();
            if (name == null) {
                continue;
            }
            if (name.toLowerCase(Locale.ROOT).contains(t)) {
                out.add(bc);
            }
        }
        return out;
    }

    private static List<String> listCashboxNames(List<IBanknoteCounts> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (IBanknoteCounts bc : items) {
            if (bc == null) {
                continue;
            }
            String name = bc.getCashboxName();
            if (name != null && !name.trim().isEmpty()) {
                names.add(name.trim());
            }
        }
        return new ArrayList<>(names);
    }

    private static String cashStatusLabel(String level) {
        if (level == null) {
            return null;
        }
        switch (level) {
            case "OK":
                return "OK";
            case "LOW_BALANCE":
                return "Low Balance";
            case "SEVERE_LOW_BALANCE":
                return "Severe low Balance";
            case "EMPTY":
                return "Empty";
            case "UNKNOWN":
            default:
                return "Unknown";
        }
    }

    /**
     * Slim JSON view of a single transaction: terminal time, remote id, type, cash + currency,
     * crypto + currency, destination address, identity, status. {@code _label} fields are derived from
     * the transaction type to make the response human-readable without an extra lookup.
     */
    private static Map<String, Object> transactionToJson(ITransactionDetails tx) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("terminal_time", dateToEpochMillis(tx.getTerminalTime()));
        row.put("remote_transaction_id", tx.getRemoteTransactionId());
        row.put("type", tx.getType());
        row.put("type_label", transactionTypeLabel(tx.getType()));
        row.put("cash_amount", tx.getCashAmount());
        row.put("cash_currency", tx.getCashCurrency());
        row.put("crypto_amount", tx.getCryptoAmount());
        row.put("crypto_currency", tx.getCryptoCurrency());
        row.put("destination_address", tx.getCryptoAddress());
        row.put("identity_public_id", tx.getIdentityPublicId());
        row.put("status", tx.getStatus());
        row.put("status_label", transactionStatusLabel(tx.getType(), tx.getStatus()));
        return row;
    }

    /**
     * Accepts epoch millis (e.g. {@code 1735689600000}), ISO-8601 instants ({@code 2025-01-01T00:00:00Z}),
     * offset/zoned datetimes, naive datetimes (treated as UTC), and bare dates (start of day UTC).
     */
    private static Date parseDateParam(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();
        try {
            return new Date(Long.parseLong(s));
        } catch (NumberFormatException ignored) {
            // not a numeric timestamp; try ISO-8601 forms below
        }
        try {
            return Date.from(Instant.parse(s));
        } catch (DateTimeParseException ignored) {
            // try other formats below
        }
        try {
            return Date.from(ZonedDateTime.parse(s).toInstant());
        } catch (DateTimeParseException ignored) {
            // try other formats below
        }
        try {
            return Date.from(LocalDateTime.parse(s).atZone(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException ignored) {
            // try date-only below
        }
        try {
            return Date.from(LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        throw new IllegalArgumentException(
                "Invalid date/time value: \"" + raw + "\" (expected ISO-8601 or epoch milliseconds)");
    }

    private static Integer parseIntegerParam(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String transactionTypeLabel(int type) {
        switch (type) {
            case ITransactionDetails.TYPE_BUY_CRYPTO:
                return "BUY_CRYPTO";
            case ITransactionDetails.TYPE_SELL_CRYPTO:
                return "SELL_CRYPTO";
            case ITransactionDetails.TYPE_WITHDRAW_CASH:
                return "WITHDRAW_CASH";
            case ITransactionDetails.TYPE_CASHBACK:
                return "CASHBACK";
            case ITransactionDetails.TYPE_ORDER_CRYPTO:
                return "ORDER_CRYPTO";
            case ITransactionDetails.TYPE_DEPOSIT_CASH:
                return "DEPOSIT_CASH";
            default:
                return "UNKNOWN";
        }
    }

    private static String transactionStatusLabel(int type, int status) {
        switch (type) {
            case ITransactionDetails.TYPE_BUY_CRYPTO:
                switch (status) {
                    case ITransactionDetails.STATUS_BUY_IN_PROGRESS:
                        return "IN_PROGRESS";
                    case ITransactionDetails.STATUS_BUY_COMPLETED:
                        return "COMPLETED";
                    case ITransactionDetails.STATUS_BUY_ERROR:
                        return "ERROR";
                    case ITransactionDetails.STATUS_BUY_CANCELED:
                        return "CANCELED";
                    default:
                        return "UNKNOWN";
                }
            case ITransactionDetails.TYPE_SELL_CRYPTO:
                switch (status) {
                    case ITransactionDetails.STATUS_SELL_PAYMENT_REQUESTED:
                        return "PAYMENT_REQUESTED";
                    case ITransactionDetails.STATUS_SELL_PAYMENT_ARRIVING:
                        return "PAYMENT_ARRIVING";
                    case ITransactionDetails.STATUS_SELL_PAYMENT_ARRIVED:
                        return "PAYMENT_ARRIVED";
                    case ITransactionDetails.STATUS_SELL_ERROR:
                        return "ERROR";
                    default:
                        return "UNKNOWN";
                }
            case ITransactionDetails.TYPE_WITHDRAW_CASH:
                switch (status) {
                    case ITransactionDetails.STATUS_WITHDRAW_IN_PROGRESS:
                        return "IN_PROGRESS";
                    case ITransactionDetails.STATUS_WITHDRAW_COMPLETED:
                        return "COMPLETED";
                    case ITransactionDetails.STATUS_WITHDRAW_ERROR:
                        return "ERROR";
                    default:
                        return "UNKNOWN";
                }
            case ITransactionDetails.TYPE_CASHBACK:
                switch (status) {
                    case ITransactionDetails.STATUS_CASHBACK_COMPLETED:
                        return "COMPLETED";
                    case ITransactionDetails.STATUS_CASHBACK_ERROR:
                        return "ERROR";
                    default:
                        return "UNKNOWN";
                }
            case ITransactionDetails.TYPE_ORDER_CRYPTO:
                switch (status) {
                    case ITransactionDetails.STATUS_ORDER_IN_PROGRESS:
                        return "IN_PROGRESS";
                    case ITransactionDetails.STATUS_ORDER_CASH_DEPOSITED:
                        return "CASH_DEPOSITED";
                    case ITransactionDetails.STATUS_ORDER_COMPLETED:
                        return "COMPLETED";
                    case ITransactionDetails.STATUS_ORDER_ERROR:
                        return "ERROR";
                    default:
                        return "UNKNOWN";
                }
            case ITransactionDetails.TYPE_DEPOSIT_CASH:
                switch (status) {
                    case ITransactionDetails.STATUS_DEPOSIT_COMPLETED:
                        return "COMPLETED";
                    case ITransactionDetails.STATUS_DEPOSIT_ERROR:
                        return "ERROR";
                    default:
                        return "UNKNOWN";
                }
            default:
                return "UNKNOWN";
        }
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
                    ctx,
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
            return applyCashbackEmailTemplate(ctx, template.content, terminalSerial, cashback, qrPayload);
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
            return applyCashbackEmailTemplateHtml(
                    ctx, template.content, terminalSerial, cashback, qrPayload, logoUrl);
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
            IExtensionContext ctx,
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
                .replace("{address}", resolveTerminalLocationAddress(ctx, terminalSerial))
                .replace("{validity_minutes}", Long.toString(validity))
                .replace("{transaction_id}", Objects.toString(cashback.getRemoteTransactionId(), ""))
                .replace("{time}", time)
                .replace("{logo}", "CoinHub")
                .replace("{qr_payload}", qrPayload);
    }

    private static String applyCashbackEmailTemplateHtml(
            IExtensionContext ctx,
            String template,
            String terminalSerial,
            ITransactionCashbackInfo cashback,
            String qrPayload,
            String logoUrl) {
        String logoHtml = buildLogoHtml(logoUrl);
        return applyCashbackEmailTemplate(ctx, template, terminalSerial, cashback, qrPayload)
                .replace("{logo_url}", logoUrl != null ? logoUrl : "")
                .replace("{logo_html}", logoHtml);
    }

    private static String resolveTerminalLocationAddress(IExtensionContext ctx, String terminalSerial) {
        try {
            ITerminal terminal = ctx.findTerminalBySerialNumber(terminalSerial);
            if (terminal == null) {
                return "";
            }
            ILocation location = terminal.getLocation();
            if (location == null) {
                return "";
            }
            return Objects.toString(location.getContactAddress(), "").trim();
        } catch (Exception e) {
            log.warn("cashback email: could not resolve terminal address for {}", terminalSerial, e);
            return "";
        }
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
