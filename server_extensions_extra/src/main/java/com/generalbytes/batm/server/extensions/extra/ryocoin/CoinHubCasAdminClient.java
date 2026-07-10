/*************************************************************************************
 * CoinHub helper: CAS Admin REST (port 7777) from the CAS host (localhost).
 ************************************************************************************/
package com.generalbytes.batm.server.extensions.extra.ryocoin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.generalbytes.batm.server.extensions.IExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authenticates against CAS Admin and performs cashbox REST calls on the CAS host.
 */
final class CoinHubCasAdminClient {

    private static final Logger log = LoggerFactory.getLogger(CoinHubCasAdminClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;

    private static final AtomicReference<String> cachedAuthToken = new AtomicReference<>();
    private static volatile long cachedAuthTokenExpiresAtMs = 0L;

    private final String baseUrl;
    private final String loginPath;
    private final String username;
    private final String password;
    private final String totpStaticCode;
    private final String totpSecret;

    CoinHubCasAdminClient(IExtensionContext ctx) {
        this.baseUrl = trimTrailingSlash(config(ctx, "cas_admin_api_url", "https://127.0.0.1:7777"));
        this.loginPath = trimLeadingSlash(config(ctx, "cas_admin_login_path", "api/v1/auth"));
        this.username = config(ctx, "cas_admin_username", "");
        this.password = config(ctx, "cas_admin_password", "");
        this.totpStaticCode = normalizeStaticTotpCode(config(ctx, "cas_admin_totp", ""));
        this.totpSecret = resolveTotpSecret(
                config(ctx, "cas_admin_totp_secret", ""),
                config(ctx, "cas_admin_totp", "")
        );
    }

    boolean isConfigured() {
        return !baseUrl.isEmpty() && !username.isEmpty() && !password.isEmpty();
    }

    JsonNode getTerminals(Map<String, String> query) throws IOException {
        return authorizedJson("GET", apiPath("/terminals"), query, null, false);
    }

    JsonNode getGlobalSearch(String query) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("search", query);
        params.put("query", query);
        return authorizedJson("GET", apiPath("/search"), params, null, false);
    }

    JsonNode getTerminal(String identifier) throws IOException {
        return authorizedJson(
                "GET",
                apiPath("/terminals/" + urlEncodePath(identifier)),
                null,
                null,
                false
        );
    }

