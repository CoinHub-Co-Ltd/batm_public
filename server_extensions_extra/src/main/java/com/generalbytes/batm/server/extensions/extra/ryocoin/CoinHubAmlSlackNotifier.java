package com.generalbytes.batm.server.extensions.extra.ryocoin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posts an AML pass/reject notice to a Slack incoming webhook.
 * Disabled when {@code slack_webhook_url} is blank.
 */
public final class CoinHubAmlSlackNotifier {
    private static final Logger log = LoggerFactory.getLogger(CoinHubAmlSlackNotifier.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String webhookUrl;
    private final ExecutorService executor;

    CoinHubAmlSlackNotifier(String webhookUrl) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        if (this.webhookUrl.isEmpty()) {
            this.executor = null;
            log.info("[Security] AML Slack notifications disabled (coinhub slack_webhook_url is blank)");
        } else {
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "coinhub-aml-slack");
                thread.setDaemon(true);
                return thread;
            });
            log.info("[Security] AML Slack notifications enabled");
        }
    }

    void notify(String receipt, BigDecimal inserted, BigDecimal allowed, String crypto, String side,
                boolean passed, String atmCode, String atmMessage) {
        if (executor == null) {
            return;
        }
        final String payload;
        try {
            payload = payload(receipt, inserted, allowed, crypto, side, passed, atmCode, atmMessage);
        } catch (Exception e) {
            log.warn("[Security] AML Slack payload failed receipt={}", receipt, e);
            return;
        }
        final String receiptId = receipt;
        executor.execute(() -> post(receiptId, payload));
    }

    /** Visible Slack text for the sample pass or reject message. */
    public static String sampleMessage(boolean passed) throws Exception {
        return JSON.readTree(samplePayload(passed)).path("text").asText();
    }

    /** Sample payload used by the command-line tester. */
    public static String samplePayload(boolean passed) throws Exception {
        if (passed) {
            return payload("RDKRR4", new BigDecimal("10000"), new BigDecimal("90000"),
                "BTC", "buy", true, "", "");
        }
        return payload("RDKRR4", new BigDecimal("10000"), new BigDecimal("100000"),
            "BTC", "buy", false, "00009",
            "The 3-hour limit of 10000 JPY has been reached. Please try again later.");
    }

    public static SendResult postPayload(String webhookUrl, String payload) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(webhookUrl).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
            return new SendResult(status, readStream(stream));
        } finally {
            connection.disconnect();
        }
    }

    public static final class SendResult {
        public final int status;
        public final String body;

        public SendResult(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    private static String payload(String receipt, BigDecimal inserted, BigDecimal allowed, String crypto, String side,
                                  boolean passed, String atmCode, String atmMessage) throws Exception {
        String receiptId = receipt == null ? "" : receipt;
        String title = "[Transaction] Receipt " + receiptId;
        String context = contextJson(receiptId, inserted, allowed, crypto, side, passed, atmCode, atmMessage);

        ObjectNode root = JSON.createObjectNode();
        root.put("text", title + "\n" + context);

        ArrayNode blocks = root.putArray("blocks");
        ObjectNode titleBlock = blocks.addObject();
        titleBlock.put("type", "section");
        ObjectNode titleText = titleBlock.putObject("text");
        titleText.put("type", "plain_text");
        titleText.put("text", title);
        titleText.put("emoji", false);

        ObjectNode jsonBlock = blocks.addObject();
        jsonBlock.put("type", "section");
        ObjectNode jsonText = jsonBlock.putObject("text");
        jsonText.put("type", "mrkdwn");
        jsonText.put("text", "```\n" + context + "\n```");

        return JSON.writeValueAsString(root);
    }

    private static String contextJson(String receipt, BigDecimal inserted, BigDecimal allowed, String crypto,
                                      String side, boolean passed, String atmCode, String atmMessage) {
        StringBuilder body = new StringBuilder();
        body.append("{\n");
        field(body, "Receipt #", quote(receipt), true);
        field(body, "inserted_amount", number(inserted), true);
        field(body, "allowed_amount_in_3_hours", number(allowed), true);
        field(body, "crypto", quote(crypto), true);
        field(body, "side", quote(side), true);
        field(body, "aml-status", quote(passed ? "passed" : "rejected"), true);
        field(body, "atm-code", quote(atmCode), true);
        field(body, "atm-message", quote(atmMessage), false);
        body.append("}");
        return body.toString();
    }

    private static void field(StringBuilder body, String name, String rawValue, boolean comma) {
        body.append("   \"").append(escape(name)).append("\": ").append(rawValue);
        if (comma) {
            body.append(',');
        }
        body.append('\n');
    }

    private static String quote(String value) {
        return "\"" + escape(value == null ? "" : value) + "\"";
    }

    private static String number(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal plain = value.stripTrailingZeros();
        if (plain.scale() < 0) {
            plain = plain.setScale(0);
        }
        return plain.toPlainString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    private void post(String receipt, String payload) {
        try {
            SendResult result = postPayload(webhookUrl, payload);
            if (result.status >= 200 && result.status < 300) {
                log.info("[Security] AML Slack sent receipt={} status={}", receipt, result.status);
            } else {
                log.warn("[Security] AML Slack failed receipt={} status={} body={}", receipt, result.status, result.body);
            }
        } catch (Exception e) {
            log.warn("[Security] AML Slack failed receipt={}", receipt, e);
        }
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }
}
