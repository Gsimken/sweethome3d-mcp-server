package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.util.FormatUtil.round2;

/** Reports disconnected, almost-connected and inconsistent wall endpoints. */
public class ValidateWallJunctionsHandler implements CommandHandler, CommandDescriptor {

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        float tolerance = request.getFloat("tolerance", 1f);
        float nearGap = request.getFloat("nearGap", 10f);
        String levelId = request.getString("levelId");
        if (tolerance < 0f || nearGap < tolerance || nearGap > 100f) {
            return Response.error("Require 0 <= tolerance <= nearGap <= 100 cm");
        }
        return Response.ok(accessor.runOnEDT(() -> analyze(accessor.getHome(), levelId,
                tolerance, nearGap)));
    }

    private static Map<String, Object> analyze(Home home, String levelId,
                                               float tolerance, float nearGap) {
        List<Wall> walls = new ArrayList<>();
        for (Wall wall : home.getWalls()) {
            if (levelId == null || (wall.getLevel() != null && levelId.equals(wall.getLevel().getId()))) {
                walls.add(wall);
            }
        }
        List<Object> nearMisses = new ArrayList<>();
        List<Object> inconsistentLinks = new ArrayList<>();
        int joinedEndpoints = 0;
        for (Wall wall : walls) {
            for (int endpoint = 0; endpoint < 2; endpoint++) {
                float x = endpoint == 0 ? wall.getXStart() : wall.getXEnd();
                float y = endpoint == 0 ? wall.getYStart() : wall.getYEnd();
                Wall linked = endpoint == 0 ? wall.getWallAtStart() : wall.getWallAtEnd();
                if (linked != null) {
                    double distance = nearestEndpointDistance(x, y, linked);
                    if (distance <= tolerance) joinedEndpoints++;
                    else inconsistentLinks.add(issue("inconsistentLink", wall, endpoint, linked,
                            distance, "Stored wall link does not share the same endpoint."));
                } else {
                    Wall nearest = null;
                    double nearestDistance = Double.POSITIVE_INFINITY;
                    for (Wall other : walls) {
                        if (other == wall) continue;
                        double distance = nearestEndpointDistance(x, y, other);
                        if (distance < nearestDistance) {
                            nearestDistance = distance;
                            nearest = other;
                        }
                    }
                    if (nearest != null && nearestDistance > tolerance && nearestDistance <= nearGap) {
                        nearMisses.add(issue("nearMiss", wall, endpoint, nearest, nearestDistance,
                                "Wall endpoints are close but not joined; this may leave a room or finish gap."));
                    } else if (nearestDistance <= tolerance) {
                        joinedEndpoints++;
                    }
                }
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("wallsChecked", walls.size());
        summary.put("joinedEndpoints", joinedEndpoints);
        summary.put("nearMisses", nearMisses.size());
        summary.put("inconsistentLinks", inconsistentLinks.size());
        summary.put("valid", nearMisses.isEmpty() && inconsistentLinks.isEmpty());
        summary.put("tolerance", tolerance);
        summary.put("nearGap", nearGap);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("nearMisses", nearMisses);
        result.put("inconsistentLinks", inconsistentLinks);
        return result;
    }

    private static Map<String, Object> issue(String type, Wall wall, int endpoint,
                                             Wall other, double distance, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("wallId", wall.getId());
        result.put("endpoint", endpoint == 0 ? "start" : "end");
        result.put("otherWallId", other.getId());
        result.put("gap", round2(distance));
        result.put("message", message);
        return result;
    }

    private static double nearestEndpointDistance(float x, float y, Wall wall) {
        return Math.min(Math.hypot(x - wall.getXStart(), y - wall.getYStart()),
                Math.hypot(x - wall.getXEnd(), y - wall.getYEnd()));
    }

    @Override
    public String getDescription() {
        return "Validates wall junctions without modifying the model. Reports endpoints that nearly "
                + "meet and stored wall links whose coordinates are inconsistent. Run after wall edits "
                + "and before clearance analysis or saving.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .string("levelId", "Only validate walls on this level")
                .numberWithDefault("tolerance", "Distance treated as a joined endpoint in cm", 1)
                .numberWithDefault("nearGap", "Maximum gap reported as an almost-join in cm", 10)
                .build();
    }
}
