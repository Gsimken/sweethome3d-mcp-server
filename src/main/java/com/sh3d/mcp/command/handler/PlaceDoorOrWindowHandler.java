package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.util.FormatUtil;
import com.sh3d.mcp.command.util.CatalogSearchUtil;

import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.DoorOrWindow;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeDoorOrWindow;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.ObjectResolver;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import com.sh3d.mcp.command.util.SchemaBuilder;

import static com.sh3d.mcp.command.util.FormatUtil.round2;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Обработчик команды "place_door_or_window".
 * Размещает дверь/окно из каталога в указанную стену, автоматически
 * вычисляя координаты и угол поворота из геометрии стены.
 *
 * <p>Поиск: exact match имени приоритетнее substring.
 * При нескольких exact match — ошибка disambiguации.
 * Параметр catalogId позволяет выбрать конкретный элемент по ID каталога.
 */
public class PlaceDoorOrWindowHandler implements CommandHandler, CommandDescriptor {

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        // --- Validate name or catalogId ---
        String name = request.getString("name");
        String catalogId = request.getString("catalogId");
        if ((name == null || name.trim().isEmpty())
                && (catalogId == null || catalogId.trim().isEmpty())) {
            return Response.error("Either 'name' or 'catalogId' must be provided");
        }

        // --- Validate wallId ---
        Map<String, Object> params = request.getParams();
        String wallId = request.getString("wallId");
        if (wallId == null) {
            return Response.error("Missing required parameter 'wallId'");
        }

        // --- Validate position ---
        float position = request.getFloat("position", 0.5f);
        if (position < 0f || position > 1f) {
            return Response.error("Parameter 'position' must be between 0.0 and 1.0, got " + position);
        }

        // --- Optional params ---
        boolean hasElevation = params.containsKey("elevation");
        float elevation = hasElevation ? request.getFloat("elevation") : 0f;
        Boolean mirrored = request.getBoolean("mirrored");
        String openingType = valueOrDefault(request.getString("openingType"), "auto");
        String openingMechanism = valueOrDefault(request.getString("openingMechanism"), "swing");
        String hingeSide = valueOrDefault(request.getString("hingeSide"), "unspecified");
        String swingDirection = valueOrDefault(request.getString("swingDirection"), "unspecified");

        // --- Search catalog (only doors/windows) ---
        CatalogSearchUtil.FurnitureSearchResult searchResult =
                CatalogSearchUtil.findFurniture(
                        accessor.getFurnitureCatalog(), name, catalogId,
                        CatalogPieceOfFurniture::isDoorOrWindow);
        if (searchResult.isError()) {
            return Response.error(searchResult.getError());
        }
        if (!searchResult.isFound()) {
            return Response.error("Door/window not found in catalog: " + name);
        }
        CatalogPieceOfFurniture found = searchResult.getFound();
        if (!(found instanceof DoorOrWindow)) {
            return Response.error("Catalog entry '" + found.getName() + "' is marked as a door/window "
                    + "but does not expose Sweet Home 3D native DoorOrWindow data. Choose a native "
                    + "CatalogDoorOrWindow entry or update the source furniture library.");
        }
        DoorOrWindow nativeOpening = (DoorOrWindow) found;

        // --- Place in EDT ---
        Map<String, Object> data = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            Wall wall = ObjectResolver.findWall(home, wallId);

            if (wall == null) {
                return null;
            }

            float xStart = wall.getXStart();
            float yStart = wall.getYStart();
            float xEnd = wall.getXEnd();
            float yEnd = wall.getYEnd();

            float x = xStart + position * (xEnd - xStart);
            float y = yStart + position * (yEnd - yStart);
            float angle = (float) Math.atan2(yEnd - yStart, xEnd - xStart);

            HomeDoorOrWindow piece = new HomeDoorOrWindow(nativeOpening);
            // Auto-fit depth to wall thickness for proper rendering
            float wallThickness = wall.getThickness();
            if (piece.getDepth() < wallThickness) {
                piece.setDepth(wallThickness);
            }
            piece.setX(x);
            piece.setY(y);
            piece.setAngle(angle);

