package com.bitbi.dfm.shared.presentation.dto;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseDtoTest {

    // Jackson 3 has java.time built in and writes dates as ISO-8601 by default
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void shouldCreateErrorResponseDtoWithAllFields() {
        // Given
        Instant timestamp = Instant.parse("2025-10-09T10:00:00Z");
        Integer status = 403;
        String error = "Forbidden";
        String message = "Authentication failed";
        String path = "/api/v1/device/errors";

        // When
        ErrorResponseDto dto = new ErrorResponseDto(timestamp, status, error, message, path);

        // Then
        assertThat(dto.timestamp()).isEqualTo(timestamp);
        assertThat(dto.status()).isEqualTo(status);
        assertThat(dto.error()).isEqualTo(error);
        assertThat(dto.message()).isEqualTo(message);
        assertThat(dto.path()).isEqualTo(path);
    }

    @Test
    void shouldSerializeToJsonCorrectly() throws Exception {
        // Given
        Instant timestamp = Instant.parse("2025-10-09T10:00:00Z");
        ErrorResponseDto dto = new ErrorResponseDto(
            timestamp,
            403,
            "Forbidden",
            "Authentication failed",
            "/api/v1/device/errors"
        );

        // When
        String json = objectMapper.writeValueAsString(dto);

        // Then
        assertThat(json).contains("\"timestamp\":\"2025-10-09T10:00:00Z\"");
        assertThat(json).contains("\"status\":403");
        assertThat(json).contains("\"error\":\"Forbidden\"");
        assertThat(json).contains("\"message\":\"Authentication failed\"");
        assertThat(json).contains("\"path\":\"/api/v1/device/errors\"");
    }

    @Test
    void shouldDeserializeFromJsonCorrectly() throws Exception {
        // Given
        String json = """
            {
              "timestamp": "2025-10-09T10:00:00Z",
              "status": 403,
              "error": "Forbidden",
              "message": "Authentication failed",
              "path": "/api/v1/device/errors"
            }
            """;

        // When
        ErrorResponseDto dto = objectMapper.readValue(json, ErrorResponseDto.class);

        // Then
        assertThat(dto.timestamp()).isEqualTo(Instant.parse("2025-10-09T10:00:00Z"));
        assertThat(dto.status()).isEqualTo(403);
        assertThat(dto.error()).isEqualTo("Forbidden");
        assertThat(dto.message()).isEqualTo("Authentication failed");
        assertThat(dto.path()).isEqualTo("/api/v1/device/errors");
    }

    @Test
    void shouldEqualWhenAllFieldsMatch() {
        // Given
        Instant timestamp = Instant.parse("2025-10-09T10:00:00Z");
        ErrorResponseDto dto1 = new ErrorResponseDto(timestamp, 403, "Forbidden", "Authentication failed", "/api/v1/device/errors");
        ErrorResponseDto dto2 = new ErrorResponseDto(timestamp, 403, "Forbidden", "Authentication failed", "/api/v1/device/errors");

        // Then
        assertThat(dto1).isEqualTo(dto2);
        assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
    }

    @Test
    void shouldNotEqualWhenFieldsDiffer() {
        // Given
        Instant timestamp = Instant.parse("2025-10-09T10:00:00Z");
        ErrorResponseDto dto1 = new ErrorResponseDto(timestamp, 403, "Forbidden", "Authentication failed", "/api/v1/device/errors");
        ErrorResponseDto dto2 = new ErrorResponseDto(timestamp, 404, "Not Found", "Resource not found", "/api/v1/device/errors/123");

        // Then
        assertThat(dto1).isNotEqualTo(dto2);
    }
}
