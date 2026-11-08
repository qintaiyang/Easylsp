package io.github.easylsp.api.internal;
public final class Protocol {
    public static final int VERSION = 4;
    public static final String MANAGER_PACKAGE = "io.github.easylsp";
    public static final String BRIDGE_SERVICE = "io.github.easylsp.app.platform.bridge.BridgeService";
    public static final String RECONNECT_PERMISSION = "io.github.easylsp.permission.RECONNECT";
    public static final String RECONNECT_ACTION = "io.github.easylsp.RECONNECT";
    public static final String BRIDGE_BINDER = "bridgeBinder";
    public static final String MANAGER_UID = "managerUid";
    private Protocol() {}
}
