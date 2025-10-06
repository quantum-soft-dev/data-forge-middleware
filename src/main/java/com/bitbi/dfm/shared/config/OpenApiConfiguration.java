package com.bitbi.dfm.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI (Swagger) configuration for API documentation.
 * <p>
 * Provides Swagger UI with security schemes for:
 * - Basic Auth (for token generation)
 * - Bearer JWT (for client API endpoints)
 * - OAuth2 (for admin endpoints with Keycloak)
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
public class OpenApiConfiguration {

    @Value("${spring.application.name:Data Forge Middleware}")
    private String applicationName;

    @Value("${spring.application.version:1.0.0}")
    private String applicationVersion;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title(applicationName + " API")
                        .version(applicationVersion)
                        .description("REST API for Data Forge batch upload middleware")
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
                                .description("Basic Auth with domain:clientSecret credentials"))
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT Bearer token for client API endpoints"))
                        .addSecuritySchemes("oauth2", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("OAuth2 with Keycloak for admin endpoints")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("basicAuth")
                        .addList("bearerAuth")
                        .addList("oauth2"));
    }
}
