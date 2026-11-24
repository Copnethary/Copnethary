package cn.willgao.beaconguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class BeaconGuardPlugin extends JavaPlugin {
    static final String PREFIX = "§6[信标保护] §f";
    static BeaconGuardPlugin instance;

    RegionStore store;
    Menu menu;
    BedrockForms bedrock; // 没装 floodgate 时为 null

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        store = new RegionStore(this);
        store.readShape();
        store.load();
        if (getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrock = new BedrockForms(this);
                getLogger().info("检测到 Floodgate，基岩版玩家将使用原生表单。");
            } catch (Throwable t) {
                bedrock = null;
                getLogger().warning("Floodgate API 不可用，基岩版将使用箱子界面: " + t);
            }
        }
        menu = new Menu(this);
        Bukkit.getPluginManager().registerEvents(new ProtectListener(this), this);
        Bukkit.getPluginManager().registerEvents(menu, this);
        Cmd cmd = new Cmd(this);
        PluginCommand pc = getCommand("guard");
        if (pc != null) { pc.setExecutor(cmd); pc.setTabCompleter(cmd); }
        getLogger().info("已加载 " + store.all().size() + " 个保护区。");
    }

    @Override
    public void onDisable() {
        if (store != null) store.save();
        instance = null;
    }

    void reload() {
        reloadConfig();
        store.readShape();
    }

    int radius() { return Math.max(1, getConfig().getInt("radius", 50)); }

    boolean isAdmin(CommandSender s) { return s.hasPermission("beaconguard.admin"); }

    boolean isBedrock(Player p) { return bedrock != null && bedrock.isBedrock(p.getUniqueId()); }

    void msg(CommandSender s, String m) { s.sendMessage(legacy(PREFIX + m)); }

    static Component legacy(String s) { return LegacyComponentSerializer.legacySection().deserialize(s); }

    static Component button(String label, String hover, String command) {
        return legacy(label).hoverEvent(HoverEvent.showText(legacy(hover))).clickEvent(ClickEvent.runCommand(command));
    }

    // ------------------------------------------------------------ 权限规则
    /** 能否修改这个保护区的设置（主人或管理员）。 */
    boolean canManage(CommandSender s, Region r) {
        if (isAdmin(s)) return true;
        return s instanceof Player p && p.getUniqueId().equals(r.owner);
    }

    /** 主人能否关闭某一项（放宽）。方块保护类默认只有管理员能关闭。 */
    boolean canRelax(CommandSender s, Flag f) {
        if (isAdmin(s)) return true;
        return !f.protection || getConfig().getBoolean("owner-can-relax-protection", false);
    }

    /** 修改一项设置；返回 null 表示成功，否则返回失败原因。 */
    String setFlag(CommandSender s, Region r, Flag f, boolean deny) {
        if (!canManage(s, r)) return "只有保护区主人（" + r.ownerName + "）或管理员可以修改保护区 #" + r.id + "。";
        if (r.denies(f) == deny) return null;
        if (!deny && !canRelax(s, f)) return "「" + f.title + "」开启后只有管理员可以关闭。";
        r.deny.put(f, deny);
        store.save();
        return null;
    }

    /** 创建保护区（命令、聊天按钮、菜单共用）。失败时已给出提示并返回 null。 */
    Region protect(CommandSender s, World w, int x, int y, int z) {
        if (!s.hasPermission("beaconguard.use")) { msg(s, "§c你没有权限。"); return null; }
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) { msg(s, "§cY 坐标超出世界高度范围。"); return null; }
        String wn = w.getName();
        int radius = radius();
        Player p = s instanceof Player pl ? pl : null;
        UUID owner = p == null ? null : p.getUniqueId();

        Region here = store.centeredAt(wn, x, y, z);
        if (here != null) { msg(s, "§e这里已经是保护区 #" + here.id + " 了。"); return null; }
        if (!isAdmin(s)) {
            int max = getConfig().getInt("max-regions-per-player", 3);
            if (max >= 0 && owner != null && store.ownedBy(owner).size() >= max) {
                msg(s, "§c你最多只能创建 " + max + " 个保护区。");
                return null;
            }
            if (!getConfig().getBoolean("allow-overlap", false)) {
                Region o = store.overlapping(wn, x, z, radius, owner);
                if (o != null) {
                    msg(s, "§c这里离保护区 #" + o.id + "（" + o.ownerName + "，" + o.coords() + "）太近，保护范围会重叠。");
                    return null;
                }
            }
        }
        Region r = store.create(wn, x, y, z, radius, owner, p == null ? "服务器" : p.getName());
        msg(s, "§a已保护此地标！§f保护区 §e#" + r.id + "§f，中心 " + r.coords() + "，半径 " + r.radius + " 格。");
        s.sendMessage(legacy("§7保护开启后只有管理员可以撤销。详细设置：").append(
                button("§b[打开保护设置]", "§7/guard menu " + r.id, "/guard menu " + r.id)));
        return r;
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
                .append(button("§a§l[保护]", "§a点击保护此地标\n§7范围内只有管理员能破坏方块，\n§7爆炸、苦力怕、TNT、火烧等都无法破坏建筑。\n§7保护后只有管理员能撤销。", cmd))
                .append(Component.text("  "))
                .append(button("§7[忽略]", "§7不保护，什么也不做", "/guard ignore")));
    }
}
