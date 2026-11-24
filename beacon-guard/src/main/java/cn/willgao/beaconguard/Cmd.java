package cn.willgao.beaconguard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;

final class Cmd implements TabExecutor, TabCompleter {
    private final BeaconGuardPlugin plugin;
    private final RegionStore store;

    Cmd(BeaconGuardPlugin plugin) { this.plugin = plugin; this.store = plugin.store; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(a, Math.min(1, a.length), a.length);
        switch (sub) {
            case "protect", "保护", "create" -> protect(s, rest);
            case "remove", "unprotect", "delete", "取消" -> remove(s, rest);
            case "flag", "set", "设置" -> flag(s, rest);
            case "info", "信息" -> info(s, rest);
            case "list", "列表" -> list(s, rest);
            case "flags" -> flags(s);
            case "radius" -> radius(s, rest);
            case "tp" -> tp(s, rest);
            case "ignore" -> plugin.msg(s, "§7已忽略，这个信标不会被保护。之后想保护可以输入 §e/guard protect§7。");
            case "reload" -> {
                if (!plugin.isAdmin(s)) { noPerm(s); return true; }
                plugin.reload();
                plugin.msg(s, "§a配置已重新加载。");
            }
            default -> help(s, label);
        }
        return true;
    }

    private void help(CommandSender s, String l) {
        plugin.msg(s, "§e信标保护 指令：");
        s.sendMessage("§e/" + l + " protect §7— 保护你脚下这个位置（半径 " + plugin.radius() + " 格）");
        s.sendMessage("§e/" + l + " protect <x> <y> <z> [世界] §7— 保护指定坐标");
        s.sendMessage("§e/" + l + " flag <开关> <allow|deny> [编号] §7— 开关规则，例如 §f/" + l + " flag pvp deny");
        s.sendMessage("§e/" + l + " flags §7— 查看所有可用开关");
        s.sendMessage("§e/" + l + " info [编号] §7— 查看保护区（可点击开关）");
        s.sendMessage("§e/" + l + " list [all] §7— 列出保护区");
        s.sendMessage("§e/" + l + " remove [编号] §7— 取消保护");
        if (plugin.isAdmin(s)) s.sendMessage("§c管理员：§e/" + l + " radius <半径> [编号]§7、§e/" + l + " tp <编号>§7、§e/" + l + " reload");
        s.sendMessage("§7不写编号时，默认是你当前所在的保护区。");
    }

    private void noPerm(CommandSender s) { plugin.msg(s, "§c你没有权限。"); }

    private boolean canManage(CommandSender s, Region r) {
        if (plugin.isAdmin(s)) return true;
        return s instanceof Player p && p.getUniqueId().equals(r.owner);
    }

    /** 解析编号；没写编号时用玩家当前位置所在的保护区。 */
    private Region resolve(CommandSender s, String[] a, int idx) {
        if (a.length > idx) {
            String t = a[idx].startsWith("#") ? a[idx].substring(1) : a[idx];
            try {
                Region r = store.get(Integer.parseInt(t));
                if (r == null) plugin.msg(s, "§c没有编号为 #" + t + " 的保护区。");
                return r;
            } catch (NumberFormatException e) {
                plugin.msg(s, "§c编号必须是数字，例如 §e3§c 或 §e#3§c。");
                return null;
            }
        }
        if (!(s instanceof Player p)) { plugin.msg(s, "§c控制台请写上保护区编号。"); return null; }
        Region r = store.at(p.getLocation());
        if (r == null) plugin.msg(s, "§c你现在不在任何保护区内，请写上编号（§e/guard list§c 查看）。");
        return r;
    }

    // ------------------------------------------------------------ protect
    private void protect(CommandSender s, String[] a) {
        if (!s.hasPermission("beaconguard.use")) { noPerm(s); return; }
        Player p = s instanceof Player pl ? pl : null;
        World w;
        int x, y, z;
        if (a.length == 0) {
            if (p == null) { plugin.msg(s, "§c控制台用法：/guard protect <x> <y> <z> <世界>"); return; }
            Location l = p.getLocation();
            w = l.getWorld(); x = l.getBlockX(); y = l.getBlockY(); z = l.getBlockZ();
        } else if (a.length >= 3) {
            Location base = p != null ? p.getLocation() : null;
            try {
                x = coord(a[0], base == null ? 0 : base.getBlockX(), base != null);
                y = coord(a[1], base == null ? 0 : base.getBlockY(), base != null);
                z = coord(a[2], base == null ? 0 : base.getBlockZ(), base != null);
            } catch (NumberFormatException e) {
                plugin.msg(s, "§c坐标格式不对，例如 §e/guard protect 100 64 -200§c（也支持 ~ 相对坐标）。");
                return;
            }
            if (a.length >= 4) w = Bukkit.getWorld(a[3]);
            else w = p != null ? p.getWorld() : null;
            if (w == null) { plugin.msg(s, "§c找不到世界" + (a.length >= 4 ? "「" + a[3] + "」" : "，请写上世界名") + "。"); return; }
        } else {
            plugin.msg(s, "§c用法：/guard protect 或 /guard protect <x> <y> <z> [世界]");
            return;
        }
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) { plugin.msg(s, "§cY 坐标超出世界高度范围。"); return; }

