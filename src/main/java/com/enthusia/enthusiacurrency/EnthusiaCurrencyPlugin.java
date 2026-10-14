package com.enthusia.enthusiacurrency;

import com.enthusia.enthusiacurrency.command.*;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyModerationApi;
import com.enthusia.enthusiacurrency.moderation.CurrencyModerationService;
import com.enthusia.enthusiacurrency.moderation.CurrencyMovementLockListener;
import com.enthusia.enthusiacurrency.moderation.MovementLockRegistry;
import com.enthusia.enthusiacurrency.analytics.CurrencyAnalyticsStorage;
import com.enthusia.enthusiacurrency.baltop.BaltopTracker;
import com.enthusia.enthusiacurrency.config.ConfigMigrator;
import com.enthusia.enthusiacurrency.debug.DebugMetrics;
import com.enthusia.enthusiacurrency.economy.TokenEconomy;
import com.enthusia.enthusiacurrency.item.ItemBalanceTracker;
import com.enthusia.enthusiacurrency.leaderboard.LeaderboardExportService;
import com.enthusia.enthusiacurrency.listener.BaltopGuiListener;
import com.enthusia.enthusiacurrency.listener.PlayerProfileListener;
import com.enthusia.enthusiacurrency.plan.PlanIntegrationHook;
import com.enthusia.enthusiacurrency.placeholder.EnthusiaCurrencyExpansion;
import com.enthusia.enthusiacurrency.placeholder.LeaderboardPlaceholderCache;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import com.enthusia.enthusiacurrency.skin.SkinCache;
import com.enthusia.enthusiacurrency.skin.SkinListener;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.storage.OfflinePaymentNotificationStorage;
import com.enthusia.enthusiacurrency.storage.PlayerProfileStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public class EnthusiaCurrencyPlugin extends JavaPlugin {

    private static final double SINGULAR_EPSILON = 0.0001D;

    private static EnthusiaCurrencyPlugin instance;

    private final MovementLockRegistry moderationLocks = new MovementLockRegistry();
    private CurrencyModerationService moderationService;
    private BalanceStorage balanceStorage;
    private CurrencyManager currencyManager;
    private CurrencyService currencyService;
    private TokenEconomy tokenEconomy;
    private BaltopTracker baltopTracker;
    private PlayerProfileStorage playerProfileStorage;
    private OfflinePaymentNotificationStorage offlinePaymentNotificationStorage;
    private LeaderboardExportService leaderboardExportService;
    private CurrencyAnalyticsStorage currencyAnalyticsStorage;
    private Optional<LeaderboardPlaceholderCache> leaderboardPlaceholderCache = Optional.empty();
    private Optional<EnthusiaCurrencyExpansion> placeholderExpansion = Optional.empty();

    private SkinCache skinCache;
    private ConfigMigrator configMigrator;
    private DebugMetrics debugMetrics;
    private ItemBalanceTracker itemBalanceTracker;
    private BaltopCommand baltopCommand;
    private FloodgateSupport floodgateSupport = FloodgateSupport.unavailable();

    @Override
    public void onEnable() {
        instance = this;

        setupConfiguration();
        if (!VaultBootstrap.isAvailable(Bukkit.getPluginManager())) {
            getLogger().severe("Vault is unavailable or disabled! Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (!startStorage()) {
            return;
        }

        this.currencyService = new CurrencyService(this, balanceStorage, currencyManager);
        setupModerationService();
        startRuntimeServices();
        if (!setupVault()) {
            return;
        }
        registerCommands();
        setupPlaceholderAPI();
        registerListeners();
        this.leaderboardExportService.start();
        setupPlanIntegration();

        getLogger().info("EnthusiaCurrency enabled.");
    }

    private void setupConfiguration() {
        this.configMigrator = new ConfigMigrator(this);
        this.configMigrator.migrateConfig();
        this.debugMetrics = new DebugMetrics(this);
        this.debugMetrics.reload();

        this.currencyManager = new CurrencyManager(this);
        this.currencyManager.reload();
    }

    private boolean startStorage() {
        this.balanceStorage = new BalanceStorage(this);
        try {
            this.balanceStorage.load();
        } catch (IllegalStateException ex) {
            getLogger().severe("Failed to start balance storage: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return false;
        }

        this.playerProfileStorage = new PlayerProfileStorage(this);
        try {
            this.playerProfileStorage.load();
        } catch (IllegalStateException ex) {
            getLogger().severe("Failed to start player profile storage: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return false;
        }

        this.offlinePaymentNotificationStorage = new OfflinePaymentNotificationStorage(this);
        try {
            this.offlinePaymentNotificationStorage.load();
        } catch (IllegalStateException ex) {
            getLogger().severe("Failed to start offline payment notification storage: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return false;
        }

        this.currencyAnalyticsStorage = new CurrencyAnalyticsStorage(this);
        try {
            this.currencyAnalyticsStorage.load();
        } catch (IllegalStateException ex) {
            getLogger().severe("Failed to start currency analytics storage: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return false;
        }
        return true;
    }

    private void startRuntimeServices() {
        this.baltopTracker = new BaltopTracker(this);
        this.baltopTracker.initializeSnapshot();
        this.baltopTracker.start();

        this.leaderboardExportService = new LeaderboardExportService(this);

        this.skinCache = new SkinCache(this);
        this.skinCache.load();
        Bukkit.getPluginManager().registerEvents(new SkinListener(this.skinCache), this);

        this.itemBalanceTracker = new ItemBalanceTracker(this);
        this.itemBalanceTracker.start();
        this.baltopTracker.refreshTop3();
        setupFloodgateCache();
    }

    @Override
    public void onDisable() {
        teardownPlaceholderAPI();
        teardownModerationService();
        stopRuntimeServices();
        closeSkinCache();
        closeStorage();
        getLogger().info("EnthusiaCurrency disabled.");
    }

    private void stopRuntimeServices() {
        if (baltopTracker != null) {
            baltopTracker.stop();
        }
        if (leaderboardExportService != null) {
            leaderboardExportService.close();
        }
        if (itemBalanceTracker != null) {
            itemBalanceTracker.stop();
        }
        if (debugMetrics != null) {
            debugMetrics.stop();
        }
        if (tokenEconomy != null) {
            Bukkit.getServicesManager().unregister(Economy.class, tokenEconomy);
        }
    }

    private void closeStorage() {
        if (currencyAnalyticsStorage != null) {
            currencyAnalyticsStorage.close();
        }
        if (balanceStorage != null) {
            balanceStorage.close();
        }
        if (playerProfileStorage != null) {
            playerProfileStorage.close();
        }
        if (offlinePaymentNotificationStorage != null) {
            offlinePaymentNotificationStorage.close();
        }
    }

    private void closeSkinCache() {
        if (skinCache != null) {
            skinCache.close();
        }
    }

    private void setupModerationService() {
        this.moderationService = new CurrencyModerationService(
                this,
                balanceStorage,
                currencyManager,
                moderationLocks
        );
        Bukkit.getServicesManager().register(
                CurrencyModerationApi.class,
                moderationService,
                this,
                ServicePriority.Normal
        );
        Bukkit.getPluginManager().registerEvents(
                new CurrencyMovementLockListener(moderationLocks),
                this
        );
        getLogger().info(
                "Registered EnthusiaCurrency moderation API v" + CurrencyModerationApi.API_VERSION + "."
        );
    }

    private void teardownModerationService() {
        if (moderationService == null) {
            return;
        }
        Bukkit.getServicesManager().unregister(CurrencyModerationApi.class, moderationService);
        moderationService.close();
    }

    private boolean setupVault() {
        VaultBootstrap.Result result = VaultBootstrap.register(
                this, balanceStorage, currencyManager,
                Bukkit.getPluginManager(), Bukkit.getServicesManager());
        if (!result.available()) {
            getLogger().severe("Vault is unavailable or disabled! Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return false;
        }
        this.tokenEconomy = result.provider();
        if (result.ownsRegistration()) {
            getLogger().info("Registered EnthusiaCurrency as Vault economy provider.");
        } else {
            getLogger().warning("Another economy provider is registered. Make sure EnthusiaCurrency is the only one.");
        }
        return true;
    }

    private void setupPlaceholderAPI() {
        teardownPlaceholderAPI();

        if (!getConfig().getBoolean("placeholderapi.enabled", true)) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }

        try {
            LeaderboardPlaceholderCache cache = new LeaderboardPlaceholderCache(this);
            leaderboardPlaceholderCache = Optional.of(cache);
            cache.start();

            EnthusiaCurrencyExpansion expansion = new EnthusiaCurrencyExpansion(this);
            if (expansion.register()) {
                placeholderExpansion = Optional.of(expansion);
                getLogger().info("PlaceholderAPI found, registered EnthusiaCurrency placeholders.");
            } else {
                getLogger().warning("Failed to register PlaceholderAPI expansion.");
                teardownPlaceholderAPI();
            }
        } catch (Exception | LinkageError ex) {
            getLogger().warning("Failed to initialize PlaceholderAPI support: " + ex.getMessage());
            teardownPlaceholderAPI();
        }
    }

    private void teardownPlaceholderAPI() {
        placeholderExpansion.ifPresent(this::unregisterPlaceholderExpansion);
        placeholderExpansion = Optional.empty();
        leaderboardPlaceholderCache.ifPresent(LeaderboardPlaceholderCache::stop);
        leaderboardPlaceholderCache = Optional.empty();
    }

    private void unregisterPlaceholderExpansion(EnthusiaCurrencyExpansion expansion) {
        try {
            expansion.unregister();
        } catch (Exception | LinkageError ignored) {
        }
    }

    private void registerCommands() {
        PluginCommand bal = getCommand("balance");
        PluginCommand dep = getCommand("deposit");
        PluginCommand wit = getCommand("withdraw");
        PluginCommand pay = getCommand("pay");
        PluginCommand bt  = getCommand("baltop");
        PluginCommand cur = getCommand("currency");

        BalanceCommand balanceCommand = new BalanceCommand(this);
        baltopCommand = new BaltopCommand(this);
        DepositCommand depositCommand = new DepositCommand(this);
        WithdrawCommand withdrawCommand = new WithdrawCommand(this);
        PayCommand payCommand = new PayCommand(this);
        EnthusiaCurrencyCommand enthusiaCurrencyCommand = new EnthusiaCurrencyCommand(this);

        if (bal != null) bal.setExecutor(balanceCommand);
        if (dep != null) {
            dep.setExecutor(depositCommand);
            dep.setTabCompleter(depositCommand);
        }
        if (wit != null) {
            wit.setExecutor(withdrawCommand);
            wit.setTabCompleter(withdrawCommand);
        }
        if (pay != null) {
            pay.setExecutor(payCommand);
            pay.setTabCompleter(payCommand);
        }
        if (bt != null) {
            bt.setExecutor(baltopCommand);
            bt.setTabCompleter(baltopCommand);
        }
        if (cur != null) cur.setExecutor(enthusiaCurrencyCommand);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new BaltopGuiListener(this, baltopCommand), this);
        if (playerProfileStorage != null) {
            Bukkit.getPluginManager().registerEvents(new PlayerProfileListener(playerProfileStorage, offlinePaymentNotificationStorage), this);
            for (Player player : Bukkit.getOnlinePlayers()) {
                playerProfileStorage.recordOnlinePlayer(player);
            }
        }
    }

    private void setupPlanIntegration() {
        if (!getConfig().getBoolean("integrations.plan.enabled", true)) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Plan") == null) {
            return;
        }

        try {
            new PlanIntegrationHook(this).hookIntoPlan();
        } catch (NoClassDefFoundError ex) {
            getLogger().fine("Plan API was not available; skipping Plan integration.");
        } catch (RuntimeException | LinkageError ex) {
            getLogger().warning("Failed to register Plan integration: " + ex.getMessage());
        }
    }

    private void setupFloodgateCache() {
        floodgateSupport = FloodgateSupport.unavailable();
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Method isPlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            floodgateSupport = new FloodgateSupport(api, isPlayerMethod);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            getLogger().fine("Floodgate API was not available; Bedrock GUI handling will use Java defaults.");
        }
    }

    public void reloadAndSyncConfig() {
        configMigrator.migrateConfig();
        currencyManager.reload();
        if (balanceStorage != null) {
            balanceStorage.reloadSettings();
        }
        if (currencyAnalyticsStorage != null) {
            currencyAnalyticsStorage.reloadSettings();
        }
        if (baltopTracker != null) {
            baltopTracker.refreshTop3();
            baltopTracker.stop();
            baltopTracker.start();
        }
        if (leaderboardExportService != null) {
            leaderboardExportService.reload();
        }
        if (itemBalanceTracker != null) {
            itemBalanceTracker.reloadSettings();
        }
        if (debugMetrics != null) {
            debugMetrics.reload();
        }
        setupFloodgateCache();
        setupPlaceholderAPI();
    }

    public static EnthusiaCurrencyPlugin getInstance() {
        return instance;
    }

    public BalanceStorage getBalanceStorage() {
        return balanceStorage;
    }

    public CurrencyService getCurrencyService() {
        return currencyService;
    }

    public CurrencyManager getCurrencyManager() {
        return currencyManager;
    }

    public TokenEconomy getTokenEconomy() {
        return tokenEconomy;
    }

    public BaltopTracker getBaltopTracker() {
        return baltopTracker;
    }

    public PlayerProfileStorage getPlayerProfileStorage() {
        return playerProfileStorage;
    }

    public OfflinePaymentNotificationStorage getOfflinePaymentNotificationStorage() {
        return offlinePaymentNotificationStorage;
    }

    public LeaderboardExportService getLeaderboardExportService() {
        return leaderboardExportService;
    }

    public LeaderboardPlaceholderCache getLeaderboardPlaceholderCache() {
        return leaderboardPlaceholderCache.orElse(null);
    }

    public CurrencyAnalyticsStorage getCurrencyAnalyticsStorage() {
        return currencyAnalyticsStorage;
    }

    public DebugMetrics getDebugMetrics() {
        return debugMetrics;
    }

    public ItemBalanceTracker getItemBalanceTracker() {
        return itemBalanceTracker;
    }

    public boolean isInBaltopTop(UUID uuid, int top) {
        return baltopTracker != null && baltopTracker.isInTop(uuid, top);
    }

    public int getBaltopRank(UUID uuid) {
        return baltopTracker == null ? -1 : baltopTracker.getRank(uuid);
    }

    public SkinCache getSkinCache() {
        return skinCache;
    }

    public boolean isBedrock(Player player) {
        if (!floodgateSupport.isAvailable()) {
            return false;
        }
        try {
            Object result = floodgateSupport.isPlayerMethod().invoke(floodgateSupport.api(), player.getUniqueId());
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
    }

    public String getPrefix() {
        String raw = getConfig().getString("messages.prefix", "&8[&6Currency&8] &r");
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String msgNoPrefix(String path) {
        String raw = getConfig().getString("messages." + path, "");
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public void sendMsg(org.bukkit.command.CommandSender sender, String path) {
        sender.sendMessage(getPrefix() + msgNoPrefix(path));
    }

    public String getCurrencySingular() {
        return getConfig().getString("economy.currency-name-singular", "Dollar");
    }

    public String getCurrencyPlural() {
        return getConfig().getString("economy.currency-name-plural", "Dollars");
    }

    public String getCurrencyName(long amount) {
        return amount == 1L ? getCurrencySingular() : getCurrencyPlural();
    }

    public String getCurrencyName(double amount) {
        if (Math.abs(amount - 1.0D) < SINGULAR_EPSILON) {
            return getCurrencySingular();
        }
        return getCurrencyPlural();
    }

    public String getCurrencySymbol() {
        return getConfig().getString("economy.currency-symbol", "$");
    }

    private record FloodgateSupport(Object api, Method isPlayerMethod) {

        private static FloodgateSupport unavailable() {
            return new FloodgateSupport(null, null);
        }

        private boolean isAvailable() {
            return api != null && isPlayerMethod != null;
        }
    }
}
