package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.ObjectResolver;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.sh3d.mcp.command.util.FormatUtil.round2;

/** Adds explicit architectural staircase semantics to a furniture object. */
public class ConfigureStaircaseHandler implements CommandHandler, CommandDescriptor {

    private static final String RECTANGULAR_OPENING = "M0,0 v1 h1 v-1 z";

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String id = request.getString("id");
        if (id == null) return Response.error("Missing required parameter 'id'");
        String startLevelId = request.getString("startLevelId");
        String endLevelId = request.getString("endLevelId");
        float width = request.getFloat("width", Float.NaN);
        float run = request.getFloat("run", Float.NaN);
        float totalRise = request.getFloat("totalRise", Float.NaN);
        float upDirection = request.getFloat("upDirection", Float.NaN);
        float startX = request.getFloat("startX", Float.NaN);
        float startY = request.getFloat("startY", Float.NaN);
        int steps = (int) request.getFloat("steps", 0f);
        float landingDepth = request.getFloat("landingDepth", 0f);
        float openingWidth = request.getFloat("openingWidth", Float.NaN);
        float openingDepth = request.getFloat("openingDepth", Float.NaN);
        String wallId = request.getString("wallId");
        String direction = request.getString("direction");
        if (direction == null) direction = "straight";
        Boolean createOpeningParam = request.getBoolean("createUpperFloorOpening");
        boolean createOpening = createOpeningParam == null || createOpeningParam;

        if (!Float.isNaN(width) && width <= 0) return Response.error("width must be positive");
        if (!Float.isNaN(run) && run <= 0) return Response.error("run must be positive");
        if (!Float.isNaN(totalRise) && totalRise <= 0) return Response.error("totalRise must be positive");
        if (Float.isNaN(startX) != Float.isNaN(startY)) {
            return Response.error("startX and startY must be provided together");
        }
        if (steps < 0 || steps > 100) return Response.error("steps must be between 1 and 100");
        if (landingDepth < 0) return Response.error("landingDepth cannot be negative");
        if (!"straight".equals(direction) && !"left".equals(direction)
                && !"right".equals(direction) && !"spiral".equals(direction)) {
            return Response.error("direction must be straight, left, right, or spiral");
        }
        final String staircaseDirection = direction;

        Map<String, Object> data = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            HomePieceOfFurniture piece = ObjectResolver.findFurniture(home, id);
            if (piece == null) return error("Furniture not found: " + id);
            Level startLevel = startLevelId != null ? ObjectResolver.findLevel(home, startLevelId)
                    : piece.getLevel();
            Level endLevel = endLevelId != null ? ObjectResolver.findLevel(home, endLevelId) : null;
            if (startLevelId != null && startLevel == null) return error("Start level not found: " + startLevelId);
            if (endLevelId != null && endLevel == null) return error("End level not found: " + endLevelId);
            if (wallId != null && ObjectResolver.findWall(home, wallId) == null) {
                return error("Wall not found: " + wallId);
            }

            float resolvedRise = totalRise;
            if (Float.isNaN(resolvedRise) && startLevel != null && endLevel != null) {
                resolvedRise = endLevel.getElevation() - startLevel.getElevation();
                if (resolvedRise <= 0) {
                    return error("End level must be above the start level");
                }
            }
            if (Float.isNaN(resolvedRise) || resolvedRise <= 0) resolvedRise = piece.getHeight();
            float resolvedRun = Float.isNaN(run) ? piece.getDepth() : run;
            float resolvedWidth = Float.isNaN(width) ? piece.getWidth() : width;
            int resolvedSteps = steps > 0 ? steps : Math.max(1, Math.round(resolvedRise / 18f));
            float resolvedDirection = Float.isNaN(upDirection)
                    ? (float) Math.toDegrees(piece.getAngle()) + 90f : upDirection;

            if ((!Float.isNaN(width) || !Float.isNaN(run) || !Float.isNaN(totalRise))
                    && !piece.isResizable()) {
                return error("Furniture model is not resizable; dimensions were not changed");
            }
            piece.setWidth(resolvedWidth);
            piece.setDepth(resolvedRun);
            piece.setHeight(resolvedRise);
            if (startLevel != null) piece.setLevel(startLevel);

            if (!Float.isNaN(startX) || !Float.isNaN(startY)) {
                double radians = Math.toRadians(resolvedDirection);
                piece.setX((float) (startX + Math.cos(radians) * resolvedRun / 2f));
                piece.setY((float) (startY + Math.sin(radians) * resolvedRun / 2f));
                piece.setAngle((float) (radians - Math.PI / 2d));
            } else if (!Float.isNaN(upDirection)) {
                piece.setAngle((float) Math.toRadians(resolvedDirection - 90f));
            }

