package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

class InspectObjectsHandlerTest {

    private Home home;
    private HomeAccessor accessor;
    private InspectObjectsHandler handler;

    @BeforeEach
    void setUp() {
        home = new Home();
        accessor = createAccessor(home);
        handler = new InspectObjectsHandler();
    }

    @Test
    @SuppressWarnings("unchecked")
    void inspectsWallWithConnectionsAndPluginProperties() {
        Wall first = new Wall(0, 0, 400, 0, 10);
        Wall second = new Wall(400, 0, 400, 300, 10);
        first.setWallAtEnd(second);
        second.setWallAtStart(first);
        first.setProperty("plugin.owner", "GenerateRoof");
        home.addWall(first);
        home.addWall(second);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ids", Collections.singletonList(first.getId()));
        Response response = handler.execute(new Request("inspect_objects", params), accessor);

        assertTrue(response.isOk());
        List<Map<String, Object>> objects = (List<Map<String, Object>>) response.getData().get("objects");
        assertEquals(1, objects.size());
        Map<String, Object> wall = objects.get(0);
        assertEquals("wall", wall.get("objectType"));
        assertEquals(first.getId(), wall.get("id"));
        Map<String, Object> properties = (Map<String, Object>) wall.get("customProperties");
        assertEquals("GenerateRoof", properties.get("plugin.owner"));
        Map<String, Object> relations = (Map<String, Object>) wall.get("relations");
        assertEquals(second.getId(), relations.get("wallAtEndId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void omittingIdsUsesCurrentSelection() {
        Room room = new Room(new float[][]{{0, 0}, {400, 0}, {400, 300}, {0, 300}});
        home.addRoom(room);
        home.setSelectedItems(Collections.singletonList(room));

        Response response = handler.execute(new Request("inspect_objects", Collections.emptyMap()), accessor);

        assertEquals("currentSelection", response.getData().get("source"));
        assertEquals(1, response.getData().get("objectCount"));
        List<Map<String, Object>> objects = (List<Map<String, Object>>) response.getData().get("objects");
        assertEquals(room.getId(), objects.get(0).get("id"));
        assertEquals(true, objects.get(0).get("selected"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsUnknownIdsWithoutFailingKnownOnes() {
        Wall wall = new Wall(0, 0, 100, 0, 10);
        home.addWall(wall);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ids", Arrays.asList(wall.getId(), "missing-id"));

        Response response = handler.execute(new Request("inspect_objects", params), accessor);

        assertTrue(response.isOk());
        assertEquals(1, response.getData().get("objectCount"));
        assertEquals(Collections.singletonList("missing-id"), response.getData().get("notFoundIds"));
    }

    @Test
    void validatesIdsAndPropertyLimit() {
        Map<String, Object> invalidIds = new LinkedHashMap<>();
        invalidIds.put("ids", "not-an-array");
        assertTrue(handler.execute(new Request("inspect_objects", invalidIds), accessor).isError());

        Map<String, Object> invalidLimit = new LinkedHashMap<>();
        invalidLimit.put("maxPropertyLength", 12);
        assertTrue(handler.execute(new Request("inspect_objects", invalidLimit), accessor).isError());
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaDocumentsOptionalIds() {
        Map<String, Object> schema = handler.getSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("ids"));
        assertTrue(properties.containsKey("includeRelations"));
        assertTrue(properties.containsKey("includeCustomProperties"));
        assertTrue(properties.containsKey("maxPropertyLength"));
        assertTrue(((List<String>) schema.get("required")).isEmpty());
    }
}
