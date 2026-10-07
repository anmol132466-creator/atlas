// src/test/java/net/atlas/offline/PendingActionLedgerTest.java
package net.atlas.offline;

import com.google.gson.JsonObject;
import net.atlas.offline.model.ActionStatus;
import net.atlas.offline.model.PendingAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PendingActionLedgerTest {

    @Test
    public void testAtomicQueueAndExecution(@TempDir Path tempDir) {
        File ledgerFile = tempDir.resolve("pending_actions.json").toFile();
        PendingActionLedger ledger = new PendingActionLedger(ledgerFile);
        ledger.load();

        UUID target = UUID.randomUUID();
        JsonObject params = new JsonObject();
        params.addProperty("world", "world");

        PendingAction action = new PendingAction(
                "act_test_01",
                target,
                "TELEPORT_SPAWN",
                params,
                "TestAdmin",
                System.currentTimeMillis(),
                ActionStatus.PENDING
        );

        ledger.queueAction(action);
        assertTrue(ledgerFile.exists());
        assertEquals(1, ledger.size());

        List<PendingAction> playerActions = ledger.getPendingActionsForPlayer(target);
        assertEquals(1, playerActions.size());
        assertEquals("TELEPORT_SPAWN", playerActions.get(0).getActionType());

        // Reload fresh from disk to verify atomic persistence
        PendingActionLedger freshLedger = new PendingActionLedger(ledgerFile);
        freshLedger.load();
        List<PendingAction> persistedActions = freshLedger.getPendingActionsForPlayer(target);
        assertEquals(1, persistedActions.size());
        assertEquals(1, freshLedger.size());

        // Mark executed
        freshLedger.markExecuted("act_test_01");
        assertTrue(freshLedger.getPendingActionsForPlayer(target).isEmpty());
        assertEquals(0, freshLedger.size());
    }

    @Test
    public void testCancelAction(@TempDir Path tempDir) {
        File ledgerFile = tempDir.resolve("pending_actions_cancel.json").toFile();
        PendingActionLedger ledger = new PendingActionLedger(ledgerFile);
        ledger.load();

        UUID target = UUID.randomUUID();
        PendingAction action = new PendingAction(
                "act_cancel_01",
                target,
                "CLEAR_INVENTORY",
                new JsonObject(),
                "TestAdmin",
                System.currentTimeMillis(),
                ActionStatus.PENDING
        );

        ledger.queueAction(action);
        assertEquals(1, ledger.size());

        boolean cancelled = ledger.cancelAction("act_cancel_01");
        assertTrue(cancelled);
        assertEquals(0, ledger.size());

        // Cancelling again should return false
        assertFalse(ledger.cancelAction("act_cancel_01"));
    }

    @Test
    public void testNullInputSafety(@TempDir Path tempDir) {
        File ledgerFile = tempDir.resolve("pending_actions_null.json").toFile();
        PendingActionLedger ledger = new PendingActionLedger(ledgerFile);
        ledger.load();

        // Queue null action should not throw
        ledger.queueAction(null);
        assertEquals(0, ledger.size());

        // Get actions for null UUID should return empty list
        List<PendingAction> result = ledger.getPendingActionsForPlayer(null);
        assertTrue(result.isEmpty());

        // Mark executed with null should not throw
        ledger.markExecuted(null);

        // Cancel with null should return false
        assertFalse(ledger.cancelAction(null));
    }

    @Test
    public void testCorruptLedgerFileDoesNotCrash(@TempDir Path tempDir) throws Exception {
        File ledgerFile = tempDir.resolve("pending_actions_corrupt.json").toFile();

        // Write corrupt JSON to the file
        try (FileWriter writer = new FileWriter(ledgerFile)) {
            writer.write("THIS IS NOT JSON {{{{{");
        }

        PendingActionLedger ledger = new PendingActionLedger(ledgerFile);
        // Should not throw
        assertDoesNotThrow(ledger::load);
        assertEquals(0, ledger.size());
    }

    @Test
    public void testEmptyLedgerFileDoesNotCrash(@TempDir Path tempDir) throws Exception {
        File ledgerFile = tempDir.resolve("pending_actions_empty.json").toFile();

        // Write valid JSON but with empty actions
        try (FileWriter writer = new FileWriter(ledgerFile)) {
            writer.write("{\"version\":1,\"actions\":[]}");
        }

        PendingActionLedger ledger = new PendingActionLedger(ledgerFile);
        assertDoesNotThrow(ledger::load);
        assertEquals(0, ledger.size());
    }

    @Test
    public void testMultipleActionsForSamePlayer(@TempDir Path tempDir) {
        File ledgerFile = tempDir.resolve("pending_actions_multi.json").toFile();
        PendingActionLedger ledger = new PendingActionLedger(ledgerFile);
        ledger.load();

        UUID target = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            PendingAction action = new PendingAction(
                    "act_multi_" + i,
                    target,
                    "GIVE_ITEM",
                    new JsonObject(),
                    "TestAdmin",
                    System.currentTimeMillis(),
                    ActionStatus.PENDING
            );
            ledger.queueAction(action);
        }

        assertEquals(5, ledger.size());
        assertEquals(5, ledger.getPendingActionsForPlayer(target).size());
        assertEquals(5, ledger.getAllPendingActions().size());

        // Mark 2 as executed
        ledger.markExecuted("act_multi_0");
        ledger.markExecuted("act_multi_2");
        assertEquals(3, ledger.size());
        assertEquals(3, ledger.getPendingActionsForPlayer(target).size());
    }
}
