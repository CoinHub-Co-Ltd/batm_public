package com.generalbytes.batm.server.extensions.extra.watchlists.ch;

import com.generalbytes.batm.server.extensions.IExtensionContext;
import com.generalbytes.batm.server.extensions.IIdentity;
import com.generalbytes.batm.server.extensions.IIdentityPiece;
import com.generalbytes.batm.server.extensions.ITransactionListener;
import com.generalbytes.batm.server.extensions.ITransactionPreparation;
import com.generalbytes.batm.server.extensions.ITransactionRequest;
import com.generalbytes.batm.server.extensions.watchlist.WatchListMatch;
import com.generalbytes.batm.server.extensions.watchlist.WatchListQuery;
import com.generalbytes.batm.server.extensions.watchlist.WatchListResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoinHubSecurity1Listener implements ITransactionListener {
    private static final Logger log = LoggerFactory.getLogger(CoinHubSecurity1Listener.class);

    private final IExtensionContext ctx;
    private final CoinHubWatchList watchList;

    public CoinHubSecurity1Listener(IExtensionContext ctx, CoinHubWatchList watchList) {
        this.ctx = ctx;
        this.watchList = watchList;
    }

    @Override
    public boolean isTransactionPreparationApproved(ITransactionPreparation prep) {
        log.info("[Security1] prep check identity={} type={}",
            prep.getIdentityPublicId(), prep.getType());
        String message = findDenyMessage(prep.getIdentityPublicId());
        if (message == null) {
            log.info("[Security1] prep ALLOW identity={}", prep.getIdentityPublicId());
            return true;
        }
        log.warn("[Security1] DENY prep identity={} msg={}", prep.getIdentityPublicId(), message);
        prep.setErrorMessage(message);
        return false;
    }

    @Override
    public boolean isTransactionApproved(ITransactionRequest request) {
        log.info("[Security1] approve check identity={} type={}",
            request.getIdentityPublicId(), request.getType());
        String message = findDenyMessage(request.getIdentityPublicId());
        if (message == null) {
            log.info("[Security1] approve ALLOW identity={}", request.getIdentityPublicId());
            return true;
        }
        log.warn("[Security1] DENY approve identity={} msg={}", request.getIdentityPublicId(), message);
        request.setErrorMessage(message);
        return false;
    }

    private String findDenyMessage(String identityId) {
        if (identityId == null || watchList == null || ctx == null) {
            log.warn("[Security1] skip — missing identity/watchList/ctx identity={}", identityId);
            return null;
        }
        try {
            String cachedDeny = watchList.getDeniedMessage(identityId);
            if (cachedDeny != null) {
                log.warn("[Security1] cached deny identity={} msg={}", identityId, cachedDeny);
                return cachedDeny;
            }

            IIdentity identity = ctx.findIdentityByIdentityId(identityId);
            if (identity == null || identity.getIdentityPieces() == null) {
                log.warn("[Security1] skip — identity/pieces missing identity={}", identityId);
                return null;
            }
            if (identity.getState() == IIdentity.STATE_PROHIBITED) {
                log.warn("[Security1] identity {} is PROHIBITED — denying", identityId);
                return CoinHubAtmErrors.S1_DENIED;
            }
            IIdentityPiece personalInfo = null;
            for (IIdentityPiece piece : identity.getIdentityPieces()) {
                if (piece.getPieceType() == IIdentityPiece.TYPE_PERSONAL_INFORMATION) {
                    personalInfo = piece;
                    break;
                }
            }
            if (personalInfo == null) {
                log.warn("[Security1] skip — no personal info piece identity={}", identityId);
                return null;
            }
            log.info("[Security1] searching identity={} firstName={} lastName={}",
                identityId, personalInfo.getFirstname(), personalInfo.getLastname());
            WatchListResult result = watchList.searchForTransactionGate(new WatchListQuery(
                personalInfo.getFirstname(), personalInfo.getLastname(), identityId));
            if (result == null || result.getMatches() == null || result.getMatches().isEmpty()) {
                log.info("[Security1] no matches identity={}", identityId);
                return null;
            }
            for (WatchListMatch match : result.getMatches()) {
                if (match != null && match.getScore() >= 100) {
                    if (match.getDetails() != null && !match.getDetails().trim().isEmpty()) {
                        return match.getDetails();
                    }
                    return CoinHubAtmErrors.S1_DENIED;
                }
            }
            log.info("[Security1] matches below threshold identity={}", identityId);
            return null;
        } catch (Exception e) {
            log.error("[Security1] check failed identity={} — denying", identityId, e);
            return CoinHubAtmErrors.S1_CHECK_FAILED;
        }
    }
}
