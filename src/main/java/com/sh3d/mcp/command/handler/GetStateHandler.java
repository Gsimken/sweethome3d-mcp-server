package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.util.FormatUtil;
import com.sh3d.mcp.command.util.SceneBounds;
import com.sh3d.mcp.command.util.SceneBoundsCalculator;

import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.DimensionLine;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeEnvironment;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Label;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.ObserverCamera;
import com.eteks.sweethome3d.model.Polyline;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import static com.sh3d.mcp.command.util.FormatUtil.colorToHex;
import static com.sh3d.mcp.command.util.FormatUtil.round2;
import static com.sh3d.mcp.command.util.FormatUtil.textureName;

import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.command.util.ObjectContextBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Обработчик команды "get_state".
 * Возвращает полное состояние сцены: стены, мебель, комнаты, labels,
 * dimension lines, камера, уровни, bounding box.
 *
 * Каждый объект получает стабильный строковый ID ({@code HomeObject.getId()}),
 * который не сдвигается при удалении других объектов и может использоваться
 * в последующих командах (delete, modify и т.д.).
 */
public class GetStateHandler implements CommandHandler, CommandDescriptor {

    private final SceneBoundsCalculator boundsCalculator = new SceneBoundsCalculator();

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        Map<String, Object> data = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            Map<String, Object> result = new LinkedHashMap<>();

            // --- Walls ---
            List<Object> wallList = buildWalls(home.getWalls(), home);
            result.put("wallCount", wallList.size());
            result.put("walls", wallList);

            // --- Furniture ---
            List<Object> furnitureList = buildFurniture(home.getFurniture(), home);
            result.put("furnitureCount", furnitureList.size());
            result.put("furniture", furnitureList);

            // --- Rooms ---
            List<Object> roomList = buildRooms(home.getRooms(), home);
            result.put("roomCount", roomList.size());
            result.put("rooms", roomList);

            // --- Labels ---
            List<Object> labelList = buildLabels(home.getLabels(), home);
            result.put("labelCount", labelList.size());
            result.put("labels", labelList);

            // --- Dimension lines ---
            List<Object> dimList = buildDimensionLines(home.getDimensionLines(), home);
            result.put("dimensionLineCount", dimList.size());
            result.put("dimensionLines", dimList);

            // --- Polylines (previously omitted from the scene snapshot) ---
            List<Object> polylineList = buildPolylines(home.getPolylines(), home);
            result.put("polylineCount", polylineList.size());
            result.put("polylines", polylineList);

            // --- Current UI selection ---
            List<Object> selection = buildSelection(home.getSelectedItems());
            result.put("selectedObjectCount", selection.size());
            result.put("selection", selection);

            // --- Camera ---
            result.put("camera", buildCamera(home));

            // --- Stored cameras ---
            List<Camera> storedCameras = home.getStoredCameras();
            List<Object> storedCamList = new ArrayList<>();
            for (Camera sc : storedCameras) {
                Map<String, Object> cam = new LinkedHashMap<>();
                cam.put("id", sc.getId());
                cam.put("name", sc.getName());
                storedCamList.add(cam);
            }
            result.put("storedCameraCount", storedCamList.size());
            result.put("storedCameras", storedCamList);

            // --- Levels ---
            List<Object> levelList = buildLevels(home.getLevels(), home.getSelectedLevel());
            result.put("levelCount", levelList.size());
            result.put("levels", levelList);

            // --- Environment ---
            result.put("environment", buildEnvironment(home.getEnvironment()));

            // --- Home metadata and plugin-owned document properties ---
            result.put("home", buildHomeInfo(home));

            // --- Compass / geographic context used for sunlight and orientation ---
            result.put("compass", buildCompass(home));

