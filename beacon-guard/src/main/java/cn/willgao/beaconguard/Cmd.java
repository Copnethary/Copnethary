package cn.willgao.beaconguard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;

final class Cmd implements TabExecutor {
    private final BeaconGuardPlugin plugin;
    private final RegionStore store;

    Cmd(BeaconGuardPlugin plugin) { this.plugin = plugin; this.store = plugin.store; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(a, Math.min(1, a.length), a.length);
        switch (sub) {
            case "protect", "保护", "create" -> protect(s, rest);
            case "menu", "gui", "菜单", "设置" -> menu(s, rest);
            case "remove", "unprotect", "delete", "取消" -> remove(s, rest);
            case "flag", "set" -> flag(s, rest);
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
        s.sendMessage("§e/" + l + " menu [编号] §7— 打开保护设置菜单（可分别开关每一项）");
        s.sendMessage("§e/" + l + " protect §7— 保护你脚下这个位置（半径 " + plugin.radius() + " 格）");
        s.sendMessage("§e/" + l + " protect <x> <y> <z> [世界] §7— 保护指定坐标");
        s.sendMessage("§e/" + l + " flag <设置> <on|off> [编号] §7— 例如 §f/" + l + " flag pvp on §7（禁止 PVP）");
        s.sendMessage("§e/" + l + " flags §7— 查看所有设置名");
        s.sendMessage("§e/" + l + " info [编号] §7— 查看保护区（可点击开关）");
        s.sendMessage("§e/" + l + " list §7— 列出你的保护区");
        if (plugin.isAdmin(s)) s.sendMessage("§c管理员：§e/" + l + " remove [编号]§7（取消保护）、§eradius <半径> [编号]§7、§etp <编号>§7、§elist all§7、§ereload");
        s.sendMessage("§7不写编号时，默认是你当前所在的保护区。保护开启后只有管理员可以撤销。");
    }

    private void noPerm(CommandSender s) { plugin.msg(s, "§c你没有权限。"); }

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

    // ------------------------------------------------------------ protect / menu
    private void protect(CommandSender s, String[] a) {
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
            w = a.length >= 4 ? Bukkit.getWorld(a[3]) : p != null ? p.getWorld() : null;
            if (w == null) { plugin.msg(s, "§c找不到世界" + (a.length >= 4 ? "「" + a[3] + "」" : "，请写上世界名") + "。"); return; }
        } else {
            plugin.msg(s, "§c用法：/guard protect 或 /guard protect <x> <y> <z> [世界]");
            return;
        }
        plugin.protect(s, w, x, y, z);
    }

    private static int coord(String t, int base, boolean relOk) {
        if (t.startsWith("~")) {
            if (!relOk) throw new NumberFormatException();
            return t.length() == 1 ? base : base + (int) Math.floor(Double.parseDouble(t.substring(1)));
        }
        return (int) Math.floor(Double.parseDouble(t));
    }

