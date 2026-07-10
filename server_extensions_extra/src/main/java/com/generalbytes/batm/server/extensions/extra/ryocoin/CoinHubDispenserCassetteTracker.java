/*************************************************************************************
 * Tracks per-slot dispenser cassette OUT state from terminal hardware notifications.
 ************************************************************************************/
package com.generalbytes.batm.server.extensions.extra.ryocoin;

import com.generalbytes.batm.server.extensions.IEventRecord;
import com.generalbytes.batm.server.extensions.IExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory cassette removal state driven by {@link com.generalbytes.batm.server.extensions.INotificationListener}
 * dispenser cassette events, with optional hydration from CAS terminal event history.
 */
final class CoinHubDispenserCassetteTracker {

    static final String GENERIC_REMOVED_LABEL = "DISPENSER CASSETTE REMOVED";

    private static final Logger log = LoggerFactory.getLogger(CoinHubDispenserCassetteTracker.class);
    private static final CoinHubDispenserCassetteTracker INSTANCE = new CoinHubDispenserCassetteTracker();
    private static final int EVENT_HYDRATION_LOOKBACK_DAYS = 90;

    private static final Pattern DISPENSER_CASSETTE_NAME = Pattern.compile(
            "(?i)dispenser_cassette_(\\d+)"
    );
    private static final Pattern CASSETTE_SLOT_TOKEN = Pattern.compile(
            "(?i)^c(?:assette)?[_\\s-]*(\\d+)$"
    );
    private static final Pattern[] INLINE_SLOT_PATTERNS = {
            Pattern.compile("(?i)cassette\\s*#?\\s*(\\d+)"),
            Pattern.compile("(?i)slot\\s*(\\d+)"),
            Pattern.compile("(?i)position\\s*(\\d+)"),
    };

    private final ConcurrentMap<String, Set<Integer>> outSlotsBySerial = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> genericRemovedBySerial = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Boolean> hydratedFromEventsBySerial = new ConcurrentHashMap<>();

    static CoinHubDispenserCassetteTracker getInstance() {
        return INSTANCE;
    }

    void markOut(String serialNumber, String cassetteInfo) {
        String serialKey = serialKey(serialNumber);
        if (serialKey.isEmpty()) {
            return;
        }

        Set<Integer> slots = parseSlots(cassetteInfo);
        if (slots.isEmpty()) {
            genericRemovedBySerial.put(serialKey, Boolean.TRUE);
            log.info("Dispenser cassette marked OUT (generic): serial={}, raw={}", serialNumber, cassetteInfo);
            return;
        }

        genericRemovedBySerial.remove(serialKey);
        outSlotsBySerial.compute(serialKey, (key, existing) -> {
            Set<Integer> merged = existing != null ? new LinkedHashSet<>(existing) : new LinkedHashSet<>();
            merged.addAll(slots);
            return merged;
        });
        log.info("Dispenser cassette marked OUT: serial={}, slots={}, raw={}",
                serialNumber, slots, cassetteInfo);
    }

    void markIn(String serialNumber, String cassetteInfo) {
        String serialKey = serialKey(serialNumber);
        if (serialKey.isEmpty()) {
            return;
        }

        Set<Integer> slots = parseSlots(cassetteInfo);
        if (slots.isEmpty()) {
            outSlotsBySerial.remove(serialKey);
            genericRemovedBySerial.remove(serialKey);
            log.info("Dispenser cassette marked IN (all cleared): serial={}, raw={}", serialNumber, cassetteInfo);
            return;
        }

        genericRemovedBySerial.remove(serialKey);
        outSlotsBySerial.computeIfPresent(serialKey, (key, existing) -> {
            Set<Integer> remaining = new LinkedHashSet<>(existing);
            remaining.removeAll(slots);
            return remaining.isEmpty() ? null : remaining;
        });
        log.info("Dispenser cassette marked IN: serial={}, slots={}, raw={}",
                serialNumber, slots, cassetteInfo);
    }

    void hydrateFromEventsIfEmpty(IExtensionContext ctx, String serialNumber) {
        String serialKey = serialKey(serialNumber);
        if (serialKey.isEmpty() || ctx == null) {
            return;
        }
        if (hasTrackedState(serialKey)) {
            return;
        }
        if (Boolean.TRUE.equals(hydratedFromEventsBySerial.putIfAbsent(serialKey, Boolean.TRUE))) {
            return;
        }

        try {
            Calendar from = Calendar.getInstance();
            from.add(Calendar.DAY_OF_MONTH, -EVENT_HYDRATION_LOOKBACK_DAYS);
            List<IEventRecord> events = ctx.getEvents(serialNumber.trim(), from.getTime(), new Date());
            if (events == null || events.isEmpty()) {
                return;
            }

            events.sort(Comparator.comparing(
                    IEventRecord::getServerTime,
                    Comparator.nullsFirst(Comparator.naturalOrder())
            ));

            Set<Integer> replayOut = new LinkedHashSet<>();
            boolean replayGeneric = false;
            for (IEventRecord event : events) {
                String blob = eventBlob(event);
                if (isCassetteOutEvent(blob)) {
                    Set<Integer> slots = parseSlots(blob);
                    if (!slots.isEmpty()) {
                        replayOut.addAll(slots);
                        replayGeneric = false;
                    } else {
                        replayGeneric = true;
                    }
                } else if (isCassetteInEvent(blob)) {
                    Set<Integer> slots = parseSlots(blob);
                    if (!slots.isEmpty()) {
                        replayOut.removeAll(slots);
                    } else {
                        replayOut.clear();
                        replayGeneric = false;
                    }
                }
            }

            if (!replayOut.isEmpty()) {
                outSlotsBySerial.put(serialKey, replayOut);
                log.info("Hydrated dispenser cassette OUT slots from events: serial={}, slots={}",
                        serialNumber, replayOut);
            } else if (replayGeneric) {
                genericRemovedBySerial.put(serialKey, Boolean.TRUE);
                log.info("Hydrated generic dispenser cassette OUT from events: serial={}", serialNumber);
            }
        } catch (Exception e) {
            log.debug("Event hydration for dispenser cassette state failed: serial={}", serialNumber, e);
        }
    }

