package com.sh3d.mcp.plugin;

import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.HomeIdentity;
import com.sh3d.mcp.bridge.LayoutAlternativeManager;
import com.sh3d.mcp.command.handler.AddDimensionLineHandler;
import com.sh3d.mcp.command.handler.AddLabelHandler;
import com.sh3d.mcp.command.handler.AddLevelHandler;
import com.sh3d.mcp.command.handler.ApplyTextureHandler;
import com.sh3d.mcp.command.handler.AnalyzeArchitectureHandler;
import com.sh3d.mcp.command.handler.AttachFurnitureToWallHandler;
import com.sh3d.mcp.bridge.CheckpointManager;
import com.sh3d.mcp.command.handler.BatchCommandsHandler;
import com.sh3d.mcp.command.handler.CheckpointHandler;
import com.sh3d.mcp.command.handler.CheckClearancesHandler;
import com.sh3d.mcp.command.handler.ClearSceneHandler;
import com.sh3d.mcp.command.CommandRegistry;
import com.sh3d.mcp.command.handler.CreateRoomPolygonHandler;
import com.sh3d.mcp.command.handler.ConnectWallsHandler;
import com.sh3d.mcp.command.handler.ConfigureStaircaseHandler;
import com.sh3d.mcp.command.handler.DeleteWallHandler;
import com.sh3d.mcp.command.handler.DuplicateObjectsHandler;
import com.sh3d.mcp.command.handler.CreateWallHandler;
import com.sh3d.mcp.command.handler.DeleteFurnitureHandler;
import com.sh3d.mcp.command.handler.DeleteLevelHandler;
import com.sh3d.mcp.command.handler.DeleteRoomHandler;
import com.sh3d.mcp.command.handler.CreateWallsHandler;
import com.sh3d.mcp.command.handler.ExportPlanImageHandler;
import com.sh3d.mcp.command.handler.ExportSvgHandler;
import com.sh3d.mcp.command.handler.ExportToObjHandler;
import com.sh3d.mcp.command.handler.GenerateShapeHandler;
import com.sh3d.mcp.command.handler.GetCamerasHandler;
import com.sh3d.mcp.command.handler.GetDocumentContextHandler;
import com.sh3d.mcp.command.handler.HealthCheckHandler;
import com.sh3d.mcp.command.handler.ListInstancesHandler;
import com.sh3d.mcp.command.handler.ActivateHomeHandler;
import com.sh3d.mcp.command.handler.ModifyFurnitureHandler;
import com.sh3d.mcp.command.handler.ModifyRoomHandler;
import com.sh3d.mcp.command.handler.ModifyWallHandler;
import com.sh3d.mcp.command.handler.GetStateHandler;
import com.sh3d.mcp.command.handler.InspectObjectsHandler;
import com.sh3d.mcp.command.handler.GroupFurnitureHandler;
import com.sh3d.mcp.command.handler.ListCheckpointsHandler;
import com.sh3d.mcp.command.handler.ListCategoriesHandler;
import com.sh3d.mcp.command.handler.ListFurnitureCatalogHandler;
import com.sh3d.mcp.command.handler.ListLevelsHandler;
import com.sh3d.mcp.command.handler.ListInstalledPluginsHandler;
import com.sh3d.mcp.command.handler.LayoutAlternativesHandler;
import com.sh3d.mcp.command.handler.ListTexturesCatalogHandler;
import com.sh3d.mcp.command.handler.PlaceDoorOrWindowHandler;
import com.sh3d.mcp.command.handler.PlaceFurnitureHandler;
import com.sh3d.mcp.command.handler.RenderPhotoHandler;
import com.sh3d.mcp.command.handler.RestoreCheckpointHandler;
import com.sh3d.mcp.command.handler.LoadHomeHandler;
import com.sh3d.mcp.command.handler.SaveHomeHandler;
import com.sh3d.mcp.command.handler.SetCameraHandler;
import com.sh3d.mcp.command.handler.SetEnvironmentHandler;
import com.sh3d.mcp.command.handler.SetSelectedLevelHandler;
import com.sh3d.mcp.command.handler.StoreCameraHandler;
import com.sh3d.mcp.command.handler.UngroupFurnitureHandler;
import com.sh3d.mcp.command.handler.ValidateWallJunctionsHandler;
import com.sh3d.mcp.config.PluginConfig;
import com.sh3d.mcp.http.HttpMcpServer;
import com.eteks.sweethome3d.viewcontroller.ExportableView;
import com.eteks.sweethome3d.viewcontroller.PlanView;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Главный класс плагина Sweet Home 3D MCP.
 * Указывается в ApplicationPlugin.properties.
 */
