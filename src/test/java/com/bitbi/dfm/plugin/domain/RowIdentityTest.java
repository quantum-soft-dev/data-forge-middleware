package com.bitbi.dfm.plugin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a Bit BI v2 consumer finds "the same row" of a table (issue #386, decision 6 of
 * {@code docs/cr-bitbi-parquet-plugin.md}).
 */
@DisplayName("RowIdentity")
class RowIdentityTest {

    @Test
    @DisplayName("A table with no primary key is keyed by _row_hash — every DBF table")
    void aKeylessTableIsKeyedByTheRowHash() {
        assertThat(RowIdentity.of(List.of())).isEqualTo(RowIdentity.ROW_HASH);
    }

    @Test
    @DisplayName("A missing primary key reads as none")
    void aMissingPrimaryKeyReadsAsNone() {
        assertThat(RowIdentity.of(null)).isEqualTo(RowIdentity.ROW_HASH);
    }

    @Test
    @DisplayName("A declared primary key, one column or several, keys the table by it")
    void aDeclaredPrimaryKeyKeysTheTable() {
        assertThat(RowIdentity.of(List.of("id"))).isEqualTo(RowIdentity.PRIMARY_KEY);
        assertThat(RowIdentity.of(List.of("tenant", "id"))).isEqualTo(RowIdentity.PRIMARY_KEY);
    }

    @Test
    @DisplayName("The wire values are the ones the CR publishes")
    void wireValuesAreTheCrsSpelling() {
        assertThat(RowIdentity.ROW_HASH.wireValue()).isEqualTo("row_hash");
        assertThat(RowIdentity.PRIMARY_KEY.wireValue()).isEqualTo("primary_key");
    }
}
