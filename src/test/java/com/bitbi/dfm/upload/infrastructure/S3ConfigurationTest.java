package com.bitbi.dfm.upload.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the decision logic that lets the production S3 beans target either
 * AWS S3 or a GCS S3-compatible endpoint purely via configuration.
 *
 * <p>These cover the bug-prone choices: credential mode (static HMAC vs IAM default chain),
 * whether a custom endpoint override is applied, and the service configuration toggles
 * (path-style, chunked-encoding, checksum) that GCS S3-compatibility requires.</p>
 */
class S3ConfigurationTest {

    @Test
    @DisplayName("resolveCredentials: static credentials when both keys provided (GCS HMAC / AWS keys)")
    void shouldUseStaticCredentialsWhenKeysProvided() {
        AwsCredentialsProvider provider = S3Configuration.resolveCredentials("GOOG1EXAMPLE", "secret");

        assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
    }

    @Test
    @DisplayName("resolveCredentials: falls back to DefaultCredentialsProvider when keys blank/null (AWS IAM)")
    void shouldFallBackToDefaultCredentialsWhenKeysBlank() {
        assertThat(S3Configuration.resolveCredentials("", "")).isInstanceOf(DefaultCredentialsProvider.class);
        assertThat(S3Configuration.resolveCredentials("   ", "secret")).isInstanceOf(DefaultCredentialsProvider.class);
        assertThat(S3Configuration.resolveCredentials("key", "  ")).isInstanceOf(DefaultCredentialsProvider.class);
        assertThat(S3Configuration.resolveCredentials(null, null)).isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    @DisplayName("shouldOverrideEndpoint: true for a non-AWS endpoint such as GCS")
    void shouldOverrideEndpointForGcs() {
        assertThat(S3Configuration.shouldOverrideEndpoint("https://storage.googleapis.com")).isTrue();
    }

    @Test
    @DisplayName("shouldOverrideEndpoint: false for canonical AWS endpoint and blank/null")
    void shouldNotOverrideEndpointForAwsOrBlank() {
        assertThat(S3Configuration.shouldOverrideEndpoint("https://s3.amazonaws.com")).isFalse();
        assertThat(S3Configuration.shouldOverrideEndpoint("https://s3.us-east-1.amazonaws.com")).isFalse();
        assertThat(S3Configuration.shouldOverrideEndpoint("")).isFalse();
        assertThat(S3Configuration.shouldOverrideEndpoint("   ")).isFalse();
        assertThat(S3Configuration.shouldOverrideEndpoint(null)).isFalse();
    }

    @Test
    @DisplayName("serviceConfiguration: GCS mode enables path-style and disables chunked-encoding + checksum")
    void shouldDisableChecksumAndChunkedEncodingForGcs() {
        software.amazon.awssdk.services.s3.S3Configuration cfg =
                S3Configuration.serviceConfiguration(true, false);

        assertThat(cfg.pathStyleAccessEnabled()).isTrue();
        assertThat(cfg.chunkedEncodingEnabled()).isFalse();
        assertThat(cfg.checksumValidationEnabled()).isFalse();
    }

    @Test
    @DisplayName("serviceConfiguration: AWS mode keeps checksum validation and virtual-host addressing")
    void shouldKeepChecksumEnabledForAws() {
        software.amazon.awssdk.services.s3.S3Configuration cfg =
                S3Configuration.serviceConfiguration(false, true);

        assertThat(cfg.pathStyleAccessEnabled()).isFalse();
        assertThat(cfg.checksumValidationEnabled()).isTrue();
    }
}
