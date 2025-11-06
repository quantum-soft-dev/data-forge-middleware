package com.bitbi.dfm.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI (Swagger) configuration for API documentation.
 * <p>
 * Provides Swagger UI with two distinct API groups:
 * </p>
 * <ul>
 *   <li><b>Device API</b>: For IoT devices and client applications (Custom JWT authentication)</li>
 *   <li><b>UI/Admin API</b>: For web interface and administrative operations (Keycloak OAuth2 authentication)</li>
 * </ul>
 * <p>
 * Security schemes:
 * </p>
 * <ul>
 *   <li>Basic Auth - for token generation endpoint</li>
 *   <li>Bearer JWT - for Device API endpoints (Custom JWT)</li>
 *   <li>OAuth2 - for UI/Admin API endpoints (Keycloak)</li>
 * </ul>
 *
 * @author Data Forge Team
 * @version 1.0.0
 * @see <a href="specs/010-api-unification-goal/spec.md">API Unification Specification</a>
 */
@Configuration
public class OpenApiConfiguration {

    @Value("${spring.application.name:Data Forge Middleware}")
    private String applicationName;

    @Value("${spring.application.version:1.0.0}")
    private String applicationVersion;

    /**
     * Main OpenAPI configuration with security schemes.
     * <p>
     * Defines the API metadata, servers, and security scheme definitions
     * that are shared across all API groups.
     * </p>
     *
     * @return OpenAPI configuration with title, servers, and security schemes
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(applicationName + " API - Unified Structure")
                        .version(applicationVersion)
                        .description("Unified REST API with separate Device and UI/Admin endpoints.\n\n" +
                                "**Device API** (`/api/v1/device/*`): For IoT devices, mobile apps, and data collection clients.\n" +
                                "Authentication: Custom JWT\n\n" +
                                "**UI/Admin API** (`/api/v1/*`): For web dashboard, user portal, and administrative operations.\n" +
                                "Authentication: Keycloak OAuth2")
                        .contact(new Contact()
                                .name("Data Forge Team")
                                .email("support@bitbi.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://bitbi.com/license")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local development server"),
                        new Server()
                                .url("https://api.dataforge.bitbi.com")
                                .description("Production server")))
                .components(new Components()
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Basic Auth with domain:clientSecret credentials (used for token generation)"))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Custom JWT Bearer token for Device API endpoints"))
                        .addSecuritySchemes("oauth2", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("Keycloak OAuth2 for UI/Admin API endpoints")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("basicAuth")
                        .addList("bearerAuth")
                        .addList("oauth2"));
    }

    /**
     * Device API group configuration.
     * <p>
     * Groups all Device API endpoints under `/api/v1/device/**` for
     * Swagger UI display. These endpoints use Custom JWT authentication.
     * </p>
     *
     * @return Grouped Device API configuration
     */
    @Bean
    public GroupedOpenApi deviceApi() {
        return GroupedOpenApi.builder()
                .group("device-api")
                .displayName("Device API")
                .pathsToMatch("/api/v1/device/**")
                .build();
    }

    /**
     * UI/Admin API group configuration.
     * <p>
     * Groups all UI/Admin API endpoints under `/api/v1/**` (excluding Device API)
     * for Swagger UI display. These endpoints use Keycloak OAuth2 authentication.
     * </p>
     *
     * @return Grouped UI/Admin API configuration
     */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin-api")
                .displayName("UI/Admin API")
                .pathsToMatch("/api/v1/**")
                .pathsToExclude("/api/v1/device/**")
                .build();
    }
}
