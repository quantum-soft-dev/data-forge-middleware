package com.bitbi.dfm.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration to enable Auth0Properties across all profiles.
 * <p>
 * This configuration is separate from Auth0SecurityConfig to ensure
 * Auth0Properties is available even when Auth0SecurityConfig is disabled
 * in test profiles.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Configuration
@EnableConfigurationProperties(Auth0Properties.class)
public class Auth0PropertiesConfiguration {
    // This class only needs to exist to enable Auth0Properties
}
