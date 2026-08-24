package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeObject;
import com.eteks.sweethome3d.model.Selectable;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.ObjectContextBuilder;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Returns focused, detailed context for selected objects or explicit stable IDs. */
public class InspectObjectsHandler implements CommandHandler, CommandDescriptor {

    private static final int MAX_IDS = 50;

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        Map<String, Object> params = request.getParams();
        Object idsValue = params.get("ids");
        Boolean relationsValue = request.getBoolean("includeRelations");
        Boolean propertiesValue = request.getBoolean("includeCustomProperties");

        int propertyLimit = ObjectContextBuilder.DEFAULT_PROPERTY_LIMIT;
        Object limitValue = params.get("maxPropertyLength");
        if (limitValue != null) {
            if (!(limitValue instanceof Number)) {
                return Response.error("Parameter 'maxPropertyLength' must be an integer");
            }
            propertyLimit = ((Number) limitValue).intValue();
            if (propertyLimit < 64 || propertyLimit > ObjectContextBuilder.MAX_PROPERTY_LIMIT) {
                return Response.error("Parameter 'maxPropertyLength' must be between 64 and "
                        + ObjectContextBuilder.MAX_PROPERTY_LIMIT);
            }
        }

        List<String> ids;
        try {
            ids = parseIds(idsValue);
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        }
        final boolean includeRelations = relationsValue == null || relationsValue;
        final boolean includeProperties = propertiesValue == null || propertiesValue;
        final int finalPropertyLimit = propertyLimit;

        Map<String, Object> data = accessor.runOnEDT(() -> inspect(accessor.getHome(), ids,
                includeRelations, includeProperties, finalPropertyLimit));
        return Response.ok(data);
    }

    private Map<String, Object> inspect(Home home, List<String> requestedIds,
                                        boolean includeRelations, boolean includeProperties,
                                        int propertyLimit) {
        List<String> ids = requestedIds;
        if (ids == null) {
            ids = new ArrayList<>();
            for (Selectable selected : home.getSelectedItems()) {
                if (selected instanceof HomeObject) {
                    ids.add(((HomeObject) selected).getId());
                }
            }
        }

        Map<String, HomeObject> available = new LinkedHashMap<>();
        for (HomeObject object : home.getHomeObjects()) {
            available.put(object.getId(), object);
        }

        List<Object> objects = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String id : ids) {
            HomeObject object = available.get(id);
            if (object == null) {
                notFound.add(id);
            } else {
                objects.add(ObjectContextBuilder.buildDetailed(object, home, includeRelations,
                        includeProperties, propertyLimit));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("source", requestedIds == null ? "currentSelection" : "requestedIds");
        data.put("objectCount", objects.size());
        data.put("objects", objects);
        data.put("notFoundIds", notFound);
        data.put("units", "centimeters");
        data.put("angleUnits", "degrees");
        if (ids.isEmpty()) {
            data.put("hint", "No objects were selected. Pass stable IDs from get_state in 'ids'.");
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseIds(Object value) {
        if (value == null) return null;
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("Parameter 'ids' must be an array of strings");
        }
        List<Object> raw = (List<Object>) value;
        if (raw.size() > MAX_IDS) {
            throw new IllegalArgumentException("Parameter 'ids' accepts at most " + MAX_IDS + " IDs");
        }
        List<String> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : raw) {
            if (!(item instanceof String) || ((String) item).trim().isEmpty()) {
                throw new IllegalArgumentException("Every item in 'ids' must be a non-empty string");
            }
            String id = ((String) item).trim();
            if (seen.add(id)) ids.add(id);
        }
        return ids;
    }

    @Override
    public String getDescription() {
        return "Returns deep, focused context for Sweet Home 3D objects: complete geometry, "
                + "materials and textures, catalog metadata, editing capabilities, group/room/wall "
                + "relationships, door/window and light details, selection state, and custom "
                + "HomeObject properties written by third-party plugins. Pass stable IDs from "
                + "get_state, or omit ids to inspect the current UI selection. Prefer this before "
                + "modifying unfamiliar or plugin-generated objects.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .array("ids", SchemaBuilder.arrayDef(
                                "Stable object or level IDs from get_state. Omit to inspect the current selection.")
                        .itemsOfType("string").maxItems(MAX_IDS).build())
                .boolWithDefault("includeRelations",
                        "Include room containment, group parent and wall intersection relationships", true)
                .boolWithDefault("includeCustomProperties",
                        "Include properties stored by Sweet Home 3D and third-party plugins", true)
                .integerWithDefault("maxPropertyLength",
                        "Maximum characters returned for each custom property (64-8192)",
                        ObjectContextBuilder.DEFAULT_PROPERTY_LIMIT)
                .build();
    }
}
