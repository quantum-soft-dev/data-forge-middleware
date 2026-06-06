package com.bitbi.dfm.delta.presentation;

import com.bitbi.dfm.auth.application.TokenService;
import io.grpc.*;
import org.springframework.stereotype.Component;

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
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class DeltaAuthInterceptor implements ServerInterceptor {

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

    private <ReqT, RespT> ServerCall.Listener<ReqT> unauthenticated(ServerCall<ReqT, RespT> call, String message) {
        call.close(Status.UNAUTHENTICATED.withDescription(message), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
