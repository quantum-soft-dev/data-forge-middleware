package com.bitbi.dfm.shared.config;

import org.springframework.boot.actuate.autoconfigure.endpoint.web.CorsEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.endpoint.web.*;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.Collection;

/**
 * Spring Boot Actuator configuration.
 * <p>
 * Configures custom health indicators and endpoint exposure.
 * Health endpoint includes:
 * - Database connectivity check (default)
 * - S3 bucket accessibility check (custom via S3HealthIndicator)
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
public class ActuatorConfiguration {

    /**
     * Configure WebMvcEndpointHandlerMapping for Actuator endpoints.
     * <p>
     * Required for proper Actuator endpoint registration with Spring MVC.
     * Handles endpoint path mapping with base path configuration.
     * </p>
     * <p>
     * Note: ServletEndpointsSupplier and ControllerEndpointsSupplier have been
     * removed as they are deprecated in Spring Boot 3.x. Only WebEndpointsSupplier
     * is needed for standard actuator endpoints.
     * </p>
     */
    @Bean
    public WebMvcEndpointHandlerMapping webEndpointServletHandlerMapping(
            WebEndpointsSupplier webEndpointsSupplier,
            EndpointMediaTypes endpointMediaTypes,
            CorsEndpointProperties corsProperties,
            WebEndpointProperties webEndpointProperties,
            Environment environment) {

        Collection<ExposableWebEndpoint> webEndpoints = webEndpointsSupplier.getEndpoints();
        String basePath = webEndpointProperties.getBasePath();
        EndpointMapping endpointMapping = new EndpointMapping(basePath);

        boolean shouldRegisterLinksMapping = shouldRegisterLinksMapping(
                webEndpointProperties, environment, basePath);

        return new WebMvcEndpointHandlerMapping(
                endpointMapping,
                webEndpoints,
                endpointMediaTypes,
                corsProperties.toCorsConfiguration(),
                new EndpointLinksResolver(webEndpoints, basePath),
                shouldRegisterLinksMapping);
    }

    private boolean shouldRegisterLinksMapping(
            WebEndpointProperties webEndpointProperties,
            Environment environment,
            String basePath) {

        return webEndpointProperties.getDiscovery().isEnabled() &&
               (StringUtils.hasText(basePath) ||
                ManagementPortType.get(environment).equals(ManagementPortType.DIFFERENT));
    }
}
