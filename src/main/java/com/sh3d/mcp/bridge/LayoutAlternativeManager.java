package com.sh3d.mcp.bridge;

import com.eteks.sweethome3d.model.Home;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent named Home snapshots used to compare layout alternatives. */
public class LayoutAlternativeManager {

    public static final int MAX_ALTERNATIVES = 10;

    private final LinkedHashMap<String, Alternative> alternatives = new LinkedHashMap<>();

    public synchronized void save(String name, String description, Home snapshot) {
        if (!alternatives.containsKey(name) && alternatives.size() >= MAX_ALTERNATIVES) {
            throw new IllegalStateException("Maximum of " + MAX_ALTERNATIVES + " alternatives reached");
        }
        alternatives.put(name, new Alternative(name, description, snapshot, System.currentTimeMillis()));
    }

    public synchronized Home getClone(String name) {
        Alternative alternative = alternatives.get(name);
        return alternative != null ? alternative.snapshot.clone() : null;
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Alternative alternative : alternatives.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", alternative.name);
            item.put("description", alternative.description);
            item.put("timestamp", alternative.timestamp);
            item.put("walls", alternative.snapshot.getWalls().size());
            item.put("rooms", alternative.snapshot.getRooms().size());
            item.put("furniture", alternative.snapshot.getFurniture().size());
            result.add(item);
        }
        return result;
    }

    public synchronized int size() {
        return alternatives.size();
    }

    private static final class Alternative {
        private final String name;
        private final String description;
        private final Home snapshot;
        private final long timestamp;

        private Alternative(String name, String description, Home snapshot, long timestamp) {
            this.name = name;
            this.description = description;
            this.snapshot = snapshot;
            this.timestamp = timestamp;
        }
    }
}
