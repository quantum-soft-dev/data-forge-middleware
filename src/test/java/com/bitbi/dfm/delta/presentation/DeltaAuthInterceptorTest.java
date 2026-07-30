package com.bitbi.dfm.delta.presentation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bitbi.dfm.auth.application.TokenService;
import io.grpc.Attributes;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * T1.2 — gRPC auth interceptor: validates the Bearer token via Auth V2 {@link TokenService},
 * binds the resolved siteId to the gRPC context, and rejects missing/invalid tokens.
 *
 * <p>Issue #83: every rejection must also leave a WARN behind, or a client that never gets past
 * authentication is indistinguishable from one that never called at all.</p>
 */
class DeltaAuthInterceptorTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String METHOD = "dfm.delta.v2.DeltaIngestion/StreamChanges";

    private final TokenService tokenService = mock(TokenService.class);
    private final DeltaAuthInterceptor interceptor = new DeltaAuthInterceptor(tokenService);

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(DeltaAuthInterceptor.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * A call carrying what a real one carries: the method being invoked and the transport
     * attributes the interceptor reports in the audit line.
     */
    private static ServerCall<Object, Object> mockCall() {
        @SuppressWarnings("unchecked")
        ServerCall<Object, Object> call = mock(ServerCall.class);
        when(call.getMethodDescriptor()).thenReturn(methodDescriptor());
        when(call.getAttributes()).thenReturn(Attributes.EMPTY);
        return call;
    }

    private static MethodDescriptor<Object, Object> methodDescriptor() {
        MethodDescriptor.Marshaller<Object> marshaller = new MethodDescriptor.Marshaller<>() {
            @Override
            public InputStream stream(Object value) {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public Object parse(InputStream stream) {
                return new Object();
            }
        };
        return MethodDescriptor.<Object, Object>newBuilder()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setFullMethodName(METHOD)
                .setRequestMarshaller(marshaller)
                .setResponseMarshaller(marshaller)
                .build();
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    @Test
    void validTokenBindsSiteAndAccountToContextAndProceeds() {
        UUID siteId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(tokenService.validateToken("good-token")).thenReturn(siteId);
        when(tokenService.extractAccountId("good-token")).thenReturn(accountId);

        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer good-token");

        AtomicReference<UUID> boundSiteId = new AtomicReference<>();
        AtomicReference<UUID> boundAccountId = new AtomicReference<>();
        ServerCallHandler<Object, Object> next = (call, md) -> {
            boundSiteId.set(DeltaAuthInterceptor.SITE_ID.get());
            boundAccountId.set(DeltaAuthInterceptor.ACCOUNT_ID.get());
            return new ServerCall.Listener<>() {
            };
        };

        interceptor.interceptCall(mockCall(), headers, next);

        assertEquals(siteId, boundSiteId.get(), "authenticated siteId must be on the gRPC context");
        assertEquals(accountId, boundAccountId.get(), "authenticated accountId must be on the gRPC context");
    }

    @Test
    void missingTokenIsRejectedAndHandlerNotInvoked() {
        ServerCall<Object, Object> call = mockCall();
        @SuppressWarnings("unchecked")
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);

        interceptor.interceptCall(call, new Metadata(), next);

        assertClosedWith(call, Status.Code.UNAUTHENTICATED);
        verifyNoInteractions(next);
    }

    @Test
    void nonBearerSchemeIsRejected() {
        ServerCall<Object, Object> call = mockCall();
        @SuppressWarnings("unchecked")
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Basic abc");

        interceptor.interceptCall(call, headers, next);

        assertClosedWith(call, Status.Code.UNAUTHENTICATED);
        verifyNoInteractions(next);
    }

    @Test
    void invalidOrExpiredTokenIsRejected() {
        when(tokenService.validateToken("bad"))
                .thenThrow(new TokenService.InvalidTokenException("Invalid or expired token"));
        ServerCall<Object, Object> call = mockCall();
        @SuppressWarnings("unchecked")
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer bad");

        interceptor.interceptCall(call, headers, next);

        assertClosedWith(call, Status.Code.UNAUTHENTICATED);
        verifyNoInteractions(next);
    }

    @Test
    void missingTokenIsLoggedAtWarnWithTheMethodAndReason() {
        interceptor.interceptCall(mockCall(), new Metadata(), mock(ServerCallHandler.class));

        assertEquals(1, warnings().size(), "a rejected call must leave exactly one WARN");
        String message = warnings().getFirst().getFormattedMessage();
        assertTrue(message.contains(METHOD), "the WARN must name the gRPC method: " + message);
        assertTrue(message.contains("Missing Bearer token"), "the WARN must carry the reason: " + message);
    }

    @Test
    void invalidTokenIsLoggedAtWarnWithTheMethodAndReason() {
        when(tokenService.validateToken("bad"))
                .thenThrow(new TokenService.InvalidTokenException("Invalid or expired token"));
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer bad");

        interceptor.interceptCall(mockCall(), headers, mock(ServerCallHandler.class));

        assertEquals(1, warnings().size(), "a rejected call must leave exactly one WARN");
        String message = warnings().getFirst().getFormattedMessage();
        assertTrue(message.contains(METHOD), "the WARN must name the gRPC method: " + message);
        assertTrue(message.contains("Invalid or expired token"), "the WARN must carry the reason: " + message);
    }

    @Test
    void acceptedCallIsNotLoggedAsAFailure() {
        when(tokenService.validateToken("good-token")).thenReturn(UUID.randomUUID());
        when(tokenService.extractAccountId("good-token")).thenReturn(UUID.randomUUID());
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer good-token");

        interceptor.interceptCall(mockCall(), headers, (call, md) -> new ServerCall.Listener<>() {
        });

        assertEquals(List.of(), warnings(), "an authenticated call must not be logged as a failure");
    }

    private static void assertClosedWith(ServerCall<Object, Object> call, Status.Code expected) {
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertEquals(expected, status.getValue().getCode());
    }
}
