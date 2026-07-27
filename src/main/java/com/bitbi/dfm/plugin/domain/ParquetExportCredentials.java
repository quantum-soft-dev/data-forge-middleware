package com.bitbi.dfm.plugin.domain;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * Freshly minted Basic Auth credentials for the Parquet Export plugin (028).
 * <p>
 * The login ({@code pex_} + 12 alphanumerics) is a stable public identifier stored in plain form;
 * the password (32 alphanumerics) exists in raw form only inside this object — persistence keeps
 * a BCrypt hash, and the raw value is shown to the owner exactly once.
 * </p>
 */
public record ParquetExportCredentials(String login, String password) {

    private static final String LOGIN_PREFIX = "pex_";
    private static final int LOGIN_LENGTH = 12;
    private static final int PASSWORD_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    public ParquetExportCredentials {
        Objects.requireNonNull(login, "login cannot be null");
        Objects.requireNonNull(password, "password cannot be null");
    }

    public static ParquetExportCredentials generate() {
        return new ParquetExportCredentials(LOGIN_PREFIX + randomAlphanumeric(LOGIN_LENGTH),
                randomAlphanumeric(PASSWORD_LENGTH));
    }

    /**
     * The {@code login:password} userinfo form — what the owner pastes into a Basic Auth client,
     * and the single-shot secret returned through the activation response.
     */
    public String basicAuthValue() {
        return login + ":" + password;
    }

    private static String randomAlphanumeric(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return value.toString();
    }

    @Override
    public String toString() {
        return "ParquetExportCredentials[login=" + login + ", password=****]";
    }
}
