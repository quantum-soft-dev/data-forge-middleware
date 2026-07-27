package com.bitbi.dfm.auth.application;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.auth.domain.RefreshToken;
import com.bitbi.dfm.auth.domain.RefreshTokenRepository;
import com.bitbi.dfm.auth.infrastructure.JwtTokenProvider;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RefreshTokenService.
 * <p>
 * Tests refresh token lifecycle: generation, rotation, revocation, cleanup.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenService")
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private RefreshTokenService refreshTokenService;

    private UUID siteId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        siteId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        refreshTokenService = new RefreshTokenService(
                refreshTokenRepository, siteRepository, accountRepository, jwtTokenProvider);
    }

    @Nested
    @DisplayName("generateRefreshToken")
    class GenerateRefreshToken {

        @Test
        @DisplayName("Should generate opaque token and store SHA-256 hash")
        void shouldGenerateOpaqueTokenAndStoreHash() {
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            String opaqueToken = refreshTokenService.generateRefreshToken(siteId);

            assertThat(opaqueToken).isNotBlank();
            // URL-safe Base64 encoded 32 bytes = 43 chars
            assertThat(opaqueToken).hasSize(43);

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(captor.capture());

            RefreshToken saved = captor.getValue();
            assertThat(saved.getSiteId()).isEqualTo(siteId);
            assertThat(saved.getTokenHash()).isNotBlank();
            assertThat(saved.getTokenHash()).hasSize(64); // SHA-256 hex = 64 chars
            assertThat(saved.isExpired()).isFalse();
            assertThat(saved.isRevoked()).isFalse();
        }

        @Test
        @DisplayName("Should generate unique tokens on successive calls")
        void shouldGenerateUniqueTokens() {
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            String token1 = refreshTokenService.generateRefreshToken(siteId);
            String token2 = refreshTokenService.generateRefreshToken(siteId);

            assertThat(token1).isNotEqualTo(token2);
        }
    }

    @Nested
    @DisplayName("issueRefreshToken")
    class IssueRefreshToken {

        @Test
        @DisplayName("Should return the opaque token together with the stored expiry")
        void shouldReturnTokenWithStoredExpiry() {
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            RefreshTokenService.IssuedRefreshToken issued = refreshTokenService.issueRefreshToken(siteId);

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(captor.capture());

            assertThat(issued.token()).isNotBlank();
            assertThat(issued.expiresAt()).isEqualTo(captor.getValue().getExpiresAt());
        }
    }

    @Nested
    @DisplayName("refreshAccessToken")
    class RefreshAccessToken {

        @Test
        @DisplayName("Should refresh token successfully with rotation")
        void shouldRefreshTokenSuccessfully() {
            String opaqueToken = "test-opaque-token";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            RefreshToken existingToken = RefreshToken.create(siteId, tokenHash);
            Site mockSite = mock(Site.class);
            Account mockAccount = mock(Account.class);
            JwtToken mockJwt = mock(JwtToken.class);

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(existingToken));
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
            when(mockSite.getIsActive()).thenReturn(true);
            when(mockSite.getAccountId()).thenReturn(accountId);
            when(accountRepository.findById(accountId)).thenReturn(Optional.of(mockAccount));
            when(mockAccount.getIsActive()).thenReturn(true);
            when(jwtTokenProvider.generateToken(siteId, accountId)).thenReturn(mockJwt);
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            RefreshTokenService.RefreshResult result =
                    refreshTokenService.refreshAccessToken(opaqueToken);

            assertThat(result.accessToken()).isEqualTo(mockJwt);
            assertThat(result.refreshToken()).isNotBlank();
            assertThat(result.refreshTokenExpiresAt()).isAfter(Instant.now());

            // Verify old token was revoked
            assertThat(existingToken.isRevoked()).isTrue();

            // Verify new token saved (revoked old + new)
            verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("Should report the expiry stored on the persisted refresh token")
        void shouldReportPersistedExpiry() {
            String opaqueToken = "rotating-token";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            RefreshToken existingToken = RefreshToken.create(siteId, tokenHash);
            Site mockSite = mock(Site.class);
            Account mockAccount = mock(Account.class);

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(existingToken));
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
            when(mockSite.getIsActive()).thenReturn(true);
            when(mockSite.getAccountId()).thenReturn(accountId);
            when(accountRepository.findById(accountId)).thenReturn(Optional.of(mockAccount));
            when(mockAccount.getIsActive()).thenReturn(true);
            when(jwtTokenProvider.generateToken(siteId, accountId)).thenReturn(mock(JwtToken.class));
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            RefreshTokenService.RefreshResult result =
                    refreshTokenService.refreshAccessToken(opaqueToken);

            ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository, times(2)).save(captor.capture());
            RefreshToken issuedToken = captor.getAllValues().get(1);

            // Must be the stored value, not an independently recomputed "now + TTL"
            assertThat(result.refreshTokenExpiresAt()).isEqualTo(issuedToken.getExpiresAt());
        }

        @Test
        @DisplayName("Should throw InvalidRefreshTokenException for unknown token")
        void shouldThrowForUnknownToken() {
            String unknownToken = "unknown-token";
            String tokenHash = RefreshTokenService.sha256(unknownToken);

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> refreshTokenService.refreshAccessToken(unknownToken))
                    .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class)
                    .hasMessageContaining("Invalid refresh token");
        }

        @Test
        @DisplayName("Should throw RefreshTokenRevokedException for revoked token")
        void shouldThrowForRevokedToken() {
            String opaqueToken = "revoked-token";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            RefreshToken revokedToken = RefreshToken.create(siteId, tokenHash);
            revokedToken.revoke();

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(revokedToken));

            assertThatThrownBy(() -> refreshTokenService.refreshAccessToken(opaqueToken))
                    .isInstanceOf(RefreshTokenService.RefreshTokenRevokedException.class)
                    .hasMessageContaining("revoked");
        }

        @Test
        @DisplayName("Should revoke the whole token family when a revoked token is reused (reuse detection)")
        void shouldRevokeWholeFamilyOnReuse() {
            String opaqueToken = "stolen-token";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            RefreshToken rotatedToken = RefreshToken.create(siteId, tokenHash);
            rotatedToken.revoke();

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(rotatedToken));

            assertThatThrownBy(() -> refreshTokenService.refreshAccessToken(opaqueToken))
                    .isInstanceOf(RefreshTokenService.RefreshTokenRevokedException.class);

            // RFC 6819 / OAuth 2.0 Security BCP: reuse of a rotated token means the family is compromised
            verify(refreshTokenRepository).revokeAllBySiteId(siteId);
        }

        @Test
        @DisplayName("Should not revoke the token family on a successful rotation")
        void shouldNotRevokeFamilyOnSuccessfulRotation() {
            String opaqueToken = "healthy-token";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            RefreshToken existingToken = RefreshToken.create(siteId, tokenHash);
            Site mockSite = mock(Site.class);
            Account mockAccount = mock(Account.class);

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(existingToken));
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
            when(mockSite.getIsActive()).thenReturn(true);
            when(mockSite.getAccountId()).thenReturn(accountId);
            when(accountRepository.findById(accountId)).thenReturn(Optional.of(mockAccount));
            when(mockAccount.getIsActive()).thenReturn(true);
            when(jwtTokenProvider.generateToken(siteId, accountId)).thenReturn(mock(JwtToken.class));
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            refreshTokenService.refreshAccessToken(opaqueToken);

            verify(refreshTokenRepository, never()).revokeAllBySiteId(any());
        }

        @Test
        @DisplayName("Should not revoke the token family for an unknown token")
        void shouldNotRevokeFamilyForUnknownToken() {
            String unknownToken = "never-issued";
            when(refreshTokenRepository.findByTokenHash(RefreshTokenService.sha256(unknownToken)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> refreshTokenService.refreshAccessToken(unknownToken))
                    .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class);

            verify(refreshTokenRepository, never()).revokeAllBySiteId(any());
        }

        @Test
        @DisplayName("Should throw RefreshTokenExpiredException for expired token")
        void shouldThrowForExpiredToken() throws Exception {
            String opaqueToken = "expired-token";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            // Create token then make it expired via reflection
            RefreshToken expiredToken = RefreshToken.create(siteId, tokenHash);
            java.lang.reflect.Field expiresAtField = RefreshToken.class.getDeclaredField("expiresAt");
            expiresAtField.setAccessible(true);
            expiresAtField.set(expiredToken, Instant.now().minus(1, ChronoUnit.DAYS));

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(expiredToken));

            assertThatThrownBy(() -> refreshTokenService.refreshAccessToken(opaqueToken))
                    .isInstanceOf(RefreshTokenService.RefreshTokenExpiredException.class)
                    .hasMessageContaining("expired");

            // Expiration is not compromise: the rest of the family must survive
            verify(refreshTokenRepository, never()).revokeAllBySiteId(any());
        }

        @Test
        @DisplayName("Should throw when site not found")
        void shouldThrowWhenSiteNotFound() {
            String opaqueToken = "orphan-token";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            RefreshToken token = RefreshToken.create(siteId, tokenHash);

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(token));
            when(siteRepository.findById(siteId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> refreshTokenService.refreshAccessToken(opaqueToken))
                    .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class)
                    .hasMessageContaining("Site not found");
        }

        @Test
        @DisplayName("Should throw when site is inactive")
        void shouldThrowWhenSiteInactive() {
            String opaqueToken = "inactive-site-token";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            RefreshToken token = RefreshToken.create(siteId, tokenHash);
            Site mockSite = mock(Site.class);

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(token));
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
            when(mockSite.getIsActive()).thenReturn(false);

            assertThatThrownBy(() -> refreshTokenService.refreshAccessToken(opaqueToken))
                    .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class)
                    .hasMessageContaining("not active");
        }

        @Test
        @DisplayName("Should throw when account is inactive")
        void shouldThrowWhenAccountInactive() {
            String opaqueToken = "inactive-account-token";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            RefreshToken token = RefreshToken.create(siteId, tokenHash);
            Site mockSite = mock(Site.class);
            Account mockAccount = mock(Account.class);

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(token));
            when(siteRepository.findById(siteId)).thenReturn(Optional.of(mockSite));
            when(mockSite.getIsActive()).thenReturn(true);
            when(mockSite.getAccountId()).thenReturn(accountId);
            when(accountRepository.findById(accountId)).thenReturn(Optional.of(mockAccount));
            when(mockAccount.getIsActive()).thenReturn(false);

            assertThatThrownBy(() -> refreshTokenService.refreshAccessToken(opaqueToken))
                    .isInstanceOf(RefreshTokenService.InvalidRefreshTokenException.class)
                    .hasMessageContaining("Account is not active");
        }
    }

    @Nested
    @DisplayName("revokeRefreshToken")
    class RevokeRefreshToken {

        @Test
        @DisplayName("Should revoke existing token")
        void shouldRevokeExistingToken() {
            String opaqueToken = "to-revoke";
            String tokenHash = RefreshTokenService.sha256(opaqueToken);

            RefreshToken token = RefreshToken.create(siteId, tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.of(token));

            refreshTokenService.revokeRefreshToken(opaqueToken);

            assertThat(token.isRevoked()).isTrue();
            verify(refreshTokenRepository).save(token);
        }

        @Test
        @DisplayName("Should silently ignore unknown token")
        void shouldIgnoreUnknownToken() {
            String unknownToken = "unknown";
            String tokenHash = RefreshTokenService.sha256(unknownToken);

            when(refreshTokenRepository.findByTokenHash(tokenHash))
                    .thenReturn(Optional.empty());

            refreshTokenService.revokeRefreshToken(unknownToken);

            verify(refreshTokenRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("revokeAllForSite")
    class RevokeAllForSite {

        @Test
        @DisplayName("Should delegate to repository")
        void shouldDelegateToRepository() {
            when(refreshTokenRepository.revokeAllBySiteId(siteId)).thenReturn(3);

            refreshTokenService.revokeAllForSite(siteId);

            verify(refreshTokenRepository).revokeAllBySiteId(siteId);
        }
    }

    @Nested
    @DisplayName("cleanupExpiredTokens")
    class CleanupExpiredTokens {

        @Test
        @DisplayName("Should delete tokens older than 7 days past expiration")
        void shouldDeleteOldTokens() {
            when(refreshTokenRepository.deleteExpiredBefore(any(Instant.class))).thenReturn(5);

            refreshTokenService.cleanupExpiredTokens();

            ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
            verify(refreshTokenRepository).deleteExpiredBefore(captor.capture());

            Instant cutoff = captor.getValue();
            // Cutoff should be ~7 days ago
            assertThat(cutoff).isBetween(
                    Instant.now().minus(7, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES),
                    Instant.now().minus(7, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES)
            );
        }
    }

    @Nested
    @DisplayName("sha256")
    class Sha256 {

        @Test
        @DisplayName("Should produce consistent 64-char hex hash")
        void shouldProduceConsistentHash() {
            String hash1 = RefreshTokenService.sha256("test-input");
            String hash2 = RefreshTokenService.sha256("test-input");

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash1).hasSize(64);
            assertThat(hash1).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("Should produce different hashes for different inputs")
        void shouldProduceDifferentHashes() {
            String hash1 = RefreshTokenService.sha256("input-1");
            String hash2 = RefreshTokenService.sha256("input-2");

            assertThat(hash1).isNotEqualTo(hash2);
        }
    }
}
