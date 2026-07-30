package com.wzx.babiq.server.business.oa.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public final class OaPasswordEncoder {
    private OaPasswordEncoder() {}
    public static String encode(char[] password) {
        try {
            if (password == null || password.length < 8 || password.length > 16
                    || !hasLetterAndDigit(password) || containsNonAscii(password)
                    || !isAsciiAlphaNumeric(password))
                throw new OaAuthenticationException(OaAuthenticationError.INVALID_PASSWORD_FORMAT);
            String raw = new String(password);
            String first = md5(raw + "huitaisystem");
            return md5(first);
        } finally { if (password != null) Arrays.fill(password, '\0'); }
    }
    private static boolean hasLetterAndDigit(char[] value) {
        boolean letter = false, digit = false;
        for (char c : value) { letter |= Character.isLetter(c); digit |= Character.isDigit(c); }
        return letter && digit;
    }
    private static boolean containsNonAscii(char[] value) {
        for (char c : value) if (c > 0x7f) return true;
        return false;
    }
    private static boolean isAsciiAlphaNumeric(char[] value) {
        for (char c : value) {
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }
    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException("MD5 unavailable", e); }
    }
}