public class SH3DMcpPlugin extends Plugin {

    public static final String PLUGIN_VERSION = PluginConfig.PLUGIN_VERSION;

    private static final Logger LOG = Logger.getLogger(SH3DMcpPlugin.class.getName());

    /** Lock for all static singleton state. */
    private static final Object LOCK = new Object();

    /** Shared MCP server — created by the first Plugin instance, stopped by the last. */
    private static HttpMcpServer sharedServer;

    /** Tracks all active Plugin instances in insertion order (most recent = last). */
    private static final LinkedHashSet<SH3DMcpPlugin> activeInstances = new LinkedHashSet<>();
    private static SH3DMcpPlugin currentInstance;

    private HttpMcpServer httpServer;
    private PluginConfig config;
    private FileHandler logFileHandler;

    /** Per-instance registry and accessor, kept for context-switch on destroy(). */
    private CommandRegistry registry;
    private HomeAccessor accessor;

    @Override
    public PluginAction[] getActions() {
        config = PluginConfig.load();
        setupFileLogging(config);

        accessor = new HomeAccessor(
                getHome(),
                getUserPreferences()
        );

        ExportableView planView = resolvePlanView();
        registry = createCommandRegistry(planView);

        synchronized (LOCK) {
            activeInstances.add(this);
            if (sharedServer == null) {
                sharedServer = new HttpMcpServer(config, registry, accessor);
                LOG.info("SH3D MCP Plugin initialized — first Home (port: " + config.getPort() + ")");
                if (config.isAutoStart()) {
                    sharedServer.start();
                }
            } else {
                sharedServer.switchContext(registry, accessor);
                LOG.info("SH3D MCP Plugin initialized — additional Home, context switched");
            }
            currentInstance = this;
            httpServer = sharedServer;
        }

        return new PluginAction[]{
                new McpSettingsAction(this, httpServer)
        };
    }

    @Override
    public void destroy() {
        synchronized (LOCK) {
            activeInstances.remove(this);
            if (activeInstances.isEmpty()) {
                if (sharedServer != null && sharedServer.isRunning()) {
                    sharedServer.stop();
                    LOG.info("SH3D MCP Plugin destroyed — last Home, server stopped");
                }
                sharedServer = null;
                currentInstance = null;
            } else {
                // The closed Home was the current context — switch to the most recent remaining
                SH3DMcpPlugin remaining = null;
                for (SH3DMcpPlugin instance : activeInstances) {
                    remaining = instance; // LinkedHashSet iteration order: last = most recent
                }
                if (remaining != null && sharedServer != null) {
                    sharedServer.switchContext(remaining.registry, remaining.accessor);
                    currentInstance = remaining;
                    LOG.info("SH3D MCP Plugin destroyed — context switched to remaining Home");
                }
            }
        }
        if (logFileHandler != null) {
            Logger.getLogger("com.sh3d.mcp").removeHandler(logFileHandler);
            logFileHandler.close();
            logFileHandler = null;
        }
    }

