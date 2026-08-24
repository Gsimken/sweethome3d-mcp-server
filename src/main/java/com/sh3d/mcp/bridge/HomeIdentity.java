package com.sh3d.mcp.bridge;

import com.eteks.sweethome3d.model.Home;

/** Stable identifier for the Home object served by this Sweet Home 3D process. */
public final class HomeIdentity {

    private HomeIdentity() {
    }

    public static String documentId(Home home) {
        if (home == null) return null;
        return ProcessHandle.current().pid() + ":"
                + Integer.toHexString(System.identityHashCode(home));
    }
}