            return result;
        });

        // --- Bounding box (via SceneBoundsCalculator, includes walls + furniture + rooms) ---
        SceneBounds bounds = boundsCalculator.computeSceneBounds(accessor);
        if (bounds != null) {
            Map<String, Object> bb = new LinkedHashMap<>();
            bb.put("minX", round2(bounds.minX));
            bb.put("minY", round2(bounds.minY));
            bb.put("maxX", round2(bounds.maxX));
            bb.put("maxY", round2(bounds.maxY));
            data.put("boundingBox", bb);
        } else {
            data.put("boundingBox", null);
        }

        return Response.ok(data);
    }

    // --- Wall builders ---

    private List<Object> buildWalls(Collection<Wall> walls, Home home) {
        List<Object> list = new ArrayList<>();
        for (Wall w : walls) {
            Map<String, Object> info = FormatUtil.buildWallInfo(w);
            ObjectContextBuilder.addSummary(info, w, home);
            info.put("wallAtStartId", w.getWallAtStart() != null ? w.getWallAtStart().getId() : null);
            info.put("wallAtEndId", w.getWallAtEnd() != null ? w.getWallAtEnd().getId() : null);
            list.add(info);
        }
        return list;
    }

    // --- Furniture builders ---

    private List<Object> buildFurniture(List<HomePieceOfFurniture> furniture, Home home) {
        List<Object> list = new ArrayList<>();
        for (HomePieceOfFurniture piece : furniture) {
            list.add(buildFurniturePiece(piece, home));
        }
        return list;
    }

    private Map<String, Object> buildFurniturePiece(HomePieceOfFurniture piece, Home home) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", piece.getId());
        item.put("name", piece.getName());
        item.put("catalogId", piece.getCatalogId());
        item.put("x", round2(piece.getX()));
        item.put("y", round2(piece.getY()));
        item.put("elevation", round2(piece.getElevation()));
        item.put("angle", round2(Math.toDegrees(piece.getAngle())));
        item.put("width", round2(piece.getWidth()));
        item.put("depth", round2(piece.getDepth()));
        item.put("height", round2(piece.getHeight()));
        item.put("isDoorOrWindow", piece.isDoorOrWindow());
        item.put("visible", piece.isVisible());
        item.put("color", colorToHex(piece.getColor()));
        item.put("texture", textureName(piece.getTexture()));
        item.put("modelMirrored", piece.isModelMirrored());
        item.put("pitch", round2(Math.toDegrees(piece.getPitch())));
        item.put("roll", round2(Math.toDegrees(piece.getRoll())));
        ObjectContextBuilder.addSummary(item, piece, home);

        if (piece instanceof HomeFurnitureGroup) {
            item.put("isGroup", true);
            HomeFurnitureGroup group = (HomeFurnitureGroup) piece;
            List<Object> groupItems = new ArrayList<>();
            for (HomePieceOfFurniture child : group.getFurniture()) {
                groupItems.add(buildFurniturePiece(child, home));
            }
            item.put("groupItems", groupItems);
        }

        Level level = piece.getLevel();
        item.put("level", level != null ? level.getName() : null);
        return item;
    }

    // --- Room builders ---

    private List<Object> buildRooms(List<Room> rooms, Home home) {
        List<Object> list = new ArrayList<>();
        for (Room room : rooms) {
            Map<String, Object> info = FormatUtil.buildRoomInfo(room);
            ObjectContextBuilder.addSummary(info, room, home);
            list.add(info);
        }
        return list;
    }

    // --- Label builders ---

    private List<Object> buildLabels(Collection<Label> labels, Home home) {
        List<Object> list = new ArrayList<>();
        for (Label label : labels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", label.getId());
            item.put("text", label.getText());
            item.put("x", round2(label.getX()));
            item.put("y", round2(label.getY()));
            item.put("angle", round2(Math.toDegrees(label.getAngle())));
            item.put("color", colorToHex(label.getColor()));
            item.put("outlineColor", colorToHex(label.getOutlineColor()));
            item.put("elevation", round2(label.getElevation()));
            item.put("pitch", label.getPitch() != null
                    ? round2(Math.toDegrees(label.getPitch())) : null);
            ObjectContextBuilder.addSummary(item, label, home);
            Level level = label.getLevel();
            item.put("level", level != null ? level.getName() : null);
            list.add(item);
        }
        return list;
    }

    // --- Dimension line builders ---

    private List<Object> buildDimensionLines(Collection<DimensionLine> dimensionLines, Home home) {
        List<Object> list = new ArrayList<>();
        for (DimensionLine dim : dimensionLines) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", dim.getId());
            item.put("xStart", round2(dim.getXStart()));
            item.put("yStart", round2(dim.getYStart()));
            item.put("xEnd", round2(dim.getXEnd()));
            item.put("yEnd", round2(dim.getYEnd()));
            item.put("offset", round2(dim.getOffset()));
            item.put("length", round2(dim.getLength()));
            item.put("elevationStart", round2(dim.getElevationStart()));
            item.put("elevationEnd", round2(dim.getElevationEnd()));
            item.put("visibleIn3D", dim.isVisibleIn3D());
            item.put("color", colorToHex(dim.getColor()));
            ObjectContextBuilder.addSummary(item, dim, home);
            Level level = dim.getLevel();
            item.put("level", level != null ? level.getName() : null);
            list.add(item);
        }
        return list;
    }

    // --- Polyline builders ---

    private List<Object> buildPolylines(Collection<Polyline> polylines, Home home) {
        List<Object> list = new ArrayList<>();
        for (Polyline line : polylines) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", line.getId());
            item.put("points", ObjectContextBuilder.points(line.getPoints()));
            item.put("length", round2(line.getLength()));
            item.put("thickness", round2(line.getThickness()));
            item.put("color", colorToHex(line.getColor()));
            item.put("closedPath", line.isClosedPath());
            item.put("visibleIn3D", line.isVisibleIn3D());
            Level level = line.getLevel();
            item.put("level", level != null ? level.getName() : null);
            ObjectContextBuilder.addSummary(item, line, home);
            list.add(item);
        }
        return list;
    }

    private List<Object> buildSelection(List<Selectable> selectedItems) {
        List<Object> list = new ArrayList<>();
        for (Selectable selected : selectedItems) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (selected instanceof com.eteks.sweethome3d.model.HomeObject) {
                item.put("id", ((com.eteks.sweethome3d.model.HomeObject) selected).getId());
            }
            item.put("objectType", ObjectContextBuilder.objectType(selected));
            list.add(item);
        }
        return list;
    }

    private Map<String, Object> buildHomeInfo(Home home) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", home.getName());
        info.put("version", home.getVersion());
        info.put("modified", home.isModified());
        info.put("recovered", home.isRecovered());
        info.put("repaired", home.isRepaired());
        info.put("wallHeight", round2(home.getWallHeight()));
        info.put("basePlanLocked", home.isBasePlanLocked());
        info.put("allLevelsSelection", home.isAllLevelsSelection());
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(home.getPropertyNames());
        java.util.Collections.sort(names);
        for (String name : names) {
            String value = home.getProperty(name);
            if (value != null && value.length() > ObjectContextBuilder.SUMMARY_PROPERTY_LIMIT) {
                value = value.substring(0, ObjectContextBuilder.SUMMARY_PROPERTY_LIMIT)
                        + "... [truncated]";
            }
            properties.put(name, value);
        }
        if (!properties.isEmpty()) info.put("customProperties", properties);
        return info;
    }

    private Map<String, Object> buildCompass(Home home) {
        com.eteks.sweethome3d.model.Compass compass = home.getCompass();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", compass.getId());
        info.put("x", round2(compass.getX()));
        info.put("y", round2(compass.getY()));
        info.put("diameter", round2(compass.getDiameter()));
        info.put("visible", compass.isVisible());
        info.put("northDirection", round2(Math.toDegrees(compass.getNorthDirection())));
        info.put("latitude", round2(Math.toDegrees(compass.getLatitude())));
        info.put("longitude", round2(Math.toDegrees(compass.getLongitude())));
        info.put("timeZone", compass.getTimeZone());
        return info;
    }

    // --- Camera builder ---

    private Map<String, Object> buildCamera(Home home) {
        Camera cam = home.getCamera();
        String mode = cam instanceof ObserverCamera ? "observer" : "top";
        return FormatUtil.buildCameraInfo(cam, mode, true);
    }

    // --- Level builders ---

    private List<Object> buildLevels(List<Level> levels, Level selectedLevel) {
        List<Object> list = new ArrayList<>();
        for (Level level : levels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", level.getId());
            item.put("name", level.getName());
            item.put("elevation", round2(level.getElevation()));
            item.put("height", round2(level.getHeight()));
            item.put("floorThickness", round2(level.getFloorThickness()));
            item.put("viewable", level.isViewable());
            item.put("selected", level.equals(selectedLevel));
            list.add(item);
        }
        return list;
    }

    // --- Environment builder ---

    private Map<String, Object> buildEnvironment(HomeEnvironment env) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("groundColor", colorToHex(env.getGroundColor()));
        info.put("groundTexture", textureName(env.getGroundTexture()));
        info.put("skyColor", colorToHex(env.getSkyColor()));
        info.put("skyTexture", textureName(env.getSkyTexture()));
        info.put("lightColor", colorToHex(env.getLightColor()));
        info.put("ceilingLightColor", colorToHex(env.getCeillingLightColor()));
        info.put("wallsAlpha", round2(env.getWallsAlpha()));
        info.put("drawingMode", env.getDrawingMode().name());
        info.put("allLevelsVisible", env.isAllLevelsVisible());
        return info;
    }

    // --- Descriptor ---

    @Override
    public String getDescription() {
        return "Returns the full state of the Sweet Home 3D scene: walls with coordinates, "
                + "furniture with positions and IDs, rooms with polygons, labels, dimension lines, "
                + "polylines, current selection, camera and compass settings, environment, home "
                + "metadata, levels, and compact custom properties written by other plugins. "
                + "Each object has a stable string 'id' field that can be "
                + "used in subsequent commands (delete, modify, etc.). Always call this before "
                + "making changes, then call inspect_objects for deep details on relevant IDs.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create().build();
    }
}
