// src/main/java/net/atlas/offline/PendingActionLedger.java
package net.atlas.offline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.atlas.offline.model.ActionStatus;
import net.atlas.offline.model.PendingAction;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PendingActionLedger {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = Logger.getLogger("ATLAS-Ledger");
    /** Actions older than 30 days are automatically purged on load. */
    private static final long STALE_ACTION_THRESHOLD_MS = 30L * 24L * 60L * 60L * 1000L;

    private final File ledgerFile;
    private final Map<String, PendingAction> actionMap = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public PendingActionLedger(File ledgerFile) {
        this.ledgerFile = ledgerFile;
    }

    public void load() {
        lock.writeLock().lock();
        try {
            actionMap.clear();
            if (!ledgerFile.exists()) {
                return;
            }
            try (FileReader reader = new FileReader(ledgerFile)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed == null || !parsed.isJsonObject()) {
                    LOGGER.warning("Ledger file is not a valid JSON object, skipping: " + ledgerFile.getAbsolutePath());
                    return;
                }
                JsonObject root = parsed.getAsJsonObject();
                if (root.has("actions") && root.get("actions").isJsonArray()) {
                    JsonArray arr = root.getAsJsonArray("actions");
                    long now = System.currentTimeMillis();
                    int loaded = 0;
                    int skippedStale = 0;
                    for (JsonElement el : arr) {
                        try {
                            if (!el.isJsonObject()) continue;
                            PendingAction action = PendingAction.fromJsonObject(el.getAsJsonObject());
                            if (action.getStatus() != ActionStatus.PENDING) {
                                continue;
                            }
                            // Evict stale actions older than the threshold
                            if ((now - action.getCreatedAt()) > STALE_ACTION_THRESHOLD_MS) {
                                skippedStale++;
                                continue;
                            }
                            actionMap.put(action.getId(), action);
                            loaded++;
                        } catch (Exception e) {
                            LOGGER.warning("Skipping malformed action entry in ledger: " + e.getMessage());
                        }
                    }
                    LOGGER.info("Loaded " + loaded + " pending actions from ledger" +
                            (skippedStale > 0 ? " (purged " + skippedStale + " stale entries)" : ""));
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to load pending actions ledger: " + ledgerFile.getAbsolutePath(), e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void queueAction(PendingAction action) {
        if (action == null || action.getId() == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            actionMap.put(action.getId(), action);
            flushInternal();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<PendingAction> getPendingActionsForPlayer(UUID uuid) {
        if (uuid == null) {
            return List.of();
        }
        lock.readLock().lock();
        try {
            List<PendingAction> result = new ArrayList<>();
            for (PendingAction action : actionMap.values()) {
                if (action.getTargetUuid().equals(uuid) && action.getStatus() == ActionStatus.PENDING) {
                    result.add(action);
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<PendingAction> getAllPendingActions() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(actionMap.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void markExecuted(String actionId) {
        if (actionId == null) return;
        lock.writeLock().lock();
        try {
            PendingAction action = actionMap.get(actionId);
            if (action != null) {
                action.setStatus(ActionStatus.EXECUTED);
                actionMap.remove(actionId);
                flushInternal();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean cancelAction(String actionId) {
        if (actionId == null) return false;
        lock.writeLock().lock();
        try {
            PendingAction removed = actionMap.remove(actionId);
            if (removed != null) {
                removed.setStatus(ActionStatus.CANCELLED);
                flushInternal();
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Public flush for shutdown hooks. Acquires write lock externally.
     */
    public void flush() {
        lock.writeLock().lock();
        try {
            flushInternal();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Internal flush that assumes the write lock is already held by the caller.
     * This prevents deadlocks from recursive lock acquisition attempts.
     */
    private void flushInternal() {
        try {
            File parent = ledgerFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            File tempFile = new File(ledgerFile.getAbsolutePath() + ".tmp");
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("lastModified", System.currentTimeMillis());

            JsonArray actionsArray = new JsonArray();
            for (PendingAction action : actionMap.values()) {
                actionsArray.add(action.toJsonObject());
            }
            root.add("actions", actionsArray);

            try (FileWriter writer = new FileWriter(tempFile)) {
                GSON.toJson(root, writer);
            }

            try {
                Files.move(tempFile.toPath(), ledgerFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), ledgerFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to flush pending actions ledger to disk", e);
        }
    }

    public int size() {
        return actionMap.size();
    }
}
