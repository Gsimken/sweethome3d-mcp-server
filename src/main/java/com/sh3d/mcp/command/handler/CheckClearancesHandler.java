package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeDoorOrWindow;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.ArchitecturalGeometry;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.util.FormatUtil.round2;

/** Detects physical collisions and likely circulation / door-approach bottlenecks. */
public class CheckClearancesHandler implements CommandHandler, CommandDescriptor {

    private static final int HARD_MAX_RESULTS = 500;

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        float minimumClearance = request.getFloat("minimumClearance", 80f);
        float doorClearance = request.getFloat("doorClearance", 90f);
        Boolean proximityParam = request.getBoolean("includeProximity");
        boolean includeProximity = proximityParam == null || proximityParam;
        int maxResults = (int) request.getFloat("maxResults", 200f);
        if (minimumClearance < 0 || minimumClearance > 500) {
            return Response.error("minimumClearance must be between 0 and 500 cm");
        }
        if (doorClearance < 0 || doorClearance > 500) {
            return Response.error("doorClearance must be between 0 and 500 cm");
        }
        if (maxResults < 1 || maxResults > HARD_MAX_RESULTS) {
            return Response.error("maxResults must be between 1 and " + HARD_MAX_RESULTS);
        }

        Map<String, Object> data = accessor.runOnEDT(() -> analyze(accessor.getHome(),
                minimumClearance, doorClearance, includeProximity, maxResults));
        return Response.ok(data);
    }

    private Map<String, Object> analyze(Home home, float minimumClearance,
                                        float doorClearance, boolean includeProximity,
                                        int maxResults) {
        List<HomePieceOfFurniture> pieces = flatten(home.getFurniture());
        List<Object> collisions = new ArrayList<>();
        List<Object> proximities = new ArrayList<>();
        List<Object> doorApproaches = new ArrayList<>();
        List<Object> wallIntrusions = new ArrayList<>();
        boolean truncated = false;

        for (int i = 0; i < pieces.size(); i++) {
            HomePieceOfFurniture first = pieces.get(i);
            if (!first.isVisible()) continue;
            for (int j = i + 1; j < pieces.size(); j++) {
                HomePieceOfFurniture second = pieces.get(j);
                if (!second.isVisible() || !sameLevel(first.getLevel(), second.getLevel())
                        || !verticalOverlap(first, second)) continue;

                double distance = ArchitecturalGeometry.polygonDistance(first.getPoints(), second.getPoints());
                if (distance == 0d) {
                    if (collisions.size() < maxResults) {
                        collisions.add(pair("objectCollision", first, second, 0d,
                                "Furniture footprints and vertical ranges overlap."));
                    } else truncated = true;
                } else if (includeProximity && !first.isDoorOrWindow() && !second.isDoorOrWindow()
                        && distance < minimumClearance) {
                    if (proximities.size() < maxResults) {
                        proximities.add(pair("narrowGap", first, second, distance,
                                "Gap is below the requested circulation clearance."));
                    } else truncated = true;
                }

                if (first.isDoorOrWindow() ^ second.isDoorOrWindow()) {
                    HomePieceOfFurniture opening = first.isDoorOrWindow() ? first : second;
                    HomePieceOfFurniture obstacle = first.isDoorOrWindow() ? second : first;
                    if (likelyDoor((HomeDoorOrWindow) opening) && distance < doorClearance) {
                        if (doorApproaches.size() < maxResults) {
                            doorApproaches.add(pair("doorApproachBlocked", opening, obstacle,
                                    distance, "Obstacle is inside the requested door approach zone."));
                        } else truncated = true;
                    }
                }
            }
        }

        for (HomePieceOfFurniture piece : pieces) {
            if (!piece.isVisible() || piece.isDoorOrWindow()) continue;
            String attachedWall = piece.getProperty("mcp.attachedWallId");
            for (Wall wall : home.getWalls()) {
                if (!sameLevel(piece.getLevel(), wall.getLevel())) continue;
                if (wall.getId().equals(attachedWall)) continue;
                if (ArchitecturalGeometry.polygonsIntersect(piece.getPoints(), wall.getPoints())) {
                    if (wallIntrusions.size() < maxResults) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("type", "wallIntrusion");
                        item.put("objectId", piece.getId());
                        item.put("objectName", piece.getName());
                        item.put("wallId", wall.getId());
                        item.put("message", "Furniture footprint intersects a wall footprint.");
                        wallIntrusions.add(item);
                    } else truncated = true;
                }
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("objectsChecked", pieces.size());
        summary.put("collisions", collisions.size());
        summary.put("narrowGaps", proximities.size());
        summary.put("doorApproachIssues", doorApproaches.size());
        summary.put("wallIntrusions", wallIntrusions.size());
        summary.put("minimumClearance", minimumClearance);
        summary.put("doorClearance", doorClearance);
        summary.put("passes", collisions.isEmpty() && doorApproaches.isEmpty() && wallIntrusions.isEmpty());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("collisions", collisions);
        result.put("doorApproachIssues", doorApproaches);
        result.put("wallIntrusions", wallIntrusions);
        result.put("narrowGaps", proximities);
        result.put("truncated", truncated);
        result.put("limitations", "Clearances are geometric candidates, not a building-code certification. "
                + "Door swing is approximated by a footprint approach zone; confirm sash direction and local code.");
        return result;
    }

    private static Map<String, Object> pair(String type, HomePieceOfFurniture first,
                                            HomePieceOfFurniture second, double distance,
                                            String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("firstId", first.getId());
        item.put("firstName", first.getName());
        item.put("secondId", second.getId());
        item.put("secondName", second.getName());
        item.put("clearance", round2(distance));
        item.put("message", message);
        return item;
    }

    private static boolean verticalOverlap(HomePieceOfFurniture first,
                                           HomePieceOfFurniture second) {
        float firstBottom = first.getGroundElevation();
        float firstTop = firstBottom + first.getHeightInPlan();
        float secondBottom = second.getGroundElevation();
        float secondTop = secondBottom + second.getHeightInPlan();
        return firstBottom < secondTop && secondBottom < firstTop;
    }

    private static boolean likelyDoor(HomeDoorOrWindow opening) {
        String name = opening.getName() != null ? opening.getName().toLowerCase() : "";
        if (name.contains("window") || name.contains("ventana")) return false;
        if (name.contains("door") || name.contains("puerta")) return true;
        return opening.getElevation() <= 5f && opening.getHeight() >= 170f;
    }

    private static boolean sameLevel(Level first, Level second) {
        return first == second || (first != null && second != null && first.getId().equals(second.getId()));
    }

    private static List<HomePieceOfFurniture> flatten(List<HomePieceOfFurniture> roots) {
        List<HomePieceOfFurniture> result = new ArrayList<>();
        for (HomePieceOfFurniture piece : roots) {
            if (piece instanceof HomeFurnitureGroup) {
                result.addAll(flatten(((HomeFurnitureGroup) piece).getFurniture()));
            } else {
                result.add(piece);
            }
        }
        return result;
    }

    @Override
    public String getDescription() {
        return "Checks furniture collisions, wall intrusions, door approach zones and likely narrow "
                + "circulation gaps using the real rotated footprints and vertical ranges of objects. "
                + "Run after a proposed layout and before saving. Results are geometric warnings, not "
                + "a substitute for local accessibility/building-code review.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .numberWithDefault("minimumClearance",
                        "Minimum desired free gap between furniture footprints in cm", 80)
                .numberWithDefault("doorClearance",
                        "Minimum clear approach zone around floor-level doors in cm", 90)
                .boolWithDefault("includeProximity",
                        "Include non-colliding furniture pairs closer than minimumClearance", true)
                .integerWithDefault("maxResults",
                        "Maximum results returned in each category (1-500)", 200)
                .build();
    }
}
