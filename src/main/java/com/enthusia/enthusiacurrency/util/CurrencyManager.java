package com.enthusia.enthusiacurrency.util;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class CurrencyManager {

    private final EnthusiaCurrencyPlugin plugin;

    private Material material;
    private Material blockMaterial;
    private boolean useName;
    private String name;
    private boolean useLore;
    private List<String> lore;
    private int blockValue;

    public CurrencyManager(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        FileConfiguration config = plugin.getConfig();

        String matName = config.getString("currency.material", "RAW_GOLD");
        this.material = Material.matchMaterial(matName);
        if (this.material == null) {
            plugin.getLogger().severe("Invalid currency.material: " + matName + ", defaulting to RAW_GOLD");
            this.material = Material.RAW_GOLD;
        }

        String blockMatName = config.getString("currency.block-material", "");
        if (blockMatName != null && !blockMatName.isEmpty()) {
            this.blockMaterial = Material.matchMaterial(blockMatName);
            if (this.blockMaterial == null) {
                plugin.getLogger().severe("Invalid currency.block-material: " + blockMatName + ", disabling block form.");
            }
        }

        this.blockValue = config.getInt("currency.block-value", 9);
        if (this.blockValue <= 0) {
            this.blockValue = 0;
        }

        this.useName = config.getBoolean("currency.use-name", false);
        this.name = config.getString("currency.name", "&6&lGold");

        this.useLore = config.getBoolean("currency.use-lore", false);
        this.lore = new ArrayList<>();
        for (String line : config.getStringList("currency.lore")) {
            this.lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    public Material getMaterial() {
        return material;
    }

    public Material getBlockMaterial() {
        return blockMaterial;
    }

    public int getBlockValue() {
        return blockMaterial != null && blockValue > 0 ? blockValue : 0;
    }

    public boolean hasBlockForm() {
        return blockMaterial != null && blockValue > 0;
    }

    public ItemStack createCurrencyItem(int amount) {
        return createCurrencyStack(material, amount);
    }

    public ItemStack createCurrencyBlock(int amount) {
        if (!hasBlockForm()) {
            return null;
        }
        return createCurrencyStack(blockMaterial, amount);
    }

    private ItemStack createCurrencyStack(Material mat, int amount) {
        ItemStack stack = new ItemStack(mat, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (useName) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            }
            if (useLore && !lore.isEmpty()) {
                meta.setLore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public boolean isCurrencyItem(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        if (stack.getType() != material) return false;
        return matchesCurrencyMeta(stack);
    }

    public boolean isCurrencyBlock(ItemStack stack) {
        if (!hasBlockForm()) return false;
        if (stack == null || stack.getType() == Material.AIR) return false;
        if (stack.getType() != blockMaterial) return false;
        return matchesCurrencyMeta(stack);
    }

    private boolean matchesCurrencyMeta(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (useName) {
            if (meta == null || !meta.hasDisplayName()) return false;
            String display = ChatColor.stripColor(meta.getDisplayName());
            String want = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', name));
            if (!display.equalsIgnoreCase(want)) return false;
        }

        if (useLore) {
            if (meta == null || !meta.hasLore()) return false;
            List<String> itemLore = meta.getLore();
            if (itemLore == null) return false;
            if (itemLore.size() < lore.size()) return false;

            List<String> strippedItemLore = new ArrayList<>();
            for (String l : itemLore) strippedItemLore.add(ChatColor.stripColor(l));

            List<String> strippedRequired = new ArrayList<>();
            for (String l : lore) strippedRequired.add(ChatColor.stripColor(l));

            for (int i = 0; i < strippedRequired.size(); i++) {
                if (!strippedItemLore.get(i).equalsIgnoreCase(strippedRequired.get(i))) {
                    return false;
                }
            }
        }

        return true;
    }
}
