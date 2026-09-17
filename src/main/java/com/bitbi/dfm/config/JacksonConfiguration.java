package com.bitbi.dfm.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson JSON serialization configuration.
 * <p>
 * Configures the application's JSON mapper to include null values in JSON responses.
 * This is important for API contracts where clients expect explicit null values.
 * </p>
 * <p>
 * Since Boot 4 (issue #302) the mapper is Jackson 3's {@code JsonMapper}, built by Boot with
 * {@code spring.jackson.use-jackson2-defaults: true} so the HTTP API keeps its Jackson 2 shape (#303
 * decides when that goes); this class customizes Boot's builder rather than declaring a mapper of its
 * own, which Boot would no longer use for HTTP message conversion.
 * </p>
 *
 * Feature: 008-upload-history-user (Phase 3)
 */
@Configuration
public class JacksonConfiguration {

    /**
     * Include null values in JSON.
     * <p>
     * For pagination responses like CursorPageResponseDto, we need to include nextCursor even when
     * it's null so clients can distinguish between "field missing" and "no next page".
     * </p>
     *
     * @return customizer applied to Boot's {@code JsonMapper.Builder}
     */
    @Bean
    public JsonMapperBuilderCustomizer includeNullValues() {
        return builder -> builder.changeDefaultPropertyInclusion(
                inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS)
                        .withContentInclusion(JsonInclude.Include.ALWAYS));
    }
}
