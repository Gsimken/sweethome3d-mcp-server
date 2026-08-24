package com.sh3d.mcp.command.handler;

import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Switches the shared MCP endpoint to another open Home in the same process. */
public class ActivateHomeHandler implements CommandHandler, CommandDescriptor {
    private final Function<String, Boolean> activator;

    public ActivateHomeHandler(Function<String, Boolean> activator) {
        this.activator = activator;
    }

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String homeId = request.getString("targetHomeId");
        if (homeId == null || homeId.trim().isEmpty()) {
            return Response.error("Missing required parameter 'targetHomeId'");
        }
        if (!activator.apply(homeId)) {
            return Response.error("Open home not found in this Sweet Home 3D process: " + homeId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeHomeId", homeId);
        result.put("activated", true);
        result.put("nextStep", "Call get_document_context on the next request, then pass its homeId to edits.");
        return Response.ok(result);
    }

    @Override
    public String getDescription() {
        return "Activates one of the documents returned by list_instances. The endpoint changes context "
                + "after this response; call get_document_context before any edit.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredString("targetHomeId", "homeId returned by list_instances")
                .build();
    }
}
