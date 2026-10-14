package com.enthusia.enthusiacurrency.item;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.util.CurrencyUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class ItemBalanceTracker implements Listener {

    private final EnthusiaCurrencyPlugin plugin;
    private final LongSupplier clock;
    private final Map<UUID, ItemBalanceSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> debounceTasks = new ConcurrentHashMap<>();
    private final Set<UUID> queuedAuditPlayers = ConcurrentHashMap.newKeySet();
    private final Queue<UUID> auditQueue = new ArrayDeque<>();

    private int auditTaskId = -1;
    private long debounceTicks = 40L;
    private long staleAfterMillis = 30_000L;
    private long auditIntervalMillis = 60_000L;
    private long nextAuditStartMillis;
    private boolean repairMode = true;
    private boolean debugLogRepairs;

    public ItemBalanceTracker(EnthusiaCurrencyPlugin plugin) {
        this(plugin, System::currentTimeMillis);
    }

    ItemBalanceTracker(EnthusiaCurrencyPlugin plugin, LongSupplier clock) {
        this.plugin = plugin;
        this.clock = clock;
    }

    public void start() {
        reloadSettings();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            scanNow(player, "startup");
        }
        startAuditTask();
    }

    public void reloadSettings() {
        long debounceSeconds = Math.max(1L, plugin.getConfig().getLong("item-balance.debounce-seconds", 2L));
        debounceTicks = debounceSeconds * 20L;
        staleAfterMillis = Math.max(5L, plugin.getConfig().getLong("item-balance.stale-after-seconds", 30L)) * 1000L;
        auditIntervalMillis = Math.max(10L, plugin.getConfig().getLong("item-balance-scan.interval-seconds", 60L)) * 1000L;
        repairMode = plugin.getConfig().getBoolean("item-balance-scan.repair-mode", true);
        debugLogRepairs = plugin.getConfig().getBoolean("item-balance-scan.debug-log-repairs", false);
        restartAuditTask();
    }

    public void stop() {
        for (int taskId : debounceTasks.values()) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        debounceTasks.clear();
        if (auditTaskId != -1) {
            Bukkit.getScheduler().cancelTask(auditTaskId);
            auditTaskId = -1;
        }
        queuedAuditPlayers.clear();
        synchronized (auditQueue) {
            auditQueue.clear();
        }
    }

    public ItemBalanceSnapshot getSnapshot(UUID uuid, String fallbackName) {
        ItemBalanceSnapshot snapshot = snapshots.get(uuid);
        if (snapshot == null) {
            plugin.getDebugMetrics().cacheMiss();
            return ItemBalanceSnapshot.empty(uuid, fallbackName);
        }
        plugin.getDebugMetrics().cacheHit();
        // Reads must not reset a refresh already queued by reads or inventory changes.
        // Check the actual task rather than dirty state so a failed scan can be retried.
        if (isStale(snapshot) && !debounceTasks.containsKey(uuid)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                markDirty(player, "stale-cache");
            }
        }
        return snapshot;
    }

    public long getCachedTotal(Player player) {
        return getSnapshot(player.getUniqueId(), player.getName()).totalItemCurrency();
    }

    public Map<UUID, ItemBalanceSnapshot> getSnapshots() {
        return Map.copyOf(snapshots);
    }

    public void markDirty(Player player, String reason) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        snapshots.compute(uuid, (ignored, current) -> {
            ItemBalanceSnapshot base = current == null ? ItemBalanceSnapshot.empty(uuid, player.getName()) : current;
            return base.markDirty();
        });
        plugin.getDebugMetrics().dirtyPlayerQueued();
        scheduleDebouncedScan(player, reason);
    }

    public void scanNow(Player player, String reason) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        snapshots.compute(uuid, (ignored, current) -> {
            ItemBalanceSnapshot base = current == null ? ItemBalanceSnapshot.empty(uuid, player.getName()) : current;
            return base.markScanning();
        });

        CurrencyUtils.CurrencyInventorySnapshot counted = CurrencyUtils.countCurrencyLocations(plugin.getCurrencyManager(), player);
        ItemBalanceSnapshot previous = snapshots.get(uuid);
        ItemBalanceSnapshot updated = new ItemBalanceSnapshot(
                uuid,
                player.getName(),
                counted.inventoryCurrency(),
                counted.enderChestCurrency(),
                counted.shulkerCurrency(),
                counted.totalCurrency(),
                clock.getAsLong(),
                false,
                false
        );
        snapshots.put(uuid, updated);
        plugin.getDebugMetrics().itemScan(1L, counted.shulkersScanned());

        if (previous != null && previous.totalItemCurrency() != counted.totalCurrency() && debugLogRepairs && repairMode) {
            plugin.getLogger().info("Item balance scan repaired " + player.getName() + " from "
                    + previous.totalItemCurrency() + " to " + counted.totalCurrency() + " (" + reason + ").");
        }
    }

    private void scheduleDebouncedScan(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        Integer oldTask = debounceTasks.remove(uuid);
        if (oldTask != null) {
            Bukkit.getScheduler().cancelTask(oldTask);
        }
        int taskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            debounceTasks.remove(uuid);
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                scanNow(online, reason);
            }
        }, debounceTicks).getTaskId();
        debounceTasks.put(uuid, taskId);
    }

    private void startAuditTask() {
        if (auditTaskId != -1) {
            return;
        }
        if (!plugin.getConfig().getBoolean("item-balance-scan.enabled", true)) {
            return;
        }
        nextAuditStartMillis = System.currentTimeMillis() + auditIntervalMillis;
        auditTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::processAuditTick, 20L, 1L).getTaskId();
    }

    private void restartAuditTask() {
        if (auditTaskId != -1) {
            Bukkit.getScheduler().cancelTask(auditTaskId);
            auditTaskId = -1;
        }
        if (plugin.isEnabled()) {
            startAuditTask();
        }
    }

    private void processAuditTick() {
        if (!plugin.getConfig().getBoolean("item-balance-scan.enabled", true)) {
            return;
        }
        fillAuditQueueIfEmpty();
        int maxPlayers = Math.max(1, plugin.getConfig().getInt("item-balance-scan.max-players-per-tick", 1));
        int processed = 0;
        while (processed < maxPlayers) {
            UUID uuid;
            synchronized (auditQueue) {
                uuid = auditQueue.poll();
            }
            if (uuid == null) {
                return;
            }
            queuedAuditPlayers.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                if (repairMode) {
                    scanNow(player, "audit");
                } else {
                    markDirty(player, "audit-mark");
                }
                processed++;
            }
        }
    }

    private void fillAuditQueueIfEmpty() {
        synchronized (auditQueue) {
            if (!auditQueue.isEmpty()) {
                return;
            }
            if (System.currentTimeMillis() < nextAuditStartMillis) {
                return;
            }
            nextAuditStartMillis = System.currentTimeMillis() + auditIntervalMillis;
            Set<UUID> onlineIds = new HashSet<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                onlineIds.add(player.getUniqueId());
            }
            for (UUID uuid : onlineIds) {
                if (queuedAuditPlayers.add(uuid)) {
                    auditQueue.add(uuid);
                }
            }
        }
    }

    private boolean isStale(ItemBalanceSnapshot snapshot) {
        return snapshot.lastScannedAtMillis() <= 0L
                || clock.getAsLong() - snapshot.lastScannedAtMillis() > staleAfterMillis;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markDirty(player, "inventory-click");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            markDirty(player, "inventory-drag");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            markDirty(player, "inventory-close");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            markDirty(player, "pickup");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        markDirty(event.getPlayer(), "drop");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        markDirty(event.getEntity(), "death");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        markDirty(event.getPlayer(), "respawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scanNow(event.getPlayer(), "join");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        scanNow(event.getPlayer(), "quit");
    }
}
