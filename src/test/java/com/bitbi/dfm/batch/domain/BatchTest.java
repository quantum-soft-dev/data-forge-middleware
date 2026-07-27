package com.bitbi.dfm.batch.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Batch aggregate (029: session activity tracking).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@DisplayName("Batch Unit Tests")
class BatchTest {

    @Test
    @DisplayName("Should have null lastActivityAt on creation (v1 batches never set it)")
    void shouldHaveNullLastActivityAtOnCreation() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID());

        assertThat(batch.getLastActivityAt()).isNull();
    }

    @Test
    @DisplayName("Should set lastActivityAt to now on touchActivity")
    void shouldTouchActivityUpdateLastActivityAt() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID());
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        batch.touchActivity();

        assertThat(batch.getLastActivityAt())
                .isNotNull()
                .isAfter(before)
                .isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
    }

    @Test
    @DisplayName("Should advance lastActivityAt on repeated touches")
    void shouldAdvanceLastActivityAtOnRepeatedTouch() {
        Batch batch = Batch.start(UUID.randomUUID(), UUID.randomUUID());

        batch.touchActivity();
        LocalDateTime first = batch.getLastActivityAt();
        batch.touchActivity();

        assertThat(batch.getLastActivityAt()).isAfterOrEqualTo(first);
    }
}
