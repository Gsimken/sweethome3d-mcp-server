package com.sh3d.mcp.command.handler;

import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lists installed .sh3p plugins and explains what is visible through the MCP model bridge. */
public class ListInstalledPluginsHandler implements CommandHandler, CommandDescriptor {

    private final List<Path> pluginDirectories;

    public ListInstalledPluginsHandler() {
        this(defaultPluginDirectories());
    }

    ListInstalledPluginsHandler(List<Path> pluginDirectories) {
        this.pluginDirectories = new ArrayList<>(pluginDirectories);
    }

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        List<Object> plugins = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<Path> seen = new HashSet<>();

        for (Path directory : pluginDirectories) {
            if (!Files.isDirectory(directory)) continue;
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sh3p"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> {
                            try {
                                Path real = path.toRealPath();
                                if (seen.add(real)) plugins.add(readPlugin(real));
                            } catch (IOException e) {
                                warnings.add(path.getFileName() + ": " + e.getMessage());
                            }
                        });
            } catch (IOException e) {
                warnings.add("Could not scan a plugin directory: " + e.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pluginCount", plugins.size());
        data.put("plugins", plugins);
        data.put("integration", "The MCP observes changes that plugins persist in the Sweet Home 3D "
                + "Home model. inspect_objects exposes standard object fields and string/content "
                + "custom properties. Plugin dialogs and private plugin APIs aren't invoked directly.");
        if (!warnings.isEmpty()) data.put("warnings", warnings);
        return Response.ok(data);
    }

    private Map<String, Object> readPlugin(Path path) throws IOException {
        Properties properties = new Properties();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith("ApplicationPlugin.properties")) {
                    try (InputStream input = zip.getInputStream(entry)) {
                        properties.load(input);
                    }
                    break;
                }
            }
        }

        Map<String, Object> plugin = new LinkedHashMap<>();
        plugin.put("fileName", path.getFileName().toString());
        put(plugin, "id", properties.getProperty("id"));
        put(plugin, "name", properties.getProperty("name"));
        put(plugin, "description", properties.getProperty("description"));
        put(plugin, "version", properties.getProperty("version"));
        put(plugin, "provider", properties.getProperty("provider"));
        put(plugin, "license", properties.getProperty("license"));
        put(plugin, "applicationMinimumVersion", properties.getProperty("applicationMinimumVersion"));
        put(plugin, "javaMinimumVersion", properties.getProperty("javaMinimumVersion"));
        put(plugin, "entryClass", properties.getProperty("class"));
        plugin.put("mcpVisibility", compatibilityNote(properties, path));
        return plugin;
    }

    private String compatibilityNote(Properties properties, Path path) {
        String identity = (properties.getProperty("name", "") + " "
                + properties.getProperty("description", "") + " "
                + path.getFileName()).toLowerCase(Locale.ROOT);
        if (identity.contains("cabinet")) {
            return "Cabinets and nested groups are visible as furniture, including materials, parts, "
                    + "dimensions, capabilities and plugin properties.";
        }
        if (identity.contains("fitin") || identity.contains("fit in")) {
            return "Layout changes are visible through updated position, dimensions, rotation and group relationships.";
        }
        if (identity.contains("roof")) {
            return "Roof geometry is visible as furniture; roof.*, roof_window and roof_window_* custom properties are exposed.";
        }
        if (identity.contains("modeller") || identity.contains("shape")) {
            return "Generated shapes are visible as furniture, including footprint, model metadata, transformations and materials.";
        }
        if (identity.contains("pan") || identity.contains("3d view")) {
            return "Camera changes are visible in get_state; this MCP doesn't invoke the plugin's private navigation actions.";
        }
        if (identity.contains("mcp")) {
            return "This is the MCP bridge itself.";
        }
        return "Objects and standard fields persisted in the Home model are visible; custom HomeObject properties are exposed.";
    }

    private static List<Path> defaultPluginDirectories() {
        String userHome = System.getProperty("user.home");
        List<Path> paths = new ArrayList<>();
        if (userHome != null && !userHome.isEmpty()) {
            paths.add(Paths.get(userHome, "eTeks", "Sweet Home 3D", "plugins"));
            paths.add(Paths.get(userHome, "AppData", "Roaming", "eTeks", "Sweet Home 3D", "plugins"));
            paths.add(Paths.get(userHome, ".sweethome3d", "plugins"));
            paths.add(Paths.get(userHome, "Library", "Application Support", "eTeks", "Sweet Home 3D", "plugins"));
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            paths.add(Paths.get(appData, "eTeks", "Sweet Home 3D", "plugins"));
        }
        return paths;
    }

    private static void put(Map<String, Object> map, String key, String value) {
        if (value != null && !value.trim().isEmpty()) map.put(key, value.trim());
    }

    @Override
    public String getDescription() {
        return "Lists installed Sweet Home 3D .sh3p plugins with version and compatibility "
                + "information. Use this to understand which plugin-generated objects may appear. "
                + "The MCP reads shared Home model data and custom HomeObject properties; it does "
                + "not call private actions belonging to other plugins.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create().build();
    }
}
