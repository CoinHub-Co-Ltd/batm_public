/*************************************************************************************
 * Copyright (C) 2015-2016 GENERAL BYTES s.r.o. All rights reserved.
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
 * GENERAL BYTES s.r.o
 * Web      :  http://www.generalbytes.com
 *
 ************************************************************************************/
package com.generalbytes.batm.server.extensions.extra.watchlists.ch;

import com.generalbytes.batm.server.extensions.IExtensionContext;
import com.generalbytes.batm.server.extensions.extra.watchlists.ch.dto.request.WatchlistSearchRequest;
import com.generalbytes.batm.server.extensions.extra.watchlists.ch.dto.response.WatchlistSearchResponse;
import com.generalbytes.batm.server.extensions.watchlist.IWatchList;
import com.generalbytes.batm.server.extensions.watchlist.WatchListMatch;
import com.generalbytes.batm.server.extensions.watchlist.WatchListQuery;
import com.generalbytes.batm.server.extensions.watchlist.WatchListResult;
import com.generalbytes.batm.server.extensions.IIdentity;
import com.generalbytes.batm.server.extensions.IIdentityPiece;

import com.generalbytes.batm.server.extensions.customfields.CustomField;
import com.generalbytes.batm.server.extensions.customfields.CustomFieldDefinition;
import com.generalbytes.batm.server.extensions.customfields.value.ChoiceCustomFieldValue;
import com.generalbytes.batm.server.extensions.customfields.value.CustomFieldValue;
import com.generalbytes.batm.server.extensions.customfields.value.StringCustomFieldValue;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import si.mazi.rescu.RestProxyFactory;

public class CoinHubWatchList implements IWatchList {

    private static final Logger log = LoggerFactory.getLogger(CoinHubWatchList.class);

    private final ICoinHubWatchListAPI api;
    private final String apiKey;
    private IExtensionContext ctx;

    private static final Set<String> checkedDobKeys = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> deniedByIdentity = new ConcurrentHashMap<>();

    public CoinHubWatchList(String apiKey, String apiEndpoint) {
        this.apiKey = apiKey;
        this.api = RestProxyFactory.createProxy(ICoinHubWatchListAPI.class, apiEndpoint);
    }

    public void setExtensionContext(IExtensionContext ctx) {
        this.ctx = ctx;
    }

    public String getDeniedMessage(String identityPublicId) {
        if (identityPublicId == null) {
            return null;
        }
        return deniedByIdentity.get(identityPublicId);
    }

    @Override
    public void init(String downloadDirectory) {
        //Nothing to do
    }

    @Override
    public String getName() {
        return "Coinhub - Watch List";
    }

    @Override
    public String getId() {
        return "coinhub";
    }

    @Override
    public String toString() {
        return getName();
    }

    @Override
    public String getDescription() {
        return "Live search via CoinHub API (Refinitiv + custom)";
    }

    @Override
    public int recommendedRefreshPeriodInMins() {
        return Integer.MAX_VALUE; //Never
    }

    @Override
    public int refresh() {
        return LIST_NOT_CHANGED;
    }

    @Override
    public WatchListResult search(WatchListQuery query) {
        return doSearch(query, false);
    }

    public WatchListResult searchForTransactionGate(WatchListQuery query) {
        return doSearch(query, true);
    }

    private WatchListResult doSearch(WatchListQuery query, boolean forceIfUnchecked) {
        try {
            String identityId = query.getIdentityPublicId();
            String priorDeny = getDeniedMessage(identityId);
            if (priorDeny != null) {
                log.warn("[CH-WatchList] identity {} already denied by Security1 — blocking", identityId);
                return deny(priorDeny);
            }

            WatchlistSearchRequest request = mapRequest(query);

            if (forceIfUnchecked) {
                String missing = getMissingRequiredFields(request);
                if (missing != null) {
                    log.info("[CH-WatchList] transaction gate skip identity={} missing={}", identityId, missing);
                    return new WatchListResult(Collections.emptyList());
                }
                return callInitialSecurity(request, "transaction-prep");
            }

            log.info("[CH-WatchList] name-step skip identity={} — wait for transaction prep", identityId);
            return new WatchListResult(WatchListResult.RESULT_TYPE_WATCHLIST_NOT_READY);
        } catch (Exception e) {
            return handleSearchException(query, e);
        }
    }

