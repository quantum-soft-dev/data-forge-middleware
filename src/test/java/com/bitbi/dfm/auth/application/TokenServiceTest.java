package com.bitbi.dfm.auth.application;

import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.domain.SiteAuthenticationRepository;
import com.bitbi.dfm.auth.domain.SiteAuthenticationStatus;
import com.bitbi.dfm.auth.infrastructure.JwtTokenProvider;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TokenService.
 * <p>
 * Focus: the validation hot path, which must re-check both the site and its parent
 * account on every request while staying a single database round-trip.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenService")
class TokenServiceTest {

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private SiteAuthenticationRepository siteAuthenticationRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private TokenService tokenService;

    private UUID siteId;
    private UUID accountId;
    private static final String TOKEN = "some.jwt.token";

    @BeforeEach
    void setUp() {
        siteId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        tokenService = new TokenService(
                siteRepository, accountRepository, siteAuthenticationRepository, jwtTokenProvider);
    }

    @Nested
    @DisplayName("validateToken")
    class ValidateToken {

        @Test
        @DisplayName("Should return site id when both site and account are active")
        void shouldReturnSiteIdWhenSiteAndAccountActive() {
            when(jwtTokenProvider.extractSiteId(TOKEN)).thenReturn(siteId);
            when(siteAuthenticationRepository.findAuthenticationStatus(siteId))
                    .thenReturn(Optional.of(new SiteAuthenticationStatus(siteId, accountId, true, true)));

            assertThat(tokenService.validateToken(TOKEN)).isEqualTo(siteId);
        }

        @Test
        @DisplayName("Should reject token when the parent account is deactivated")
        void shouldRejectWhenAccountInactive() {
            when(jwtTokenProvider.extractSiteId(TOKEN)).thenReturn(siteId);
            when(siteAuthenticationRepository.findAuthenticationStatus(siteId))
                    .thenReturn(Optional.of(new SiteAuthenticationStatus(siteId, accountId, true, false)));

            assertThatThrownBy(() -> tokenService.validateToken(TOKEN))
                    .isInstanceOf(TokenService.InvalidTokenException.class)
                    .hasMessageContaining("Account is not active");
        }

        @Test
        @DisplayName("Should reject token when the site is deactivated")
        void shouldRejectWhenSiteInactive() {
            when(jwtTokenProvider.extractSiteId(TOKEN)).thenReturn(siteId);
            when(siteAuthenticationRepository.findAuthenticationStatus(siteId))
                    .thenReturn(Optional.of(new SiteAuthenticationStatus(siteId, accountId, false, true)));

            assertThatThrownBy(() -> tokenService.validateToken(TOKEN))
                    .isInstanceOf(TokenService.InvalidTokenException.class)
                    .hasMessageContaining("Site is not active");
        }

        @Test
        @DisplayName("Should reject token when the site no longer exists")
        void shouldRejectWhenSiteNotFound() {
            when(jwtTokenProvider.extractSiteId(TOKEN)).thenReturn(siteId);
            when(siteAuthenticationRepository.findAuthenticationStatus(siteId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> tokenService.validateToken(TOKEN))
                    .isInstanceOf(TokenService.InvalidTokenException.class)
                    .hasMessageContaining("Site not found");
        }

        @Test
        @DisplayName("Should reject a malformed or expired JWT")
        void shouldRejectInvalidJwt() {
            when(jwtTokenProvider.extractSiteId(TOKEN))
                    .thenThrow(new JwtTokenProvider.InvalidTokenException("bad token", new RuntimeException()));

            assertThatThrownBy(() -> tokenService.validateToken(TOKEN))
                    .isInstanceOf(TokenService.InvalidTokenException.class)
                    .hasMessageContaining("Invalid or expired token");
        }

        @Test
        @DisplayName("Should resolve site and account in a single query (hot path)")
        void shouldResolveSiteAndAccountInSingleQuery() {
            when(jwtTokenProvider.extractSiteId(TOKEN)).thenReturn(siteId);
            when(siteAuthenticationRepository.findAuthenticationStatus(siteId))
                    .thenReturn(Optional.of(new SiteAuthenticationStatus(siteId, accountId, true, true)));

            tokenService.validateToken(TOKEN);

            verify(siteAuthenticationRepository, times(1)).findAuthenticationStatus(siteId);
            // validateToken runs on every client API request - no extra round-trips allowed
            verify(siteRepository, never()).findById(any());
            verify(accountRepository, never()).findById(any());
        }
    }
}
