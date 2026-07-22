package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * T1.2 — gRPC auth interceptor: validates the Bearer token via Auth V2 {@link TokenService},
 * binds the resolved siteId to the gRPC context, and rejects missing/invalid tokens.
 */
class DeltaAuthInterceptorTest {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final TokenService tokenService = mock(TokenService.class);
    private final SiteService siteService = mock(SiteService.class);
    private final DeltaAuthInterceptor interceptor = new DeltaAuthInterceptor(tokenService, siteService);

    private Site v2Site() {
        Site site = mock(Site.class);
        when(site.isDeltaV2()).thenReturn(true);
        return site;
    }

    @SuppressWarnings("unchecked")
    private static ServerCall<Object, Object> mockCall() {
        return mock(ServerCall.class);
    }

    @Test
    void validTokenBindsSiteAndAccountToContextAndProceeds() {
        UUID siteId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Site site = v2Site();
        when(tokenService.validateToken("good-token")).thenReturn(siteId);
        when(tokenService.extractAccountId("good-token")).thenReturn(accountId);
        when(siteService.getSite(siteId)).thenReturn(site);

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
    void v1SiteTokenIsRejectedWithPermissionDenied() {
        // A legacy v1 site's token authenticates (same claims as v2) but must not open a delta
        // session — the surface is scoped to Delta v2 sites (review r4).
        UUID siteId = UUID.randomUUID();
        when(tokenService.validateToken("v1-token")).thenReturn(siteId);
        when(tokenService.extractAccountId("v1-token")).thenReturn(UUID.randomUUID());
        Site v1Site = mock(Site.class);
        when(v1Site.isDeltaV2()).thenReturn(false);
        when(siteService.getSite(siteId)).thenReturn(v1Site);

        ServerCall<Object, Object> call = mockCall();
        @SuppressWarnings("unchecked")
        ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);
        Metadata headers = new Metadata();
        headers.put(AUTHORIZATION, "Bearer v1-token");

        interceptor.interceptCall(call, headers, next);

        assertClosedWith(call, Status.Code.PERMISSION_DENIED);
        verifyNoInteractions(next);
    }

    private static void assertClosedWith(ServerCall<Object, Object> call, Status.Code expected) {
        ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
        verify(call).close(status.capture(), any(Metadata.class));
        assertEquals(expected, status.getValue().getCode());
    }
}
