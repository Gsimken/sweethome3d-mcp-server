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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.sh3d.mcp.command.util.FormatUtil.round2;

/** Builds a semantic, room-aware interpretation of the current home. */
public class AnalyzeArchitectureHandler implements CommandHandler, CommandDescriptor {

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        Map<String, Object> data = accessor.runOnEDT(() -> analyze(accessor.getHome()));
        return Response.ok(data);
    }

    private Map<String, Object> analyze(Home home) {
        List<HomePieceOfFurniture> furniture = flatten(home.getFurniture());
        List<HomeDoorOrWindow> openings = new ArrayList<>();
        for (HomePieceOfFurniture piece : furniture) {
            if (piece instanceof HomeDoorOrWindow) openings.add((HomeDoorOrWindow) piece);
        }

        List<Object> wallResults = new ArrayList<>();
        int exteriorWalls = 0;
        int interiorWalls = 0;
        int unclassifiedWalls = 0;
        for (Wall wall : home.getWalls()) {
            Map<String, Object> item = wallInfo(wall, home.getRooms(), openings);
            String role = item.get("architecturalRole").toString();
            if ("exteriorWall".equals(role)) exteriorWalls++;
            else if ("partitionWall".equals(role)) interiorWalls++;
            else unclassifiedWalls++;
            wallResults.add(item);
        }

        List<Object> openingResults = new ArrayList<>();
        int doors = 0;
        int windows = 0;
        for (HomeDoorOrWindow opening : openings) {
            Map<String, Object> item = openingInfo(opening, home.getWalls(), home.getRooms());
            if ("door".equals(item.get("openingType"))) doors++; else windows++;
            openingResults.add(item);
        }

        List<Object> roomResults = new ArrayList<>();
        List<Object> warnings = new ArrayList<>();
        for (Room room : home.getRooms()) {
            Map<String, Object> item = roomInfo(room, home.getWalls(), openings, furniture);
            if (Boolean.FALSE.equals(item.get("hasDoorAccess"))) {
                warnings.add(warning("roomWithoutDetectedDoor", room.getId(),
                        "No floor-level door was detected for this room."));
            }
            roomResults.add(item);
        }

        List<Object> protectedElements = new ArrayList<>();
        for (HomePieceOfFurniture piece : furniture) {
            String role = protectedRole(piece);
            if (role != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", piece.getId());
                item.put("name", piece.getName());
                item.put("role", role);
                item.put("movable", piece.isMovable());
                item.put("reason", piece.isDoorOrWindow()
                        ? "architectural opening"
                        : (!piece.isMovable() ? "marked non-movable" : "installation name/category heuristic"));
                protectedElements.add(item);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("levels", home.getLevels().size());
        summary.put("rooms", home.getRooms().size());
        summary.put("walls", home.getWalls().size());
        summary.put("exteriorWalls", exteriorWalls);
        summary.put("partitionWalls", interiorWalls);
        summary.put("unclassifiedWalls", unclassifiedWalls);
        summary.put("doors", doors);
        summary.put("windows", windows);
        summary.put("protectedElements", protectedElements.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("levels", levels(home.getLevels(), home.getSelectedLevel()));
        result.put("walls", wallResults);
        result.put("openings", openingResults);
        result.put("rooms", roomResults);
        result.put("protectedElements", protectedElements);
        result.put("warnings", warnings);
        result.put("interpretation", "Wall roles and installation roles are inferred from geometry and metadata; "
                + "review low-confidence/unclassified results before editing the base plan.");
        return result;
    }

    private Map<String, Object> wallInfo(Wall wall, List<Room> rooms,
                                         List<HomeDoorOrWindow> openings) {
        List<String> adjacentRooms = new ArrayList<>();
        for (Room room : rooms) {
            if (sameLevel(wall.getLevel(), room.getLevel())
                    && ArchitecturalGeometry.polygonDistance(wall.getPoints(), room.getPoints()) <= 2d) {
                adjacentRooms.add(room.getId());
            }
        }
        List<String> openingIds = new ArrayList<>();
        for (HomeDoorOrWindow opening : openings) {
            if (sameLevel(wall.getLevel(), opening.getLevel())
                    && ArchitecturalGeometry.polygonsIntersect(wall.getPoints(), opening.getPoints())) {
                openingIds.add(opening.getId());
            }
        }
        String role;
        String confidence;
        if (adjacentRooms.size() >= 2) {
            role = "partitionWall";
            confidence = "high";
        } else if (adjacentRooms.size() == 1) {
            role = "exteriorWall";
            confidence = "medium";
        } else {
            role = "unclassifiedWall";
            confidence = "low";
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", wall.getId());
        item.put("architecturalRole", role);
        item.put("confidence", confidence);
        item.put("xStart", round2(wall.getXStart()));
        item.put("yStart", round2(wall.getYStart()));
        item.put("xEnd", round2(wall.getXEnd()));
        item.put("yEnd", round2(wall.getYEnd()));
        item.put("length", round2(ArchitecturalGeometry.length(
                wall.getXStart(), wall.getYStart(), wall.getXEnd(), wall.getYEnd())));
        item.put("thickness", round2(wall.getThickness()));
        item.put("height", round2(wall.getHeight()));
        item.put("levelId", id(wall.getLevel()));
        item.put("adjacentRoomIds", adjacentRooms);
        item.put("openingIds", openingIds);
        item.put("connectedAtStartId", wall.getWallAtStart() != null ? wall.getWallAtStart().getId() : null);
        item.put("connectedAtEndId", wall.getWallAtEnd() != null ? wall.getWallAtEnd().getId() : null);
        return item;
    }

    private Map<String, Object> openingInfo(HomeDoorOrWindow opening,
                                            Collection<Wall> walls, List<Room> rooms) {
        List<String> wallIds = new ArrayList<>();
        for (Wall wall : walls) {
            if (sameLevel(wall.getLevel(), opening.getLevel())
                    && ArchitecturalGeometry.polygonsIntersect(wall.getPoints(), opening.getPoints())) {
                wallIds.add(wall.getId());
            }
        }
        List<String> roomIds = containingRooms(rooms, opening);
        boolean door = isLikelyDoor(opening);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", opening.getId());
        item.put("name", opening.getName());
        item.put("openingType", door ? "door" : "window");
        item.put("classificationConfidence", classificationConfidence(opening));
        item.put("x", round2(opening.getX()));
        item.put("y", round2(opening.getY()));
        item.put("elevation", round2(opening.getElevation()));
        item.put("width", round2(opening.getWidth()));
        item.put("depth", round2(opening.getDepth()));
        item.put("height", round2(opening.getHeight()));
        item.put("angle", round2(Math.toDegrees(opening.getAngle())));
        item.put("boundToWall", opening.isBoundToWall());
        item.put("sashCount", opening.getSashes().length);
        item.put("wallIds", wallIds);
        item.put("roomIds", roomIds);
        item.put("levelId", id(opening.getLevel()));
        return item;
    }

    private Map<String, Object> roomInfo(Room room, Collection<Wall> walls,
                                         List<HomeDoorOrWindow> openings,
                                         List<HomePieceOfFurniture> furniture) {
        List<String> wallIds = new ArrayList<>();
        for (Wall wall : walls) {
            if (sameLevel(room.getLevel(), wall.getLevel())
                    && ArchitecturalGeometry.polygonDistance(room.getPoints(), wall.getPoints()) <= 2d) {
                wallIds.add(wall.getId());
            }
        }
        List<String> openingIds = new ArrayList<>();
        boolean hasDoor = false;
        for (HomeDoorOrWindow opening : openings) {
            if (containingRooms(java.util.Collections.singletonList(room), opening).isEmpty()) continue;
            openingIds.add(opening.getId());
            if (isLikelyDoor(opening)) hasDoor = true;
        }
        List<String> furnitureIds = new ArrayList<>();
        for (HomePieceOfFurniture piece : furniture) {
            if (!piece.isDoorOrWindow() && sameLevel(room.getLevel(), piece.getLevel())
                    && room.containsPoint(piece.getX(), piece.getY(), 0f)) {
                furnitureIds.add(piece.getId());
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", room.getId());
        item.put("name", room.getName());
        item.put("levelId", id(room.getLevel()));
        item.put("areaSquareMeters", round2(room.getArea() / 10000d));
        item.put("centerX", round2(room.getXCenter()));
        item.put("centerY", round2(room.getYCenter()));
        item.put("wallIds", wallIds);
        item.put("openingIds", openingIds);
        item.put("furnitureIds", furnitureIds);
        item.put("hasDoorAccess", hasDoor);
        return item;
    }

    private static List<String> containingRooms(List<Room> rooms, HomePieceOfFurniture piece) {
        List<String> ids = new ArrayList<>();
        for (Room room : rooms) {
            if (!sameLevel(room.getLevel(), piece.getLevel())) continue;
            float[][] points = piece.getPoints();
            boolean contained = room.containsPoint(piece.getX(), piece.getY(), 0f);
            if (!contained) {
                for (float[] point : points) {
                    if (room.containsPoint(point[0], point[1], 0f)) {
                        contained = true;
                        break;
                    }
                }
            }
            if (contained) ids.add(room.getId());
        }
        return ids;
    }

    private static boolean isLikelyDoor(HomeDoorOrWindow opening) {
        String text = normalized(opening.getName()) + " " + normalized(opening.getDescription());
        if (containsAny(text, "window", "ventana", "fenetre", "fenster")) return false;
        if (containsAny(text, "door", "puerta", "porte", "tur")) return true;
        return opening.getElevation() <= 5f && opening.getHeight() >= 170f;
    }

    private static String classificationConfidence(HomeDoorOrWindow opening) {
        String text = normalized(opening.getName()) + " " + normalized(opening.getDescription());
        return containsAny(text, "door", "puerta", "window", "ventana", "porte", "fenetre")
                ? "high" : "medium";
    }

    private static String protectedRole(HomePieceOfFurniture piece) {
        if (piece.isDoorOrWindow()) return isLikelyDoor((HomeDoorOrWindow) piece) ? "door" : "window";
        if (!piece.isMovable()) return "fixedElement";
        String text = normalized(piece.getName()) + " " + normalized(piece.getDescription());
        if (containsAny(text, "toilet", "wc", "inodoro", "lavabo", "sink", "fregadero",
                "bathtub", "shower", "ducha", "radiator", "caldera", "boiler")) {
            return "installation";
        }
        if ("staircase".equals(piece.getProperty("mcp.semanticType"))
                || piece.getStaircaseCutOutShape() != null
                || containsAny(text, "stair", "escalera")) return "staircase";
        return null;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean sameLevel(Level first, Level second) {
        return first == second || (first != null && second != null && first.getId().equals(second.getId()));
    }

    private static String id(Level level) {
        return level != null ? level.getId() : null;
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

    private static List<Object> levels(List<Level> levels, Level selected) {
        List<Object> result = new ArrayList<>();
        for (Level level : levels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", level.getId());
            item.put("name", level.getName());
            item.put("elevation", round2(level.getElevation()));
            item.put("height", round2(level.getHeight()));
            item.put("floorThickness", round2(level.getFloorThickness()));
            item.put("selected", level == selected);
            result.add(item);
        }
        return result;
    }

    private static Map<String, Object> warning(String type, String objectId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("objectId", objectId);
        result.put("message", message);
        return result;
    }

    @Override
    public String getDescription() {
        return "Interprets the home as architecture rather than a flat object list. Classifies exterior "
                + "and partition walls, maps openings to walls and rooms, identifies protected/fixed "
                + "installations, reports room access, levels and low-confidence warnings. Run before "
                + "moving furniture or changing the base plan.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create().build();
    }
}
