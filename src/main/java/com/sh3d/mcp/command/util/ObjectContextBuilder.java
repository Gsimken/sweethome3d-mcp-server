package com.sh3d.mcp.command.util;

import com.eteks.sweethome3d.model.Baseboard;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Compass;
import com.eteks.sweethome3d.model.DimensionLine;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeDoorOrWindow;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeEnvironment;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomeMaterial;
import com.eteks.sweethome3d.model.HomeObject;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.Label;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.LightSource;
import com.eteks.sweethome3d.model.ObserverCamera;
import com.eteks.sweethome3d.model.Polyline;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Sash;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.TextStyle;
import com.eteks.sweethome3d.model.Transformation;
import com.eteks.sweethome3d.model.Wall;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.util.FormatUtil.colorToHex;
import static com.sh3d.mcp.command.util.FormatUtil.round2;

/**
 * Builds AI-friendly, JSON-safe descriptions of Sweet Home 3D objects.
 *
 * <p>The formatter deliberately exposes {@link HomeObject} custom properties. Third-party
 * plugins use these properties to tag and reconstruct generated objects (for example the
 * GenerateRoof plugin stores {@code roof.*} and {@code roof_window*} values). Values are
 * length-limited to prevent a serialized model from consuming the complete model context.</p>
 */
public final class ObjectContextBuilder {

    public static final int SUMMARY_PROPERTY_LIMIT = 256;
    public static final int DEFAULT_PROPERTY_LIMIT = 2048;
    public static final int MAX_PROPERTY_LIMIT = 8192;

    private ObjectContextBuilder() {
    }

    /** Adds compact type, selection and plugin-property information to an existing map. */
    public static void addSummary(Map<String, Object> target, HomeObject object, Home home) {
        target.put("objectType", objectType(object));
        if (object instanceof Selectable) {
            target.put("selected", home.isItemSelected((Selectable) object));
        }
        addCustomProperties(target, object, SUMMARY_PROPERTY_LIMIT);
    }

    /** Returns a detailed description suitable for the inspect_objects tool. */
    public static Map<String, Object> buildDetailed(HomeObject object, Home home,
                                                     boolean includeRelations,
                                                     boolean includeCustomProperties,
                                                     int propertyLimit) {
        Map<String, Object> info;
        if (object instanceof HomePieceOfFurniture) {
            info = buildFurniture((HomePieceOfFurniture) object, home, includeRelations,
                    includeCustomProperties, propertyLimit);
        } else if (object instanceof Wall) {
            info = buildWall((Wall) object, home, includeRelations);
        } else if (object instanceof Room) {
            info = buildRoom((Room) object, home, includeRelations);
        } else if (object instanceof Polyline) {
            info = buildPolyline((Polyline) object);
        } else if (object instanceof DimensionLine) {
            info = buildDimensionLine((DimensionLine) object);
        } else if (object instanceof Label) {
            info = buildLabel((Label) object);
        } else if (object instanceof Level) {
            info = buildLevel((Level) object, home);
        } else if (object instanceof Compass) {
            info = buildCompass((Compass) object);
        } else if (object instanceof Camera) {
            info = buildCamera((Camera) object);
        } else if (object instanceof HomeEnvironment) {
            info = buildEnvironment((HomeEnvironment) object);
        } else {
            info = new LinkedHashMap<>();
            info.put("id", object.getId());
            info.put("objectType", objectType(object));
        }

        info.put("implementationClass", object.getClass().getName());
        if (object instanceof Selectable) {
            info.put("selected", home.isItemSelected((Selectable) object));
        }
        if (includeCustomProperties) {
            addCustomProperties(info, object, propertyLimit);
        }
        return info;
    }

