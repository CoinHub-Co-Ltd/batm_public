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

    // Security 1.2 — fingerprint must match first name, last name, and date of birth
    public static final String CODE_S12_IDENTITY_REQUIRED = "00017";
    public static final String CODE_S12_IDENTITY_MISMATCH = "00018";
    public static final String CODE_S12_UNAVAILABLE = "00019";

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
        defaults.put(CODE_S1_DENIED, "This identity is prohibited and cannot make a transaction.");
        defaults.put(CODE_S1_BLACKLISTED, "This name is on the blocklist. Please contact support.");
        defaults.put(CODE_S1_UNAVAILABLE, "The blocklist check could not be completed. Please try again later.");
        defaults.put(CODE_S1_CHECK_FAILED, "The blocklist check failed. Please try again later.");
        defaults.put(CODE_S12_IDENTITY_REQUIRED, "A first name, last name, and date of birth are required to verify this fingerprint.");
        defaults.put(CODE_S12_IDENTITY_MISMATCH, "This fingerprint is already registered to a different name.");
        defaults.put(CODE_S12_UNAVAILABLE, "The fingerprint identity check could not be completed. Please try again later.");
        defaults.put(CODE_S2_MISSING_ADDRESS, "The coin or destination wallet address is missing.");
        defaults.put(CODE_S2_FINGERPRINT_DENIED, "This wallet address is already registered to a different fingerprint.");
        defaults.put(CODE_S2_FINGERPRINT_UNAVAILABLE, "The wallet fingerprint check could not be completed. Please try again later.");
        defaults.put(CODE_S2_LEDGER_UNAVAILABLE, "The purchase history could not be loaded. Please try again later.");
        defaults.put(CODE_S2_LIMIT_TRY_LATER, "The 3-hour limit of {0} JPY has been reached. Please try again later.");
        defaults.put(CODE_S2_LIMIT_EXCEEDED, "The {0} purchase limit of {1} JPY has been reached.");
        defaults.put(CODE_S2_MAX_PER_TX, "This purchase of more than {0} JPY is above the single-transaction limit.");
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
