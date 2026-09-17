package com.bitbi.dfm.account.application;

import com.auth0.exception.APIException;
import com.bitbi.dfm.account.domain.AccountRepository;
import com.bitbi.dfm.auth.config.Auth0Configuration;
import com.bitbi.dfm.auth.domain.UserRole;
import com.bitbi.dfm.auth.infrastructure.Auth0ManagementApiClient;
import com.bitbi.dfm.shared.exception.Auth0RateLimitException;
import com.bitbi.dfm.shared.exception.Auth0ServiceUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The retry around {@link AccountSyncService#createAccount} observed through a real proxy (issue #302).
 * <p>
 * Spring Retry is gone with Boot 4.1; its replacement is Spring Framework's own
 * {@code @Retryable}, enabled by {@code Auth0Configuration}. The two annotations spell the same
 * behaviour differently — {@code maxAttempts = 3} counts the first call, {@code maxRetries = 2} does
 * not — so the behaviour is pinned here rather than the attributes: an unavailable Auth0 is called
 * three times with a one-second and then a two-second pause, and any other failure is not retried.
 * The context holds only {@code Auth0Configuration} (whose empty Auth0 settings create no API bean,
 * so its scheduled refresh is a no-op) and the service over mocks: what enables the retry is the
 * configuration class the application actually loads.
 */
class AccountSyncServiceRetryTest {

    private static final String EMAIL = "retry@example.com";

    private AnnotationConfigApplicationContext context;
    private AccountRepository accountRepository;
    private Auth0ManagementApiClient auth0Client;
    private AccountSyncService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        auth0Client = mock(Auth0ManagementApiClient.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(AccountRepository.class, () -> accountRepository);
        context.registerBean(Auth0ManagementApiClient.class, () -> auth0Client);
        context.registerBean(ApplicationEventPublisher.class, () -> mock(ApplicationEventPublisher.class));
        context.register(Auth0Configuration.class, AccountSyncService.class);
        context.refresh();
        service = context.getBean(AccountSyncService.class);
        when(accountRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    @DisplayName("An unavailable Auth0 is tried three times, 1 s then 2 s apart, and the failure surfaces unchanged")
    void shouldRetryUnavailableAuth0ThreeTimesWithDoublingBackoff() throws Exception {
        when(auth0Client.createUserWithPassword(eq(EMAIL), anyString(), anyString(), eq(true)))
                .thenThrow(new APIException("Service unavailable", 503, null));

        assertThat(AopUtils.isAopProxy(service)).as("the retry needs a proxy around the service").isTrue();
        long started = System.nanoTime();
        assertThatThrownBy(() -> service.createAccount(EMAIL, "Retry", null, null, UserRole.USER))
                .isInstanceOf(Auth0ServiceUnavailableException.class);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        verify(auth0Client, times(3)).createUserWithPassword(eq(EMAIL), anyString(), anyString(), eq(true));
        assertThat(elapsedMillis).as("1 s + 2 s of backoff").isBetween(2_900L, 8_000L);
    }

    @Test
    @DisplayName("A failure other than unavailability is not retried")
    void shouldNotRetryRateLimit() throws Exception {
        when(auth0Client.createUserWithPassword(eq(EMAIL), anyString(), anyString(), eq(true)))
                .thenThrow(new APIException("Rate limit exceeded", 429, null));

        assertThatThrownBy(() -> service.createAccount(EMAIL, "Retry", null, null, UserRole.USER))
                .isInstanceOf(Auth0RateLimitException.class);

        verify(auth0Client, times(1)).createUserWithPassword(eq(EMAIL), anyString(), anyString(), eq(true));
    }
}
