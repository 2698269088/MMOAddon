package top.mcocet.mMOAddon.gui;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import top.mcocet.mMOAddon.MMOAddon;

public class MMOGUIListener implements Listener {

    private final MMOAddon plugin;

    public MMOGUIListener(MMOAddon plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        InventoryHolder holder = event.getInventory().getHolder();

        if (!(holder instanceof MMOMainGUI.MMOAddonHolder)) {
            return;
        }

        event.setCancelled(true);

        MMOMainGUI.MMOAddonHolder mmoHolder = (MMOMainGUI.MMOAddonHolder) holder;
        MMOMainGUI.GUIType type = mmoHolder.getType();
        ItemStack clickedItem = event.getCurrentItem();

        if (clickedItem == null || clickedItem.getType() == Material.AIR || clickedItem.getType() == Material.BLACK_STAINED_GLASS_PANE) {
            return;
        }

        switch (type) {
            case MAIN_MENU:
                handleMainMenuClick(player, clickedItem);
                break;
            case MMOITEMS_TYPE_MENU:
                handleTypeMenuClick(player, clickedItem);
                break;
            case MMOITEMS_ITEM_MENU:
                handleItemMenuClick(player, clickedItem, mmoHolder);
                break;
            case MMOITEMS_DETAIL_MENU:
                handleDetailMenuClick(player, clickedItem, mmoHolder);
                break;
        }
    }

    private void handleMainMenuClick(Player player, ItemStack clickedItem) {
        String pluginName = plugin.getMMOMainGUI().getPluginFromItem(clickedItem);
        if (pluginName == null) return;

        switch (pluginName) {
            case "MMOItems":
                plugin.getMMOMainGUI().openMMOItemsTypeMenu(player);
                break;
            case "MythicLib":
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a[MMOAddon] &7MythicLib 暂无配置浏览器"));
                break;
            case "AEAddon":
                player.performCommand("aeaddon");
                break;
        }
    }

    private void handleTypeMenuClick(Player player, ItemStack clickedItem) {
        String typeName = plugin.getMMOMainGUI().getTypeFromItem(clickedItem);
        if (typeName != null) {
            plugin.getMMOMainGUI().openMMOItemsItemMenu(player, typeName, 1);
            return;
        }

        String title = clickedItem.getItemMeta().getDisplayName();
        if (ChatColor.stripColor(title).contains("返回")) {
            plugin.getMMOMainGUI().openMainMenu(player);
        }
    }

    private void handleItemMenuClick(Player player, ItemStack clickedItem, MMOMainGUI.MMOAddonHolder holder) {
        Material material = clickedItem.getType();
        String displayName = clickedItem.getItemMeta().getDisplayName();

        if (material == Material.ARROW && displayName.contains("返回类型菜单")) {
            plugin.getMMOMainGUI().openMMOItemsTypeMenu(player);
            return;
        }

        if (material == Material.PAPER && displayName.contains("上一页")) {
            plugin.getMMOMainGUI().openMMOItemsItemMenu(player, holder.getData(), holder.getPage() - 1);
            return;
        }

        if (material == Material.PAPER && displayName.contains("下一页")) {
            plugin.getMMOMainGUI().openMMOItemsItemMenu(player, holder.getData(), holder.getPage() + 1);
            return;
        }

        String itemId = plugin.getMMOMainGUI().getItemIdFromItem(clickedItem);
        if (itemId != null) {
            plugin.getMMOMainGUI().openMMOItemsDetailMenu(player, holder.getData(), itemId);
        }
    }

    private void handleDetailMenuClick(Player player, ItemStack clickedItem, MMOMainGUI.MMOAddonHolder holder) {
        Material material = clickedItem.getType();
        String displayName = clickedItem.getItemMeta().getDisplayName();

        if (material == Material.ARROW && displayName.contains("返回物品列表")) {
            String[] parts = holder.getData().split(":");
            if (parts.length == 2) {
                plugin.getMMOMainGUI().openMMOItemsItemMenu(player, parts[0], 1);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof MMOMainGUI.MMOAddonHolder) {
            // No persistent state to clean in this version
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up if persistent state is added later
    }
}