    private WatchListResult callInitialSecurity(WatchlistSearchRequest request, String reason) {
        String identityId = request.identityPublicId;
        String dob = request.birthOfDate;
        String checkKey = identityId + "|" + dob;

        String priorDeny = getDeniedMessage(identityId);
        if (priorDeny != null) {
            log.warn("[CH-WatchList] identity {} already denied — blocking", identityId);
            return deny(priorDeny);
        }

        if (checkedDobKeys.contains(checkKey)) {
            log.info("[CH-WatchList] already checked identity={} dob={} — pass", identityId, dob);
            return new WatchListResult(Collections.emptyList());
        }

        log.info("[CH-WatchList] Calling initial-security identity={} firstName={} lastName={} dob={} reason={}",
            identityId, request.firstName, request.lastName, dob, reason);

        WatchlistSearchResponse response = api.searchWatchlist(apiKey, request);
        if (response == null || response.grade == null) {
            return deny(CoinHubAtmErrors.S1_UNAVAILABLE);
        }
        log.info("[CH-WatchList] initial-security result identity={} grade={}",
            identityId, response.grade);
        checkedDobKeys.add(checkKey);
        WatchListResult result = mapResult(response.grade);
        if (Boolean.TRUE.equals(response.grade)) {
            String denyMessage = CoinHubAtmErrors.S1_BLACKLISTED;
            deniedByIdentity.put(identityId, denyMessage);
            log.warn("[CH-WatchList] grade=true — deny cached for identity={}", identityId);
            // prohibitIdentity(identityId, "Security 1: matched Coinhub initial-security check.");
        }
        return result;
    }

    private WatchListResult handleSearchException(WatchListQuery query, Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String detail = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        if (root instanceof si.mazi.rescu.HttpStatusIOException) {
            si.mazi.rescu.HttpStatusIOException http = (si.mazi.rescu.HttpStatusIOException) root;
            detail = "HTTP " + http.getHttpStatusCode() + " body=" + http.getHttpBody();
        }
        log.error("CoinHub watchlist search failed identity={} detail={}",
            query.getIdentityPublicId(), detail, e);
        return deny(CoinHubAtmErrors.s1UnavailableDetail(detail));
    }

    private WatchListResult deny(String reason) {
        log.warn("CoinHub initial-security deny: {}", reason);
        return new WatchListResult(Collections.singletonList(
            new WatchListMatch(100, reason, getId(), getName(), null)
        ));
    }

    private WatchlistSearchRequest mapRequest(WatchListQuery query) {
        WatchlistSearchRequest request = new WatchlistSearchRequest();
        request.firstName = query.getFirstName();
        request.lastName = query.getLastName();
        request.name = query.getName();
        request.identityPublicId = query.getIdentityPublicId();
        fillContact(request, query.getIdentityPublicId());
        getCustomFields(request, query.getIdentityPublicId());
        return request;
    }

    private static String getMissingRequiredFields(WatchlistSearchRequest request) {
        List<String> missing = new ArrayList<>();
        if (isBlank(request.firstName)) {
            missing.add("firstName");
        }
        if (isBlank(request.lastName)) {
            missing.add("lastName");
        }
        if (isBlank(request.birthOfDate)) {
            missing.add("birthOfDate");
        }
        return missing.isEmpty() ? null : String.join(", ", missing);
    }

    private void getCustomFields(WatchlistSearchRequest request, String identityPublicId) {
        if (ctx == null || identityPublicId == null) {
            return;
        }

        try {
            for (CustomField field: ctx.getIdentityCustomFields(identityPublicId)) {
                String name = field.getDefinition().getName();
                if (name == null) {
                    continue;
                }
               
                log.info("[CH-WatchList] identity {} custom field name='{}' label='{}' value='{}'",
                    identityPublicId,
                    field.getDefinition().getName(),
                    field.getDefinition().getLabel(),
                    getFieldValue(field));

                switch (name) {
                    case "Address":
                        request.address = getFieldValue(field);
                        break;
                    case "Contact Number":
                        request.phone = getFieldValue(field);
                        break;
                    case "Occupation":
                    case "occupation":
                        request.occupation = getFieldValue(field);
                        break;
                    case "Purpose":
                        request.purpose = getFieldValue(field);
                        break;
                    default:
                        break;
                }
            }
        }  catch (RuntimeException e) {
            log.warn("Could not read custom fields for identity {}", identityPublicId, e);
        }
        
    }

