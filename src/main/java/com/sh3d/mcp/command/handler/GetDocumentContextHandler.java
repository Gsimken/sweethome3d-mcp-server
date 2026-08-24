package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Level;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.HomeIdentity;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** Identifies the exact Sweet Home document and process served by this MCP endpoint. */
public class GetDocumentContextHandler implements CommandHandler, CommandDescriptor {

    private final int port;

    public GetDocumentContextHandler(int port) {
        this.port = port;
    }

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        Map<String, Object> data = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            String name = home.getName();
            long processId = ProcessHandle.current().pid();

            Map<String, Object> result = new LinkedHashMap<>();
            String documentId = HomeIdentity.documentId(home);
            result.put("homeId", documentId);
            result.put("documentId", documentId);
            result.put("documentSessionId", documentId);
            result.put("processId", processId);
            result.put("mcpEndpoint", "http://127.0.0.1:" + port + "/mcp");
            result.put("name", name);
            result.put("filePath", absolutePath(name));
            result.put("untitled", name == null || name.trim().isEmpty());
            result.put("modified", home.isModified());
            result.put("recovered", home.isRecovered());
            result.put("repaired", home.isRepaired());
            result.put("modelVersion", home.getVersion());
            result.put("basePlanLocked", home.isBasePlanLocked());
            result.put("selectedLevel", level(home.getSelectedLevel()));

            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("levels", home.getLevels().size());
            counts.put("walls", home.getWalls().size());
            counts.put("rooms", home.getRooms().size());
            counts.put("furniture", home.getFurniture().size());
            counts.put("selectedObjects", home.getSelectedItems().size());
            result.put("counts", counts);

            result.put("sessionScope", "current Sweet Home 3D process");
            result.put("multiProcessNote", "A second Sweet Home 3D process must use a different MCP port; "
                    + "documentSessionId and mcpEndpoint should be checked before editing.");
            return result;
        });
        return Response.ok(data);
    }

    private static String absolutePath(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        try {
            Path path = Paths.get(name);
            return path.isAbsolute() ? path.normalize().toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> level(Level level) {
        if (level == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", level.getId());
        result.put("name", level.getName());
        result.put("elevation", level.getElevation());
        result.put("floorThickness", level.getFloorThickness());
        result.put("height", level.getHeight());
        return result;
    }

    @Override
    public String getDescription() {
        return "Identifies the exact Sweet Home 3D document, process, MCP endpoint, selected level, "
                + "dirty state, and object counts. Call this before any edit and compare "
                + "homeId/filePath with the intended home. Pass homeId to every later tool call so "
                + "the server can reject edits aimed at another document. Separate Sweet Home 3D processes "
                + "must use different ports.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create().build();
    }
}
