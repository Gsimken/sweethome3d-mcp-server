package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ArchitecturalToolsTest {

    private Home home;
    private HomeAccessor accessor;

    @BeforeEach
    void setUp() {
        home = new Home();
        accessor = createAccessor(home);
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzesRoomsAndBoundaryWalls() {
        Room room = addRoom(home, 0, 0, 400, 300);
        room.setName("Living room");
        addWall(home, 0, 0, 400, 0);
        addWall(home, 400, 0, 400, 300);
        addWall(home, 400, 300, 0, 300);
        addWall(home, 0, 300, 0, 0);

        Response response = new AnalyzeArchitectureHandler().execute(
                new Request("analyze_architecture", Collections.emptyMap()), accessor);

        assertTrue(response.isOk());
        Map<String, Object> summary = (Map<String, Object>) response.getData().get("summary");
        assertEquals(1, summary.get("rooms"));
        assertEquals(4, summary.get("walls"));
        assertEquals(4, summary.get("exteriorWalls"));
        List<Map<String, Object>> rooms = (List<Map<String, Object>>) response.getData().get("rooms");
        assertEquals("Living room", rooms.get(0).get("name"));
        assertEquals(false, rooms.get(0).get("hasDoorAccess"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detectsFurnitureCollisionAndWallIntrusion() {
        addWall(home, 0, 0, 400, 0);
        addFurniture(home, "Sofa", 100, 0);
        addFurniture(home, "Table", 100, 0);

        Response response = new CheckClearancesHandler().execute(
                new Request("check_clearances", Collections.emptyMap()), accessor);

        assertTrue(response.isOk());
        Map<String, Object> summary = (Map<String, Object>) response.getData().get("summary");
        assertEquals(1, summary.get("collisions"));
        assertEquals(2, summary.get("wallIntrusions"));
        assertEquals(false, summary.get("passes"));
    }

    @Test
    void attachesFurnitureToDetectedInteriorWallSide() {
        Wall wall = addWall(home, 0, 0, 400, 0);
        addRoom(home, 0, 0, 400, 300);
        HomePieceOfFurniture tv = addFurniture(home, "TV", 0, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("furnitureId", tv.getId());
        params.put("wallId", wall.getId());
        params.put("side", "auto");
        params.put("along", 0.5);
        Response response = new AttachFurnitureToWallHandler().execute(
                new Request("attach_furniture_to_wall", params), accessor);

        assertTrue(response.isOk());
        assertEquals("left", response.getData().get("resolvedSide"));
        assertEquals(200d, response.getData().get("x"));
        assertEquals(30d, response.getData().get("y"));
        assertEquals(wall.getId(), tv.getProperty("mcp.attachedWallId"));
    }

    @Test
    void configuresPersistentStaircaseSemantics() {
        Level ground = addLevel(home, "Ground", 0, 250, 20);
        Level upper = addLevel(home, "Upper", 280, 250, 20);
        HomePieceOfFurniture stairs = addFurniture(home, "Stairs", 0, 0);
        stairs.setLevel(ground);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", stairs.getId());
        params.put("startLevelId", ground.getId());
        params.put("endLevelId", upper.getId());
        params.put("startX", 100);
        params.put("startY", 200);
        params.put("upDirection", 0);
        params.put("width", 90);
        params.put("run", 280);
        params.put("steps", 16);
        Response response = new ConfigureStaircaseHandler().execute(
                new Request("configure_staircase", params), accessor);

        assertTrue(response.isOk());
        assertEquals("staircase", stairs.getProperty("mcp.semanticType"));
        assertEquals(upper.getId(), stairs.getProperty("mcp.staircase.endLevelId"));
        assertEquals("16", stairs.getProperty("mcp.staircase.steps"));
        assertEquals("M0,0 v1 h1 v-1 z", stairs.getStaircaseCutOutShape());
        assertEquals(240f, stairs.getX(), 0.01f);
        assertEquals(200f, stairs.getY(), 0.01f);
        assertEquals(280f, stairs.getHeight(), 0.01f);
    }

    @Test
    void identifiesCurrentDocumentAndEndpoint() {
        String path = Paths.get("build", "house.sh3d").toAbsolutePath().normalize().toString();
        home.setName(path);
        home.setModified(true);

        Response response = new GetDocumentContextHandler(9877).execute(
                new Request("get_document_context", Collections.emptyMap()), accessor);

        assertTrue(response.isOk());
        assertEquals(path, response.getData().get("filePath"));
        assertEquals(true, response.getData().get("modified"));
        assertEquals("http://127.0.0.1:9877/mcp", response.getData().get("mcpEndpoint"));
        assertNotNull(response.getData().get("documentSessionId"));
    }
}
