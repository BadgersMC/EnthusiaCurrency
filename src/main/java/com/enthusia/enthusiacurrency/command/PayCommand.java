package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.economy.TokenEconomy;
import com.enthusia.enthusiacurrency.event.CurrencyBalanceZeroEvent;
import com.enthusia.enthusiacurrency.event.CurrencyPayEvent;
import com.enthusia.enthusiacurrency.event.CurrencyPaySelfAttemptEvent;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PayCommand implements CommandExecutor, TabCompleter {

    private final EnthusiaCurrencyPlugin plugin;

    public PayCommand(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player player)) {
            plugin.sendMsg(sender, "player-only");
            return true;
        }

        if (args.length != 2) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        String targetName = args[0];
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        if ((target.getName() == null || !target.hasPlayedBefore()) && !target.isOnline()) {
            plugin.sendMsg(player, "player-not-found");
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            Bukkit.getPluginManager().callEvent(new CurrencyPaySelfAttemptEvent(player.getUniqueId()));
            plugin.sendMsg(player, "self-pay");
            return true;
        }

        boolean allowDecimals = plugin.getConfig().getBoolean("economy.allow-decimals", false);

        double amountDouble;
        try {
            amountDouble = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        if (amountDouble <= 0) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        if (!allowDecimals && amountDouble != Math.floor(amountDouble)) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        int amount = (int) Math.floor(amountDouble);
        TokenEconomy economy = plugin.getTokenEconomy();

        EconomyResponse withdrawResponse = economy.withdrawPlayer(player, amount);
        if (withdrawResponse.type != EconomyResponse.ResponseType.SUCCESS) {
            String msg = plugin.msgNoPrefix("not-enough-funds")
                    .replace("%have%", String.valueOf((long) withdrawResponse.balance))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency%", plugin.getCurrencyName(withdrawResponse.balance));
            player.sendMessage(plugin.getPrefix() + msg);
            return true;
        }

        EconomyResponse depositResponse = economy.depositPlayer(target, amount);
        if (depositResponse.type != EconomyResponse.ResponseType.SUCCESS) {
            economy.depositPlayer(player, amount);
            plugin.getLogger().warning("PayCommand: deposit to " + targetName + " failed; refunded sender.");
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        Bukkit.getPluginManager().callEvent(new CurrencyPayEvent(
                player.getUniqueId(), target.getUniqueId(), amount));
        long senderBalance = plugin.getBalanceStorage().getBalance(player.getUniqueId());
        if (senderBalance <= 0) {
            Bukkit.getPluginManager().callEvent(new CurrencyBalanceZeroEvent(player.getUniqueId()));
        }

        String senderMsg = plugin.msgNoPrefix("pay-success-sender")
                .replace("%target%", target.getName() == null ? "Unknown" : target.getName())
                .replace("%amount%", String.valueOf(amount))
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency%", plugin.getCurrencyName(amount));
        player.sendMessage(plugin.getPrefix() + senderMsg);

        if (target.isOnline() && target.getPlayer() != null) {
            String targetMsg = plugin.msgNoPrefix("pay-success-target")
                    .replace("%sender%", player.getName())
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency%", plugin.getCurrencyName(amount));
            target.getPlayer().sendMessage(plugin.getPrefix() + targetMsg);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> matches = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                String name = online.getName();
                if (name.toLowerCase().startsWith(prefix)) {
                    matches.add(name);
                }
            }
            return matches;
        }

        return Collections.emptyList();
    }
}