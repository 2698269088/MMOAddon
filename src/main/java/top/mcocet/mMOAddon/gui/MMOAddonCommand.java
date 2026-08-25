package top.mcocet.mMOAddon.gui;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import top.mcocet.mMOAddon.MMOAddon;

import java.util.ArrayList;
import java.util.List;

public class MMOAddonCommand implements CommandExecutor, TabCompleter {

    private final MMOAddon plugin;

    public MMOAddonCommand(MMOAddon plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c[MMOAddon] 此命令只能由玩家执行！"));
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("mmoaddon.admin")) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c[MMOAddon] 你没有权限使用此命令！"));
            return true;
        }

        plugin.getMMOMainGUI().openMainMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return new ArrayList<>();
    }
}
