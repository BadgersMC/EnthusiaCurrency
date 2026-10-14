package com.enthusia.enthusiacurrency;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import java.util.concurrent.atomic.AtomicReference;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

class VaultBootstrapTest {
    private static final String VAULT_PLUGIN = "Vault";

    @Test
    void rejectsStartupWhenVaultIsMissing() {
        EnthusiaCurrencyPlugin plugin = mock(EnthusiaCurrencyPlugin.class);
        PluginManager pluginManager = mock(PluginManager.class);
        ServicesManager servicesManager = mock(ServicesManager.class);
        when(pluginManager.isPluginEnabled(VAULT_PLUGIN)).thenReturn(false);

        VaultBootstrap.Result result = VaultBootstrap.register(
                plugin,
                mock(BalanceStorage.class),
                mock(CurrencyManager.class),
                pluginManager,
                servicesManager);

        assertFalse(result.available());
        assertFalse(result.ownsRegistration());
        verifyNoInteractions(servicesManager);
    }

    @Test
    void rejectsStartupWhenVaultIsLoadedButDisabled() {
        EnthusiaCurrencyPlugin plugin = mock(EnthusiaCurrencyPlugin.class);
        PluginManager pluginManager = mock(PluginManager.class);
        ServicesManager servicesManager = mock(ServicesManager.class);
        when(pluginManager.getPlugin(VAULT_PLUGIN)).thenReturn(mock(Plugin.class));
        when(pluginManager.isPluginEnabled(VAULT_PLUGIN)).thenReturn(false);

        VaultBootstrap.Result result = VaultBootstrap.register(
                plugin,
                mock(BalanceStorage.class),
                mock(CurrencyManager.class),
                pluginManager,
                servicesManager);

        assertFalse(result.available());
        assertFalse(result.ownsRegistration());
        verifyNoInteractions(servicesManager);
    }

    @Test
    void acceptsStartupWhenRegisteredProviderRemainsActive() {
        EnthusiaCurrencyPlugin plugin = mock(EnthusiaCurrencyPlugin.class);
        PluginManager pluginManager = mock(PluginManager.class);
        ServicesManager servicesManager = mock(ServicesManager.class);
        when(pluginManager.isPluginEnabled(VAULT_PLUGIN)).thenReturn(true);

        AtomicReference<Economy> registered = new AtomicReference<>();
        doAnswer(invocation -> {
            registered.set(invocation.getArgument(1));
            return null;
        }).when(servicesManager).register(
                eq(Economy.class), any(Economy.class), eq(plugin), eq(ServicePriority.Highest));

        @SuppressWarnings("unchecked")
        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenAnswer(invocation -> registered.get());
        when(servicesManager.getRegistration(Economy.class)).thenReturn(registration);

        VaultBootstrap.Result result = VaultBootstrap.register(
                plugin,
                mock(BalanceStorage.class),
                mock(CurrencyManager.class),
                pluginManager,
                servicesManager);

        assertTrue(result.available());
        assertTrue(result.ownsRegistration());
        assertNotNull(result.provider());
        verify(servicesManager).register(
                eq(Economy.class), eq(result.provider()), eq(plugin), eq(ServicePriority.Highest));
    }

    @Test
    void reportsConflictWhenAnotherProviderWinsRegistration() {
        EnthusiaCurrencyPlugin plugin = mock(EnthusiaCurrencyPlugin.class);
        PluginManager pluginManager = mock(PluginManager.class);
        ServicesManager servicesManager = mock(ServicesManager.class);
        when(pluginManager.isPluginEnabled(VAULT_PLUGIN)).thenReturn(true);

        @SuppressWarnings("unchecked")
        RegisteredServiceProvider<Economy> registration = mock(RegisteredServiceProvider.class);
        when(registration.getProvider()).thenReturn(mock(Economy.class));
        when(servicesManager.getRegistration(Economy.class)).thenReturn(registration);

        VaultBootstrap.Result result = VaultBootstrap.register(
                plugin,
                mock(BalanceStorage.class),
                mock(CurrencyManager.class),
                pluginManager,
                servicesManager);

        assertTrue(result.available());
        assertFalse(result.ownsRegistration());
        assertNotNull(result.provider());
    }
}
