/*************************************************************************************
 * CoinHub CAS Admin cashbox reads/writes for replenish (normalized portal shape).
 ************************************************************************************/
package com.generalbytes.batm.server.extensions.extra.ryocoin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.generalbytes.batm.server.extensions.IExtensionContext;
import com.generalbytes.batm.server.extensions.IBanknoteCounts;
import com.generalbytes.batm.server.extensions.ITerminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class CoinHubCasAdminCashboxService {

    private static final Logger log = LoggerFactory.getLogger(CoinHubCasAdminCashboxService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long SERIAL_SIGNATURE_INDEX_TTL_MS = 60_000L;
    private static final long HARDWARE_LABELS_TTL_MS = 30_000L;

    private static volatile TimedCacheEntry<Map<String, String>> sharedSerialSignatureIndex;

    private final CoinHubCasAdminClient client;
    private final IExtensionContext ctx;
    private final Map<String, TimedCacheEntry<HardwareSnapshot>> hardwareSnapshotBySerial = new ConcurrentHashMap<>();

    static final class HardwareSnapshot {
        final List<String> labels;
        final Set<Integer> installedDispenserSlots;

        HardwareSnapshot(List<String> labels, Set<Integer> installedDispenserSlots) {
            this.labels = labels != null ? labels : Collections.emptyList();
            this.installedDispenserSlots = installedDispenserSlots != null
                    ? installedDispenserSlots
                    : Collections.emptySet();
        }

        static HardwareSnapshot empty() {
            return new HardwareSnapshot(Collections.emptyList(), Collections.emptySet());
        }
    }

    CoinHubCasAdminCashboxService(IExtensionContext ctx) {
        this.ctx = ctx;
        this.client = new CoinHubCasAdminClient(ctx);
    }

    Map<String, Object> getTerminalCashboxesBySerial(String serialNumber) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (serialNumber == null || serialNumber.trim().isEmpty()) {
            out.put("status", false);
            out.put("message", "serial_number is required.");
            return out;
        }

        if (!client.isConfigured()) {
            out.put("status", false);
            out.put("message", "CAS Admin credentials are not configured in coinhub.properties.");
            return out;
        }

        String sn = serialNumber.trim();
        try {
            String terminalSignature = resolveTerminalSignature(sn);
            if (terminalSignature == null || terminalSignature.isEmpty()) {
                out.put("status", false);
                out.put("message", terminalNotFoundMessage(sn));
                return out;
            }

            JsonNode payload = client.getTerminalCashboxes(terminalSignature);
            out.put("status", true);
            out.put("data", normalizeCashboxesPayload(payload, sn, terminalSignature));
            return out;
        } catch (IOException e) {
            log.warn("CAS admin cashboxes lookup failed for serial {}", sn, e);
            out.put("status", false);
            out.put("message", e.getMessage());
            return out;
        }
    }

    Map<String, Object> updateCashboxItems(List<Map<String, Object>> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            out.put("status", false);
            out.put("message", "No cashbox items to update.");
            out.put("data", new ArrayList<>());
            return out;
        }

        if (!client.isConfigured()) {
            out.put("status", false);
            out.put("message", "CAS Admin credentials are not configured in coinhub.properties.");
            out.put("data", new ArrayList<>());
            return out;
        }

        List<Map<String, Object>> responses = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String cashboxSignature = stringValue(item.get("cashbox_signature"));
            String itemSignature = stringValue(item.get("item_signature"));
            int count = intValue(item.get("count"));

            if (cashboxSignature.isEmpty() || itemSignature.isEmpty()) {
                out.put("status", false);
                out.put("message", "cashbox_signature and item_signature are required.");
                out.put("data", responses);
                return out;
            }

            try {
                CoinHubCasAdminClient.HttpResult result = client.patchCashboxItem(
                        cashboxSignature,
                        itemSignature,
                        count
                );

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("cashbox_signature", cashboxSignature);
                row.put("item_signature", itemSignature);
                row.put("count", count);
                row.put("http_status", result.statusCode);
                row.put("body", parseJsonObject(result.body));
                responses.add(row);

                if (result.statusCode < 200 || result.statusCode >= 300) {
                    out.put("status", false);
                    out.put("message", extractErrorMessage(
                            result.body,
                            "Failed to update CAS cashbox item (HTTP " + result.statusCode + ")."
                    ));
                    out.put("data", responses);
                    return out;
                }
            } catch (IOException e) {
                log.warn("CAS admin cashbox item update failed", e);
                out.put("status", false);
                out.put("message", e.getMessage());
                out.put("data", responses);
                return out;
            }
        }

        out.put("status", true);
        out.put("data", responses);
        return out;
    }

    /**
     * Preloads serial → CAS Admin signature mappings once per request so bulk terminal
     * endpoints do not repeat expensive per-serial searches.
     */
    void warmSerialSignatureIndex() {
        if (!client.isConfigured()) {
            return;
        }
        try {
            ensureSerialSignatureIndexLoaded();
        } catch (IOException e) {
            log.debug("CAS admin serial signature index warm failed", e);
        }
    }

    List<String> getHardwareOutStatusLabels(String serialNumber) {
        return getHardwareSnapshot(serialNumber).labels;
    }

    Set<Integer> getInstalledDispenserCassetteSlots(String serialNumber) {
        return getHardwareSnapshot(serialNumber).installedDispenserSlots;
    }

    /**
     * Hardware removal labels and installed dispenser slots from CAS Admin cashboxes.
     *
     * <p>In CAS Admin, cashbox {@code type} is the cash direction slot — {@code OUT} on
     * {@code dispenser_cassette_*} means the cassette is <em>installed</em> in the dispenser,
     * not physically removed. {@code CASSETTES OUT} is derived from missing dispenser cassette
     * rows while the acceptor cashbox is still present.</p>
     */
    HardwareSnapshot getHardwareSnapshot(String serialNumber) {
        if (serialNumber == null || serialNumber.trim().isEmpty() || !client.isConfigured()) {
            return HardwareSnapshot.empty();
        }

        String sn = serialNumber.trim();
        String cacheKey = sn.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        TimedCacheEntry<HardwareSnapshot> cached = hardwareSnapshotBySerial.get(cacheKey);
        if (cached != null && now - cached.atMs < HARDWARE_LABELS_TTL_MS) {
            return cached.value;
        }

        HardwareSnapshot snapshot = loadHardwareSnapshot(sn);
        hardwareSnapshotBySerial.put(cacheKey, new TimedCacheEntry<>(snapshot, now));
        return snapshot;
    }

    private HardwareSnapshot loadHardwareSnapshot(String serialNumber) {
        try {
            String terminalSignature = resolveTerminalSignature(serialNumber);
            if (terminalSignature == null || terminalSignature.isEmpty()) {
                return HardwareSnapshot.empty();
            }
            JsonNode payload = client.getTerminalCashboxes(terminalSignature);
            return hardwareSnapshotFromPayload(payload);
        } catch (IOException e) {
            log.debug("CAS admin hardware out status lookup failed for serial {}", serialNumber, e);
            return HardwareSnapshot.empty();
        }
    }

    Map<String, Object> clearShortCountersBySerial(String serialNumber) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (serialNumber == null || serialNumber.trim().isEmpty()) {
            out.put("status", false);
            out.put("message", "serial_number is required.");
            return out;
        }

        if (!client.isConfigured()) {
            out.put("status", false);
            out.put("message", "CAS Admin credentials are not configured in coinhub.properties.");
            return out;
        }

        String sn = serialNumber.trim();
        try {
            String terminalSignature = resolveTerminalSignature(sn);
            if (terminalSignature == null || terminalSignature.isEmpty()) {
                out.put("status", false);
                out.put("message", terminalNotFoundMessage(sn));
                return out;
            }

            CoinHubCasAdminClient.HttpResult result = client.clearShortCounters(terminalSignature);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("serial_number", sn);
            data.put("terminal_signature", terminalSignature);
            data.put("http_status", result.statusCode);
            data.put("body", parseJsonObject(result.body));
            out.put("status", true);
            out.put("data", data);
            return out;
        } catch (IOException e) {
            log.warn("CAS admin clear short counters failed for serial {}", sn, e);
            out.put("status", false);
            out.put("message", e.getMessage());
            return out;
        }
    }

    private String terminalNotFoundMessage(String serialNumber) {
        if (ctx != null) {
            try {
                ITerminal terminal = ctx.findTerminalBySerialNumber(serialNumber);
                if (terminal == null) {
                    return "Terminal " + serialNumber + " is not registered on this CAS server.";
                }
            } catch (Exception e) {
                log.debug("Extension terminal lookup failed for {}", serialNumber, e);
            }
        }

        return "Terminal was not found in CAS Admin.";
    }

    private String resolveTerminalSignature(String serialNumber) throws IOException {
        String configured = configuredTerminalSignature(serialNumber);
        if (configured != null && !configured.isEmpty()) {
            return configured;
        }

        if (client.isConfigured()) {
            String fromIndex = serialSignatureFromIndex(serialNumber);
            if (fromIndex != null && !fromIndex.isEmpty()) {
                return fromIndex;
            }
        }

        for (String query : new String[]{"sn:" + serialNumber, serialNumber}) {
            try {
                JsonNode payload = client.getGlobalSearch(query);
                String signature = extractTerminalSignature(payload, serialNumber);
                if (signature != null && !signature.isEmpty()) {
                    return signature;
                }
                signature = extractTerminalSignatureFromFilteredList(payload);
                if (signature != null && !signature.isEmpty()) {
                    return signature;
                }
            } catch (IOException e) {
                log.debug("Global search failed for query {}", query, e);
            }
        }

        List<Map<String, String>> searches = new ArrayList<>();
        searches.add(query("search", "sn:" + serialNumber));
        searches.add(query("search", "serialNumber:" + serialNumber));
        searches.add(query("search", serialNumber));
        searches.add(query("q", "sn:" + serialNumber));
        searches.add(query("q", serialNumber));
        searches.add(query("filter", "sn:" + serialNumber));
        searches.add(query("serialNumber", serialNumber));
        searches.add(query("sn", serialNumber));

        for (Map<String, String> searchQuery : searches) {
            try {
                JsonNode payload = client.getTerminals(searchQuery);
                String signature = extractTerminalSignature(payload, serialNumber);
                if (signature != null && !signature.isEmpty()) {
                    return signature;
                }
                signature = extractTerminalSignatureFromFilteredList(payload);
                if (signature != null && !signature.isEmpty()) {
                    return signature;
                }
            } catch (IOException e) {
                log.debug("Terminal search failed for {}", searchQuery, e);
            }
        }

        for (String identifier : new String[]{serialNumber, "sn:" + serialNumber}) {
            try {
                JsonNode payload = client.getTerminal(identifier);
                String signature = extractTerminalSignature(payload, serialNumber);
                if (signature != null && !signature.isEmpty()) {
                    return signature;
                }
            } catch (IOException e) {
                log.debug("Terminal lookup failed for identifier {}", identifier, e);
            }
        }

        for (String identifier : new String[]{"sn:" + serialNumber, serialNumber}) {
            try {
                JsonNode payload = client.getTerminalCashboxes(identifier);
                if (payload != null
                        && payload.isObject()
                        && payload.has("cashboxes")
                        && payload.get("cashboxes").isArray()
                        && payload.get("cashboxes").size() > 0) {
                    return identifier;
                }
            } catch (IOException e) {
                log.debug("Direct cashboxes lookup failed for identifier {}", identifier);
            }
        }

        int pageSize = 100;
        int maxPages = 50;
        for (int pageStart : new int[]{0, 1}) {
            for (int page = pageStart; page < pageStart + maxPages; page++) {
                JsonNode payload;
                try {
                    payload = client.getTerminalsPage(page, pageSize);
                } catch (IOException e) {
                    log.debug("Terminal page lookup failed for page {}", page, e);
                    break;
                }

                String signature = extractTerminalSignature(payload, serialNumber);
                if (signature != null && !signature.isEmpty()) {
                    return signature;
                }

                JsonNode terminals = firstArray(payload, "terminals", "content", "data");
                if (terminals == null || !terminals.isArray() || terminals.size() == 0) {
                    break;
                }
                if (terminals.size() < pageSize) {
                    break;
                }
            }
        }

        if (ctx != null) {
            String viaExtension = resolveTerminalSignatureViaExtensionContext(serialNumber);
            if (viaExtension != null && !viaExtension.isEmpty()) {
                return viaExtension;
            }
        }

        return null;
    }

    private String serialSignatureFromIndex(String serialNumber) throws IOException {
        if (serialNumber == null || serialNumber.trim().isEmpty()) {
            return null;
        }
        Map<String, String> index = ensureSerialSignatureIndexLoaded();
        return index.get(serialNumber.trim().toLowerCase(Locale.ROOT));
    }

    private Map<String, String> ensureSerialSignatureIndexLoaded() throws IOException {
        long now = System.currentTimeMillis();
        TimedCacheEntry<Map<String, String>> cached = sharedSerialSignatureIndex;
        if (cached != null && now - cached.atMs < SERIAL_SIGNATURE_INDEX_TTL_MS) {
            return cached.value;
        }

        Map<String, String> index = loadSerialSignatureIndexFromCasAdmin();
        sharedSerialSignatureIndex = new TimedCacheEntry<>(index, now);
        return index;
    }

    private Map<String, String> loadSerialSignatureIndexFromCasAdmin() throws IOException {
        Map<String, String> index = new LinkedHashMap<>();
        int pageSize = 100;
        for (int page = 0; page < 50; page++) {
            JsonNode payload = client.getTerminalsPage(page, pageSize);
            JsonNode terminals = firstArray(payload, "terminals", "content", "data");
            if (terminals == null || !terminals.isArray() || terminals.size() == 0) {
                break;
            }

            for (JsonNode terminal : terminals) {
                if (terminal == null || !terminal.isObject()) {
                    continue;
                }
                String serial = terminalSerialFromRow(terminal);
                String signature = normalizeTerminalSignature(terminal);
                if (!serial.isEmpty() && signature != null && !signature.isEmpty()) {
                    index.put(serial.toLowerCase(Locale.ROOT), signature);
                }
            }

            if (terminals.size() < pageSize) {
                break;
            }
        }
        return index;
    }

    /**
     * Optional per-serial override when CAS Admin search cannot resolve by serial alone.
     * coinhub.properties:
     *   cas_admin_terminal_signature_BT401469=082a785297ebab48fd62832a6b93ca39
     * or comma-separated map:
     *   cas_admin_terminal_signatures=BT401469=082a785297ebab48fd62832a6b93ca39,BT300123=...
     */
    private String configuredTerminalSignature(String serialNumber) {
        if (ctx == null || serialNumber == null || serialNumber.trim().isEmpty()) {
            return null;
        }

        String sn = serialNumber.trim();
        String direct = configProperty("cas_admin_terminal_signature_" + sn, "");
        if (looksLikeAdminSignature(direct)) {
            log.info("Using configured CAS Admin terminal signature for {}", sn);
            return direct;
        }

        String map = configProperty("cas_admin_terminal_signatures", "");
        if (map.isEmpty()) {
            return null;
        }

        for (String entry : map.split(",")) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim();
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String mapSerial = trimmed.substring(0, separator).trim();
            String mapSignature = trimmed.substring(separator + 1).trim();
            if (mapSerial.equalsIgnoreCase(sn) && looksLikeAdminSignature(mapSignature)) {
                log.info("Using mapped CAS Admin terminal signature for {}", sn);
                return mapSignature;
            }
        }

        return null;
    }

    private String configProperty(String key, String defaultValue) {
        String value = ctx.getConfigProperty("coinhub", key, defaultValue);
        return value != null ? value.trim() : defaultValue;
    }

    /**
     * Resolve CAS Admin terminal signature using the local extension terminal registry when REST search misses.
     */
    private String resolveTerminalSignatureViaExtensionContext(String serialNumber) {
        ITerminal terminal;
        try {
            terminal = ctx.findTerminalBySerialNumber(serialNumber);
        } catch (Exception e) {
            log.debug("Extension terminal lookup failed for {}", serialNumber, e);
            return null;
        }
        if (terminal == null) {
            return null;
        }

        List<String> identifiers = new ArrayList<>();
        addIdentifierCandidate(identifiers, terminal.getSerialNumber());
        addIdentifierCandidate(identifiers, terminal.getName());
        addReflectionTerminalIdentifiers(identifiers, terminal);

        for (String identifier : identifiers) {
            try {
                JsonNode payload = client.getTerminal(identifier);
                String signature = extractTerminalSignature(payload, serialNumber);
                if (signature != null && !signature.isEmpty()) {
                    log.info("Resolved CAS Admin terminal signature via extension identifier {}", identifier);
                    return signature;
                }
            } catch (IOException e) {
                log.debug("CAS admin terminal lookup failed for extension identifier {}", identifier, e);
            }
        }

        for (String identifier : identifiers) {
            try {
                JsonNode payload = client.getTerminalCashboxes(identifier);
                if (payload == null || !payload.isObject()) {
                    continue;
                }
                JsonNode cashboxes = payload.get("cashboxes");
                if (cashboxes == null || !cashboxes.isArray() || cashboxes.size() == 0) {
                    continue;
                }

                String signature = extractTerminalSignature(payload, serialNumber);
                if (signature != null && !signature.isEmpty()) {
                    log.info("Resolved CAS Admin terminal signature via extension cashboxes identifier {}", identifier);
                    return signature;
                }

                String hrefSignature = signatureFromTerminalHref(payload);
                if (!hrefSignature.isEmpty()) {
                    log.info("Resolved CAS Admin terminal signature from cashboxes href via {}", identifier);
                    return hrefSignature;
                }

                if (looksLikeAdminSignature(identifier)) {
                    return identifier;
                }
            } catch (IOException e) {
                log.debug("CAS admin cashboxes lookup failed for extension identifier {}", identifier, e);
            }
        }

        return null;
    }

    private static void addIdentifierCandidate(List<String> identifiers, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (identifiers.contains(trimmed)) {
            return;
        }
        identifiers.add(trimmed);
    }

    private static void addReflectionTerminalIdentifiers(List<String> identifiers, ITerminal terminal) {
        for (String methodName : new String[]{
                "getId",
                "getTerminalId",
                "getDatabaseId",
                "getInternalId",
                "getTerminalID"
        }) {
            addIdentifierCandidate(identifiers, reflectTerminalString(terminal, methodName));
        }

        for (java.lang.reflect.Method method : terminal.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || !method.getName().startsWith("get")) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType != Long.class
                    && returnType != long.class
                    && returnType != Integer.class
                    && returnType != int.class
                    && returnType != String.class) {
                continue;
            }

            String value = reflectTerminalInvoke(terminal, method);
            if (value.isEmpty()) {
                continue;
            }
            if (value.matches("\\d+") || looksLikeAdminSignature(value)) {
                addIdentifierCandidate(identifiers, value);
            }
        }
    }

    private static String reflectTerminalInvoke(ITerminal terminal, java.lang.reflect.Method method) {
        try {
            Object value = method.invoke(terminal);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String reflectTerminalString(ITerminal terminal, String methodName) {
        try {
            java.lang.reflect.Method method = terminal.getClass().getMethod(methodName);
            Object value = method.invoke(terminal);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractTerminalSignatureFromFilteredList(JsonNode payload) {
        JsonNode terminals = firstArray(payload, "terminals", "content", "data");
        if (terminals == null || !terminals.isArray() || terminals.size() != 1) {
            return null;
        }
        return normalizeTerminalSignature(terminals.get(0));
    }

    private static String normalizeTerminalSignature(JsonNode terminal) {
        if (terminal == null || !terminal.isObject()) {
            return null;
        }

        String hrefSignature = signatureFromTerminalHref(terminal);
        if (!hrefSignature.isEmpty()) {
            return hrefSignature;
        }

        for (String key : new String[]{"signature", "terminalSignature", "terminal_signature", "id", "uuid"}) {
            String value = firstText(terminal, key);
            if (looksLikeAdminSignature(value)) {
                return value;
            }
        }

        return null;
    }

    private static String signatureFromTerminalHref(JsonNode terminal) {
        String href = hrefFromLinks(terminal);
        if (href.isEmpty()) {
            return "";
        }
        String marker = "/terminals/";
        int index = href.indexOf(marker);
        if (index < 0) {
            return "";
        }
        String tail = href.substring(index + marker.length());
        int end = tail.indexOf('?');
        if (end >= 0) {
            tail = tail.substring(0, end);
        }
        end = tail.indexOf('#');
        if (end >= 0) {
            tail = tail.substring(0, end);
        }
        end = tail.indexOf('/');
        if (end >= 0) {
            tail = tail.substring(0, end);
        }
        String signature = tail.trim();
        return looksLikeAdminSignature(signature) ? signature : "";
    }

    private static boolean looksLikeAdminSignature(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.matches("(?i)[a-f0-9]{32}")) {
            return true;
        }
        return value.toLowerCase(Locale.ROOT).startsWith("sn:");
    }

    private static Map<String, String> query(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static String extractTerminalSignature(JsonNode payload, String serialNumber) {
        if (payload != null && payload.isObject()) {
            String directSerial = terminalSerialFromRow(payload);
            if (directSerial.isEmpty() || directSerial.equalsIgnoreCase(serialNumber)) {
                String signature = normalizeTerminalSignature(payload);
                if (signature != null && !signature.isEmpty()) {
                    return signature;
                }
            }
        }

        JsonNode terminals = firstArray(payload, "terminals", "content", "data");
        if (terminals == null) {
            return null;
        }

        for (JsonNode terminal : terminals) {
            if (terminal == null || !terminal.isObject()) {
                continue;
            }
            String candidateSerial = terminalSerialFromRow(terminal);
            if (!candidateSerial.isEmpty() && !candidateSerial.equalsIgnoreCase(serialNumber)) {
                continue;
            }
            String signature = normalizeTerminalSignature(terminal);
            if (signature != null && !signature.isEmpty()) {
                return signature;
            }
        }
        return null;
    }

    private static final class TimedCacheEntry<T> {
        final T value;
        final long atMs;

        TimedCacheEntry(T value, long atMs) {
            this.value = value;
            this.atMs = atMs;
        }
    }

    private static String terminalSerialFromRow(JsonNode terminal) {
        if (terminal == null || !terminal.isObject()) {
            return "";
        }
        for (String key : new String[]{"serialNumber", "serial_number", "serialnumber", "sn", "serial"}) {
            String value = firstText(terminal, key);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static HardwareSnapshot hardwareSnapshotFromPayload(JsonNode payload) {
        return new HardwareSnapshot(
                hardwareOutStatusLabelsFromPayload(payload),
                dispenserCassetteSlotsFromPayload(payload)
        );
    }

    private static Set<Integer> dispenserCassetteSlotsFromPayload(JsonNode payload) {
        Set<Integer> slots = new LinkedHashSet<>();
        JsonNode cashboxes = payload != null ? payload.get("cashboxes") : null;
        if (cashboxes == null || !cashboxes.isArray()) {
            return slots;
        }

        for (JsonNode cashbox : cashboxes) {
            if (cashbox == null || !cashbox.isObject()) {
                continue;
            }
            Integer slot = CoinHubDispenserCassetteTracker.slotFromCashboxName(textValue(cashbox.get("name")));
            if (slot != null) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private static List<String> hardwareOutStatusLabelsFromPayload(JsonNode payload) {
        List<String> labels = new ArrayList<>();
        JsonNode cashboxes = payload != null ? payload.get("cashboxes") : null;
        if (cashboxes == null || !cashboxes.isArray() || cashboxes.size() == 0) {
            return labels;
        }

        boolean hasAcceptorCashbox = false;
        boolean acceptorMarkedOut = false;
        boolean hasDispenserCassette = false;

        for (JsonNode cashbox : cashboxes) {
            if (cashbox == null || !cashbox.isObject()) {
                continue;
            }

            String cashboxName = textValue(cashbox.get("name"));
            if (cashboxName.isEmpty()) {
                continue;
            }

            String cashboxType = textValue(cashbox.get("type"));

            if (isAcceptorCashboxName(cashboxName)) {
                hasAcceptorCashbox = true;
                // Acceptor/stacker is normally IN; OUT on acceptor cashbox means physically removed.
                if ("OUT".equalsIgnoreCase(cashboxType)) {
                    acceptorMarkedOut = true;
                }
            }

            if (cashboxName.startsWith("dispenser_cassette_")) {
                hasDispenserCassette = true;
            }
        }

        if (acceptorMarkedOut) {
            labels.add("ACCEPTOR OUT");
        }

        // Dispenser cassettes use type OUT when installed; they disappear from the list when pulled.
        if (hasAcceptorCashbox && !hasDispenserCassette) {
            labels.add("CASSETTES OUT");
        }

        return labels;
    }

    private static boolean isAcceptorCashboxName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String n = name.trim();
        if (IBanknoteCounts.CN_ACCEPTOR_CASHBOX.equalsIgnoreCase(n)) {
            return true;
        }
        return n.toLowerCase(Locale.ROOT).contains("acceptor");
    }

    private static Map<String, Object> normalizeCashboxesPayload(
            JsonNode payload,
            String serialNumber,
            String terminalSignature
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> cassettes = new ArrayList<>();
        List<Map<String, Object>> dispenserCassettes = new ArrayList<>();

        JsonNode cashboxes = payload != null ? payload.get("cashboxes") : null;
        if (cashboxes != null && cashboxes.isArray()) {
            for (JsonNode cashbox : cashboxes) {
                if (cashbox == null || !cashbox.isObject()) {
                    continue;
                }

                String cashboxSignature = resourceSignatureFromHref(cashbox, "cashboxes");
                if (cashboxSignature.isEmpty()) {
                    cashboxSignature = firstText(cashbox, "id", "signature");
                }
                String cashboxName = textValue(cashbox.get("name"));
                String cashboxType = textValue(cashbox.get("type"));

                JsonNode items = cashbox.get("items");
                if (items == null || !items.isArray()) {
                    continue;
                }

                for (JsonNode item : items) {
                    if (item == null || !item.isObject()) {
                        continue;
                    }

                    String itemSignature = resourceSignatureFromHref(item, "items");
                    if (itemSignature.isEmpty()) {
                        itemSignature = firstText(item, "id", "signature");
                    }

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("cashbox_signature", cashboxSignature);
                    row.put("cashbox_name", cashboxName);
                    row.put("cashbox_type", cashboxType);
                    row.put("item_signature", itemSignature);
                    row.put("denomination", jsonValue(item.get("denomination")));
                    row.put("currency", textValue(item.get("currency")));
                    row.put("count", item.has("count") ? item.get("count").asInt(0) : 0);
                    row.put("value", jsonValue(item.get("value")));
                    cassettes.add(row);

                    if ("OUT".equalsIgnoreCase(cashboxType) && cashboxName.startsWith("dispenser_cassette_")) {
                        dispenserCassettes.add(row);
                    }
                }
            }
        }

        data.put("terminal_signature", terminalSignature);
        data.put("serial_number", serialNumber);
        data.put("configuration_currency", textValue(payload != null ? payload.get("configurationCurrency") : null));
        data.put("cassettes", cassettes);
        data.put("dispenser_cassettes", dispenserCassettes);
        data.put("cashboxes", jsonValue(cashboxes));
        return data;
    }

    private static String resourceSignatureFromHref(JsonNode entity, String resourceSegment) {
        String href = hrefFromLinks(entity);
        if (href.isEmpty()) {
            return "";
        }
        String marker = "/" + resourceSegment + "/";
        int index = href.indexOf(marker);
        if (index < 0) {
            return "";
        }
        String tail = href.substring(index + marker.length());
        int end = tail.indexOf('?');
        if (end >= 0) {
            tail = tail.substring(0, end);
        }
        end = tail.indexOf('#');
        if (end >= 0) {
            tail = tail.substring(0, end);
        }
        end = tail.indexOf('/');
        if (end >= 0) {
            tail = tail.substring(0, end);
        }
        return tail.trim();
    }

    private static String hrefFromLinks(JsonNode entity) {
        if (entity == null || !entity.isObject()) {
            return "";
        }
        JsonNode links = entity.get("_links");
        if (links == null || !links.isObject()) {
            links = entity.get("links");
        }
        if (links != null && links.isObject()) {
            JsonNode self = links.get("self");
            if (self != null) {
                if (self.isTextual()) {
                    return self.asText("").trim();
                }
                if (self.isObject()) {
                    return textValue(self.get("href"));
                }
            }
        }
        return textValue(entity.get("self"));
    }

    private static JsonNode firstArray(JsonNode payload, String... keys) {
        if (payload == null) {
            return null;
        }
        if (payload.isArray()) {
            return payload;
        }
        for (String key : keys) {
            JsonNode node = payload.get(key);
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = textValue(node.get(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String textValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").trim();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isValueNode()) {
            if (node.isNumber()) {
                return node.numberValue();
            }
            return node.asText();
        }
        return JSON.convertValue(node, Object.class);
    }

    private static Object parseJsonObject(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.readValue(body, Object.class);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", body);
            return fallback;
        }
    }

    private static String extractErrorMessage(String body, String fallback) {
        String message = "";
        if (body != null && !body.trim().isEmpty()) {
            try {
                JsonNode json = JSON.readTree(body);
                message = firstText(json, "message", "error", "detail");
                if (message.isEmpty()) {
                    JsonNode errors = json.get("errors");
                    if (errors != null && errors.isArray()) {
                        for (JsonNode entry : errors) {
                            if (entry != null && entry.isObject()) {
                                String entryMessage = firstText(entry, "message");
                                if (!entryMessage.isEmpty()) {
                                    message = entryMessage;
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                message = body.trim();
            }
        }
        return message.isEmpty() ? fallback : message;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> parseItems(JsonNode itemsNode) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (itemsNode == null || !itemsNode.isArray()) {
            return items;
        }
        for (JsonNode node : itemsNode) {
            if (node != null && node.isObject()) {
                items.add(JSON.convertValue(node, Map.class));
            }
        }
        return items;
    }
}
