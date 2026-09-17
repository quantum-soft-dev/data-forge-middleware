package com.bitbi.dfm.config;

import io.hypersistence.utils.hibernate.type.util.ObjectMapperSupplier;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper every {@code JsonBinaryType} column reads and writes through (issue #302).
 * <p>
 * hypersistence-utils-hibernate-73 is on Jackson 3 and, left alone, builds
 * {@code JsonMapper.builder().findAndAddModules()} — Jackson 3's defaults, under which an
 * {@code Instant} in a free-form JSONB map is written as an ISO string where existing rows hold epoch
 * seconds, and a {@code null} for a primitive fails where it used to read as {@code 0}. The seven JSONB
 * columns hold documents written by the Jackson 2 default mapper of hypersistence-utils-hibernate-63,
 * so this supplier keeps that mapper's behaviour: Jackson 2 defaults plus the modules on the
 * classpath. {@code JsonbColumnCharacterizationIntegrationTest} (#300) holds both directions.
 * Registered in {@code hypersistence-utils.properties}, where the library looks for it; it is not a
 * Spring bean, because Hibernate instantiates the types.
 */
public class HypersistenceJsonMapperSupplier implements ObjectMapperSupplier {

    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
            .findAndAddModules()
            .build();

    @Override
    public ObjectMapper get() {
        return MAPPER;
    }
}