        String wn = w.getName();
        int radius = plugin.radius();
        boolean admin = plugin.isAdmin(s);
        UUID owner = p == null ? null : p.getUniqueId();

        Region here = store.at(wn, x, y, z);
        if (here != null && here.isCenter(wn, x, y, z)) {
            plugin.msg(s, "§e这里已经是保护区 #" + here.id + " 了。"); return;
        }
        if (!admin) {
            int max = plugin.getConfig().getInt("max-regions-per-player", 3);
            if (max >= 0 && owner != null && store.ownedBy(owner).size() >= max) {
                plugin.msg(s, "§c你最多只能创建 " + max + " 个保护区。可用 §e/guard remove <编号>§c 取消旧的。");
                return;
            }
            if (!plugin.getConfig().getBoolean("allow-overlap", false)) {
                Region o = store.overlapping(wn, x, z, radius, owner);
                if (o != null) {
                    plugin.msg(s, "§c这里离保护区 #" + o.id + "（" + o.ownerName + "，" + o.coords() + "）太近，保护范围会重叠。");
                    return;
                }
            }
        }
        Region r = store.create(wn, x, y, z, radius, owner, p == null ? "服务器" : p.getName());
        plugin.msg(s, "§a已保护此地标！§f保护区 §e#" + r.id + "§f，中心 " + r.coords() + "，半径 " + r.radius + " 格。");
        s.sendMessage("§7范围内只有管理员可以破坏方块；爆炸、苦力怕、TNT、怪物、火烧都无法破坏建筑。");
        sendFlagPanel(s, r);
    }

    private static int coord(String t, int base, boolean relOk) {
        if (t.startsWith("~")) {
            if (!relOk) throw new NumberFormatException();
            return t.length() == 1 ? base : base + (int) Math.floor(Double.parseDouble(t.substring(1)));
        }
        return (int) Math.floor(Double.parseDouble(t));
    }

    // ------------------------------------------------------------ remove
    private void remove(CommandSender s, String[] a) {
        Region r = resolve(s, a, 0);
        if (r == null) return;
        if (!canManage(s, r)) { plugin.msg(s, "§c只有保护区主人或管理员可以取消保护区 #" + r.id + "。"); return; }
        store.remove(r);
        plugin.msg(s, "§e已取消保护区 #" + r.id + "（" + r.coords() + "）。");
    }

    // ------------------------------------------------------------ flag
    private void flag(CommandSender s, String[] a) {
        if (a.length < 2) {
            plugin.msg(s, "§c用法：/guard flag <开关> <allow|deny> [编号]  §7（/guard flags 查看所有开关）");
            return;
        }
        Flag f = Flag.parse(a[0]);
        if (f == null) { plugin.msg(s, "§c没有这个开关：" + a[0] + "。§7输入 /guard flags 查看。"); return; }
        Boolean v = parseBool(a[1]);
        if (v == null) { plugin.msg(s, "§c第二个参数请写 allow（允许）或 deny（禁止）。"); return; }
        Region r = resolve(s, a, 2);
        if (r == null) return;
        if (!canManage(s, r)) { plugin.msg(s, "§c只有保护区主人或管理员可以修改保护区 #" + r.id + "。"); return; }
        r.flags.put(f, v);
        store.save();
        plugin.msg(s, "保护区 §e#" + r.id + "§f：" + f.desc + " → " + (v ? "§a允许" : "§c禁止"));
    }

    private static Boolean parseBool(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "allow", "true", "on", "yes", "1", "允许", "开" -> true;
            case "deny", "false", "off", "no", "0", "禁止", "关" -> false;
            default -> null;
        };
    }

    private void flags(CommandSender s) {
        plugin.msg(s, "§e可用开关：");
        for (Flag f : Flag.values()) s.sendMessage("§e" + f.key + " §7— " + f.desc);
        s.sendMessage("§7方块保护（防挖、防爆、防火烧）始终开启，不能关闭。");
    }

    // ------------------------------------------------------------ info / list
    private void info(CommandSender s, String[] a) {
        Region r = resolve(s, a, 0);
        if (r == null) return;
        plugin.msg(s, "§e保护区 #" + r.id);
        s.sendMessage("§7主人：§f" + r.ownerName + "  §7中心：§f" + r.coords() + "  §7半径：§f" + r.radius
                + (store.circle ? "（圆形）" : "（正方形）"));
        s.sendMessage("§7创建时间：§f" + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(r.created)));
        sendFlagPanel(s, r);
    }

    /** 每个开关一行，主人/管理员可以直接点击切换。 */
    private void sendFlagPanel(CommandSender s, Region r) {
        boolean manage = canManage(s, r);
        for (Flag f : Flag.values()) {
            boolean v = r.allows(f);
            Component line = BeaconGuardPlugin.legacy("§7 • " + f.desc + "：" + (v ? "§a允许" : "§c禁止") + "  ");
            if (manage) {
                String cmd = "/guard flag " + f.key + " " + (v ? "deny" : "allow") + " " + r.id;
                line = line.append(BeaconGuardPlugin.button(v ? "§c[点击禁止]" : "§a[点击允许]", "§7" + cmd, cmd, true));
            }
            s.sendMessage(line);
        }
    }

    private void list(CommandSender s, String[] a) {
        boolean all = a.length > 0 && a[0].equalsIgnoreCase("all");
        if (!(s instanceof Player)) all = true;
        if (all && !plugin.isAdmin(s)) { noPerm(s); return; }
        List<Region> l = all ? new ArrayList<>(store.all()) : store.ownedBy(((Player) s).getUniqueId());
        if (l.isEmpty()) { plugin.msg(s, "§7没有保护区。"); return; }
        plugin.msg(s, "§e保护区（" + l.size() + " 个）：");
        for (Region r : l) {
            s.sendMessage(BeaconGuardPlugin.button("§e#" + r.id + " §f" + r.coords() + " §7半径 " + r.radius + " · " + r.ownerName,
                    "§7点击查看详情", "/guard info " + r.id, true));
        }
    }

    // ------------------------------------------------------------ admin
    private void radius(CommandSender s, String[] a) {
        if (!plugin.isAdmin(s)) { noPerm(s); return; }
        if (a.length < 1) { plugin.msg(s, "§c用法：/guard radius <半径> [编号]"); return; }
        int v;
        try { v = Integer.parseInt(a[0]); } catch (NumberFormatException e) { plugin.msg(s, "§c半径必须是整数。"); return; }
        if (v < 1 || v > 10000) { plugin.msg(s, "§c半径需在 1 ~ 10000 之间。"); return; }
        Region r = resolve(s, a, 1);
        if (r == null) return;
        r.radius = v;
        store.save();
        plugin.msg(s, "§a保护区 #" + r.id + " 的半径已改为 " + v + " 格。");
    }

    private void tp(CommandSender s, String[] a) {
        if (!plugin.isAdmin(s)) { noPerm(s); return; }
        if (!(s instanceof Player p)) { plugin.msg(s, "§c只有玩家可以传送。"); return; }
        if (a.length < 1) { plugin.msg(s, "§c用法：/guard tp <编号>"); return; }
        Region r = resolve(s, a, 0);
        if (r == null) return;
        World w = Bukkit.getWorld(r.world);
        if (w == null) { plugin.msg(s, "§c世界 " + r.world + " 未加载。"); return; }
        p.teleportAsync(new Location(w, r.x + 0.5, r.y + 1, r.z + 0.5));
    }

    // ------------------------------------------------------------ tab
    private static final List<String> SUBS = List.of("protect", "flag", "flags", "info", "list", "remove");
    private static final List<String> ADMIN_SUBS = List.of("radius", "tp", "reload");

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(SUBS);
            if (plugin.isAdmin(s)) out.addAll(ADMIN_SUBS);
        } else {
            String sub = a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "flag" -> {
                    if (a.length == 2) for (Flag f : Flag.values()) out.add(f.key);
                    else if (a.length == 3) out.addAll(List.of("allow", "deny"));
                    else if (a.length == 4) out.addAll(ids(s));
                }
                case "protect" -> {
                    if (s instanceof Player p && a.length <= 4) {
                        Location l = p.getLocation();
                        int[] v = {l.getBlockX(), l.getBlockY(), l.getBlockZ()};
                        out.add(String.valueOf(v[a.length - 2]));
                        out.add("~");
                    } else if (a.length == 5) for (World w : Bukkit.getWorlds()) out.add(w.getName());
                }
                case "info", "remove", "tp" -> { if (a.length == 2) out.addAll(ids(s)); }
                case "radius" -> { if (a.length == 2) out.add(String.valueOf(plugin.radius())); else if (a.length == 3) out.addAll(ids(s)); }
                case "list" -> { if (a.length == 2 && plugin.isAdmin(s)) out.add("all"); }
                default -> { }
            }
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    private List<String> ids(CommandSender s) {
        List<String> out = new ArrayList<>();
        Collection<Region> src = plugin.isAdmin(s) || !(s instanceof Player p) ? store.all() : store.ownedBy(p.getUniqueId());
        for (Region r : src) out.add(String.valueOf(r.id));
        return out;
    }
}
