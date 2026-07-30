package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.SocketAddress;
import java.util.UUID;

/**
 * gRPC server interceptor for the Delta Client v2 ingestion service (feature 022).
 *
 * <p>Authenticates each call using the Auth V2 Bearer token carried in the {@code authorization}
 * metadata header (reusing {@link TokenService#validateToken(String)}), and binds the resolved
 * site identifier to the gRPC {@link Context} under {@link #SITE_ID}. Calls without a valid Bearer
 * token are closed with {@link Status#UNAUTHENTICATED} and never reach the handler.</p>
 *
 * <p>The authenticated {@link #SITE_ID} is the only site a call may act on; downstream handlers
 * read it from the context (cross-site requests are rejected by comparing against it).</p>
 *
 * <p>Every rejection is logged at WARN (#83), mirroring
 * {@link com.bitbi.dfm.shared.auth.AuthenticationAuditLogger} on the REST side. Without it a client
 * stuck on authentication leaves no trace at all — the call never reaches a handler, so the only
 * evidence is a gRPC status the server never records.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaAuthInterceptor implements ServerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(DeltaAuthInterceptor.class);

    /** gRPC context key holding the authenticated site identifier for the current call. */
    public static final Context.Key<UUID> SITE_ID = Context.key("delta-site-id");

    /** gRPC context key holding the authenticated account identifier for the current call. */
    public static final Context.Key<UUID> ACCOUNT_ID = Context.key("delta-account-id");

    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    public DeltaAuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String authorization = headers.get(AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthenticated(call, "Missing Bearer token");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        UUID siteId;
        UUID accountId;
        try {
            siteId = tokenService.validateToken(token);
            accountId = tokenService.extractAccountId(token);
        } catch (TokenService.InvalidTokenException | TokenService.AuthenticationException e) {
            return unauthenticated(call, "Invalid or expired token");
        }

        Context context = Context.current()
                .withValue(SITE_ID, siteId)
                .withValue(ACCOUNT_ID, accountId);
        return Contexts.interceptCall(context, call, headers, next);
    }

    /**
     * Close the call as unauthenticated. Every rejection path funnels through here, so the audit
     * line cannot be forgotten on a new one.
     */
    private <ReqT, RespT> ServerCall.Listener<ReqT> unauthenticated(ServerCall<ReqT, RespT> call, String reason) {
        SocketAddress peer = call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
        logger.warn("auth_failure: gRPC {} rejected: {} (peer={})",
                call.getMethodDescriptor().getFullMethodName(), reason, peer);
        call.close(Status.UNAUTHENTICATED.withDescription(reason), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