    List<String> outLabelsFor(String serialNumber) {
        String serialKey = serialKey(serialNumber);
        if (serialKey.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> labels = new ArrayList<>();
        Set<Integer> outSlots = outSlotsBySerial.get(serialKey);
        if (outSlots != null) {
            outSlots.stream()
                    .filter(slot -> slot != null && slot > 0)
                    .sorted()
                    .forEach(slot -> labels.add(slotToLabel(slot)));
        }

        if (labels.isEmpty() && Boolean.TRUE.equals(genericRemovedBySerial.get(serialKey))) {
            labels.add(GENERIC_REMOVED_LABEL);
        }
        return labels;
    }

    boolean hasCassetteOut(String serialNumber) {
        return !outLabelsFor(serialNumber).isEmpty();
    }

    static Set<Integer> parseSlots(String cassetteInfo) {
        if (cassetteInfo == null || cassetteInfo.trim().isEmpty()) {
            return Collections.emptySet();
        }

        Set<Integer> slots = new LinkedHashSet<>();
        Matcher dispenserMatcher = DISPENSER_CASSETTE_NAME.matcher(cassetteInfo);
        while (dispenserMatcher.find()) {
            Integer slot = parseSlotNumber(dispenserMatcher.group(1));
            if (slot != null) {
                slots.add(slot);
            }
        }

        for (Pattern pattern : INLINE_SLOT_PATTERNS) {
            Matcher matcher = pattern.matcher(cassetteInfo);
            while (matcher.find()) {
                Integer slot = parseSlotNumber(matcher.group(1));
                if (slot != null) {
                    slots.add(slot);
                }
            }
        }

        if (slots.isEmpty()) {
            for (String token : splitTokens(cassetteInfo)) {
                Integer slot = parseSlotToken(token);
                if (slot != null) {
                    slots.add(slot);
                }
            }
        }
        return slots;
    }

    static Integer slotFromCashboxName(String cashboxName) {
        if (cashboxName == null || cashboxName.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = DISPENSER_CASSETTE_NAME.matcher(cashboxName.trim());
        if (!matcher.find()) {
            return null;
        }
        return parseSlotNumber(matcher.group(1));
    }

    static String slotToLabel(int slot) {
        return "C" + slot + " OUT";
    }

    private boolean hasTrackedState(String serialKey) {
        Set<Integer> slots = outSlotsBySerial.get(serialKey);
        return (slots != null && !slots.isEmpty())
                || Boolean.TRUE.equals(genericRemovedBySerial.get(serialKey));
    }

    private static String eventBlob(IEventRecord event) {
        if (event == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (event.getTypeAsText() != null) {
            sb.append(event.getTypeAsText()).append(' ');
        }
        if (event.getReadableData() != null) {
            sb.append(event.getReadableData()).append(' ');
        }
        if (event.getData() != null) {
            sb.append(event.getData());
        }
        return sb.toString();
    }

    private static boolean isCassetteOutEvent(String blob) {
        String lower = blob.toLowerCase(Locale.ROOT);
        if (!lower.contains("cassette")) {
            return false;
        }
        return lower.contains("removed")
                || lower.contains("eject")
                || lower.contains("cassette out")
                || lower.matches(".*\\bout\\b.*");
    }

    private static boolean isCassetteInEvent(String blob) {
        String lower = blob.toLowerCase(Locale.ROOT);
        if (!lower.contains("cassette")) {
            return false;
        }
        return lower.contains("insert")
                || lower.contains("cassette in")
                || lower.matches(".*\\bin\\b.*");
    }

    private static String[] splitTokens(String cassetteInfo) {
        return cassetteInfo.trim().split("[,;|\\s]+");
    }

    private static Integer parseSlotToken(String token) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        Matcher dispenserMatcher = DISPENSER_CASSETTE_NAME.matcher(trimmed);
        if (dispenserMatcher.find()) {
            return parseSlotNumber(dispenserMatcher.group(1));
        }

        Matcher cassetteMatcher = CASSETTE_SLOT_TOKEN.matcher(trimmed);
        if (cassetteMatcher.matches()) {
            return parseSlotNumber(cassetteMatcher.group(1));
        }

        if (trimmed.matches("\\d+")) {
            return parseSlotNumber(trimmed);
        }

        return null;
    }

    private static Integer parseSlotNumber(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            int slot = Integer.parseInt(raw.trim());
            return slot >= 1 && slot <= 9 ? slot : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String serialKey(String serialNumber) {
        return serialNumber != null ? serialNumber.trim().toUpperCase(Locale.ROOT) : "";
    }
}