    /** /guard menu [编号] 或 /guard menu <世界> <x> <y> <z> */
    private void menu(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { plugin.msg(s, "§c只有玩家可以打开菜单。"); return; }
        if (a.length >= 4) {
            try {
                plugin.menu.open(p, a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), null, null);
            } catch (NumberFormatException e) { plugin.msg(s, "§c用法：/guard menu <世界> <x> <y> <z>"); }
            return;
        }
        if (a.length == 0) {
            Region r = store.at(p.getLocation());
            if (r != null) { plugin.menu.open(p, r, null); return; }
            // 不在保护区内：打开脚下位置的菜单（可以直接点「保护此地标」）
            Location l = p.getLocation();
            plugin.menu.open(p, l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), "你脚下的位置", null);
            return;
        }
        Region r = resolve(s, a, 0);
        if (r != null) plugin.menu.open(p, r, null);
    }

    // ------------------------------------------------------------ remove（仅管理员）
    private void remove(CommandSender s, String[] a) {
        if (!plugin.isAdmin(s)) { plugin.msg(s, "§c保护开启后只有管理员可以撤销。"); return; }
        Region r = resolve(s, a, 0);
        if (r == null) return;
        store.remove(r);
        plugin.msg(s, "§e已取消保护区 #" + r.id + "（" + r.coords() + "）。");
        if (r.owner != null && !(s instanceof Player p && p.getUniqueId().equals(r.owner))) {
            Player o = Bukkit.getPlayer(r.owner);
            if (o != null) plugin.msg(o, "§e你的保护区 #" + r.id + "（" + r.coords() + "）已被管理员取消。");
        }
    }

    // ------------------------------------------------------------ flag
    private void flag(CommandSender s, String[] a) {
        if (a.length < 2) {
            plugin.msg(s, "§c用法：/guard flag <设置> <on|off> [编号]  §7on = 开启（禁止），off = 关闭（允许）；/guard flags 查看所有设置");
            return;
        }
        Flag f = Flag.parse(a[0]);
        if (f == null) { plugin.msg(s, "§c没有这个设置：" + a[0] + "。§7输入 /guard flags 查看。"); return; }
        Boolean v = parseBool(a[1]);
        if (v == null) { plugin.msg(s, "§c第二个参数请写 on（开启/禁止）或 off（关闭/允许）。"); return; }
        Region r = resolve(s, a, 2);
        if (r == null) return;
        String err = plugin.setFlag(s, r, f, v);
        if (err != null) { plugin.msg(s, "§c" + err); return; }
        plugin.msg(s, "保护区 §e#" + r.id + "§f：" + f.title + " → " + (v ? "§a已开启" : "§c已关闭"));
    }

    private static Boolean parseBool(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "on", "deny", "true", "yes", "1", "禁止", "开", "开启" -> true;
            case "off", "allow", "false", "no", "0", "允许", "关", "关闭" -> false;
            default -> null;
        };
    }

    private void flags(CommandSender s) {
        plugin.msg(s, "§e所有设置（on = 开启/禁止，off = 关闭/允许）：");
        s.sendMessage("§6方块保护：");
        for (Flag f : Flag.values()) if (f.protection) s.sendMessage("  §e" + f.key + " §7— " + f.title);
        s.sendMessage("§6区域规则：");
        for (Flag f : Flag.values()) if (!f.protection) s.sendMessage("  §e" + f.key + " §7— " + f.title);
        if (!plugin.getConfig().getBoolean("owner-can-relax-protection", false))
            s.sendMessage("§7方块保护类开启后只有管理员可以关闭。");
    }

    // ------------------------------------------------------------ info / list
    private void info(CommandSender s, String[] a) {
        Region r = resolve(s, a, 0);
        if (r == null) return;
        plugin.msg(s, "§e保护区 #" + r.id);
        s.sendMessage("§7主人：§f" + r.ownerName + "  §7中心：§f" + r.coords() + "  §7半径：§f" + r.radius
                + (store.circle ? "（圆形）" : "（正方形）"));
        s.sendMessage("§7创建时间：§f" + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(r.created)));
        boolean manage = plugin.canManage(s, r);
        String section = null;
        for (Flag f : Flag.values()) {
            String sec = f.protection ? "§6方块保护：" : "§6区域规则：";
            if (!sec.equals(section)) { s.sendMessage(sec); section = sec; }
            boolean on = r.denies(f);
            Component line = BeaconGuardPlugin.legacy("§7 • " + f.title + "：" + (on ? "§a已开启" : "§c已关闭") + "  ");
            if (manage && (!on || plugin.canRelax(s, f))) {
                String cmd = "/guard flag " + f.key + " " + (on ? "off" : "on") + " " + r.id;
                line = line.append(BeaconGuardPlugin.button(on ? "§c[关闭]" : "§a[开启]", "§7" + cmd, cmd));
            }
            s.sendMessage(line);
        }
        if (s instanceof Player) s.sendMessage(BeaconGuardPlugin.button("§b[打开设置菜单]", "§7/guard menu " + r.id, "/guard menu " + r.id));
    }

    private void list(CommandSender s, String[] a) {
        boolean all = (a.length > 0 && a[0].equalsIgnoreCase("all")) || !(s instanceof Player);
        if (all && !plugin.isAdmin(s)) { noPerm(s); return; }
        List<Region> l = all ? new ArrayList<>(store.all()) : store.ownedBy(((Player) s).getUniqueId());
        if (l.isEmpty()) { plugin.msg(s, "§7没有保护区。"); return; }
        plugin.msg(s, "§e保护区（" + l.size() + " 个）：");
        for (Region r : l) {
            s.sendMessage(BeaconGuardPlugin.button("§e#" + r.id + " §f" + r.coords() + " §7半径 " + r.radius + " · " + r.ownerName
                    + " · 开启 " + r.enabledCount() + " 项", "§7点击打开设置菜单", "/guard menu " + r.id));
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
    private static final List<String> SUBS = List.of("menu", "protect", "flag", "flags", "info", "list");
    private static final List<String> ADMIN_SUBS = List.of("remove", "radius", "tp", "reload");

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(SUBS);
            if (plugin.isAdmin(s)) out.addAll(ADMIN_SUBS);
        } else {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "flag" -> {
                    if (a.length == 2) for (Flag f : Flag.values()) out.add(f.key);
                    else if (a.length == 3) out.addAll(List.of("on", "off"));
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
                case "info", "menu", "remove", "tp" -> { if (a.length == 2) out.addAll(ids(s)); }
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
