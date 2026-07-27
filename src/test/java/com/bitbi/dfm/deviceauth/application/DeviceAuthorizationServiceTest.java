package com.bitbi.dfm.deviceauth.application;

import com.bitbi.dfm.auth.application.RefreshTokenService;
import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.auth.infrastructure.JwtTokenProvider;
import com.bitbi.dfm.deviceauth.domain.DeviceAuthorization;
import com.bitbi.dfm.deviceauth.domain.DeviceAuthorizationRepository;
import com.bitbi.dfm.deviceauth.domain.DeviceAuthorizationStatus;
import com.bitbi.dfm.site.application.SiteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DeviceAuthorizationService token polling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceAuthorizationService")
class DeviceAuthorizationServiceTest {

    private static final String DEVICE_CODE = "device-code-0123456789";

    @Mock
    private DeviceAuthorizationRepository repository;

    @Mock
    private SiteService siteService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    private DeviceAuthorizationService service;

    private UUID siteId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        siteId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        service = new DeviceAuthorizationService(repository, siteService, jwtTokenProvider, refreshTokenService);
    }

    @Test
    @DisplayName("Should report the refresh token expiry stored with the issued token")
    void shouldReportStoredRefreshTokenExpiry() {
        Instant storedExpiry = Instant.parse("2030-03-04T05:06:07Z");

        DeviceAuthorization authorization = mock(DeviceAuthorization.class);
        when(authorization.isExpired()).thenReturn(false);
        when(authorization.getStatus()).thenReturn(DeviceAuthorizationStatus.APPROVED);
        when(authorization.getSiteId()).thenReturn(siteId);
        when(authorization.getAccountId()).thenReturn(accountId);
        when(authorization.getSiteName()).thenReturn("store-99");

        JwtToken accessToken = mock(JwtToken.class);
        when(accessToken.token()).thenReturn("access.jwt.token");
        when(accessToken.expiresAt()).thenReturn(Instant.parse("2030-03-04T05:21:07Z"));

        when(repository.findByDeviceCode(DEVICE_CODE)).thenReturn(Optional.of(authorization));
        when(jwtTokenProvider.generateToken(siteId, accountId)).thenReturn(accessToken);
        when(refreshTokenService.issueRefreshToken(siteId))
                .thenReturn(new RefreshTokenService.IssuedRefreshToken("opaque-refresh-token", storedExpiry));

        DeviceAuthorizationService.TokenResult result = service.getToken(DEVICE_CODE);

        assertThat(result).isInstanceOf(DeviceAuthorizationService.TokenResult.Success.class);
        DeviceAuthorizationService.TokenResult.Success success =
                (DeviceAuthorizationService.TokenResult.Success) result;

        assertThat(success.refreshToken()).isEqualTo("opaque-refresh-token");
        // The expiry must come from the persisted token, not a recomputed "now + TTL"
        assertThat(success.refreshTokenExpiresAt()).isEqualTo(storedExpiry);
    }
}
