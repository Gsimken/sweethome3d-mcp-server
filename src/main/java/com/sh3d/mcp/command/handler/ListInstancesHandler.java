package com.sh3d.mcp.command.handler;

import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Lists documents reachable through the current Sweet Home 3D MCP process. */
public class ListInstancesHandler implements CommandHandler, CommandDescriptor {
    private final Supplier<List<Map<String, Object>>> supplier;

    public ListInstancesHandler(Supplier<List<Map<String, Object>>> supplier) {
        this.supplier = supplier;
    }

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        List<Map<String, Object>> instances = supplier.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instances", instances);
        result.put("count", instances.size());
        result.put("scope", "documents open in this Sweet Home 3D process");
        result.put("crossProcessNote", "Other Sweet Home 3D processes use separate MCP ports and "
                + "must be configured as separate MCP endpoints.");
        return Response.ok(result);
    }

    @Override
    public String getDescription() {
        return "Lists every open Sweet Home 3D document reachable through this MCP process, including "
                + "homeId, file path, modified state and which document is active. Use activate_home "
                + "before editing a different listed document.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create().build();
    }
}
