package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;

import com.eteks.sweethome3d.io.HomeFileRecorder;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.RecorderException;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import com.sh3d.mcp.bridge.PathValidator;

import com.sh3d.mcp.command.util.SchemaBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Обработчик команды "save_home".
 * Сохраняет текущую сцену в .sh3d файл через HomeFileRecorder.
 *
 * <pre>
 * Параметры:
 *   filePath (optional) — путь к файлу. Если не указан, используется Home.getName().
 * Возвращает:
 *   filePath — абсолютный путь к сохранённому файлу
 *   sizeBytes — размер файла в байтах
 * </pre>
 */
public class SaveHomeHandler implements CommandHandler, CommandDescriptor {

    private static final Logger LOG = Logger.getLogger(SaveHomeHandler.class.getName());

    private static final int COMPRESSION_LEVEL = 9;
    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final boolean copyMode;

    public SaveHomeHandler() {
        this(false);
    }

    public SaveHomeHandler(boolean copyMode) {
        this.copyMode = copyMode;
    }

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        // 1. Определяем путь к файлу
        String filePath = request.getString("filePath");
        boolean createBackup = defaultValue(request.getBoolean("createBackup"), true);
        boolean allowOverwrite = defaultValue(request.getBoolean("allowOverwrite"), true);
        boolean markAsCurrent = copyMode
                ? false
                : defaultValue(request.getBoolean("markAsCurrent"), true);
        if (copyMode && (filePath == null || filePath.trim().isEmpty())) {
            return Response.error("save_as_copy requires filePath");
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            filePath = accessor.runOnEDT(() -> accessor.getHome().getName());
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            return Response.error(
                    "No file path specified and home has not been saved before. "
                            + "Provide 'filePath' parameter.");
        }

        // 2. Нормализация пути + расширение + создание директорий
        Path path;
        try {
            path = PathValidator.validateAndNormalize(filePath, ".sh3d");
        } catch (Exception e) {
            return Response.error("Cannot create directory: " + e.getMessage());
        }
        String normalizedPath = path.toString();

        if (Files.exists(path) && !allowOverwrite) {
            return Response.error("Target already exists. Choose another filePath or set allowOverwrite=true: "
                    + normalizedPath);
        }

        // 4. Клонируем Home на EDT
        Home clonedHome = accessor.runOnEDT(() -> accessor.getHome().clone());

        // 5. Записываем файл вне EDT
        Path temporary = null;
        Path backup = null;
        try {
            if (Files.exists(path) && createBackup) {
                backup = backupPath(path);
                Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            temporary = Files.createTempFile(path.getParent(),
                    "." + path.getFileName().toString() + "-", ".tmp");
            HomeFileRecorder recorder = new HomeFileRecorder(COMPRESSION_LEVEL, false);
            recorder.writeHome(clonedHome, temporary.toString());
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;

            // 6. Обновляем состояние оригинального Home на EDT
            if (markAsCurrent) {
                accessor.runOnEDT(() -> {
                    Home home = accessor.getHome();
                    home.setName(normalizedPath);
                    home.setModified(false);
                    return null;
                });
            }

            // 7. Результат
            long sizeBytes = Files.size(path);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", normalizedPath);
            data.put("sizeBytes", sizeBytes);
            data.put("backupPath", backup != null ? backup.toString() : null);
            data.put("atomicWrite", true);
            data.put("markedAsCurrent", markAsCurrent);
            data.put("copyOnly", !markAsCurrent);

            LOG.info("Home saved: " + normalizedPath + " (" + sizeBytes + " bytes)");
            return Response.ok(data);

        } catch (RecorderException e) {
            LOG.log(Level.WARNING, "Save failed", e);
            return Response.error("Save failed: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            LOG.log(Level.SEVERE, "OOM during save", e);
            return Response.error("Out of memory during save — reduce scene complexity");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Save failed", e);
            return Response.error("Save failed: " + e.getMessage());
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception cleanupError) {
                    LOG.fine("Could not remove temporary save file: " + cleanupError.getMessage());
                }
            }
        }
    }

    private static boolean defaultValue(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Path backupPath(Path path) {
        String name = path.getFileName().toString();
        int dot = name.toLowerCase().endsWith(".sh3d") ? name.length() - 5 : name.length();
        String backupName = name.substring(0, dot) + ".backup-"
                + BACKUP_STAMP.format(LocalDateTime.now()) + ".sh3d";
        Path candidate = path.resolveSibling(backupName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = path.resolveSibling(name.substring(0, dot) + ".backup-"
                    + BACKUP_STAMP.format(LocalDateTime.now()) + "-" + suffix++ + ".sh3d");
        }
        return candidate;
    }

    @Override
    public String getDescription() {
        return (copyMode ? "Saves an independent copy of the current home without changing its active file. "
                : "Saves the current home to a .sh3d file on disk. ")
                + "If filePath is provided, saves to that location (Save As). "
                + "If filePath is omitted, saves to the current file path "
                + "(requires home to have been saved before). "
                + "Returns the absolute path and file size in bytes.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .string("filePath",
                        "Absolute path for the .sh3d file. "
                                + "If omitted, saves to the current file (Home > Save). "
                                + "The .sh3d extension is added automatically if missing.")
                .boolWithDefault("createBackup",
                        "Create a timestamped backup before replacing an existing target", true)
                .boolWithDefault("allowOverwrite",
                        "Allow replacing an existing target; createBackup still preserves its previous contents", true)
                .boolWithDefault("markAsCurrent",
                        "Make the saved path the active document and clear its modified flag", !copyMode)
                .build();
    }
}