    /** Configures file-based logging for the com.sh3d.mcp logger hierarchy. */
    private void setupFileLogging(PluginConfig cfg) {
        Path logPath = PluginConfig.resolveLogPath();
        if (logPath == null) {
            return;
        }
        try {
            FileHandler fh = new FileHandler(logPath.toString(), 1_048_576, 2, true);
            fh.setFormatter(new SimpleFormatter());

            Logger rootLogger = Logger.getLogger("com.sh3d.mcp");
            rootLogger.addHandler(fh);
            logFileHandler = fh;

            Level level;
            try {
                level = Level.parse(cfg.getLogLevel());
            } catch (IllegalArgumentException e) {
                LOG.warning("Invalid log level '" + cfg.getLogLevel() + "', falling back to INFO");
                level = Level.INFO;
            }
            rootLogger.setLevel(level);

            LOG.info("SH3D MCP Plugin v" + PLUGIN_VERSION + " | port=" + cfg.getPort()
                    + " | Java " + System.getProperty("java.version")
                    + " | " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                    + " | log=" + logPath);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to setup file logging at " + logPath, e);
        }
    }

    /** Resolves the plan view for SVG/PNG export via HomeController. */
    private ExportableView resolvePlanView() {
        try {
            PlanView planView = getHomeController().getPlanController().getView();
            if (planView instanceof ExportableView) {
                LOG.info("Resolved PlanView for SVG export: " + planView.getClass().getSimpleName());
                return (ExportableView) planView;
            }
            LOG.warning("PlanView does not implement ExportableView: " + planView.getClass().getName());
        } catch (Exception e) {
            LOG.warning("Could not resolve PlanView: " + e.getMessage());
        }
        return null;
    }

    private CommandRegistry createCommandRegistry(ExportableView planView) {
        CommandRegistry registry = new CommandRegistry();
        CheckpointManager checkpointManager = new CheckpointManager();
        LayoutAlternativeManager alternativeManager = new LayoutAlternativeManager();
        registry.register("checkpoint", new CheckpointHandler(checkpointManager));
        registry.register("restore_checkpoint", new RestoreCheckpointHandler(checkpointManager));
        registry.register("list_checkpoints", new ListCheckpointsHandler(checkpointManager));
        registry.register("add_dimension_line", new AddDimensionLineHandler());
        registry.register("add_label", new AddLabelHandler());
        registry.register("add_level", new AddLevelHandler());
        registry.register("apply_texture", new ApplyTextureHandler());
        registry.register("analyze_architecture", new AnalyzeArchitectureHandler());
        registry.register("attach_furniture_to_wall", new AttachFurnitureToWallHandler());
        registry.register("check_clearances", new CheckClearancesHandler());
        registry.register("clear_scene", new ClearSceneHandler(checkpointManager));
        registry.register("connect_walls", new ConnectWallsHandler());
        registry.register("configure_staircase", new ConfigureStaircaseHandler());
        registry.register("create_room_polygon", new CreateRoomPolygonHandler());
        registry.register("create_wall", new CreateWallHandler());
        registry.register("create_walls", new CreateWallsHandler());
        registry.register("delete_furniture", new DeleteFurnitureHandler());
        registry.register("delete_level", new DeleteLevelHandler());
        registry.register("delete_room", new DeleteRoomHandler());
        registry.register("delete_wall", new DeleteWallHandler());
        registry.register("duplicate_objects", new DuplicateObjectsHandler());
        registry.register("generate_shape", new GenerateShapeHandler());
        registry.register("modify_furniture", new ModifyFurnitureHandler());
        registry.register("modify_room", new ModifyRoomHandler());
        registry.register("modify_wall", new ModifyWallHandler());
        registry.register("validate_wall_junctions", new ValidateWallJunctionsHandler());
        registry.register("place_door_or_window", new PlaceDoorOrWindowHandler());
        registry.register("place_furniture", new PlaceFurnitureHandler());
        registry.register("get_state", new GetStateHandler());
        registry.register("get_document_context", new GetDocumentContextHandler(
                config != null ? config.getPort() : PluginConfig.DEFAULT_PORT));
        registry.register("health_check", new HealthCheckHandler(
                config != null ? config.getPort() : PluginConfig.DEFAULT_PORT));
        registry.register("list_instances", new ListInstancesHandler(SH3DMcpPlugin::listOpenHomes));
        registry.register("activate_home", new ActivateHomeHandler(SH3DMcpPlugin::activateOpenHome));
        registry.register("connect_instance", new ActivateHomeHandler(SH3DMcpPlugin::activateOpenHome));
        registry.register("inspect_objects", new InspectObjectsHandler());
        registry.register("list_installed_plugins", new ListInstalledPluginsHandler());
        registry.register("layout_alternatives",
                new LayoutAlternativesHandler(alternativeManager, checkpointManager));
        registry.register("list_categories", new ListCategoriesHandler());
        registry.register("list_furniture_catalog", new ListFurnitureCatalogHandler());
        registry.register("list_levels", new ListLevelsHandler());
        registry.register("list_textures_catalog", new ListTexturesCatalogHandler());
        registry.register("render_photo", new RenderPhotoHandler());
        registry.register("load_home", new LoadHomeHandler());
        registry.register("save_home", new SaveHomeHandler());
        registry.register("save_as_copy", new SaveHomeHandler(true));
        registry.register("export_plan_image", new ExportPlanImageHandler(planView));
        registry.register("export_svg", new ExportSvgHandler(planView));
        registry.register("export_to_obj", new ExportToObjHandler());
        registry.register("set_camera", new SetCameraHandler());
        registry.register("set_environment", new SetEnvironmentHandler());
        registry.register("set_selected_level", new SetSelectedLevelHandler());
        registry.register("store_camera", new StoreCameraHandler());
        registry.register("get_cameras", new GetCamerasHandler());
        registry.register("group_furniture", new GroupFurnitureHandler());
        registry.register("ungroup_furniture", new UngroupFurnitureHandler());
        registry.register("batch_commands", new BatchCommandsHandler(registry, checkpointManager));
        return registry;
    }

