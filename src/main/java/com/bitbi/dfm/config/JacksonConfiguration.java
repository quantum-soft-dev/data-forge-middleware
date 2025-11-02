package com.bitbi.dfm.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Jackson JSON serialization configuration.
 * <p>
 * Configures ObjectMapper to include null values in JSON responses.
 * This is important for API contracts where clients expect explicit null values.
 * </p>
 *
 * Feature: 008-upload-history-user (Phase 3)
 */
@Configuration
public class JacksonConfiguration {

    /**
     * Configure ObjectMapper to include null values in JSON.
     * <p>
     * By default, Jackson omits null values. However, for pagination responses
     * like CursorPageResponseDto, we need to include nextCursor even when it's null
     * so clients can distinguish between "field missing" and "no next page".
     * </p>
     *
     * @param builder Jackson2ObjectMapperBuilder
     * @return Configured ObjectMapper
     */
    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        ObjectMapper mapper = builder.build();
        // Include null values in all responses
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        return mapper;
    }
}
