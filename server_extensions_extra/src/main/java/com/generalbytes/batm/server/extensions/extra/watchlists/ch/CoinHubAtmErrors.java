package com.generalbytes.batm.server.extensions.extra.watchlists.ch;

import com.generalbytes.batm.server.extensions.IExtensionContext;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ATM deny messages loaded from {@code /batm/config/coinhub_errors}.
 * <p>
 * Keys: {@code <code>.<lang>} e.g. {@code 00005.en}, {@code 00005.ja}.
 * Falls back to English config, then built-in English defaults.
 */
public final class CoinHubAtmErrors {

    public static final String CONFIG_FILE = "coinhub_errors";

    // Security 1 — initial-security / watchlist
    public static final String CODE_S1_DENIED = "00001";
    public static final String CODE_S1_BLACKLISTED = "00002";
    public static final String CODE_S1_UNAVAILABLE = "00003";
    public static final String CODE_S1_CHECK_FAILED = "00004";

    // Security 2 — fingerprint / limits
    public static final String CODE_S2_MISSING_ADDRESS = "00005";
    public static final String CODE_S2_FINGERPRINT_DENIED = "00006";
    public static final String CODE_S2_FINGERPRINT_UNAVAILABLE = "00007";
    public static final String CODE_S2_LEDGER_UNAVAILABLE = "00008";
    public static final String CODE_S2_LIMIT_TRY_LATER = "00009";
    public static final String CODE_S2_LIMIT_EXCEEDED = "00010";
    public static final String CODE_S2_MAX_PER_TX = "00016";

    private static final Map<String, String> DEFAULT_EN;

    static {
        Map<String, String> defaults = new HashMap<>();
        defaults.put(CODE_S1_DENIED, "Transaction denied.");
        defaults.put(CODE_S1_BLACKLISTED, "Black Listed. Please contact support.");
        defaults.put(CODE_S1_UNAVAILABLE, "CoinHub watchlist unavailable (empty response)");
        defaults.put(CODE_S1_CHECK_FAILED, "Transaction denied.");
        defaults.put(CODE_S2_MISSING_ADDRESS, "Crypto currency or destination address is missing.");
        defaults.put(CODE_S2_FINGERPRINT_DENIED, "Transaction denied.");
        defaults.put(CODE_S2_FINGERPRINT_UNAVAILABLE, "Transaction limit check unavailable. Please try again later.");
        defaults.put(CODE_S2_LEDGER_UNAVAILABLE, "Transaction limit check unavailable. Please try again later.");
        defaults.put(CODE_S2_LIMIT_TRY_LATER, "Transaction limit exceeded. Please try again later.");
        defaults.put(CODE_S2_LIMIT_EXCEEDED, "Transaction limit exceeded.");
        defaults.put(CODE_S2_MAX_PER_TX, "Maximum {0} JPY per transaction exceeded.");
        DEFAULT_EN = Collections.unmodifiableMap(defaults);
    }

    private CoinHubAtmErrors() {
    }

    /**
     * Localized message {@code [code] text} for the given ATM language.
     */
    public static String msg(IExtensionContext ctx, String code, String language) {
        return wrap(code, resolveText(ctx, code, language));
    }

    /**
     * Localized message with {@link MessageFormat} placeholders ({@code {0}}, {@code {1}}, …).
     */
    public static String msg(IExtensionContext ctx, String code, String language, Object... args) {
        String text = resolveText(ctx, code, language);
        if (args != null && args.length > 0) {
            text = MessageFormat.format(text, args);
        }
        return wrap(code, text);
    }

    /**
     * If {@code codeOrMessage} is a known code (or {@code [code] ...}), re-localize it.
     * Otherwise return as-is (e.g. custom watchlist match details).
     */
    public static String localize(IExtensionContext ctx, String codeOrMessage, String language) {
        String code = extractCode(codeOrMessage);
        if (code != null && DEFAULT_EN.containsKey(code)) {
            return msg(ctx, code, language);
        }
        return codeOrMessage;
    }

    public static String normalizeLanguage(String language) {
        if (language == null || language.trim().isEmpty()) {
            return "en";
        }
        String primary = language.trim().toLowerCase(Locale.ROOT);
        int sep = Math.min(
            primary.contains("_") ? primary.indexOf('_') : primary.length(),
            primary.contains("-") ? primary.indexOf('-') : primary.length()
        );
        if (sep < primary.length()) {
            primary = primary.substring(0, sep);
        }
        if (primary.startsWith("ja")) {
            return "ja";
        }
        return "en";
    }

    public static String extractCode(String codeOrMessage) {
        if (codeOrMessage == null) {
            return null;
        }
        String trimmed = codeOrMessage.trim();
        if (DEFAULT_EN.containsKey(trimmed)) {
            return trimmed;
        }
        if (trimmed.startsWith("[") && trimmed.length() > 2) {
            int end = trimmed.indexOf(']');
            if (end > 1) {
                String code = trimmed.substring(1, end).trim();
                if (DEFAULT_EN.containsKey(code)) {
                    return code;
                }
            }
        }
        return null;
    }

    private static String resolveText(IExtensionContext ctx, String code, String language) {
        String lang = normalizeLanguage(language);
        String text = readConfig(ctx, code + "." + lang);
        if (isBlank(text) && !"en".equals(lang)) {
            text = readConfig(ctx, code + ".en");
        }
        if (isBlank(text)) {
            text = DEFAULT_EN.get(code);
        }
        if (isBlank(text)) {
            text = "Transaction denied.";
        }
        return text.trim();
    }

    private static String readConfig(IExtensionContext ctx, String key) {
        if (ctx == null) {
            return null;
        }
        try {
            return ctx.getConfigProperty(CONFIG_FILE, key, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String wrap(String code, String text) {
        return "[" + code + "] " + text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
