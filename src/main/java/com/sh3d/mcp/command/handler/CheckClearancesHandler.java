package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeDoorOrWindow;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.ArchitecturalGeometry;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

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
        Map<String, Object> params = request.getParams();
        Filter filter;
        try {
            filter = new Filter(request.getString("levelId"),
                    strings(params.get("objectIds"), "objectIds"),
                    strings(params.get("roomIds"), "roomIds"),
                    strings(params.get("ignoreIds"), "ignoreIds"),
                    strings(params.get("ignoreCategories"), "ignoreCategories"),
                    defaultTrue(request.getBoolean("includeRoof"), false),
                    defaultTrue(request.getBoolean("includeCladding"), false));
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        }
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
                minimumClearance, doorClearance, includeProximity, maxResults, filter));
        return Response.ok(data);
    }

    private Map<String, Object> analyze(Home home, float minimumClearance,
                                        float doorClearance, boolean includeProximity,
                                        int maxResults, Filter filter) {
        List<HomePieceOfFurniture> pieces = filter(home, flatten(home.getFurniture()), filter);
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
                        && distance < minimumClearance && !wallSeparates(home, first, second)) {
                    if (proximities.size() < maxResults) {
                        proximities.add(pair("narrowGap", first, second, distance,
                                "Gap is below the requested circulation clearance."));
                    } else truncated = true;
                }

                if (first.isDoorOrWindow() ^ second.isDoorOrWindow()) {
                    HomePieceOfFurniture opening = first.isDoorOrWindow() ? first : second;
                    HomePieceOfFurniture obstacle = first.isDoorOrWindow() ? second : first;
                    if (likelyDoor(opening) && distance < doorClearance) {
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
                        item.put("objectFootprint", points(piece.getPoints()));
                        item.put("wallFootprint", points(wall.getPoints()));
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
        summary.put("filters", filter.describe());
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
        item.put("firstFootprint", points(first.getPoints()));
        item.put("secondFootprint", points(second.getPoints()));
        if (first.isDoorOrWindow()) item.put("openingSemantics", openingSemantics(first));
        if (second.isDoorOrWindow()) item.put("openingSemantics", openingSemantics(second));
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

    private static boolean likelyDoor(HomePieceOfFurniture opening) {
        String semantic = opening.getProperty("mcp.openingType");
        if ("door".equals(semantic)) return true;
        if ("window".equals(semantic)) return false;
        String name = opening.getName() != null ? opening.getName().toLowerCase() : "";
        if (name.contains("window") || name.contains("ventana")) return false;
        if (name.contains("door") || name.contains("puerta")) return true;
        return opening.getElevation() <= 5f && opening.getHeight() >= 170f;
    }

    private static Map<String, Object> openingSemantics(HomePieceOfFurniture opening) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openingType", opening.getProperty("mcp.openingType"));
        result.put("mechanism", opening.getProperty("mcp.openingMechanism"));
        result.put("hingeSide", opening.getProperty("mcp.hingeSide"));
        result.put("swingDirection", opening.getProperty("mcp.swingDirection"));
        result.put("hostWallId", opening.getProperty("mcp.hostWallId"));
        result.put("nativeDoorOrWindow", opening instanceof HomeDoorOrWindow);
        return result;
    }

    private static List<Object> points(float[][] source) {
        List<Object> result = new ArrayList<>();
        if (source == null) return result;
        for (float[] point : source) {
            List<Object> coordinate = new ArrayList<>();
            coordinate.add(round2(point[0]));
            coordinate.add(round2(point[1]));
            result.add(coordinate);
        }
        return result;
    }

    private static boolean wallSeparates(Home home, HomePieceOfFurniture first,
                                         HomePieceOfFurniture second) {
        for (Wall wall : home.getWalls()) {
            if (!sameLevel(first.getLevel(), wall.getLevel())) continue;
            float[][] polygon = wall.getPoints();
            if (lineIntersectsPolygon(first.getX(), first.getY(), second.getX(), second.getY(), polygon)) {
                return true;
            }
        }
        return false;
    }

    private static boolean lineIntersectsPolygon(float x1, float y1, float x2, float y2,
                                                 float[][] polygon) {
        if (polygon == null) return false;
        for (int i = 0; i < polygon.length; i++) {
            float[] a = polygon[i];
            float[] b = polygon[(i + 1) % polygon.length];
            if (ArchitecturalGeometry.segmentDistance(x1, y1, x2, y2,
                    a[0], a[1], b[0], b[1]) < 0.01d) return true;
        }
        return false;
    }

    private static List<HomePieceOfFurniture> filter(Home home,
                                                      List<HomePieceOfFurniture> source,
                                                      Filter filter) {
        if (filter == null) return source;
        List<HomePieceOfFurniture> result = new ArrayList<>();
        for (HomePieceOfFurniture piece : source) {
            if (filter.levelId != null && (piece.getLevel() == null
                    || !filter.levelId.equals(piece.getLevel().getId()))) continue;
            if (!filter.objectIds.isEmpty() && !filter.objectIds.contains(piece.getId())) continue;
            if (filter.ignoreIds.contains(piece.getId())) continue;
            String category = semanticCategory(piece);
            if (filter.ignoreCategories.contains(category)) continue;
            if (!filter.includeRoof && "roof".equals(category)) continue;
            if (!filter.includeCladding && "cladding".equals(category)) continue;
            if (!filter.roomIds.isEmpty() && !insideAnyRoom(home, piece, filter.roomIds)) continue;
            result.add(piece);
        }
        return result;
    }

    private static boolean insideAnyRoom(Home home, HomePieceOfFurniture piece, Set<String> roomIds) {
        for (Room room : home.getRooms()) {
            if (roomIds.contains(room.getId()) && sameLevel(room.getLevel(), piece.getLevel())
                    && room.containsPoint(piece.getX(), piece.getY(), 0f)) return true;
        }
        return false;
    }

    private static String semanticCategory(HomePieceOfFurniture piece) {
        String explicit = piece.getProperty("mcp.semanticType");
        String value = explicit != null ? explicit : piece.getName();
        value = value != null ? value.toLowerCase(Locale.ROOT) : "";
        if (value.contains("roof") || value.contains("techo") || value.contains("cubierta")) return "roof";
        if (value.contains("cladding") || value.contains("revestimiento")) return "cladding";
        if (piece.isDoorOrWindow()) return likelyDoor(piece) ? "door" : "window";
        return "furniture";
    }

    private static Set<String> strings(Object value, String parameter) {
        if (value == null) return Collections.emptySet();
        if (!(value instanceof Collection)) {
            throw new IllegalArgumentException(parameter + " must be an array of strings");
        }
        Set<String> result = new HashSet<>();
        for (Object item : (Collection<?>) value) {
            if (item != null) result.add(item.toString());
        }
        return result;
    }

    private static boolean defaultTrue(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static final class Filter {
        final String levelId;
        final Set<String> objectIds;
        final Set<String> roomIds;
        final Set<String> ignoreIds;
        final Set<String> ignoreCategories;
        final boolean includeRoof;
        final boolean includeCladding;

        Filter(String levelId, Set<String> objectIds, Set<String> roomIds,
               Set<String> ignoreIds, Set<String> ignoreCategories,
               boolean includeRoof, boolean includeCladding) {
            this.levelId = levelId;
            this.objectIds = objectIds;
            this.roomIds = roomIds;
            this.ignoreIds = ignoreIds;
            Set<String> normalized = new HashSet<>();
            for (String category : ignoreCategories) normalized.add(category.toLowerCase(Locale.ROOT));
            this.ignoreCategories = normalized;
            this.includeRoof = includeRoof;
            this.includeCladding = includeCladding;
        }

        Map<String, Object> describe() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("levelId", levelId);
            result.put("objectIds", objectIds);
            result.put("roomIds", roomIds);
            result.put("ignoreIds", ignoreIds);
            result.put("ignoreCategories", ignoreCategories);
            result.put("includeRoof", includeRoof);
            result.put("includeCladding", includeCladding);
            return result;
        }
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
                .string("levelId", "Only analyze objects on this level ID")
                .array("objectIds", SchemaBuilder.arrayDef(
                        "Only analyze these furniture/opening IDs").itemsOfType("string").build())
                .array("roomIds", SchemaBuilder.arrayDef(
                        "Only analyze objects whose center lies inside these rooms").itemsOfType("string").build())
                .array("ignoreIds", SchemaBuilder.arrayDef(
                        "Object IDs to exclude").itemsOfType("string").build())
                .array("ignoreCategories", SchemaBuilder.arrayDef(
                        "Semantic categories to exclude, such as furniture, door, window, roof or cladding")
                        .itemsOfType("string").build())
                .boolWithDefault("includeRoof", "Include roof-like helper geometry", false)
                .boolWithDefault("includeCladding", "Include cladding / wall-finish helper geometry", false)
                .build();
    }
}