    public static String objectType(Object object) {
        if (object instanceof HomeFurnitureGroup) return "furnitureGroup";
        if (object instanceof HomeDoorOrWindow) return "doorOrWindow";
        if (object instanceof HomeLight) return "light";
        if (object instanceof HomePieceOfFurniture) return "furniture";
        if (object instanceof Wall) return "wall";
        if (object instanceof Room) return "room";
        if (object instanceof Polyline) return "polyline";
        if (object instanceof DimensionLine) return "dimensionLine";
        if (object instanceof Label) return "label";
        if (object instanceof Level) return "level";
        if (object instanceof Compass) return "compass";
        if (object instanceof ObserverCamera) return "observerCamera";
        if (object instanceof Camera) return "camera";
        if (object instanceof HomeEnvironment) return "environment";
        return object != null ? object.getClass().getSimpleName() : "unknown";
    }

    public static Map<String, Object> customProperties(HomeObject object, int valueLimit,
                                                        List<String> truncatedNames) {
        Collection<String> propertyNames = object.getPropertyNames();
        if (propertyNames == null || propertyNames.isEmpty()) {
            return Collections.emptyMap();
        }

        List<String> names = new ArrayList<>(propertyNames);
        names.sort(Comparator.naturalOrder());
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String name : names) {
            if (object.isContentProperty(name)) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("kind", "content");
                content.put("available", object.getContentProperty(name) != null);
                properties.put(name, content);
                continue;
            }
            String value = object.getProperty(name);
            if (value != null && value.length() > valueLimit) {
                properties.put(name, value.substring(0, valueLimit) + "... [truncated]");
                truncatedNames.add(name);
            } else {
                properties.put(name, value);
            }
        }
        return properties;
    }

    private static void addCustomProperties(Map<String, Object> target, HomeObject object,
                                            int valueLimit) {
        List<String> truncated = new ArrayList<>();
        Map<String, Object> properties = customProperties(object, valueLimit, truncated);
        if (!properties.isEmpty()) {
            target.put("customProperties", properties);
        }
        if (!truncated.isEmpty()) {
            target.put("truncatedCustomProperties", truncated);
        }
    }

    private static Map<String, Object> buildFurniture(HomePieceOfFurniture piece, Home home,
                                                       boolean includeRelations,
                                                       boolean includeCustomProperties,
                                                       int propertyLimit) {
        Map<String, Object> info = FormatUtil.buildFurnitureInfo(piece);
        info.put("objectType", objectType(piece));
        info.put("catalogId", piece.getCatalogId());
        info.put("visible", piece.isVisible());
        info.put("groundElevation", round2(piece.getGroundElevation()));
        info.put("dropOnTopElevation", round2(piece.getDropOnTopElevation()));
        info.put("widthInPlan", round2(piece.getWidthInPlan()));
        info.put("depthInPlan", round2(piece.getDepthInPlan()));
        info.put("heightInPlan", round2(piece.getHeightInPlan()));
        info.put("pitch", round2(Math.toDegrees(piece.getPitch())));
        info.put("roll", round2(Math.toDegrees(piece.getRoll())));
        info.put("modelMirrored", piece.isModelMirrored());
        info.put("level", levelInfo(piece.getLevel()));
        info.put("footprint", points(piece.getPoints()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(metadata, "description", piece.getDescription());
        putIfNotNull(metadata, "information", piece.getInformation());
        putIfNotNull(metadata, "creator", piece.getCreator());
        putIfNotNull(metadata, "license", piece.getLicense());
        putIfNotNull(metadata, "staircaseCutOutShape", piece.getStaircaseCutOutShape());
        putIfNotNull(metadata, "price", decimal(piece.getPrice()));
        putIfNotNull(metadata, "valueAddedTaxPercentage", decimal(piece.getValueAddedTaxPercentage()));
        putIfNotNull(metadata, "priceWithTax", decimal(piece.getPriceValueAddedTaxIncluded()));
        putIfNotNull(metadata, "currency", piece.getCurrency());
        if (!metadata.isEmpty()) info.put("metadata", metadata);

        if ("staircase".equals(piece.getProperty("mcp.semanticType"))
                || piece.getStaircaseCutOutShape() != null) {
            Map<String, Object> staircase = new LinkedHashMap<>();
            staircase.put("startLevelId", piece.getProperty("mcp.staircase.startLevelId"));
            staircase.put("endLevelId", piece.getProperty("mcp.staircase.endLevelId"));
            staircase.put("direction", piece.getProperty("mcp.staircase.direction"));
            staircase.put("upDirectionDegrees", numericProperty(piece, "mcp.staircase.upDirectionDegrees"));
            staircase.put("steps", numericProperty(piece, "mcp.staircase.steps"));
            staircase.put("totalRise", numericProperty(piece, "mcp.staircase.totalRise"));
            staircase.put("run", numericProperty(piece, "mcp.staircase.run"));
            staircase.put("riserHeight", numericProperty(piece, "mcp.staircase.riserHeight"));
            staircase.put("treadDepth", numericProperty(piece, "mcp.staircase.treadDepth"));
            staircase.put("landingDepth", numericProperty(piece, "mcp.staircase.landingDepth"));
            staircase.put("openingWidth", numericProperty(piece, "mcp.staircase.openingWidth"));
            staircase.put("openingDepth", numericProperty(piece, "mcp.staircase.openingDepth"));
            staircase.put("wallId", piece.getProperty("mcp.staircase.wallId"));
            staircase.put("cutOutShape", piece.getStaircaseCutOutShape());
            info.put("staircase", staircase);
        }

        Map<String, Object> appearance = new LinkedHashMap<>();
        appearance.put("color", colorToHex(piece.getColor()));
        appearance.put("texture", textureInfo(piece.getTexture()));
        appearance.put("shininess", piece.getShininess() != null
                ? round2(piece.getShininess()) : null);
        appearance.put("materials", materials(piece.getModelMaterials()));
        info.put("appearance", appearance);

        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("movable", piece.isMovable());
        capabilities.put("resizable", piece.isResizable());
        capabilities.put("deformable", piece.isDeformable());
        capabilities.put("widthDepthDeformable", piece.isWidthDepthDeformable());
        capabilities.put("texturable", piece.isTexturable());
        capabilities.put("horizontallyRotatable", piece.isHorizontallyRotatable());
        info.put("capabilities", capabilities);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("sizeBytes", piece.getModelSize());
        model.put("flags", piece.getModelFlags());
        model.put("centeredAtOrigin", piece.isModelCenteredAtOrigin());
        model.put("backFaceShown", piece.isBackFaceShown());
        model.put("rotation", matrix(piece.getModelRotation()));
        model.put("transformations", transformations(piece.getModelTransformations()));
        info.put("model", model);

        if (piece instanceof HomeDoorOrWindow) {
            info.put("doorOrWindow", doorOrWindowInfo((HomeDoorOrWindow) piece));
        }
        if (piece instanceof HomeLight) {
            info.put("light", lightInfo((HomeLight) piece));
        }
        if (piece instanceof HomeFurnitureGroup) {
            HomeFurnitureGroup group = (HomeFurnitureGroup) piece;
            List<Object> children = new ArrayList<>();
            for (HomePieceOfFurniture child : group.getFurniture()) {
                children.add(buildFurniture(child, home, false, includeCustomProperties, propertyLimit));
            }
            info.put("groupItems", children);
        }

        if (includeRelations) {
            Map<String, Object> relations = new LinkedHashMap<>();
            relations.put("containingRoomIds", containingRoomIds(home, piece));
            String parentId = findParentGroupId(home.getFurniture(), piece.getId());
            if (parentId != null) relations.put("parentGroupId", parentId);
            if (piece.isDoorOrWindow()) {
                relations.put("intersectingWallIds", intersectingWallIds(home, piece));
            }
            info.put("relations", relations);
        }
        return info;
    }

    private static Map<String, Object> buildWall(Wall wall, Home home, boolean includeRelations) {
        Map<String, Object> info = FormatUtil.buildWallInfo(wall);
        info.put("objectType", "wall");
        info.put("level", levelInfo(wall.getLevel()));
        info.put("points", points(wall.getPoints()));
        info.put("trapezoidal", wall.isTrapezoidal());
        info.put("pattern", wall.getPattern() != null ? wall.getPattern().getName() : null);
        info.put("leftSideBaseboard", baseboardInfo(wall.getLeftSideBaseboard()));
        info.put("rightSideBaseboard", baseboardInfo(wall.getRightSideBaseboard()));
        if (includeRelations) {
            Map<String, Object> relations = new LinkedHashMap<>();
            relations.put("wallAtStartId", wall.getWallAtStart() != null
                    ? wall.getWallAtStart().getId() : null);
            relations.put("wallAtEndId", wall.getWallAtEnd() != null
                    ? wall.getWallAtEnd().getId() : null);
            relations.put("intersectingDoorOrWindowIds", intersectingDoorOrWindowIds(home, wall));
            info.put("relations", relations);
        }
        return info;
    }

    private static Map<String, Object> buildRoom(Room room, Home home, boolean includeRelations) {
        Map<String, Object> info = FormatUtil.buildRoomInfo(room);
        info.put("objectType", "room");
        info.put("level", levelInfo(room.getLevel()));
        info.put("pointCount", room.getPointCount());
        info.put("clockwise", room.isClockwise());
        info.put("singular", room.isSingular());
        info.put("ceilingFlat", room.isCeilingFlat());
        if (includeRelations) {
            List<String> furnitureIds = new ArrayList<>();
            collectFurnitureInRoom(home.getFurniture(), room, furnitureIds);
            Map<String, Object> relations = new LinkedHashMap<>();
            relations.put("containedFurnitureIds", furnitureIds);
            info.put("relations", relations);
        }
        return info;
    }

    private static Map<String, Object> buildPolyline(Polyline line) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", line.getId());
        info.put("objectType", "polyline");
        info.put("points", points(line.getPoints()));
        info.put("length", round2(line.getLength()));
        info.put("thickness", round2(line.getThickness()));
        info.put("elevation", round2(line.getElevation()));
        info.put("color", colorToHex(line.getColor()));
        info.put("capStyle", line.getCapStyle().name());
        info.put("joinStyle", line.getJoinStyle().name());
        info.put("dashStyle", line.getDashStyle().name());
        info.put("dashPattern", floats(line.getDashPattern()));
        info.put("dashOffset", round2(line.getDashOffset()));
        info.put("startArrowStyle", line.getStartArrowStyle().name());
        info.put("endArrowStyle", line.getEndArrowStyle().name());
        info.put("closedPath", line.isClosedPath());
        info.put("visibleIn3D", line.isVisibleIn3D());
        info.put("level", levelInfo(line.getLevel()));
        return info;
    }

    private static Map<String, Object> buildDimensionLine(DimensionLine line) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", line.getId());
        info.put("objectType", "dimensionLine");
        info.put("xStart", round2(line.getXStart()));
        info.put("yStart", round2(line.getYStart()));
        info.put("elevationStart", round2(line.getElevationStart()));
        info.put("xEnd", round2(line.getXEnd()));
        info.put("yEnd", round2(line.getYEnd()));
        info.put("elevationEnd", round2(line.getElevationEnd()));
        info.put("offset", round2(line.getOffset()));
        info.put("pitch", round2(Math.toDegrees(line.getPitch())));
        info.put("length", round2(line.getLength()));
        info.put("elevationDimensionLine", line.isElevationDimensionLine());
        info.put("color", colorToHex(line.getColor()));
        info.put("endMarkSize", round2(line.getEndMarkSize()));
        info.put("visibleIn3D", line.isVisibleIn3D());
        info.put("textStyle", textStyleInfo(line.getLengthStyle()));
        info.put("level", levelInfo(line.getLevel()));
        return info;
    }

    private static Map<String, Object> buildLabel(Label label) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", label.getId());
        info.put("objectType", "label");
        info.put("text", label.getText());
        info.put("x", round2(label.getX()));
        info.put("y", round2(label.getY()));
        info.put("elevation", round2(label.getElevation()));
        info.put("angle", round2(Math.toDegrees(label.getAngle())));
        info.put("pitch", label.getPitch() != null
                ? round2(Math.toDegrees(label.getPitch())) : null);
        info.put("color", colorToHex(label.getColor()));
        info.put("outlineColor", colorToHex(label.getOutlineColor()));
        info.put("textStyle", textStyleInfo(label.getStyle()));
        info.put("level", levelInfo(label.getLevel()));
        return info;
    }

    private static Map<String, Object> buildLevel(Level level, Home home) {
        Map<String, Object> info = levelInfo(level);
        info.put("objectType", "level");
        info.put("floorThickness", round2(level.getFloorThickness()));
        info.put("visible", level.isVisible());
        info.put("viewable", level.isViewable());
        info.put("viewableAndVisible", level.isViewableAndVisible());
        info.put("elevationIndex", level.getElevationIndex());
        info.put("selected", level.equals(home.getSelectedLevel()));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("walls", countAtLevel(home.getWalls(), level));
        counts.put("rooms", countAtLevel(home.getRooms(), level));
        counts.put("furniture", countAtLevel(home.getFurniture(), level));
        counts.put("polylines", countAtLevel(home.getPolylines(), level));
        counts.put("dimensionLines", countAtLevel(home.getDimensionLines(), level));
        counts.put("labels", countAtLevel(home.getLabels(), level));
        info.put("objectCounts", counts);
        return info;
    }

    private static Map<String, Object> buildCompass(Compass compass) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", compass.getId());
        info.put("objectType", "compass");
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

    private static Map<String, Object> buildCamera(Camera camera) {
        Map<String, Object> info = FormatUtil.buildCameraInfo(camera,
                camera instanceof ObserverCamera ? "observer" : "top", true);
        info.put("id", camera.getId());
        info.put("objectType", objectType(camera));
        info.put("name", camera.getName());
        info.put("time", camera.getTime());
        info.put("lens", camera.getLens().name());
        return info;
    }

    private static Map<String, Object> buildEnvironment(HomeEnvironment environment) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", environment.getId());
        info.put("objectType", "environment");
        info.put("groundColor", colorToHex(environment.getGroundColor()));
        info.put("groundTexture", textureInfo(environment.getGroundTexture()));
        info.put("skyColor", colorToHex(environment.getSkyColor()));
        info.put("skyTexture", textureInfo(environment.getSkyTexture()));
        info.put("lightColor", colorToHex(environment.getLightColor()));
        info.put("ceilingLightColor", colorToHex(environment.getCeillingLightColor()));
        info.put("wallsAlpha", round2(environment.getWallsAlpha()));
        info.put("drawingMode", environment.getDrawingMode().name());
        info.put("allLevelsVisible", environment.isAllLevelsVisible());
        return info;
    }

    private static int countAtLevel(Collection<?> objects, Level level) {
        int count = 0;
        for (Object object : objects) {
            Level objectLevel = null;
            if (object instanceof HomePieceOfFurniture) objectLevel = ((HomePieceOfFurniture) object).getLevel();
            else if (object instanceof Wall) objectLevel = ((Wall) object).getLevel();
            else if (object instanceof Room) objectLevel = ((Room) object).getLevel();
            else if (object instanceof Polyline) objectLevel = ((Polyline) object).getLevel();
            else if (object instanceof DimensionLine) objectLevel = ((DimensionLine) object).getLevel();
            else if (object instanceof Label) objectLevel = ((Label) object).getLevel();
            if (level.equals(objectLevel)) count++;
        }
        return count;
    }

    private static Map<String, Object> doorOrWindowInfo(HomeDoorOrWindow door) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("boundToWall", door.isBoundToWall());
        info.put("wallThickness", round2(door.getWallThickness()));
        info.put("wallDistance", round2(door.getWallDistance()));
        info.put("wallWidth", round2(door.getWallWidth()));
        info.put("wallLeft", round2(door.getWallLeft()));
        info.put("wallHeight", round2(door.getWallHeight()));
        info.put("wallTop", round2(door.getWallTop()));
        info.put("cutOutShape", door.getCutOutShape());
        info.put("wallCutOutOnBothSides", door.isWallCutOutOnBothSides());
        List<Object> sashes = new ArrayList<>();
        for (Sash sash : door.getSashes()) {
            Map<String, Object> sashInfo = new LinkedHashMap<>();
            sashInfo.put("xAxis", round2(sash.getXAxis()));
            sashInfo.put("yAxis", round2(sash.getYAxis()));
            sashInfo.put("width", round2(sash.getWidth()));
            sashInfo.put("startAngle", round2(Math.toDegrees(sash.getStartAngle())));
            sashInfo.put("endAngle", round2(Math.toDegrees(sash.getEndAngle())));
            sashes.add(sashInfo);
        }
        info.put("sashes", sashes);
        return info;
    }

    private static Map<String, Object> lightInfo(HomeLight light) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("power", round2(light.getPower()));
        List<Object> sources = new ArrayList<>();
        for (LightSource source : light.getLightSources()) {
            Map<String, Object> sourceInfo = new LinkedHashMap<>();
            sourceInfo.put("x", round2(source.getX()));
            sourceInfo.put("y", round2(source.getY()));
            sourceInfo.put("z", round2(source.getZ()));
            sourceInfo.put("color", colorToHex(source.getColor()));
            sourceInfo.put("diameter", source.getDiameter() != null
                    ? round2(source.getDiameter()) : null);
            sources.add(sourceInfo);
        }
        info.put("sources", sources);
        info.put("sourceMaterialNames", stringList(light.getLightSourceMaterialNames()));
        return info;
    }

    private static List<Object> materials(HomeMaterial[] materials) {
        List<Object> result = new ArrayList<>();
        if (materials == null) return result;
        for (HomeMaterial material : materials) {
            if (material == null) continue;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", material.getName());
            info.put("key", material.getKey());
            info.put("color", colorToHex(material.getColor()));
            info.put("texture", textureInfo(material.getTexture()));
            info.put("shininess", material.getShininess() != null
                    ? round2(material.getShininess()) : null);
            result.add(info);
        }
        return result;
    }

    private static Map<String, Object> textureInfo(HomeTexture texture) {
        if (texture == null) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("catalogId", texture.getCatalogId());
        info.put("name", texture.getName());
        info.put("creator", texture.getCreator());
        info.put("width", round2(texture.getWidth()));
        info.put("height", round2(texture.getHeight()));
        info.put("xOffset", round2(texture.getXOffset()));
        info.put("yOffset", round2(texture.getYOffset()));
        info.put("angle", round2(Math.toDegrees(texture.getAngle())));
        info.put("scale", round2(texture.getScale()));
        info.put("fittingArea", texture.isFittingArea());
        info.put("leftToRightOriented", texture.isLeftToRightOriented());
        return info;
    }

    private static Map<String, Object> baseboardInfo(Baseboard baseboard) {
        if (baseboard == null) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("thickness", round2(baseboard.getThickness()));
        info.put("height", round2(baseboard.getHeight()));
        info.put("color", colorToHex(baseboard.getColor()));
        info.put("texture", textureInfo(baseboard.getTexture()));
        return info;
    }

    private static Map<String, Object> textStyleInfo(TextStyle style) {
        if (style == null) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("fontName", style.getFontName());
        info.put("fontSize", round2(style.getFontSize()));
        info.put("bold", style.isBold());
        info.put("italic", style.isItalic());
        info.put("alignment", style.getAlignment().name());
        return info;
    }

    private static Map<String, Object> levelInfo(Level level) {
        if (level == null) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", level.getId());
        info.put("name", level.getName());
        info.put("elevation", round2(level.getElevation()));
        info.put("height", round2(level.getHeight()));
        return info;
    }

    private static List<Object> transformations(Transformation[] transformations) {
        List<Object> result = new ArrayList<>();
        if (transformations == null) return result;
        for (Transformation transformation : transformations) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", transformation.getName());
            info.put("matrix", matrix(transformation.getMatrix()));
            result.add(info);
        }
        return result;
    }

    private static List<Object> matrix(float[][] matrix) {
        List<Object> rows = new ArrayList<>();
        if (matrix == null) return rows;
        for (float[] row : matrix) rows.add(floats(row));
        return rows;
    }

    public static List<Object> points(float[][] points) {
        List<Object> result = new ArrayList<>();
        if (points == null) return result;
        for (float[] point : points) {
            Map<String, Object> value = new LinkedHashMap<>();
            if (point.length > 0) value.put("x", round2(point[0]));
            if (point.length > 1) value.put("y", round2(point[1]));
            if (point.length > 2) value.put("z", round2(point[2]));
            result.add(value);
        }
        return result;
    }

    private static List<Object> floats(float[] values) {
        List<Object> result = new ArrayList<>();
        if (values != null) {
            for (float value : values) result.add(round2(value));
        }
        return result;
    }

    private static List<Object> stringList(String[] values) {
        List<Object> result = new ArrayList<>();
        if (values != null) Collections.addAll(result, values);
        return result;
    }

    private static List<String> containingRoomIds(Home home, HomePieceOfFurniture piece) {
        List<String> ids = new ArrayList<>();
        for (Room room : home.getRooms()) {
            if (sameLevel(piece.getLevel(), room.getLevel())
                    && room.containsPoint(piece.getX(), piece.getY(), 0.01f)) {
                ids.add(room.getId());
            }
        }
        return ids;
    }

    private static void collectFurnitureInRoom(List<HomePieceOfFurniture> furniture, Room room,
                                                List<String> ids) {
        for (HomePieceOfFurniture piece : furniture) {
            if (sameLevel(piece.getLevel(), room.getLevel())
                    && room.containsPoint(piece.getX(), piece.getY(), 0.01f)) {
                ids.add(piece.getId());
            }
            if (piece instanceof HomeFurnitureGroup) {
                collectFurnitureInRoom(((HomeFurnitureGroup) piece).getFurniture(), room, ids);
            }
        }
    }

    private static List<String> intersectingWallIds(Home home, HomePieceOfFurniture piece) {
        float[][] footprint = piece.getPoints();
        float[] bounds = bounds(footprint);
        List<String> ids = new ArrayList<>();
        if (bounds == null) return ids;
        for (Wall wall : home.getWalls()) {
            if (sameLevel(piece.getLevel(), wall.getLevel())
                    && wall.intersectsRectangle(bounds[0], bounds[1], bounds[2], bounds[3])) {
                ids.add(wall.getId());
            }
        }
        return ids;
    }

    private static List<String> intersectingDoorOrWindowIds(Home home, Wall wall) {
        List<String> ids = new ArrayList<>();
        collectIntersectingDoorOrWindowIds(home.getFurniture(), wall, ids);
        return ids;
    }

    private static void collectIntersectingDoorOrWindowIds(List<HomePieceOfFurniture> furniture,
                                                            Wall wall, List<String> ids) {
        for (HomePieceOfFurniture piece : furniture) {
            if (piece.isDoorOrWindow() && sameLevel(piece.getLevel(), wall.getLevel())) {
                float[] bounds = bounds(piece.getPoints());
                if (bounds != null && wall.intersectsRectangle(bounds[0], bounds[1], bounds[2], bounds[3])) {
                    ids.add(piece.getId());
                }
            }
            if (piece instanceof HomeFurnitureGroup) {
                collectIntersectingDoorOrWindowIds(((HomeFurnitureGroup) piece).getFurniture(), wall, ids);
            }
        }
    }

    private static float[] bounds(float[][] points) {
        if (points == null || points.length == 0) return null;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (float[] point : points) {
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0]);
            maxY = Math.max(maxY, point[1]);
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    private static String findParentGroupId(List<HomePieceOfFurniture> furniture, String childId) {
        for (HomePieceOfFurniture piece : furniture) {
            if (piece instanceof HomeFurnitureGroup) {
                HomeFurnitureGroup group = (HomeFurnitureGroup) piece;
                for (HomePieceOfFurniture child : group.getFurniture()) {
                    if (child.getId().equals(childId)) return group.getId();
                }
                String nested = findParentGroupId(group.getFurniture(), childId);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static boolean sameLevel(Level first, Level second) {
        return first == null ? second == null : first.equals(second);
    }

    private static String decimal(BigDecimal value) {
        return value != null ? value.toPlainString() : null;
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private static Number numericProperty(HomeObject object, String name) {
        String value = object.getProperty(name);
        if (value == null || value.trim().isEmpty()) return null;
        try {
            double number = Double.parseDouble(value);
            return Math.rint(number) == number ? Long.valueOf((long) number) : Double.valueOf(number);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
