package com.bitbi.dfm.deviceauth.presentation;

import com.bitbi.dfm.deviceauth.application.DeviceAuthorizationService;
import com.bitbi.dfm.shared.auth.AuthorizationHelper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceAuthorizationControllerTest {

    @Test
    void shouldReturnGoneWhenVerificationAuthorizationHasExpired() {
        DeviceAuthorizationService service = mock(DeviceAuthorizationService.class);
        AuthorizationHelper authorizationHelper = mock(AuthorizationHelper.class);
        DeviceAuthorizationController controller = new DeviceAuthorizationController(service, authorizationHelper);
        when(authorizationHelper.getAuthenticatedAccountId()).thenReturn(java.util.UUID.randomUUID());
        when(service.getAuthorizationInfo("ABCD-1234"))
                .thenThrow(new DeviceAuthorizationService.AuthorizationExpiredException("Authorization has expired"));

        ResponseEntity<?> response = controller.getVerificationInfo("ABCD-1234");

        assertEquals(HttpStatus.GONE, response.getStatusCode());
    }

    @Test
    void shouldReturnGoneWhenApprovedAuthorizationHasExpired() {
        DeviceAuthorizationService service = mock(DeviceAuthorizationService.class);
        AuthorizationHelper authorizationHelper = mock(AuthorizationHelper.class);
        DeviceAuthorizationController controller = new DeviceAuthorizationController(service, authorizationHelper);
        java.util.UUID accountId = java.util.UUID.randomUUID();
        when(authorizationHelper.getAuthenticatedAccountId()).thenReturn(accountId);
        when(service.verify("ABCD-1234", accountId))
                .thenThrow(new DeviceAuthorizationService.AuthorizationExpiredException("Authorization has expired"));

        ResponseEntity<?> response = controller.verify(
                new com.bitbi.dfm.deviceauth.presentation.dto.DeviceVerifyRequestDto("ABCD-1234", "approve"));

        assertEquals(HttpStatus.GONE, response.getStatusCode());
    }
}
