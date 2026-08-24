package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.HomeIdentity;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tool-level health and active-document check. */
public class HealthCheckHandler implements CommandHandler, CommandDescriptor {
    private final int port;

    public HealthCheckHandler(int port) {
        this.port = port;
    }

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        return Response.ok(accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("homeId", HomeIdentity.documentId(home));
            result.put("name", home.getName());
            result.put("modified", home.isModified());
            result.put("processId", ProcessHandle.current().pid());
            result.put("port", port);
            result.put("mcpEndpoint", "http://127.0.0.1:" + port + "/mcp");
            result.put("httpHealthEndpoint", "http://127.0.0.1:" + port + "/health");
            return result;
        }));
    }

    @Override
    public String getDescription() {
        return "Checks that the MCP tool session is responsive and returns the exact active homeId, "
                + "process and port. Safe to call after reconnecting or before a batch of edits.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create().build();
    }
}
