package com.sh3d.mcp.command.handler;

import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ListInstalledPluginsHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void readsPluginManifestAndReturnsCompatibilityNote() throws Exception {
        Path plugin = tempDir.resolve("GenerateRoof-5.2.sh3p");
        Properties properties = new Properties();
        properties.setProperty("id", "SweetHome3D#GenerateRoofPlugin");
        properties.setProperty("name", "Roof generator");
        properties.setProperty("description", "Add a roof to home");
        properties.setProperty("version", "5.2");
        properties.setProperty("class", "example.GenerateRoofPlugin");
        try (OutputStream output = Files.newOutputStream(plugin);
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("ApplicationPlugin.properties"));
            properties.store(zip, null);
            zip.closeEntry();
        }

        ListInstalledPluginsHandler handler =
                new ListInstalledPluginsHandler(Collections.singletonList(tempDir));
        Response response = handler.execute(
                new Request("list_installed_plugins", Collections.emptyMap()), null);

        assertTrue(response.isOk());
        assertEquals(1, response.getData().get("pluginCount"));
        List<Map<String, Object>> plugins = (List<Map<String, Object>>) response.getData().get("plugins");
        assertEquals("Roof generator", plugins.get(0).get("name"));
        assertEquals("5.2", plugins.get(0).get("version"));
        assertTrue(plugins.get(0).get("mcpVisibility").toString().contains("roof.*"));
        assertNotNull(response.getData().get("integration"));
    }

    @Test
    void emptyDirectoryReturnsEmptyList() {
        ListInstalledPluginsHandler handler =
                new ListInstalledPluginsHandler(Collections.singletonList(tempDir));
        Response response = handler.execute(
                new Request("list_installed_plugins", Collections.emptyMap()), null);
        assertTrue(response.isOk());
        assertEquals(0, response.getData().get("pluginCount"));
    }
}
