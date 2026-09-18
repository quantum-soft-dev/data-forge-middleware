package com.bitbi.dfm.plugin.infrastructure;

import com.bitbi.dfm.auth.config.Auth0Properties;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The request body the audit filter records: hashed when it fits the 1 MiB hashing limit, reported
 * as {@code BODY_TOO_LARGE} when it does not — and passed downstream whole either way.
 * <p>
 * Pinned in #299, where Spring deprecated the unbounded {@code ContentCachingRequestWrapper}
 * constructor for removal and the filter moved to a bounded cache: the bound must stay above the
 * hashing limit, or an oversized body would be cached truncated and hashed as if it fitted.
 */
@DisplayName("PluginAuditFilter request body")
class PluginAuditFilterRequestBodyTest {

    private static final int HASHING_LIMIT = 1024 * 1024;

    private final PluginAuditLogRepository repository = mock(PluginAuditLogRepository.class);
    private final PluginAuditFilter filter = new PluginAuditFilter(repository, mock(Auth0Properties.class));

    private PluginAuditLog audit(byte[] body, AtomicInteger bytesReadDownstream) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/plugins/bit-bi/activate");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> bytesReadDownstream.set(req.getInputStream().readAllBytes().length);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<PluginAuditLog> saved = ArgumentCaptor.forClass(PluginAuditLog.class);
        verify(repository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("Should hash a body that fits the hashing limit")
    void shouldHashBodyWithinLimit() throws Exception {
        byte[] body = new byte[HASHING_LIMIT];
        Arrays.fill(body, (byte) 'a');
        AtomicInteger read = new AtomicInteger();

        PluginAuditLog log = audit(body, read);

        assertThat(read.get()).isEqualTo(HASHING_LIMIT);
        assertThat(log.getRequestBodySize()).isEqualTo(HASHING_LIMIT);
        assertThat(log.getRequestBodyHash()).isNotEqualTo("BODY_TOO_LARGE").hasSize(44);
    }

    @Test
    @DisplayName("Should report a body above the hashing limit as too large and still pass it downstream whole")
    void shouldReportOversizedBodyAndPassItDownstream() throws Exception {
        byte[] body = new byte[HASHING_LIMIT + 4096];
        AtomicInteger read = new AtomicInteger();

        PluginAuditLog log = audit(body, read);

        assertThat(read.get()).isEqualTo(HASHING_LIMIT + 4096);
        assertThat(log.getRequestBodyHash()).isEqualTo("BODY_TOO_LARGE");
        assertThat(log.getRequestBodySize()).isGreaterThan(HASHING_LIMIT);
    }
}
