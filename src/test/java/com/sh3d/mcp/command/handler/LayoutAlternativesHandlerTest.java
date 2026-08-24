package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.sh3d.mcp.bridge.CheckpointManager;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.LayoutAlternativeManager;
import com.sh3d.mcp.bridge.ObjectResolver;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.addFurniture;
import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

class LayoutAlternativesHandlerTest {

    private Home home;
    private HomeAccessor accessor;
    private CheckpointManager checkpoints;
    private LayoutAlternativesHandler handler;

    @BeforeEach
    void setUp() {
        home = new Home();
        accessor = createAccessor(home);
        checkpoints = new CheckpointManager();
        handler = new LayoutAlternativesHandler(new LayoutAlternativeManager(), checkpoints);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savesComparesAndRestoresIndependentAlternative() {
        HomePieceOfFurniture sofa = addFurniture(home, "Sofa", 100, 100);
        Response saved = execute("save", "Base", null);
        assertTrue(saved.isOk());

        sofa.setX(300);
        Response comparison = execute("compare", "Base", null);
        assertTrue(comparison.isOk());
        Map<String, Object> summary = (Map<String, Object>) comparison.getData().get("summary");
        assertEquals(1, summary.get("changed"));
        List<Map<String, Object>> changed =
                (List<Map<String, Object>>) comparison.getData().get("changedObjects");
        assertTrue(((List<String>) changed.get(0).get("changedFields")).contains("x"));

        Response restored = execute("restore", "Base", null);
        assertTrue(restored.isOk());
        HomePieceOfFurniture restoredSofa = ObjectResolver.findFurniture(home, sofa.getId());
        assertNotNull(restoredSofa);
        assertEquals(100f, restoredSofa.getX(), 0.01f);
        assertEquals(1, checkpoints.size());
    }

    @Test
    void comparesTwoSavedAlternatives() {
        HomePieceOfFurniture chair = addFurniture(home, "Chair", 50, 50);
        execute("save", "A", null);
        chair.setY(200);
        execute("save", "B", null);

        Response comparison = execute("compare", "A", "B");

        assertTrue(comparison.isOk());
        assertEquals("B", comparison.getData().get("comparison"));
    }

    private Response execute(String action, String name, String otherName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", action);
        if (name != null) params.put("name", name);
        if (otherName != null) params.put("otherName", otherName);
        return handler.execute(new Request("layout_alternatives", params), accessor);
    }
}
