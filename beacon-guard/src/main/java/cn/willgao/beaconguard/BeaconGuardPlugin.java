package cn.willgao.beaconguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BeaconGuardPlugin extends JavaPlugin {
    static final String PREFIX = "§6[信标保护] §f";
    RegionStore store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new RegionStore(this);
        store.readShape();
        store.load();
        Bukkit.getPluginManager().registerEvents(new ProtectListener(this), this);
        Cmd cmd = new Cmd(this);
        PluginCommand pc = getCommand("guard");
        if (pc != null) { pc.setExecutor(cmd); pc.setTabCompleter(cmd); }
        getLogger().info("已加载 " + store.all().size() + " 个保护区。"
                + (Bukkit.getPluginManager().getPlugin("TeleportBeacon") != null ? " 检测到 TeleportBeacon，已联动。" : ""));
    }

    @Override
    public void onDisable() { if (store != null) store.save(); }

    void reload() {
        reloadConfig();
        store.readShape();
    }

    int radius() { return Math.max(1, getConfig().getInt("radius", 50)); }

    boolean isAdmin(CommandSender s) { return s.hasPermission("beaconguard.admin"); }

    void msg(CommandSender s, String m) { s.sendMessage(legacy(PREFIX + m)); }

    static Component legacy(String s) { return LegacyComponentSerializer.legacySection().deserialize(s); }

    static Component button(String label, String hover, String command, boolean run) {
        return legacy(label)
                .hoverEvent(HoverEvent.showText(legacy(hover)))
                .clickEvent(run ? ClickEvent.runCommand(command) : ClickEvent.suggestCommand(command));
    }

    /** 放下信标后弹出的「是否保护此地标？」 */
    void sendProtectPrompt(Player p, Block b) {
        Region existing = store.at(b);
        if (existing != null) {
            if (existing.isCenter(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) return;
            msg(p, "§7这个信标位于保护区 §e#" + existing.id + "§7（" + existing.ownerName + "）内，已受保护。");
            if (!isAdmin(p)) return;
        }
        String cmd = "/guard protect " + b.getX() + " " + b.getY() + " " + b.getZ() + " " + b.getWorld().getName();
        p.sendMessage(legacy(PREFIX + "是否保护此地标？§7（以信标为中心 " + radius() + " 格内）  ")
                .append(button("§a§l[保护]", "§a点击保护此地标\n§7范围内只有管理员能破坏方块，\n§7爆炸、苦力怕、TNT、火烧等都无法破坏建筑。", cmd, true))
                .append(Component.text("  "))
                .append(button("§7[忽略]", "§7不保护，什么也不做", "/guard ignore", true)));
    }
}