    JsonNode getTerminalsPage(int page, int size) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", String.valueOf(page));
        params.put("size", String.valueOf(size));
        return authorizedJson("GET", apiPath("/terminals"), params, null, false);
    }

    JsonNode getTerminalCashboxes(String terminalSignature) throws IOException {
        return authorizedJson(
                "GET",
                apiPath("/terminals/" + urlEncodePath(terminalSignature) + "/cashboxes"),
                null,
                null,
                false
        );
    }

    HttpResult patchCashboxItem(String cashboxSignature, String itemSignature, int count) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", count);
        String json = JSON.writeValueAsString(body);
        String url = apiPath("/cashboxes/" + urlEncodePath(cashboxSignature) + "/items/" + urlEncodePath(itemSignature));

        HttpResult result = patchCashboxItemRequest(url, json);
        if (result.statusCode == 401) {
            invalidateAuthToken();
            result = patchCashboxItemRequest(url, json);
        }
        return result;
    }

    HttpResult clearShortCounters(String terminalSignature) throws IOException {
        String url = apiPath(
                "/terminals/" + urlEncodePath(terminalSignature) + "/clear-short-counters"
        );
        HttpResult result = authorizedRaw("POST", url, null, "{}", false);
        if (result.statusCode < 200 || result.statusCode >= 300) {
            String message = extractErrorMessage(result.body);
            if (message.isEmpty()) {
                message = "Failed to clear short counters (HTTP " + result.statusCode + ").";
            }
            throw new IOException(message);
        }
        return result;
    }

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private HttpResult patchCashboxItemRequest(String url, String json) throws IOException {
        String token = getAuthToken();
        return executePatchWithOkHttp(url, json, token);
    }

    /**
     * OkHttp supports PATCH and full SSL bypass for IP-based cas_admin_api_url hosts.
     */
    private HttpResult executePatchWithOkHttp(String url, String jsonBody, String authToken) throws IOException {
        X509TrustManager trustManager = permissiveTrustManager();
        SSLContext sslContext;
        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
        } catch (Exception e) {
            throw new IOException("Failed to configure SSL for CAS Admin PATCH.", e);
        }

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                .hostnameVerifier((hostname, session) -> true)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .patch(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
                .header("Accept", "application/json");

        if (authToken != null && !authToken.isEmpty()) {
            requestBuilder.header("Cookie", "auth=" + authToken);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            return new HttpResult(response.code(), body, response.headers("Set-Cookie"));
        }
    }

    private static X509TrustManager permissiveTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private JsonNode authorizedJson(
            String method,
            String path,
            Map<String, String> query,
            String jsonBody,
            boolean retried
    ) throws IOException {
        HttpResult result = authorizedRaw(method, path, query, jsonBody, retried);
        if (result.statusCode < 200 || result.statusCode >= 300) {
            String message = extractErrorMessage(result.body);
            if (message.isEmpty()) {
                message = "CAS Admin request failed (HTTP " + result.statusCode + ").";
            }
            throw new IOException(message);
        }
        if (result.body == null || result.body.trim().isEmpty()) {
            return JSON.createObjectNode();
        }
        return JSON.readTree(result.body);
    }

    private HttpResult authorizedRaw(
            String method,
            String path,
            Map<String, String> query,
            String jsonBody,
            boolean retried
    ) throws IOException {
        String token = getAuthToken();
        HttpResult result = execute(method, path, query, jsonBody, token);
        if (result.statusCode == 401 && !retried) {
            invalidateAuthToken();
            return authorizedRaw(method, path, query, jsonBody, true);
        }
        return result;
    }

    private String getAuthToken() throws IOException {
        long now = System.currentTimeMillis();
        String cached = cachedAuthToken.get();
        if (cached != null && !cached.isEmpty() && now < cachedAuthTokenExpiresAtMs) {
            return cached;
        }
        return authenticate();
    }

    private void invalidateAuthToken() {
        cachedAuthToken.set(null);
        cachedAuthTokenExpiresAtMs = 0L;
    }

    private String authenticate() throws IOException {
        if (!isConfigured()) {
            throw new IOException("CAS Admin username/password are not configured in coinhub.properties.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        String totpCode = resolveLoginTotpCode();
        if (!totpCode.isEmpty()) {
            payload.put("otp", totpCode);
        }

        String json = JSON.writeValueAsString(payload);
        HttpResult result = execute("POST", joinUrl(baseUrl, loginPath), null, json, null);
        if (result.statusCode < 200 || result.statusCode >= 300) {
            String message = extractErrorMessage(result.body);
            if (message.isEmpty()) {
                message = "CAS Admin login failed (HTTP " + result.statusCode + ").";
            }
            throw new IOException(message);
        }

        String token = extractAuthToken(result);
        if (token == null || token.isEmpty()) {
            throw new IOException("CAS Admin login succeeded but no auth token was returned.");
        }

        cachedAuthToken.set(token);
        cachedAuthTokenExpiresAtMs = System.currentTimeMillis() + tokenTtlMillis(token);
        return token;
    }

    private HttpResult execute(
            String method,
            String url,
            Map<String, String> query,
            String jsonBody,
            String authToken
    ) throws IOException {
        HttpURLConnection connection = openConnection(appendQuery(url, query));
        try {
            applyRequestMethod(connection, method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Cookie", "auth=" + authToken);
            }

            if (jsonBody != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }

            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            return new HttpResult(status, body, collectSetCookieHeaders(connection));
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (connection instanceof HttpsURLConnection) {
            applyPermissiveSsl((HttpsURLConnection) connection);
        }
        return connection;
    }

    private static SSLContext createPermissiveSslContext() throws IOException {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            }, new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new IOException("Failed to configure SSL for CAS Admin client.", e);
        }
    }

    private static void applyPermissiveSsl(HttpsURLConnection connection) {
        try {
            SSLContext sslContext = createPermissiveSslContext();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            log.warn("Failed to configure permissive SSL for CAS Admin client", e);
        }
    }

    private static void applyRequestMethod(HttpURLConnection connection, String method) throws IOException {
        setRequestMethod(connection, method);
    }

    private static void setRequestMethod(HttpURLConnection connection, String method) throws IOException {
        try {
            connection.setRequestMethod(method);
        } catch (java.net.ProtocolException e) {
            try {
                Field methodField = HttpURLConnection.class.getDeclaredField("method");
                methodField.setAccessible(true);
                methodField.set(connection, method);
            } catch (Exception reflectionError) {
                throw new IOException("HTTP method not supported: " + method, reflectionError);
            }
        }
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        return body.toString();
    }

    private static List<String> collectSetCookieHeaders(HttpURLConnection connection) {
        List<String> cookies = new ArrayList<>();
        for (int i = 0; ; i++) {
            String key = connection.getHeaderFieldKey(i);
            String value = connection.getHeaderField(i);
            if (key == null && value == null) {
                break;
            }
            if (key != null && "Set-Cookie".equalsIgnoreCase(key) && value != null) {
                cookies.add(value);
            }
        }
        return cookies;
    }

    private static String extractAuthToken(HttpResult result) {
        for (String setCookie : result.setCookies) {
            int authIndex = setCookie.indexOf("auth=");
            if (authIndex < 0) {
                continue;
            }
            String token = setCookie.substring(authIndex + 5);
            int semi = token.indexOf(';');
            if (semi >= 0) {
                token = token.substring(0, semi);
            }
            token = token.trim();
            if (!token.isEmpty()) {
                return token;
            }
        }

        try {
            JsonNode body = result.body != null && !result.body.isEmpty()
                    ? JSON.readTree(result.body)
                    : null;
            if (body == null) {
                return null;
            }
            for (String key : new String[]{"token", "auth", "accessToken", "access_token", "jwt"}) {
                JsonNode node = body.get(key);
                if (node != null && node.isTextual() && !node.asText().trim().isEmpty()) {
                    return node.asText().trim();
                }
            }
            JsonNode data = body.get("data");
            if (data != null && data.isObject()) {
                for (String key : new String[]{"token", "auth", "accessToken", "access_token", "jwt"}) {
                    JsonNode node = data.get(key);
                    if (node != null && node.isTextual() && !node.asText().trim().isEmpty()) {
                        return node.asText().trim();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse CAS Admin auth response body", e);
        }
        return null;
    }

    private static long tokenTtlMillis(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return 3600_000L;
            }
            String payloadJson = new String(
                    java.util.Base64.getUrlDecoder().decode(padBase64(parts[1])),
                    StandardCharsets.UTF_8
            );
            JsonNode payload = JSON.readTree(payloadJson);
            JsonNode exp = payload.get("exp");
            if (exp != null && exp.isNumber()) {
                long ttl = (exp.asLong() * 1000L) - System.currentTimeMillis() - 60_000L;
                return Math.max(60_000L, Math.min(ttl, 86_400_000L));
            }
        } catch (Exception e) {
            log.debug("Could not derive JWT TTL, using default", e);
        }
        return 3600_000L;
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value);
        for (int i = remainder; i < 4; i++) {
            sb.append('=');
        }
        return sb.toString();
    }

    private static String extractErrorMessage(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        try {
            JsonNode json = JSON.readTree(body);
            for (String key : new String[]{"message", "error", "detail"}) {
                JsonNode node = json.get(key);
                if (node != null && node.isTextual() && !node.asText().trim().isEmpty()) {
                    return node.asText().trim();
                }
            }
            JsonNode errors = json.get("errors");
            if (errors != null && errors.isArray()) {
                for (JsonNode entry : errors) {
                    if (entry != null && entry.isObject()) {
                        JsonNode messageNode = entry.get("message");
                        if (messageNode != null && messageNode.isTextual() && !messageNode.asText().trim().isEmpty()) {
                            return messageNode.asText().trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            return body.trim();
        }
        return "";
    }

    private static String appendQuery(String url, Map<String, String> query) throws IOException {
        if (query == null || query.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url);
        sb.append(url.contains("?") ? "&" : "?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append("&");
            }
            first = false;
            sb.append(urlEncodeQuery(entry.getKey()));
            sb.append("=");
            sb.append(urlEncodeQuery(entry.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncodeQuery(String value) throws IOException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IOException("UTF-8 not supported", e);
        }
    }

    private String apiPath(String suffix) {
        return joinUrl(joinUrl(baseUrl, "api/v1"), trimLeadingSlash(suffix));
    }

    private static String joinUrl(String base, String path) {
        String left = trimTrailingSlash(base);
        String right = trimLeadingSlash(path);
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + "/" + right;
    }

    private static String urlEncodePath(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static String config(IExtensionContext ctx, String key, String defaultValue) {
        if (ctx == null) {
            return defaultValue;
        }
        String value = ctx.getConfigProperty("coinhub", key, defaultValue);
        return value != null ? value.trim() : defaultValue;
    }

    private String resolveLoginTotpCode() throws IOException {
        if (!totpStaticCode.isEmpty()) {
            return totpStaticCode;
        }
        if (totpSecret.isEmpty()) {
            return "";
        }
        try {
            return CoinHubCasAdminTotp.currentCode(totpSecret);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid CAS Admin TOTP secret in coinhub.properties.", e);
        }
    }

    private static String normalizeStaticTotpCode(String value) {
        if (value == null) {
            return "";
        }
        String digits = value.replaceAll("\\D+", "");
        return digits.length() == 6 ? digits : "";
    }

    private static String resolveTotpSecret(String secretConfig, String legacyTotpConfig) {
        String secret = CoinHubCasAdminTotp.normalizeSecret(secretConfig);
        if (!secret.isEmpty()) {
            return secret;
        }
        if (normalizeStaticTotpCode(legacyTotpConfig).isEmpty()) {
            return CoinHubCasAdminTotp.normalizeSecret(legacyTotpConfig);
        }
        return "";
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String trimLeadingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    static final class HttpResult {
        final int statusCode;
        final String body;
        final List<String> setCookies;

        HttpResult(int statusCode, String body, List<String> setCookies) {
            this.statusCode = statusCode;
            this.body = body;
            this.setCookies = setCookies != null ? setCookies : new ArrayList<String>();
        }
    }
}
