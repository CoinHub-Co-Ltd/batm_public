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
    private static final String DENY_MESSAGE = "Transaction denied. Please contact support.";

    private final IExtensionContext ctx;
    private final CoinHubWatchList watchList;

    public CoinHubSecurity1Listener(IExtensionContext ctx, CoinHubWatchList watchList) {
        this.ctx = ctx;
        this.watchList = watchList;
    }

    @Override
    public boolean isTransactionPreparationApproved(ITransactionPreparation prep) {
        String message = findDenyMessage(prep.getIdentityPublicId());
        if (message == null) {
            return true;
        }
        log.warn("[Security1] DENY prep identity={}", prep.getIdentityPublicId());
        prep.setErrorMessage(message);
        return false;
    }

    @Override
    public boolean isTransactionApproved(ITransactionRequest request) {
        String message = findDenyMessage(request.getIdentityPublicId());
        if (message == null) {
            return true;
        }
        log.warn("[Security1] DENY approve identity={}", request.getIdentityPublicId());
        request.setErrorMessage(message);
        return false;
    }

    private String findDenyMessage(String identityId) {
        if (identityId == null || watchList == null || ctx == null) {
            return null;
        }
        try {
            IIdentity identity = ctx.findIdentityByIdentityId(identityId);
            if (identity == null || identity.getIdentityPieces() == null) {
                return null;
            }
            IIdentityPiece personalInfo = null;
            for (IIdentityPiece piece : identity.getIdentityPieces()) {
                if (piece.getPieceType() == IIdentityPiece.TYPE_PERSONAL_INFORMATION) {
                    personalInfo = piece;
                    break;
                }
            }
            if (personalInfo == null) {
                return null;
            }
            WatchListResult result = watchList.search(new WatchListQuery(
                personalInfo.getFirstname(), personalInfo.getLastname(), identityId));
            if (result == null || result.getMatches() == null) {
                return null;
            }
            for (WatchListMatch match : result.getMatches()) {
                if (match != null && match.getScore() >= 100) {
                    if (match.getDetails() != null && !match.getDetails().trim().isEmpty()) {
                        return match.getDetails();
                    }
                    return DENY_MESSAGE;
                }
            }
        } catch (Exception e) {
            log.error("[Security1] check failed identity={}", identityId, e);
        }
        return null;
    }
}
