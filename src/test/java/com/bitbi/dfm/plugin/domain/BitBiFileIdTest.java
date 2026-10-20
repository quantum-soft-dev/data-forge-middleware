package com.bitbi.dfm.plugin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BitBiFileId — opaque, version-pinned file identifier of the v2 surface (#387, #388)")
class BitBiFileIdTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID BATCH = UUID.fromString("0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41");

    /** {@code ck1:<SITE>:3:5020646:nsftrns}, written out: the wire form is a contract, not encode()'s echo. */
    private static final String CHECKPOINT_WIRE =
            "Y2sxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDozOjUwMjA2NDY6bnNmdHJucw";

    /** {@code bp1:<SITE>:<BATCH>:nsftrns}, written out. */
    private static final String BATCH_FILE_WIRE =
            "YnAxOjAxOTliYWFjLWY4NTItNzUzZi02ZmMzLTdjOTk0ZmMzODY1NDowMTk5YmIxMC00YzJlLTdhMWQtOWYwMC0zYjVlMWEyYzdkNDE6bnNmdHJucw";

    private static String encodeRaw(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A checkpoint id round-trips through its wire form")
    void roundTrips() {
        BitBiFileId.Checkpoint id = new BitBiFileId.Checkpoint(SITE, 3L, 5_020_646L, "nsftrns");

        String wire = id.encode();

        assertThat(BitBiFileId.decode(wire)).contains(id);
    }

    @Test
    @DisplayName("A checkpoint id has exactly the wire form the guide shows")
    void checkpointWireFormIsPinned() {
        assertThat(new BitBiFileId.Checkpoint(SITE, 3L, 5_020_646L, "nsftrns").encode()).isEqualTo(CHECKPOINT_WIRE);
        assertThat(BitBiFileId.decode(CHECKPOINT_WIRE))
                .contains(new BitBiFileId.Checkpoint(SITE, 3L, 5_020_646L, "nsftrns"));
    }

    @Test
    @DisplayName("A batch file id has exactly the wire form the guide shows")
    void batchFileWireFormIsPinned() {
        assertThat(new BitBiFileId.BatchFile(SITE, BATCH, "nsftrns").encode()).isEqualTo(BATCH_FILE_WIRE);
        assertThat(BitBiFileId.decode(BATCH_FILE_WIRE)).contains(new BitBiFileId.BatchFile(SITE, BATCH, "nsftrns"));
    }

    @Test
    @DisplayName("A batch file id round-trips, a table carrying the separator or a slash included")
    void batchFileRoundTrips() {
        for (String table : new String[]{"orders", "a:b", "a/b"}) {
            BitBiFileId.BatchFile id = new BitBiFileId.BatchFile(SITE, BATCH, table);
            assertThat(BitBiFileId.decode(id.encode())).contains(id);
        }
    }

    @ParameterizedTest(name = "raw \"{0}\" is a second spelling of an issued id and is refused")
    @ValueSource(strings = {
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:+3:5020646:nsftrns",
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:03:5020646:nsftrns",
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:3:05020646:nsftrns",
            "ck1:0199BAAC-F852-753F-6FC3-7C994FC38654:3:5020646:nsftrns",
            "bp1:0199baac-f852-753f-6fc3-7c994fc38654:0199BB10-4C2E-7A1D-9F00-3B5E1A2C7D41:nsftrns",
            "bp1:0199BAAC-F852-753F-6FC3-7C994FC38654:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41:nsftrns"
    })
    void anotherSpellingIsRefused(String raw) {
        assertThat(BitBiFileId.decode(encodeRaw(raw))).isEmpty();
    }

    @Test
    @DisplayName("A padded wire form is a second spelling and is refused")
    void paddingIsRefused() {
        String padded = Base64.getUrlEncoder().encodeToString(
                "ck1:0199baac-f852-753f-6fc3-7c994fc38654:3:5020646:nsftrns".getBytes(StandardCharsets.UTF_8));
        assertThat(padded).as("this raw form needs padding, or the case proves nothing").endsWith("=");

        assertThat(BitBiFileId.decode(padded)).isEmpty();
    }

    @Test
    @DisplayName("The wire form is URL-safe and carries no padding")
    void wireFormIsUrlSafe() {
        String wire = new BitBiFileId.Checkpoint(SITE, 0L, 1L, "t?>+x~").encode();

        assertThat(wire).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("A table name carrying the separator survives, since the table is the last field")
    void tableWithSeparatorRoundTrips() {
        BitBiFileId.Checkpoint id = new BitBiFileId.Checkpoint(SITE, 1L, 7L, "a:b");

        assertThat(BitBiFileId.decode(id.encode())).contains(id);
    }

    @Test
    @DisplayName("The same version of the same table always has the same id")
    void deterministic() {
        assertThat(new BitBiFileId.Checkpoint(SITE, 3L, 9L, "t").encode())
                .isEqualTo(new BitBiFileId.Checkpoint(SITE, 3L, 9L, "t").encode());
        assertThat(new BitBiFileId.Checkpoint(SITE, 3L, 9L, "t").encode())
                .as("another seq is another file")
                .isNotEqualTo(new BitBiFileId.Checkpoint(SITE, 3L, 10L, "t").encode());
        assertThat(new BitBiFileId.Checkpoint(SITE, 3L, 9L, "t").encode())
                .as("another epoch is another file")
                .isNotEqualTo(new BitBiFileId.Checkpoint(SITE, 4L, 9L, "t").encode());
    }

    @ParameterizedTest(name = "\"{0}\" is not a file id")
    @ValueSource(strings = {"", "not base64 !", "Zm9v", "@@@"})
    void garbageDecodesToNothing(String wire) {
        assertThat(BitBiFileId.decode(wire)).isEmpty();
    }

    @ParameterizedTest(name = "raw \"{0}\" is not a file id")
    @ValueSource(strings = {
            "ck2:0199baac-f852-753f-6fc3-7c994fc38654:3:9:t",   // unknown kind/version
            "ck1:not-a-uuid:3:9:t",
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:x:9:t",
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:3:x:t",
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:-1:9:t", // negative epoch
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:3:0:t",  // seq 0 = no checkpoint
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:3:9:",   // no table
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:3:9:../other/t", // a table cannot name a path
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:3:9",
            "bp2:0199baac-f852-753f-6fc3-7c994fc38654:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41:t",
            "bp1:0199baac-f852-753f-6fc3-7c994fc38654:not-a-uuid:t",
            "bp1:0199baac-f852-753f-6fc3-7c994fc38654:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41:",
            "bp1:0199baac-f852-753f-6fc3-7c994fc38654:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41",
            "bp1:0199baac-f852-753f-6fc3-7c994fc38654:0199bb10-4c2e-7a1d-9f00-3b5e1a2c7d41: "
    })
    void malformedDecodesToNothing(String raw) {
        assertThat(BitBiFileId.decode(encodeRaw(raw))).isEmpty();
    }

    @Test
    @DisplayName("A null wire form decodes to nothing")
    void nullDecodesToNothing() {
        assertThat(BitBiFileId.decode(null)).isEmpty();
    }

    @Test
    @DisplayName("An id that could not be decoded again is refused at construction")
    void constructionRefusesWhatDecodeWouldRefuse() {
        assertThatThrownBy(() -> new BitBiFileId.Checkpoint(SITE, 1L, 0L, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BitBiFileId.Checkpoint(SITE, 1L, 1L, "a/b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BitBiFileId.Checkpoint(SITE, -1L, 1L, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BitBiFileId.Checkpoint(null, 1L, 1L, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BitBiFileId.BatchFile(SITE, null, "t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BitBiFileId.BatchFile(SITE, BATCH, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Whether a table can carry a checkpoint id is answered without throwing")
    void checkpointTableNameCheck() {
        assertThat(BitBiFileId.Checkpoint.isAddressable("orders")).isTrue();
        assertThat(BitBiFileId.Checkpoint.isAddressable("a/b")).isFalse();
        assertThat(BitBiFileId.Checkpoint.isAddressable(" ")).isFalse();
    }
}
