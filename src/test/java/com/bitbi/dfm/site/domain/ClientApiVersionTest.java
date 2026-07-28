package com.bitbi.dfm.site.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientApiVersionTest {

    @Test
    void shouldExposeOnlyDeltaV2() {
        assertThat(ClientApiVersion.values()).containsExactly(ClientApiVersion.V2);
    }
}
