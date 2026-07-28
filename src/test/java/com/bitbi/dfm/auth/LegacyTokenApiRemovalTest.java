package com.bitbi.dfm.auth;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.auth.infrastructure.JwtTokenProvider;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyTokenApiRemovalTest {

    @Test
    void shouldRemoveCredentialBasedTokenGeneration() {
        assertThatThrownBy(() ->
                TokenService.class.getDeclaredMethod("generateToken", String.class, String.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void shouldRemoveDomainBearingTokenHelpers() {
        assertThatThrownBy(() ->
                JwtTokenProvider.class.getDeclaredMethod(
                        "generateTokenWithDomain", UUID.class, UUID.class, String.class))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() ->
                JwtTokenProvider.class.getDeclaredMethod("extractDomain", String.class))
                .isInstanceOf(NoSuchMethodException.class);
    }
}
