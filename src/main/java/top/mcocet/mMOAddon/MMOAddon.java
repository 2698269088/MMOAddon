package top.mcocet.mMOAddon;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.mMOAddon.gui.MMOMainGUI;
import top.mcocet.mMOAddon.gui.MMOAddonCommand;
import top.mcocet.mMOAddon.gui.MMOGUIListener;

public final class MMOAddon extends JavaPlugin implements Listener {

    private MMOMainGUI mmoMainGUI;

    @Override
    public void onEnable() {
        // Detect Folia and apply runtime bytecode patches for MMO plugins.
        if (isFolia()) {
            getLogger().info("[MMOAddon] Folia detected, applying compatibility patches...");
            try {
                top.mcocet.mMOAddon.foliafix.patcher.FoliaSchedulerPatcher.init(this);
                getLogger().info("[MMOAddon] Scheduler patch initialized");
            } catch (Exception e) {
                getLogger().warning("[MMOAddon] Failed to initialize scheduler patch: " + e.getMessage());
            }

            // Listen for target plugin enable events to retransform classes loaded afterwards.
            Bukkit.getPluginManager().registerEvents(this, this);
        } else {
            getLogger().info("[MMOAddon] Not running on Folia, skipping compatibility patches");
        }

        getLogger().info("MMOAddon 已加载!");

        // Initialize GUI manager and register command/event listeners.
        this.mmoMainGUI = new MMOMainGUI(this);
        MMOAddonCommand commandExecutor = new MMOAddonCommand(this);
        getCommand("mmoaddon").setExecutor(commandExecutor);
        getCommand("mmoaddon").setTabCompleter(commandExecutor);
        Bukkit.getPluginManager().registerEvents(new MMOGUIListener(this), this);
    }

    public MMOMainGUI getMMOMainGUI() {
        return mmoMainGUI;
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        String pluginName = event.getPlugin().getName();
        if (pluginName.equals("MythicLib") || pluginName.equals("MMOItems")) {
            getLogger().info("[MMOAddon] " + pluginName + " enabled, retransforming loaded classes...");
            try {
                top.mcocet.mMOAddon.foliafix.patcher.FoliaSchedulerPatcher.retransformLoadedClasses(this);
                getLogger().info("[MMOAddon] Retransform complete");
            } catch (Exception e) {
                getLogger().warning("[MMOAddon] Failed to retransform classes: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("MMOAddon 已卸载!");
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