            piece.setProperty("mcp.semanticType", "staircase");
            set(piece, "mcp.staircase.startLevelId", id(startLevel));
            set(piece, "mcp.staircase.endLevelId", id(endLevel));
            set(piece, "mcp.staircase.direction", staircaseDirection);
            set(piece, "mcp.staircase.upDirectionDegrees", number(resolvedDirection));
            set(piece, "mcp.staircase.steps", Integer.toString(resolvedSteps));
            set(piece, "mcp.staircase.totalRise", number(resolvedRise));
            set(piece, "mcp.staircase.run", number(resolvedRun));
            set(piece, "mcp.staircase.width", number(resolvedWidth));
            set(piece, "mcp.staircase.riserHeight", number(resolvedRise / resolvedSteps));
            set(piece, "mcp.staircase.treadDepth", number(resolvedRun / resolvedSteps));
            set(piece, "mcp.staircase.landingDepth", number(landingDepth));
            set(piece, "mcp.staircase.openingWidth",
                    number(Float.isNaN(openingWidth) ? resolvedWidth : openingWidth));
            set(piece, "mcp.staircase.openingDepth",
                    number(Float.isNaN(openingDepth) ? resolvedRun : openingDepth));
            set(piece, "mcp.staircase.wallId", wallId);
            if (!Float.isNaN(startX)) {
                set(piece, "mcp.staircase.startX", number(startX));
                set(piece, "mcp.staircase.startY", number(startY));
                double radians = Math.toRadians(resolvedDirection);
                set(piece, "mcp.staircase.endX", number(startX + Math.cos(radians) * resolvedRun));
                set(piece, "mcp.staircase.endY", number(startY + Math.sin(radians) * resolvedRun));
            }
            piece.setStaircaseCutOutShape(createOpening ? RECTANGULAR_OPENING : null);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", piece.getId());
            result.put("name", piece.getName());
            result.put("semanticType", "staircase");
            result.put("startLevelId", id(startLevel));
            result.put("endLevelId", id(endLevel));
            result.put("direction", staircaseDirection);
            result.put("upDirection", round2(resolvedDirection));
            result.put("steps", resolvedSteps);
            result.put("width", round2(resolvedWidth));
            result.put("run", round2(resolvedRun));
            result.put("totalRise", round2(resolvedRise));
            result.put("riserHeight", round2(resolvedRise / resolvedSteps));
            result.put("treadDepth", round2(resolvedRun / resolvedSteps));
            result.put("upperFloorOpening", createOpening);
            result.put("wallId", wallId);
            return result;
        });

        if (data.containsKey("error")) return Response.error(data.get("error").toString());
        return Response.ok(data);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", message);
        return result;
    }

    private static void set(HomePieceOfFurniture piece, String key, String value) {
        piece.setProperty(key, value);
    }

    private static String id(Level level) {
        return level != null ? level.getId() : null;
    }

    private static String number(double value) {
        return Double.toString(round2(value));
    }

    @Override
    public String getDescription() {
        return "Converts an existing furniture object into an explicitly described architectural "
                + "staircase. Stores start/end levels, rise, run, direction of ascent, steps, riser, "
                + "tread, landing, upper opening and wall relation as persistent .sh3d metadata; also "
                + "sets Sweet Home 3D's staircase cut-out shape. The source 3D model remains furniture "
                + "because Sweet Home 3D has no richer native staircase entity.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredString("id", "Furniture ID of the staircase model")
                .string("startLevelId", "Level where ascent starts; defaults to the furniture level")
                .string("endLevelId", "Level reached by the staircase")
                .number("startX", "Plan X coordinate of the first riser in cm; requires startY")
                .number("startY", "Plan Y coordinate of the first riser in cm; requires startX")
                .number("upDirection", "Direction of ascent in plan degrees: 0=east, 90=south")
                .number("width", "Clear staircase width in cm")
                .number("run", "Total horizontal run in cm")
                .number("totalRise", "Total vertical rise in cm; inferred from levels when omitted")
                .integer("steps", "Number of risers, 1-100; inferred near 18 cm when omitted")
                .enumProp("direction", "Stair layout/turn direction", "straight", "left", "right", "spiral")
                .numberWithDefault("landingDepth", "Landing depth in cm", 0)
                .number("openingWidth", "Upper floor opening width in cm; defaults to staircase width")
                .number("openingDepth", "Upper floor opening depth in cm; defaults to staircase run")
                .string("wallId", "Optional wall the staircase is anchored to")
                .boolWithDefault("createUpperFloorOpening",
                        "Set Sweet Home 3D's normalized rectangular upper-floor cut-out", true)
                .build();
    }
}
