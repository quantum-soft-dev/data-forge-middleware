package com.bitbi.dfm.shared.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audited token type must be derived from the token, not from something the caller can assert.
 * <p>
 * Detection used to short-circuit on an {@code X-Keycloak-Token} request header, which any client
 * can set: the token type recorded in the audit trail was therefore caller-controlled. Keycloak is
 * also gone, so RSA-signed tokens are Auth0's.
 * </p>
 */
@DisplayName("AuthenticationAuditLogger — token type detection")
class AuthenticationAuditLoggerTest {

    private final AuthenticationAuditLogger auditLogger = new AuthenticationAuditLogger();

    private static String tokenWithHeader(String headerJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(headerJson.getBytes())
                + "." + encoder.encodeToString("{\"sub\":\"test\"}".getBytes())
                + ".signature";
    }

    private static MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    @Test
    @DisplayName("HMAC-signed tokens are the custom client JWT")
    void shouldDetectCustomJwt() {
        String token = tokenWithHeader("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");

        assertThat(auditLogger.detectTokenType(requestWithBearer(token))).isEqualTo("jwt");
    }

    @Test
    @DisplayName("RSA-signed tokens are Auth0's")
    void shouldDetectAuth0Token() {
        String token = tokenWithHeader("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");

        assertThat(auditLogger.detectTokenType(requestWithBearer(token))).isEqualTo("auth0");
    }

    @Test
    @DisplayName("a request without a bearer token is unknown")
    void shouldReportUnknownWithoutAToken() {
        assertThat(auditLogger.detectTokenType(new MockHttpServletRequest())).isEqualTo("unknown");
    }

    @Test
    @DisplayName("an unparsable token is unknown")
    void shouldReportUnknownForGarbage() {
        assertThat(auditLogger.detectTokenType(requestWithBearer("not-a-jwt"))).isEqualTo("unknown");
    }

    @Test
    @DisplayName("a caller-supplied header cannot dictate the token type")
    void shouldIgnoreCallerSuppliedTokenTypeHeader() {
        MockHttpServletRequest request = requestWithBearer(tokenWithHeader("{\"alg\":\"HS256\",\"typ\":\"JWT\"}"));
        request.addHeader("X-Keycloak-Token", "anything");

        assertThat(auditLogger.detectTokenType(request))
                .as("the audit trail must reflect the token, not a header the client controls")
                .isEqualTo("jwt");
    }
}
