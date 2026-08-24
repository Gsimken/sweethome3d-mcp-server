package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;
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

/** Places a furniture item flush and parallel to a real wall. */
public class AttachFurnitureToWallHandler implements CommandHandler, CommandDescriptor {

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String furnitureId = request.getString("furnitureId");
        String wallId = request.getString("wallId");
        if (furnitureId == null) return Response.error("Missing required parameter 'furnitureId'");
        if (wallId == null) return Response.error("Missing required parameter 'wallId'");
        String side = request.getString("side");
        if (side == null) side = "auto";
        if (!"auto".equals(side) && !"left".equals(side) && !"right".equals(side)) {
            return Response.error("side must be 'auto', 'left', or 'right'");
        }
        float along = request.getFloat("along", 0.5f);
        float gap = request.getFloat("gap", 0f);
        float rotationOffset = request.getFloat("rotationOffset", 0f);
        if (along < 0f || along > 1f) return Response.error("along must be between 0 and 1");
        if (gap < -50f || gap > 500f) return Response.error("gap must be between -50 and 500 cm");
        final String requestedSide = side;

        Map<String, Object> data = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            HomePieceOfFurniture piece = ObjectResolver.findFurniture(home, furnitureId);
            Wall wall = ObjectResolver.findWall(home, wallId);
            if (piece == null || wall == null) return null;
            if (piece.isDoorOrWindow()) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "Doors/windows must be placed with place_door_or_window");
                return error;
            }

            double dx = wall.getXEnd() - wall.getXStart();
            double dy = wall.getYEnd() - wall.getYStart();
            double length = Math.hypot(dx, dy);
            if (length < 0.01d) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("error", "Wall has zero length");
                return error;
            }
            double tx = dx / length;
            double ty = dy / length;
            double leftX = -ty;
            double leftY = tx;
            String resolvedSide = "auto".equals(requestedSide)
                    ? chooseInteriorSide(home, wall, leftX, leftY) : requestedSide;
            double sign = "left".equals(resolvedSide) ? 1d : -1d;
            double baseX = wall.getXStart() + dx * along;
            double baseY = wall.getYStart() + dy * along;
            double normalDistance = wall.getThickness() / 2d + piece.getDepthInPlan() / 2d + gap;

            piece.setX((float) (baseX + leftX * sign * normalDistance));
            piece.setY((float) (baseY + leftY * sign * normalDistance));
            piece.setAngle((float) (Math.atan2(dy, dx) + Math.toRadians(rotationOffset)));
            if (request.getParams().containsKey("elevation")) {
                piece.setElevation(request.getFloat("elevation"));
            }
            piece.setProperty("mcp.attachedWallId", wall.getId());
            piece.setProperty("mcp.attachedWallSide", resolvedSide);
            piece.setProperty("mcp.attachedWallAlong", Float.toString(along));
            piece.setProperty("mcp.attachedWallGap", Float.toString(gap));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("furnitureId", piece.getId());
            result.put("wallId", wall.getId());
            result.put("resolvedSide", resolvedSide);
            result.put("along", along);
            result.put("gap", gap);
            result.put("x", round2(piece.getX()));
            result.put("y", round2(piece.getY()));
            result.put("elevation", round2(piece.getElevation()));
            result.put("angle", round2(Math.toDegrees(piece.getAngle())));
            result.put("wallLength", round2(length));
            return result;
        });

        if (data == null) return Response.error("Furniture or wall not found");
        if (data.containsKey("error")) return Response.error(data.get("error").toString());
        return Response.ok(data);
    }

    private static String chooseInteriorSide(Home home, Wall wall, double nx, double ny) {
        double midX = (wall.getXStart() + wall.getXEnd()) / 2d;
        double midY = (wall.getYStart() + wall.getYEnd()) / 2d;
        double sample = Math.max(25d, wall.getThickness() * 2d);
        int leftRooms = 0;
        int rightRooms = 0;
        for (Room room : home.getRooms()) {
            if (wall.getLevel() != room.getLevel()) continue;
            if (room.containsPoint((float) (midX + nx * sample), (float) (midY + ny * sample), 0f)) {
                leftRooms++;
            }
            if (room.containsPoint((float) (midX - nx * sample), (float) (midY - ny * sample), 0f)) {
                rightRooms++;
            }
        }
        return rightRooms > leftRooms ? "right" : "left";
    }

    @Override
    public String getDescription() {
        return "Attaches furniture such as TVs, shelves and wall cabinets to a real wall. Computes "
                + "position from the wall centerline, thickness, furniture depth, side and along-wall "
                + "fraction; aligns rotation and stores the wall relationship as persistent object metadata. "
                + "Use side='auto' to choose the detected room-facing side.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredString("furnitureId", "Furniture ID from get_state or inspect_objects")
                .requiredString("wallId", "Wall ID to attach the furniture to")
                .enumProp("side", "Wall side relative to start→end; auto selects a room-facing side",
                        "auto", "left", "right")
                .numberWithDefault("along", "Position along wall: 0=start, 0.5=center, 1=end", 0.5)
                .numberWithDefault("gap", "Gap from the finished wall face in cm", 0)
                .number("elevation", "Optional elevation above the selected level in cm")
                .numberWithDefault("rotationOffset", "Extra rotation relative to wall direction in degrees", 0)
                .build();
    }
}
