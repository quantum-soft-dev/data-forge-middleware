package com.bitbi.dfm.shared.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Delta v2 gRPC server carries the site Bearer token in gRPC metadata
 * ({@code DeltaAuthInterceptor}). Running it without TLS puts that token on the wire in cleartext,
 * which is a legitimate deployment when TLS is terminated in front of the server (GKE Gateway,
 * ingress or sidecar) — but it must be visible in the logs rather than silently assumed.
 * <p>
 * Warning only, never fail-fast: hard-failing would break every deployment that terminates TLS
 * upstream, which is the normal topology here.
 * </p>
 */
@DisplayName("Delta gRPC plaintext transport warning")
class SecurityConfigValidatorGrpcTlsTest {

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(SecurityConfigValidator.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private SecurityConfigValidator validatorWith(boolean grpcEnabled, boolean tlsEnabled) {
        SecurityConfigValidator validator = new SecurityConfigValidator();
        ReflectionTestUtils.setField(validator, "grpcEnabled", grpcEnabled);
        ReflectionTestUtils.setField(validator, "grpcTlsEnabled", tlsEnabled);
        ReflectionTestUtils.setField(validator, "grpcPort", 9090);
        return validator;
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("warns when gRPC is enabled without TLS")
    void shouldWarnWhenGrpcEnabledWithoutTls() {
        SecurityConfigValidator validator = validatorWith(true, false);

        assertDoesNotThrow(validator::warnIfGrpcTransportIsPlaintext,
                "this is a warning, not a fail-fast check");

        List<ILoggingEvent> warnings = warnings();
        assertEquals(1, warnings.size(), "expected exactly one WARN line");

        String message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("delta.grpc.tls.enabled"),
                "the warning must name the property that enables TLS: " + message);
        assertTrue(message.contains("delta.grpc.tls.cert-path") && message.contains("delta.grpc.tls.key-path"),
                "the warning must name the cert/key properties: " + message);
        assertTrue(message.toLowerCase().contains("cleartext") || message.toLowerCase().contains("plaintext"),
                "the warning must say the token travels unencrypted: " + message);
        assertTrue(message.contains("9090"), "the warning should name the port: " + message);
    }

    @Test
    @DisplayName("stays quiet when gRPC is enabled with TLS")
    void shouldNotWarnWhenTlsEnabled() {
        SecurityConfigValidator validator = validatorWith(true, true);

        assertDoesNotThrow(validator::warnIfGrpcTransportIsPlaintext);

        assertTrue(warnings().isEmpty(), "TLS is on — nothing to warn about");
    }

    @Test
    @DisplayName("stays quiet when gRPC is disabled, whatever the TLS flag says")
    void shouldNotWarnWhenGrpcDisabled() {
        for (boolean tlsEnabled : new boolean[]{false, true}) {
            appender.list.clear();
            SecurityConfigValidator validator = validatorWith(false, tlsEnabled);

            assertDoesNotThrow(validator::warnIfGrpcTransportIsPlaintext);

            assertTrue(warnings().isEmpty(),
                    "no gRPC server is bound (tls=" + tlsEnabled + ") — nothing to warn about");
        }
    }
}
