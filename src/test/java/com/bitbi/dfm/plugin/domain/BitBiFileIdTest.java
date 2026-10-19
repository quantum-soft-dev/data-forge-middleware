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

@DisplayName("BitBiFileId — opaque, version-pinned file identifier of the v2 surface (#387)")
class BitBiFileIdTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");

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
            "ck1:0199baac-f852-753f-6fc3-7c994fc38654:3:9"
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
    }
}
