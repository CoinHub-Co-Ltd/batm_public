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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import si.mazi.rescu.RestProxyFactory;

import java.util.List;
import java.util.stream.Collectors;

public class CoinHubWatchList implements IWatchList {

    private static final Logger log = LoggerFactory.getLogger(CoinHubWatchList.class);

    private final ICoinHubWatchListAPI api;
    private final String apiKey;
    private IExtensionContext ctx;

    public CoinHubWatchList(String apiKey, String apiEndpoint) {
        this.apiKey = apiKey;
        this.api = RestProxyFactory.createProxy(ICoinHubWatchListAPI.class, apiEndpoint);
    }

    public void setExtensionContext(IExtensionContext ctx) {
        this.ctx = ctx;
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
        try {
            WatchlistSearchRequest request = mapRequest(query);
            WatchlistSearchResponse response = api.searchWatchlist(apiKey, request);
            if (response == null || response.matches == null) {
                return new WatchListResult(WatchListResult.RESULT_TYPE_WATCHLIST_NOT_READY);
            }
            return mapResult(response.matches);
        } catch (Exception e) {
            log.error("CoinHub watchlist search failed", e);
            return new WatchListResult(WatchListResult.RESULT_TYPE_WATCHLIST_NOT_READY);
        }
    }

    private WatchlistSearchRequest mapRequest(WatchListQuery query) {
        WatchlistSearchRequest request = new WatchlistSearchRequest();
        request.type = query.getType();
        request.firstName = query.getFirstName();
        request.lastName = query.getLastName();
        request.name = query.getName();
        request.identityPublicId = query.getIdentityPublicId();
        fillContact(request, query.getIdentityPublicId());
        return request;
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
        }
    }

    private WatchListResult mapResult(List<WatchlistSearchResponse.Match> result) {
        List<WatchListMatch> matches = result.stream()
            .map(match -> new WatchListMatch(
                match.score,
                "Matched Coinhub Watch List. PartyIndex: " + match.partyId + ".",
                getId(),
                getName(),
                match.partyId))
            .collect(Collectors.toList());
        return new WatchListResult(matches);
    }
}
