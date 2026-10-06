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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CoinHubSecurity1Listener implements ITransactionListener {
    private static final Logger log = LoggerFactory.getLogger(CoinHubSecurity1Listener.class);

    private final IExtensionContext ctx;
    private final CoinHubWatchList watchList;
    /** Language from prep (ITransactionRequest has no getLanguage). Keyed by identity or terminal. */
    private final Map<String, String> languageByKey = new ConcurrentHashMap<>();

    public CoinHubSecurity1Listener(IExtensionContext ctx, CoinHubWatchList watchList) {
        this.ctx = ctx;
        this.watchList = watchList;
    }

    @Override
    public boolean isTransactionPreparationApproved(ITransactionPreparation prep) {
        String language = rememberLanguage(prep.getIdentityPublicId(), prep.getTerminalSerialNumber(), prep.getLanguage());
        log.info("[Security1] prep check identity={} type={} lang={}",
            prep.getIdentityPublicId(), prep.getType(), language);
        String message = findDenyMessage(prep.getIdentityPublicId(), language);
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
        String language = languageFor(request.getIdentityPublicId(), request.getTerminalSerialNumber());
        log.info("[Security1] approve check identity={} type={} lang={}",
            request.getIdentityPublicId(), request.getType(), language);
        String message = findDenyMessage(request.getIdentityPublicId(), language);
        if (message == null) {
            log.info("[Security1] approve ALLOW identity={}", request.getIdentityPublicId());
            return true;
        }
        log.warn("[Security1] DENY approve identity={} msg={}", request.getIdentityPublicId(), message);
        request.setErrorMessage(message);
        return false;
    }

    private String findDenyMessage(String identityId, String language) {
        if (identityId == null || watchList == null || ctx == null) {
            log.warn("[Security1] skip — missing identity/watchList/ctx identity={}", identityId);
            return null;
        }
        try {
            String cachedDeny = watchList.getDeniedMessage(identityId);
            if (cachedDeny != null) {
                String localized = CoinHubAtmErrors.localize(ctx, cachedDeny, language);
                log.warn("[Security1] cached deny identity={} msg={}", identityId, localized);
                return localized;
            }

            IIdentity identity = ctx.findIdentityByIdentityId(identityId);
            if (identity == null || identity.getIdentityPieces() == null) {
                log.warn("[Security1] skip — identity/pieces missing identity={}", identityId);
                return null;
            }
            if (identity.getState() == IIdentity.STATE_PROHIBITED) {
                log.warn("[Security1] identity {} is PROHIBITED — denying", identityId);
                return CoinHubAtmErrors.msg(ctx, CoinHubAtmErrors.CODE_S1_DENIED, language);
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
                        return CoinHubAtmErrors.localize(ctx, match.getDetails(), language);
                    }
                    return CoinHubAtmErrors.msg(ctx, CoinHubAtmErrors.CODE_S1_DENIED, language);
                }
            }
            log.info("[Security1] matches below threshold identity={}", identityId);
            return null;
        } catch (Exception e) {
            log.error("[Security1] check failed identity={} — denying", identityId, e);
            return CoinHubAtmErrors.msg(ctx, CoinHubAtmErrors.CODE_S1_CHECK_FAILED, language);
        }
    }

    private String rememberLanguage(String identityId, String terminalSerial, String language) {
        String normalized = CoinHubAtmErrors.normalizeLanguage(language);
        if (identityId != null && !identityId.trim().isEmpty()) {
            languageByKey.put(identityId, normalized);
        }
        if (terminalSerial != null && !terminalSerial.trim().isEmpty()) {
            languageByKey.put(terminalSerial, normalized);
        }
        return normalized;
    }

    private String languageFor(String identityId, String terminalSerial) {
        if (identityId != null) {
            String lang = languageByKey.get(identityId);
            if (lang != null) {
                return lang;
            }
        }
        if (terminalSerial != null) {
            String lang = languageByKey.get(terminalSerial);
            if (lang != null) {
                return lang;
            }
        }
        return "en";
    }
}