            if (hasElevation) {
                piece.setElevation(elevation);
            }
            if (mirrored != null && mirrored) {
                piece.setModelMirrored(true);
            }

            piece.setBoundToWall(true);
            piece.setProperty("mcp.hostWallId", wallId);
            piece.setProperty("mcp.wallPosition", Float.toString(position));
            piece.setProperty("mcp.openingType", inferOpeningType(piece, openingType));
            piece.setProperty("mcp.openingMechanism", openingMechanism);
            piece.setProperty("mcp.hingeSide", hingeSide);
            piece.setProperty("mcp.swingDirection", swingDirection);

            home.addPieceOfFurniture(piece);

            Map<String, Object> result = FormatUtil.buildFurnitureInfo(piece);
            result.put("isDoorOrWindow", piece.isDoorOrWindow());
            result.put("mirrored", piece.isModelMirrored());
            result.put("wallId", wallId);
            result.put("position", round2(position));
            result.put("nativeType", HomeDoorOrWindow.class.getSimpleName());
            result.put("boundToWall", piece.isBoundToWall());
            result.put("openingType", piece.getProperty("mcp.openingType"));
            result.put("openingMechanism", openingMechanism);
            result.put("hingeSide", hingeSide);
            result.put("swingDirection", swingDirection);
            return result;
        });

        if (data == null) {
            return Response.error("Wall not found: " + wallId);
        }

        return Response.ok(data);
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private static String inferOpeningType(HomeDoorOrWindow piece, String requested) {
        if (!"auto".equals(requested)) return requested;
        String name = piece.getName() != null ? piece.getName().toLowerCase() : "";
        if (name.contains("window") || name.contains("ventana")) return "window";
        if (name.contains("door") || name.contains("puerta")) return "door";
        return piece.getElevation() <= 5f && piece.getHeight() >= 170f ? "door" : "window";
    }

    @Override
    public String getDescription() {
        return "Places a door or window from the catalog into a specific wall. "
                + "Searches the catalog by name, filtering only doors and windows (not regular furniture). "
                + "The piece is automatically positioned and rotated to align with the wall. "
                + "Use 'position' (0.0-1.0) to control placement along the wall: "
                + "0.0 = wall start, 0.5 = center (default), 1.0 = wall end. "
                + "Use 'elevation' for windows (typically 80-100 cm above floor). "
                + "Doors usually have elevation 0 (default from catalog). "
                + "Use get_state to find wall IDs and list_furniture_catalog to browse available doors/windows. "
                + "Use 'catalogId' for precise selection when multiple items share the same name. "
                + "Returns the furniture id for use with modify_furniture, delete_furniture.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .string("name", "Door/window name to search in catalog (e.g., 'door', 'window', 'French door')")
                .string("catalogId",
                        "Exact catalog ID for precise selection (bypasses name search). "
                                + "Use list_furniture_catalog to find catalog IDs")
                .requiredString("wallId", "ID of the wall to place the door/window in (from get_state)")
                .numberWithDefault("position",
                        "Position along the wall: 0.0 = start, 0.5 = center, 1.0 = end", 0.5)
                .number("elevation", "Height above floor in cm. Doors default to 0, windows typically 80-100")
                .boolWithDefault("mirrored", "Mirror the door/window model (e.g., change hinge side)", false)
                .enumProp("openingType", "Semantic opening type; auto infers it from catalog geometry/name",
                        "auto", "door", "window")
                .enumProp("openingMechanism", "Movement used by clearance checks",
                        "swing", "sliding", "folding", "pocket", "fixed")
                .enumProp("hingeSide", "Hinge side viewed from the wall front",
                        "left", "right", "none", "unspecified")
                .enumProp("swingDirection", "Door leaf swing direction",
                        "inward", "outward", "both", "none", "unspecified")
                .build();
    }

}
