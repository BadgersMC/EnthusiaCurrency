package com.enthusia.enthusiacurrency;

import com.enthusia.enthusiacurrency.economy.TokenEconomy;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/** Registers the Vault economy provider without owning plugin lifecycle decisions. */
final class VaultBootstrap {

    private VaultBootstrap() {
    }

    static boolean isAvailable(PluginManager pluginManager) {
        return pluginManager.isPluginEnabled("Vault");
    }

    static Result register(
            EnthusiaCurrencyPlugin plugin,
            BalanceStorage balanceStorage,
            CurrencyManager currencyManager,
            PluginManager pluginManager,
            ServicesManager servicesManager
    ) {
        if (!isAvailable(pluginManager)) {
            return Result.unavailable();
        }

        TokenEconomy provider = new TokenEconomy(plugin, balanceStorage);
        servicesManager.register(Economy.class, provider, plugin, ServicePriority.Highest);
        RegisteredServiceProvider<Economy> registration = servicesManager.getRegistration(Economy.class);
        boolean ownsRegistration = registration != null && registration.getProvider() == provider;
        return new Result(true, provider, ownsRegistration);
    }

    record Result(boolean available, TokenEconomy provider, boolean ownsRegistration) {
        private static Result unavailable() {
            return new Result(false, null, false);
        }
    }
}