    private static List<Map<String, Object>> listOpenHomes() {
        List<SH3DMcpPlugin> snapshot;
        SH3DMcpPlugin active;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(activeInstances);
            active = currentInstance;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (SH3DMcpPlugin instance : snapshot) {
            Map<String, Object> info = instance.accessor.runOnEDT(() -> {
                com.eteks.sweethome3d.model.Home home = instance.accessor.getHome();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("homeId", HomeIdentity.documentId(home));
                item.put("documentId", HomeIdentity.documentId(home));
                item.put("name", home.getName());
                item.put("filePath", absolutePath(home.getName()));
                item.put("modified", home.isModified());
                item.put("active", instance == active);
                item.put("processId", ProcessHandle.current().pid());
                item.put("port", instance.config != null
                        ? instance.config.getPort() : PluginConfig.DEFAULT_PORT);
                return item;
            });
            result.add(info);
        }
        return result;
    }

    private static Boolean activateOpenHome(String homeId) {
        List<SH3DMcpPlugin> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(activeInstances);
        }
        for (SH3DMcpPlugin instance : snapshot) {
            String candidate = instance.accessor.runOnEDT(
                    () -> HomeIdentity.documentId(instance.accessor.getHome()));
            if (candidate.equals(homeId)) {
                synchronized (LOCK) {
                    if (!activeInstances.contains(instance) || sharedServer == null) return false;
                    sharedServer.switchContext(instance.registry, instance.accessor);
                    currentInstance = instance;
                    // Make this document the fallback context if another document closes.
                    activeInstances.remove(instance);
                    activeInstances.add(instance);
                    return true;
                }
            }
        }
        return false;
    }

    private static String absolutePath(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        try {
            Path path = Paths.get(name);
            return path.isAbsolute() ? path.normalize().toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
