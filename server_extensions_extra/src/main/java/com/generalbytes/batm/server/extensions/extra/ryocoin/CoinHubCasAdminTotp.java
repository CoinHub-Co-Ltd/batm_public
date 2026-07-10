/*************************************************************************************
 * RFC 6238 TOTP (Google Authenticator) for CAS Admin login.
 ************************************************************************************/
package com.generalbytes.batm.server.extensions.extra.ryocoin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;

final class CoinHubCasAdminTotp {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;

    private CoinHubCasAdminTotp() {
    }

    static String normalizeSecret(String secret) {
        if (secret == null) {
            return "";
        }
        String normalized = secret.toUpperCase().replaceAll("\\s+", "");
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.matches("^[A-Z2-7]+=*$") ? normalized.replace("=", "") : "";
    }

    static String currentCode(String base32Secret) throws IllegalArgumentException {
        String secret = normalizeSecret(base32Secret);
        if (secret.isEmpty()) {
            throw new IllegalArgumentException("Invalid TOTP secret.");
        }
        long counter = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;
        return generateCode(decodeBase32(secret), counter);
    }

    private static String generateCode(byte[] key, long counter) {
        try {
            byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counterBytes);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to generate TOTP code.", e);
        }
    }

    private static byte[] decodeBase32(String input) {
        String normalized = input.toUpperCase().replace("=", "");
        if (normalized.isEmpty()) {
            return new byte[0];
        }

        int buffer = 0;
        int bitsLeft = 0;
        byte[] output = new byte[normalized.length() * 5 / 8 + 1];
        int index = 0;

        for (int i = 0; i < normalized.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(normalized.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Invalid base32 character in TOTP secret.");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }

        byte[] trimmed = new byte[index];
        System.arraycopy(output, 0, trimmed, 0, index);
        return trimmed;
    }
}
