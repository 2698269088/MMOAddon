package top.mcocet.mMOAddon.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import top.mcocet.mMOAddon.MMOAddon;

import java.io.File;
import java.util.*;

/**
 * MMOAddon GUI manager - browse items from MMOItems/MythicLib/AEAddon configurations.
 * Mirrors the structure of AEAddon's EnchantBookGUI.
 */
public class MMOMainGUI {

    private final MMOAddon plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey itemKey;
    private final NamespacedKey pluginKey;
    private final NamespacedKey pageKey;

    public static final String MAIN_MENU_TITLE = ChatColor.translateAlternateColorCodes('&', "&8[&bMMOAddon&8] &7插件物品浏览器");
    public static final String TYPE_MENU_TITLE_PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&bMMOAddon&8] &7类型: ");
    public static final String ITEM_MENU_TITLE_PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&bMMOAddon&8] &7物品: ");
    public static final String DETAIL_MENU_TITLE_PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&bMMOAddon&8] &7详情: ");

    private static final int ITEMS_PER_PAGE = 45;
    private static final int NAV_ROW = 45;

    public MMOMainGUI(MMOAddon plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "mmo_type");
        this.itemKey = new NamespacedKey(plugin, "mmo_item");
        this.pluginKey = new NamespacedKey(plugin, "mmo_plugin");
        this.pageKey = new NamespacedKey(plugin, "mmo_page");
    }

    /**
     * Open the main menu listing supported plugins.
     */
    public void openMainMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                new MMOAddonHolder(GUIType.MAIN_MENU, null, null, 1),
                27, MAIN_MENU_TITLE);

        int slot = 10;
        if (isPluginLoaded("MMOItems")) {
            inventory.setItem(slot++, createPluginItem("MMOItems", Material.DIAMOND_SWORD,
                    "&bMMOItems", "&7浏览 MMOItems 的物品模板"));
        }
        if (isPluginLoaded("MythicLib")) {
            inventory.setItem(slot++, createPluginItem("MythicLib", Material.BOOK,
                    "&aMythicLib", "&7查看 MythicLib 基础信息"));
        }
        if (isPluginLoaded("AdvancedEnchantments")) {
            inventory.setItem(slot++, createPluginItem("AEAddon", Material.ENCHANTED_BOOK,
                    "&dAEAddon", "&7点击执行 /aeaddon"));
        }

        fillEmptySlots(inventory, createGlassPane());
        player.openInventory(inventory);
    }

    /**
     * Open the MMOItems item-type selection menu.
     */
    public void openMMOItemsTypeMenu(Player player) {
        File itemDir = getMMOItemsItemDir();
        if (itemDir == null || !itemDir.exists() || !itemDir.isDirectory()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c[MMOAddon] 找不到 MMOItems 物品配置目录"));
            return;
        }

        File[] files = itemDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null || files.length == 0) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c[MMOAddon] MMOItems 没有物品配置文件"));
            return;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));

        int size = Math.max(9, ((files.length - 1) / 9 + 1) * 9);
        size = Math.min(size, 54);
        String title = TYPE_MENU_TITLE_PREFIX + "MMOItems";
        if (title.length() > 32) title = title.substring(0, 32);

        Inventory inventory = Bukkit.createInventory(
                new MMOAddonHolder(GUIType.MMOITEMS_TYPE_MENU, "MMOItems", null, 1),
                size, title);

        for (int i = 0; i < files.length && i < size; i++) {
            String typeName = files[i].getName().replace(".yml", "");
            inventory.setItem(i, createTypeItem(typeName, files[i]));
        }

        fillEmptySlots(inventory, createGlassPane());
        player.openInventory(inventory);
    }

    /**
     * Open the item list menu for a specific MMOItems type.
     */
    public void openMMOItemsItemMenu(Player player, String typeName, int page) {
        File file = new File(getMMOItemsItemDir(), typeName + ".yml");
        if (!file.exists()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c[MMOAddon] 找不到类型 " + typeName));
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        Set<String> keys = config.getKeys(false);
        List<String> itemIds = new ArrayList<>(keys);
        Collections.sort(itemIds);

        int totalPages = (int) Math.ceil((double) itemIds.size() / ITEMS_PER_PAGE);
        page = Math.max(1, Math.min(page, totalPages));

        String title = ITEM_MENU_TITLE_PREFIX + typeName + " &7(" + page + "/" + totalPages + ")";
        title = ChatColor.translateAlternateColorCodes('&', title);
        if (title.length() > 32) title = title.substring(0, 32);

        Inventory inventory = Bukkit.createInventory(
                new MMOAddonHolder(GUIType.MMOITEMS_ITEM_MENU, "MMOItems", typeName, page),
                54, title);

        int startIndex = (page - 1) * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, itemIds.size());

        for (int i = startIndex; i < endIndex; i++) {
            String itemId = itemIds.get(i);
            ConfigurationSection section = config.getConfigurationSection(itemId);
            if (section == null) continue;
            inventory.setItem(i - startIndex, createItemPreview(itemId, section));
        }

        inventory.setItem(NAV_ROW + 3, createNavigationItem(Material.ARROW, "&c返回类型菜单"));
        if (page > 1) {
            inventory.setItem(NAV_ROW + 1, createNavigationItem(Material.PAPER, "&e上一页"));
        }
        if (page < totalPages) {
            inventory.setItem(NAV_ROW + 5, createNavigationItem(Material.PAPER, "&e下一页"));
        }
        inventory.setItem(NAV_ROW + 4, createInfoItem("&7页码: &f" + page + "/" + totalPages));

        fillEmptySlots(inventory, createGlassPane());
        player.openInventory(inventory);
    }

    /**
     * Open the detail view for an MMOItems item.
     */
    public void openMMOItemsDetailMenu(Player player, String typeName, String itemId) {
        File file = new File(getMMOItemsItemDir(), typeName + ".yml");
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection(itemId);
        if (section == null) return;

        ConfigurationSection base = section.getConfigurationSection("base");
        if (base == null) base = section;

        String title = DETAIL_MENU_TITLE_PREFIX + itemId;
        title = ChatColor.translateAlternateColorCodes('&', title);
        if (title.length() > 32) title = title.substring(0, 32);

        Inventory inventory = Bukkit.createInventory(
                new MMOAddonHolder(GUIType.MMOITEMS_DETAIL_MENU, "MMOItems", typeName + ":" + itemId, 1),
                27, title);

        ItemStack preview = createItemPreview(itemId, section);
        inventory.setItem(13, preview);

        List<String> infoLore = new ArrayList<>();
        addIfPresent(infoLore, "material", base.getString("material"));
        addIfPresent(infoLore, "tier", base.getString("tier"));
        addIfPresent(infoLore, "required-level", base.getString("required-level"));
        addIfPresent(infoLore, "attack-damage", base.getString("attack-damage"));
        addIfPresent(infoLore, "attack-speed", base.getString("attack-speed"));
        addIfPresent(infoLore, "custom-model-data", base.getString("custom-model-data"));

        ItemStack info = createNavigationItem(Material.BOOK, "&e物品属性");
        ItemMeta meta = info.getItemMeta();
        if (meta != null) {
            meta.setLore(infoLore);
            info.setItemMeta(meta);
        }
        inventory.setItem(11, info);

        inventory.setItem(15, createNavigationItem(Material.ARROW, "&c返回物品列表"));
        fillEmptySlots(inventory, createGlassPane());
        player.openInventory(inventory);
    }

    private void addIfPresent(List<String> list, String key, String value) {
        if (value != null && !value.isEmpty()) {
            list.add(ChatColor.translateAlternateColorCodes('&', "&7" + key + ": &f" + value));
        }
    }

    /**
     * Parse a material string safely.
     */
    public Material parseMaterial(String name) {
        if (name == null || name.isEmpty()) return Material.PAPER;
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.PAPER;
        }
    }

    private File getMMOItemsItemDir() {
        Plugin mmoItems = Bukkit.getPluginManager().getPlugin("MMOItems");
        if (mmoItems == null) return null;
        return new File(mmoItems.getDataFolder(), "item");
    }

    private boolean isPluginLoaded(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled();
    }

    private ItemStack createPluginItem(String pluginName, Material material, String displayName, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(material);
        }
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', description));
            lore.add("");
            lore.add(ChatColor.translateAlternateColorCodes('&', "&e点击查看"));
            meta.setLore(lore);

            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(pluginKey, PersistentDataType.STRING, pluginName);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createTypeItem(String typeName, File file) {
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(Material.CHEST);
        }
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b" + typeName));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.translateAlternateColorCodes('&', "&7文件: &f" + file.getName()));
            lore.add("");
            lore.add(ChatColor.translateAlternateColorCodes('&', "&e点击浏览此类型"));
            meta.setLore(lore);

            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(typeKey, PersistentDataType.STRING, typeName);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createItemPreview(String itemId, ConfigurationSection section) {
        ConfigurationSection base = section.getConfigurationSection("base");
        if (base == null) base = section;

        Material material = parseMaterial(base.getString("material"));
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(material);
        }
        if (meta != null) {
            String name = base.getString("name", itemId);
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

            List<String> lore = new ArrayList<>();
            if (base.contains("tier")) {
                lore.add(ChatColor.translateAlternateColorCodes('&', "&7品质: &f" + base.getString("tier")));
            }
            if (base.contains("required-level")) {
                lore.add(ChatColor.translateAlternateColorCodes('&', "&7等级需求: &f" + base.getString("required-level")));
            }
            lore.add("");
            lore.add(ChatColor.translateAlternateColorCodes('&', "&e点击查看详情"));

            meta.setLore(lore);

            PersistentDataContainer container = meta.getPersistentDataContainer();
            container.set(itemKey, PersistentDataType.STRING, itemId);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createNavigationItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createInfoItem(String text) {
        return createNavigationItem(Material.BOOK, text);
    }

    private ItemStack createGlassPane() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillEmptySlots(Inventory inventory, ItemStack filler) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler.clone());
            }
        }
    }

    public String getPluginFromItem(ItemStack item) {
        return getPersistentString(item, pluginKey);
    }

    public String getTypeFromItem(ItemStack item) {
        return getPersistentString(item, typeKey);
    }

    public String getItemIdFromItem(ItemStack item) {
        return getPersistentString(item, itemKey);
    }

    private String getPersistentString(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.get(key, PersistentDataType.STRING);
    }

    public enum GUIType {
        MAIN_MENU,
        MMOITEMS_TYPE_MENU,
        MMOITEMS_ITEM_MENU,
        MMOITEMS_DETAIL_MENU
    }

    public static class MMOAddonHolder implements InventoryHolder {
        private final GUIType type;
        private final String pluginName;
        private final String data;
        private final int page;

        public MMOAddonHolder(GUIType type, String pluginName, String data, int page) {
            this.type = type;
            this.pluginName = pluginName;
            this.data = data;
            this.page = page;
        }

        public GUIType getType() { return type; }
        public String getPluginName() { return pluginName; }
        public String getData() { return data; }
        public int getPage() { return page; }

        @Override
        public Inventory getInventory() { return null; }
    }
}
