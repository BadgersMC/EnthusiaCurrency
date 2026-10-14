package com.enthusia.enthusiacurrency.item;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.debug.DebugMetrics;
import com.enthusia.enthusiacurrency.economy.TokenEconomy;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import com.enthusia.enthusiacurrency.util.CurrencyUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ItemBalanceReadPerformanceTest {
    private static final String PLAYER_NAME = "Synthetic";
    @Test
    void repeatedVaultReadsQueueOneRefreshWithoutCancellation() {
        try (Fixture f = new Fixture()) {
            long start = System.nanoTime();
            for (int i = 0; i < 10_000; i++) assertEquals(173.0, f.economy.getBalance(f.player));
            System.out.printf("stale-vault-profile reads=10000 scheduled=%d cancelled=%d elapsed-ms=%.3f%n",
                    f.scheduler.created, f.scheduler.cancelled, (System.nanoTime() - start) / 1_000_000.0);
            assertEquals(1, f.scheduler.created);
            assertEquals(0, f.scheduler.cancelled);
            assertEquals(1, f.scans);
            assertTrue(f.tracker.getSnapshot(f.player.getUniqueId(), PLAYER_NAME).dirty());
        }
    }

    @Test
    void continuousReadsCannotPostponePendingRefresh() {
        try (Fixture f = new Fixture()) {
            for (int tick = 1; tick <= 80; tick++) {
                f.economy.getBalance(f.player);
                f.scheduler.runTo(tick);
            }
            assertEquals(2, f.scans);
            assertEquals(1, f.scheduler.created);
            assertFalse(f.tracker.getSnapshot(f.player.getUniqueId(), PLAYER_NAME).dirty());
        }
    }

    @Test
    void refreshedCacheCanQueueAgainInAnotherStalePeriod() {
        try (Fixture f = new Fixture()) {
            f.economy.getBalance(f.player);
            f.scheduler.runTo(40);
            assertEquals(2, f.scans);
            f.clock.addAndGet(31_000);
            for (int i = 0; i < 20; i++) f.economy.getBalance(f.player);
            assertEquals(2, f.scheduler.created);
            assertEquals(0, f.scheduler.cancelled);
            f.scheduler.runTo(80);
            assertEquals(3, f.scans);
        }
    }

    @Test
    void realInventoryChangesRetainTrailingDebounce() {
        try (Fixture f = new Fixture()) {
            f.tracker.markDirty(f.player, "inventory-click");
            f.scheduler.runTo(10);
            f.tracker.markDirty(f.player, "inventory-drag");
            f.scheduler.runTo(40);
            f.economy.getBalance(f.player); // a read must not extend the inventory-change deadline
            assertEquals(1, f.scans);
            f.scheduler.runTo(50);
            assertEquals(2, f.scans);
            assertEquals(2, f.scheduler.created);
            assertEquals(1, f.scheduler.cancelled);
        }
    }

    @Test
    void pendingPlayersAreIndependentAndStopCancelsTheirWork() {
        try (Fixture f = new Fixture()) {
            Player other = f.syntheticPlayer();
            f.tracker.scanNow(other, "seed");
            f.clock.addAndGet(31_000);
            for (int i = 0; i < 20; i++) {
                f.economy.getBalance(f.player);
                f.economy.getBalance(other);
            }
            assertEquals(2, f.scheduler.created);
            f.tracker.stop();
            assertEquals(2, f.scheduler.cancelled);
            f.scheduler.runTo(100);
            assertEquals(2, f.scans);
        }
    }

    @Test
    void missingAndOfflineCachedReadsKeepExistingValuesWithoutScheduling() {
        try (Fixture f = new Fixture()) {
            Player missing = f.syntheticPlayer();
            assertEquals(50.0, f.economy.getBalance(missing));
            when(f.player.isOnline()).thenReturn(false);
            assertEquals(50.0, f.economy.getBalance(f.player));
            assertEquals(0, f.scheduler.created);
            assertEquals(1, f.scans);
        }
    }

    @Test
    void failedRefreshCanBeRetriedByLaterReads() {
        try (Fixture f = new Fixture()) {
            f.economy.getBalance(f.player);
            f.currency.when(() -> CurrencyUtils.countCurrencyLocations(any(), any()))
                    .thenThrow(new IllegalStateException("synthetic scan failure"));
            assertThrows(IllegalStateException.class, () -> f.scheduler.runTo(40));
            assertEquals(173.0, f.economy.getBalance(f.player));
            assertEquals(2, f.scheduler.created);
            f.configureCurrency();
            f.scheduler.runTo(80);
            assertFalse(f.tracker.getSnapshot(f.player.getUniqueId(), PLAYER_NAME).dirty());
            assertEquals(0, f.scheduler.cancelled);
        }
    }

    @Test
    void transactionAffordabilityDoesNotTrustStaleDisplaySnapshot() {
        try (Fixture f = new Fixture()) {
            f.currency.when(() -> CurrencyUtils.countCurrencyInPlayer(any(), any())).thenReturn(0L);
            assertEquals(173.0, f.economy.getBalance(f.player));
            assertFalse(f.economy.has(f.player, 100)); // actual current bank is only 50
            assertEquals(1, f.scheduler.created);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        private final MockedStatic<CurrencyUtils> currency = mockStatic(CurrencyUtils.class);
        private final EnthusiaCurrencyPlugin plugin = mock(EnthusiaCurrencyPlugin.class);
        private final AtomicLong clock = new AtomicLong(1_000);
        private final ControlledScheduler scheduler = new ControlledScheduler();
        private final ItemBalanceTracker tracker = new ItemBalanceTracker(plugin, clock::get);
        private final Player player;
        private final TokenEconomy economy;
        private int scans;

        Fixture() {
            configureCurrency();
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler.api);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            player = syntheticPlayer();
            BalanceStorage storage = mock(BalanceStorage.class);
            when(storage.getBalance(any(UUID.class))).thenReturn(50L);
            CurrencyService service = new CurrencyService(plugin, storage, plugin.getCurrencyManager());
            when(plugin.getCurrencyService()).thenReturn(service);
            economy = new TokenEconomy(plugin, storage);
            tracker.scanNow(player, "seed");
            clock.addAndGet(31_000);
        }

        private void configureCurrency() {
            when(plugin.getDebugMetrics()).thenReturn(mock(DebugMetrics.class));
            when(plugin.getCurrencyManager()).thenReturn(mock(CurrencyManager.class));
            when(plugin.getItemBalanceTracker()).thenReturn(tracker);
            currency.when(() -> CurrencyUtils.countCurrencyLocations(any(), any())).thenAnswer(call -> {
                scans++;
                return new CurrencyUtils.CurrencyInventorySnapshot(123, 0, 0, 123, 0);
            });
        }

        private Player syntheticPlayer() {
            Player result = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(result.getUniqueId()).thenReturn(id);
            when(result.getName()).thenReturn(PLAYER_NAME);
            when(result.isOnline()).thenReturn(true);
            when(result.getPlayer()).thenReturn(result);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(result);
            return result;
        }

        @Override public void close() {
            currency.close();
            bukkit.close();
        }
    }

    private static final class ControlledScheduler {
        private record Scheduled(long due, Runnable callback) { }
        private final BukkitScheduler api = mock(BukkitScheduler.class);
        private final Map<Integer, Scheduled> tasks = new ConcurrentHashMap<>();
        private long tick;
        private int created;
        private int cancelled;

        ControlledScheduler() {
            when(api.runTaskLater(any(EnthusiaCurrencyPlugin.class), any(Runnable.class), anyLong()))
                    .thenAnswer(call -> schedule(call.getArgument(1), call.getArgument(2)));
            doAnswer(call -> { cancelled++; tasks.remove(call.<Integer>getArgument(0)); return null; })
                    .when(api).cancelTask(anyInt());
        }

        private BukkitTask schedule(Runnable callback, long delay) {
            int id = ++created;
            tasks.put(id, new Scheduled(tick + delay, callback));
            BukkitTask task = mock(BukkitTask.class);
            when(task.getTaskId()).thenReturn(id);
            return task;
        }

        private void runTo(long target) {
            tick = target;
            for (var entry : Map.copyOf(tasks).entrySet()) {
                if (entry.getValue().due() <= target) {
                    tasks.remove(entry.getKey());
                    entry.getValue().callback().run();
                }
            }
        }
    }
}