    private static String getFieldValue(CustomField field) {
        CustomFieldValue value = field.getValue();
        if (value instanceof StringCustomFieldValue) {
            return ((StringCustomFieldValue) value).getStringValue();
        }
        if (value instanceof ChoiceCustomFieldValue) {
            if (field.getDefinition().getElements() == null) {
                return null;
            }
            long choiceId = ((ChoiceCustomFieldValue) value).getChoiceId();
            for (CustomFieldDefinition.Element element : field.getDefinition().getElements()) {
                if (element.getId() == choiceId) {
                    return element.getValue();
                }
            }
        }
        return null;
    }

    private void fillContact(WatchlistSearchRequest request, String identityPublicId) {
        if (ctx == null || identityPublicId == null) {
            return;
        }

        IIdentity identity = ctx.findIdentityByIdentityId(identityPublicId);
        if (identity == null || identity.getIdentityPieces() == null) {
            return;
        }

        for (IIdentityPiece piece : identity.getIdentityPieces()) {
            if (piece.getPieceType() == IIdentityPiece.TYPE_EMAIL
                    && piece.getEmailAddress() != null) {
                request.email = piece.getEmailAddress();
            }
            if (piece.getPieceType() == IIdentityPiece.TYPE_CELLPHONE
                    && piece.getPhoneNumber() != null) {
                request.phone = piece.getPhoneNumber();
            }
            if (piece.getPieceType() == IIdentityPiece.TYPE_PERSONAL_INFORMATION) {
                if (isBlank(request.firstName) && piece.getFirstname() != null) {
                    request.firstName = piece.getFirstname();
                }
                if (isBlank(request.lastName) && piece.getLastname() != null) {
                    request.lastName = piece.getLastname();
                }
                if (request.birthOfDate == null && piece.getDateOfBirth() != null) {
                    request.birthOfDate = formatDob(piece.getDateOfBirth());
                }
                if (request.occupation == null && piece.getOccupation() != null) {
                    request.occupation = piece.getOccupation();
                }
            }
            // Fingerprint is intentionally omitted: Security1 matches on name/DOB only.
            // Address ↔ fingerprint is enforced separately in Security2.
        }
    }

    private static String formatDob(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        format.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        return format.format(date);
    }

    private WatchListResult mapResult(Boolean grade) {
         if (!grade) {
            return new WatchListResult(Collections.emptyList());
        }
        WatchListMatch match = new WatchListMatch(
            100,
            CoinHubAtmErrors.S1_BLACKLISTED,
            getId(),
            getName(),
            null);
        return new WatchListResult(Collections.singletonList(match));
    }

    private void prohibitIdentity(String identityPublicId, String note) {
        if (ctx == null || identityPublicId == null) {
            return;
        }
        try {
            IIdentity identity = ctx.findIdentityByIdentityId(identityPublicId);
            if (identity == null || identity.getState() == IIdentity.STATE_PROHIBITED) {
                return;
            }
            String newNote = note;
            if (identity.getNote() != null && !identity.getNote().trim().isEmpty()) {
                newNote = identity.getNote() + " | " + note;
            }
            ctx.updateIdentity(
                identity.getPublicId(),
                identity.getExternalId(),
                IIdentity.STATE_PROHIBITED,
                identity.getType(),
                identity.getCreated(),
                identity.getRegistered(),
                identity.getVipBuyDiscount(),
                identity.getVipSellDiscount(),
                newNote,
                identity.getLimitCashPerTransaction(),
                identity.getLimitCashPerHour(),
                identity.getLimitCashPerDay(),
                identity.getLimitCashPerWeek(),
                identity.getLimitCashPerMonth(),
                identity.getLimitCashPer3Months(),
                identity.getLimitCashPer12Months(),
                identity.getLimitCashPerCalendarQuarter(),
                identity.getLimitCashPerCalendarYear(),
                identity.getLimitCashTotalIdentity(),
                identity.getConfigurationCashCurrency());
            log.warn("[CH-WatchList] identity {} set to PROHIBITED ({})", identityPublicId, note);
        } catch (Exception e) {
            log.error("[CH-WatchList] failed to prohibit identity {}", identityPublicId, e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
