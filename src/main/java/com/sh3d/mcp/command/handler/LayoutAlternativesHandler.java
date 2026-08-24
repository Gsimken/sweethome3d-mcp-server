package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeObject;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.CheckpointManager;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.LayoutAlternativeManager;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.ObjectContextBuilder;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.sh3d.mcp.command.util.FormatUtil.round2;

/** Saves, compares and restores independent named layout proposals. */
public class LayoutAlternativesHandler implements CommandHandler, CommandDescriptor {

    private final LayoutAlternativeManager alternatives;
    private final CheckpointManager checkpoints;

    public LayoutAlternativesHandler(LayoutAlternativeManager alternatives,
                                     CheckpointManager checkpoints) {
        this.alternatives = alternatives;
        this.checkpoints = checkpoints;
    }

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String action = request.getString("action");
        if (action == null) return Response.error("Missing required parameter 'action'");
        switch (action) {
            case "list":
                return list();
            case "save":
                return save(request, accessor);
            case "compare":
                return compare(request, accessor);
            case "restore":
                return restore(request, accessor);
            default:
                return Response.error("Unknown action '" + action + "'. Use save, list, compare, or restore");
        }
    }

    private Response list() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("alternativeCount", alternatives.size());
        data.put("maxAlternatives", LayoutAlternativeManager.MAX_ALTERNATIVES);
        data.put("alternatives", alternatives.list());
        return Response.ok(data);
    }

    private Response save(Request request, HomeAccessor accessor) {
        String name = cleanName(request.getString("name"));
        if (name == null) return Response.error("name is required for action='save'");
        String description = request.getString("description");
        Home snapshot = accessor.runOnEDT(() -> accessor.getHome().clone());
        try {
            alternatives.save(name, description, snapshot);
        } catch (IllegalStateException e) {
            return Response.error(e.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("saved", name);
        data.put("description", description);
        data.put("alternativeCount", alternatives.size());
        data.put("walls", snapshot.getWalls().size());
        data.put("rooms", snapshot.getRooms().size());
        data.put("furniture", snapshot.getFurniture().size());
        return Response.ok(data);
    }

    private Response compare(Request request, HomeAccessor accessor) {
        String name = cleanName(request.getString("name"));
        if (name == null) return Response.error("name is required for action='compare'");
        String otherName = cleanName(request.getString("otherName"));
        Home first = alternatives.getClone(name);
        if (first == null) return Response.error("Layout alternative not found: " + name);
        Home second;
        String secondLabel;
        if (otherName != null) {
            second = alternatives.getClone(otherName);
            if (second == null) return Response.error("Layout alternative not found: " + otherName);
            secondLabel = otherName;
        } else {
            second = accessor.runOnEDT(() -> accessor.getHome().clone());
            secondLabel = "current";
        }
        return Response.ok(buildComparison(name, first, secondLabel, second));
    }

    private Response restore(Request request, HomeAccessor accessor) {
        String name = cleanName(request.getString("name"));
        if (name == null) return Response.error("name is required for action='restore'");
        Home source = alternatives.getClone(name);
        if (source == null) return Response.error("Layout alternative not found: " + name);
        checkpoints.autoCheckpoint(accessor, "Auto: before restoring layout alternative '" + name + "'");
        accessor.runOnEDT(() -> {
            restoreHome(accessor.getHome(), source);
            return null;
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("restored", name);
        data.put("walls", source.getWalls().size());
        data.put("rooms", source.getRooms().size());
        data.put("furniture", source.getFurniture().size());
        data.put("checkpointCreated", true);
        return Response.ok(data);
    }

    private static Map<String, Object> buildComparison(String firstName, Home first,
                                                       String secondName, Home second) {
        Map<String, HomeObject> firstObjects = objects(first);
        Map<String, HomeObject> secondObjects = objects(second);
        List<Object> added = new ArrayList<>();
        List<Object> removed = new ArrayList<>();
        List<Object> changed = new ArrayList<>();

        for (Map.Entry<String, HomeObject> entry : secondObjects.entrySet()) {
            HomeObject previous = firstObjects.get(entry.getKey());
            if (previous == null) {
                added.add(identity(entry.getValue()));
            } else {
                Map<String, Object> before = state(previous);
                Map<String, Object> after = state(entry.getValue());
                List<String> fields = changedFields(before, after);
                if (!fields.isEmpty()) {
                    Map<String, Object> item = identity(entry.getValue());
                    item.put("changedFields", fields);
                    item.put("before", before);
                    item.put("after", after);
                    changed.add(item);
                }
            }
        }
        for (Map.Entry<String, HomeObject> entry : firstObjects.entrySet()) {
            if (!secondObjects.containsKey(entry.getKey())) removed.add(identity(entry.getValue()));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("added", added.size());
        summary.put("removed", removed.size());
        summary.put("changed", changed.size());
        summary.put("identical", added.isEmpty() && removed.isEmpty() && changed.isEmpty());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base", firstName);
        result.put("comparison", secondName);
        result.put("summary", summary);
        result.put("addedObjects", added);
        result.put("removedObjects", removed);
        result.put("changedObjects", changed);
        result.put("baseCounts", counts(first));
        result.put("comparisonCounts", counts(second));
        return result;
    }

    private static Map<String, HomeObject> objects(Home home) {
        Map<String, HomeObject> result = new LinkedHashMap<>();
        for (HomeObject object : home.getHomeObjects()) {
            if (object instanceof Wall || object instanceof Room || object instanceof HomePieceOfFurniture) {
                result.put(object.getId(), object);
            }
        }
        return result;
    }

    private static Map<String, Object> state(HomeObject object) {
        Map<String, Object> state = new LinkedHashMap<>();
        if (object instanceof HomePieceOfFurniture) {
            HomePieceOfFurniture piece = (HomePieceOfFurniture) object;
            state.put("name", piece.getName());
            state.put("x", round2(piece.getX()));
            state.put("y", round2(piece.getY()));
            state.put("angle", round2(Math.toDegrees(piece.getAngle())));
            state.put("elevation", round2(piece.getElevation()));
            state.put("width", round2(piece.getWidth()));
            state.put("depth", round2(piece.getDepth()));
            state.put("height", round2(piece.getHeight()));
            state.put("visible", piece.isVisible());
            state.put("levelId", piece.getLevel() != null ? piece.getLevel().getId() : null);
        } else if (object instanceof Wall) {
            Wall wall = (Wall) object;
            state.put("xStart", round2(wall.getXStart()));
            state.put("yStart", round2(wall.getYStart()));
            state.put("xEnd", round2(wall.getXEnd()));
            state.put("yEnd", round2(wall.getYEnd()));
            state.put("thickness", round2(wall.getThickness()));
            state.put("height", round2(wall.getHeight()));
        } else if (object instanceof Room) {
            Room room = (Room) object;
            state.put("name", room.getName());
            state.put("area", round2(room.getArea()));
            state.put("centerX", round2(room.getXCenter()));
            state.put("centerY", round2(room.getYCenter()));
            state.put("pointCount", room.getPoints().length);
        }
        return state;
    }

    private static Map<String, Object> identity(HomeObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", object.getId());
        result.put("objectType", ObjectContextBuilder.objectType(object));
        if (object instanceof HomePieceOfFurniture) {
            result.put("name", ((HomePieceOfFurniture) object).getName());
        } else if (object instanceof Room) {
            result.put("name", ((Room) object).getName());
        }
        return result;
    }

    private static List<String> changedFields(Map<String, Object> before,
                                              Map<String, Object> after) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!java.util.Objects.equals(before.get(key), after.get(key))) changed.add(key);
        }
        return changed;
    }

    private static Map<String, Object> counts(Home home) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("walls", home.getWalls().size());
        result.put("rooms", home.getRooms().size());
        result.put("furniture", home.getFurniture().size());
        return result;
    }

    private static void restoreHome(Home home, Home source) {
        LoadHomeHandler.clearAll(home);
        LoadHomeHandler.addAll(home, source.getLevels(), home::addLevel);
        LoadHomeHandler.addAll(home, source.getWalls(), home::addWall);
        LoadHomeHandler.addAll(home, source.getRooms(), home::addRoom);
        LoadHomeHandler.addAll(home, source.getFurniture(), home::addPieceOfFurniture);
        LoadHomeHandler.addAll(home, source.getLabels(), home::addLabel);
        LoadHomeHandler.addAll(home, source.getDimensionLines(), home::addDimensionLine);
        LoadHomeHandler.addAll(home, source.getPolylines(), home::addPolyline);
        LoadHomeHandler.copyCameras(home, source);
        home.setStoredCameras(source.getStoredCameras());
        LoadHomeHandler.copyEnvironment(home.getEnvironment(), source.getEnvironment());
        LoadHomeHandler.copyCompass(home.getCompass(), source.getCompass());
        home.setBackgroundImage(source.getBackgroundImage());
        home.setBasePlanLocked(source.isBasePlanLocked());
        Level selected = source.getSelectedLevel();
        if (selected != null) home.setSelectedLevel(selected);
    }

    private static String cleanName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String clean = name.trim();
        return clean.length() <= 80 ? clean : clean.substring(0, 80);
    }

    @Override
    public String getDescription() {
        return "Manages independent named layout alternatives without branching the checkpoint timeline. "
                + "Use action='save' for the base and each proposal, 'compare' to obtain added/removed/changed "
                + "objects and measurements, 'restore' to activate a proposal safely, and 'list' to browse them. "
                + "Restore automatically creates an undo checkpoint first.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredEnum("action", "Operation to perform", "save", "list", "compare", "restore")
                .string("name", "Alternative name for save, compare, or restore")
                .string("otherName", "Second saved alternative for compare; omit to compare against current layout")
                .string("description", "Optional description when saving an alternative")
                .build();
    }
}
