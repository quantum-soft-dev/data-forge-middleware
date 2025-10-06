package com.bitbi.dfm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main entry point for Data Forge Middleware application.
 * <p>
 * This application provides a REST API for receiving, storing, and managing
 * data files uploaded by client applications at remote locations.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableScheduling
public class DataForgeMiddlewareApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataForgeMiddlewareApplication.class, args);
    }
}
