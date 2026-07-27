package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.ParquetExportCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ParquetExportPlugin} (028-parquet-export-plugin, T005).
 */
@ExtendWith(MockitoExtension.class)
class ParquetExportPluginTest {

    @Mock
    private ParquetExportCredentialsService credentialsService;

    private ParquetExportPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new ParquetExportPlugin(credentialsService);
    }

    private AccountPlugin activation() {
        return AccountPlugin.activate(UUID.randomUUID(), "parquet-export", Map.of());
    }

    @Test
    @DisplayName("Should identify as parquet-export")
    void shouldIdentifyAsParquetExport() {
        assertEquals("parquet-export", plugin.getId());
        assertEquals("Parquet Export", plugin.getName());
        assertNotNull(plugin.getVersion());
    }

    @Test
    @DisplayName("Should not subscribe to any events")
    void shouldNotSubscribeToEvents() {
        assertTrue(plugin.getSupportedEvents().isEmpty());
    }

    @Test
    @DisplayName("Should accept empty pluginData in schema")
    void shouldAcceptEmptyPluginData() {
        assertNotNull(plugin.getSchemaJson());
        assertTrue(plugin.getSchemaJson().contains("object"));
    }

    @Test
    @DisplayName("Should return login:password once on activation")
    void shouldReturnCredentialsOnActivation() {
        AccountPlugin accountPlugin = activation();
        ParquetExportCredentials credentials = ParquetExportCredentials.generate();
        when(credentialsService.mintCredentials(accountPlugin)).thenReturn(Optional.of(credentials));

        String secret = plugin.onActivate(accountPlugin);

        assertEquals(credentials.basicAuthValue(), secret);
    }

    @Test
    @DisplayName("Should return null when credentials already exist")
    void shouldReturnNullWhenCredentialsExist() {
        AccountPlugin accountPlugin = activation();
        when(credentialsService.mintCredentials(accountPlugin)).thenReturn(Optional.empty());

        assertNull(plugin.onActivate(accountPlugin));
    }

    @Test
    @DisplayName("Should tolerate deactivation callback")
    void shouldTolerateDeactivation() {
        AccountPlugin accountPlugin = activation();
        assertDoesNotThrow(() -> plugin.onDeactivate(accountPlugin));
    }

    @Test
    @DisplayName("Should ignore events in execute")
    void shouldIgnoreEvents() {
        AccountPlugin accountPlugin = activation();
        assertDoesNotThrow(() -> plugin.execute(null, accountPlugin));
        verify(credentialsService, org.mockito.Mockito.never()).mintCredentials(accountPlugin);
    }
}
