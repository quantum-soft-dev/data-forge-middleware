package com.bitbi.dfm.plugin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ParquetExportCredentials} (028-parquet-export-plugin, T002).
 */
class ParquetExportCredentialsTest {

    @Test
    @DisplayName("Should generate login with pex_ prefix and 12 alphanumerics")
    void shouldGenerateLoginFormat() {
        ParquetExportCredentials credentials = ParquetExportCredentials.generate();

        assertTrue(credentials.login().matches("^pex_[a-zA-Z0-9]{12}$"),
                "unexpected login format: " + credentials.login());
    }

    @Test
    @DisplayName("Should generate 32-alphanumeric password")
    void shouldGeneratePasswordFormat() {
        ParquetExportCredentials credentials = ParquetExportCredentials.generate();

        assertTrue(credentials.password().matches("^[a-zA-Z0-9]{32}$"),
                "unexpected password format");
    }

    @Test
    @DisplayName("Should format basic auth value as login:password")
    void shouldFormatBasicAuthValue() {
        ParquetExportCredentials credentials = ParquetExportCredentials.generate();

        assertEquals(credentials.login() + ":" + credentials.password(), credentials.basicAuthValue());
    }

    @Test
    @DisplayName("Should mask password in toString")
    void shouldMaskPasswordInToString() {
        ParquetExportCredentials credentials = ParquetExportCredentials.generate();

        assertFalse(credentials.toString().contains(credentials.password()),
                "toString must never leak the raw password");
        assertTrue(credentials.toString().contains(credentials.login()),
                "login is not secret and may appear in toString");
    }

    @Test
    @DisplayName("Should generate distinct credentials across calls")
    void shouldGenerateDistinctCredentials() {
        Set<String> logins = new HashSet<>();
        Set<String> passwords = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ParquetExportCredentials credentials = ParquetExportCredentials.generate();
            logins.add(credentials.login());
            passwords.add(credentials.password());
        }
        assertEquals(100, logins.size());
        assertEquals(100, passwords.size());
    }
}
