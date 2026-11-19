package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

final class Cmd implements CommandExecutor, TabCompleter {
    private final TeleportBeaconPlugin plugin;

    Cmd(TeleportBeaconPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (!(s instanceof Player p)) { listText(s, null); return true; }
                plugin.ui.openMenu(p, null, 0, null);
            }
            case "search", "搜索" -> {
                String kw = String.join(" ", Arrays.copyOfRange(a, 1, a.length)).trim();
                if (s instanceof Player p) {
                    if (kw.isEmpty()) plugin.ui.openMenu(p, null, 0, null);
                    else plugin.ui.openMenu(p, kw, 0, null);
                } else listText(s, kw.isEmpty() ? null : kw);
            }
            case "list", "列表" -> listText(s, a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : null);
            case "get", "领取" -> {
                if (!(s instanceof Player p)) { plugin.msg(s, "只有玩家可以领取。"); return true; }
                plugin.ensureItems(p, true);
            }
            case "tp", "传送" -> {
                if (!(s instanceof Player p)) { plugin.msg(s, "只有玩家可以传送。"); return true; }
                if (a.length < 2) { plugin.msg(s, "用法: /tb tp <传送点名字>"); return true; }
                String name = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                Point pt = plugin.store.byName(name);
                if (pt == null) { plugin.msg(s, "§c找不到名为「" + name + "」的传送点。试试 /tb search " + name); return true; }
                plugin.teleporter.teleport(p, pt);
            }
            case "requests", "申请" -> {
                if (!(s instanceof Player p)) { plugin.msg(s, "只有玩家可以查看。"); return true; }
                plugin.ui.showRequests(p);
            }
            case "accept", "deny" -> {
                if (a.length < 2) { plugin.msg(s, "用法: /tb " + sub + " <申请编号>"); return true; }
                int id;
                try { id = Integer.parseInt(a[1]); } catch (NumberFormatException ex) { plugin.msg(s, "§c编号无效。"); return true; }
                if (sub.equals("accept")) plugin.ui.accept(s, id); else plugin.ui.deny(s, id);
            }
            case "fav", "favorite", "收藏" -> fav(s, a, null);
            case "unfav", "unfavorite", "取消收藏" -> fav(s, a, false);
            case "pin", "置顶" -> pin(s, a, null);
            case "unpin", "取消置顶" -> pin(s, a, false);
            case "moveup", "上移" -> move(s, a, true);
            case "movedown", "下移" -> move(s, a, false);
            case "give" -> give(s, a);
            case "lock", "unlock", "锁定", "解锁" -> lock(s, a, sub.equals("lock") || sub.equals("锁定"));
            case "reload" -> {
                if (!plugin.isAdmin(s)) { plugin.msg(s, "§c你没有权限。"); return true; }
                plugin.store.flush();
                plugin.reloadConfig();
                plugin.teleporter.reloadSettings();
                plugin.markers.reloadSettings();
                plugin.store.load();
                plugin.markers.removeAll();
                plugin.markers.syncAll();
                plugin.msg(s, "已重载配置和数据，共 " + plugin.store.byId.size() + " 个传送点。");
            }
            default -> {
                plugin.msg(s, "用法: /tb [search <关键字>|list|get|tp <名字>|requests|fav|pin|moveup|movedown]"
                        + (plugin.isAdmin(s) ? " | give <玩家> <beacon|compass> [数量] | lock/unlock [名字] | reload" : ""));
            }
        }
        return true;
    }

    private void fav(CommandSender s, String[] a, Boolean force) {
        if (!(s instanceof Player p)) { plugin.msg(s, "只有玩家可以收藏传送点。"); return; }
        if (a.length < 2) { showPrefs(p); return; }
        Point pt = requirePoint(s, a);
        if (pt == null) return;
        boolean now = force == null ? plugin.store.toggleFavorite(p, pt) : plugin.store.setFavorite(p, pt, force);
        plugin.msg(p, now ? "已收藏「§b" + pt.displayName() + "§f」。" : "已取消收藏「§b" + pt.displayName() + "§f」。");
    }

    private void pin(CommandSender s, String[] a, Boolean force) {
        if (!(s instanceof Player p)) { plugin.msg(s, "只有玩家可以置顶传送点。"); return; }
        if (a.length < 2) { showPrefs(p); return; }
        Point pt = requirePoint(s, a);
        if (pt == null) return;
        if (force == null) {
            boolean now = plugin.store.togglePin(p, pt);
            plugin.msg(p, now ? "已置顶「§b" + pt.displayName() + "§f」。" : "已取消置顶「§b" + pt.displayName() + "§f」。");
        } else {
            plugin.store.setPinned(p, pt, force);
            plugin.msg(p, force ? "已置顶「§b" + pt.displayName() + "§f」。" : "已取消置顶「§b" + pt.displayName() + "§f」。");
        }
    }

    private void move(CommandSender s, String[] a, boolean up) {
        if (!(s instanceof Player p)) { plugin.msg(s, "只有玩家可以调整排序。"); return; }
        if (a.length < 2) { plugin.msg(s, "用法: /tb " + (up ? "moveup" : "movedown") + " <传送点名字>"); return; }
        Point pt = requirePoint(s, a);
        if (pt == null) return;
        if (plugin.store.move(p, pt, up)) {
            plugin.msg(p, "「§b" + pt.displayName() + "§f」已" + (up ? "上移" : "下移") + "。");
        } else {
            plugin.msg(p, "「§b" + pt.displayName() + "§f」已经在本组的最" + (up ? "上面" : "下面") + "了（置顶、收藏、其他分组内各自排序）。");
        }
    }

    private void showPrefs(Player p) {
        UUID u = p.getUniqueId();
        List<Point> all = plugin.store.sortedFor(u, null);
        List<Point> pins = new ArrayList<>(), favs = new ArrayList<>();
        for (Point pt : all) {
            if (plugin.store.isPinned(u, pt.id)) pins.add(pt);
            else if (plugin.store.isFavorite(u, pt.id)) favs.add(pt);
        }
        plugin.msg(p, "你的置顶 " + pins.size() + " 个、收藏 " + favs.size() + " 个。用法: /tb fav|pin|moveup|movedown <名字>");
        if (!pins.isEmpty()) {
            p.sendMessage(TeleportBeaconPlugin.text("§e置顶：§f" + joinNames(pins)));
        }
        if (!favs.isEmpty()) {
            p.sendMessage(TeleportBeaconPlugin.text("§6收藏：§f" + joinNames(favs)));
        }
        if (pins.isEmpty() && favs.isEmpty()) {
            plugin.msg(p, "§7还没有置顶或收藏。在菜单里右键收藏，Shift+左键置顶。");
        }
    }

    private static String joinNames(List<Point> pts) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) b.append("§7, §f");
            b.append(pts.get(i).displayName());
        }
        return b.toString();
    }

    private Point requirePoint(CommandSender s, String[] a) {
        String name = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
        Point pt = plugin.store.byName(name);
        if (pt == null) plugin.msg(s, "§c找不到名为「" + name + "」的传送点。试试 /tb search " + name);
        return pt;
    }

    private void lock(CommandSender s, String[] a, boolean lock) {
        if (!plugin.isAdmin(s)) { plugin.msg(s, "§c你没有权限。"); return; }
        Point pt = null;
        if (a.length >= 2) {
            String name = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
            pt = plugin.store.byName(name);
            if (pt == null) { plugin.msg(s, "§c找不到名为「" + name + "」的传送点。"); return; }
        } else if (s instanceof Player p) {
            org.bukkit.block.Block b = p.getTargetBlockExact(8);
            if (b != null && b.getType() == org.bukkit.Material.BEACON) pt = plugin.store.at(b);
            if (pt == null) { plugin.msg(s, "§c请对准一个传送信标（8 格内），或用 /tb " + (lock ? "lock" : "unlock") + " <名字>。"); return; }
        } else { plugin.msg(s, "用法: /tb " + (lock ? "lock" : "unlock") + " <传送点名字>"); return; }
        plugin.ui.setLocked(s, pt, lock);
    }

    private void listText(CommandSender s, String kw) {
        UUID viewer = s instanceof Player p ? p.getUniqueId() : null;
        List<Point> l = viewer == null
                ? plugin.store.sorted(kw == null || kw.isBlank() ? null : kw.trim())
                : plugin.store.sortedFor(viewer, kw == null || kw.isBlank() ? null : kw.trim());
        plugin.msg(s, "共 " + l.size() + " 个传送点" + (kw != null ? "（关键字: " + kw + "）" : "") + "：");
        int n = 0;
        for (Point p : l) {
            if (n++ >= 50) { s.sendMessage(TeleportBeaconPlugin.text("§7… 还有 " + (l.size() - 50) + " 个，请用 /tb search 缩小范围")); break; }
            String tags = "";
            if (viewer != null) {
                if (plugin.store.isPinned(viewer, p.id)) tags += "§e[置顶]";
                if (plugin.store.isFavorite(viewer, p.id)) tags += "§6[收藏]";
                if (!tags.isEmpty()) tags += " ";
            }
            s.sendMessage(TeleportBeaconPlugin.text(tags + "§b" + p.displayName() + (p.locked ? " §c[已锁定]" : "") + " §7| " + p.world + " " + p.coords() + " | " + p.ownerName));
        }
    }

    private void give(CommandSender s, String[] a) {
        if (!plugin.isAdmin(s)) { plugin.msg(s, "§c你没有权限。"); return; }
        if (a.length < 3) { plugin.msg(s, "用法: /tb give <玩家> <beacon|compass> [数量]"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { plugin.msg(s, "§c玩家不在线: " + a[1]); return; }
        int amt = 1;
        if (a.length > 3) {
            try { amt = Math.max(1, Math.min(64, Integer.parseInt(a[3]))); } catch (NumberFormatException ex) { plugin.msg(s, "§c数量无效。"); return; }
        }
        boolean beacon;
        switch (a[2].toLowerCase(Locale.ROOT)) {
            case "beacon", "信标" -> beacon = true;
            case "compass", "罗盘" -> beacon = false;
            default -> { plugin.msg(s, "§c类型必须是 beacon 或 compass。"); return; }
        }
        ItemStack it = beacon ? plugin.items.beacon(amt) : plugin.items.compass(amt);
        Map<Integer, ItemStack> left = t.getInventory().addItem(it);
        for (ItemStack l : left.values()) t.getWorld().dropItem(t.getLocation(), l);
        plugin.msg(s, "已给 " + t.getName() + " " + amt + " 个" + (beacon ? "传送信标" : "传送罗盘") + "。");
        if (s != t) plugin.msg(t, "管理员给了你 " + amt + " 个" + (beacon ? "传送信标" : "传送罗盘") + "。");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("search", "list", "get", "tp", "requests", "fav", "unfav", "pin", "unpin", "moveup", "movedown"));
            if (plugin.isAdmin(s)) out.addAll(List.of("give", "reload", "lock", "unlock"));
        } else if (a.length >= 2 && isPointSub(a[0], s)) {
            String typed = String.join(" ", Arrays.copyOfRange(a, 1, a.length)).toLowerCase(Locale.ROOT);
            for (Point p : plugin.store.byId.values()) {
                if (typed.isEmpty() || p.lowerName().contains(typed)) {
                    out.add(p.displayName());
                    if (out.size() >= 100) break; // Tab 补全列表没必要太长
                }
            }
            out.sort(String.CASE_INSENSITIVE_ORDER);
            return out;
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give") && plugin.isAdmin(s)) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give") && plugin.isAdmin(s)) {
            out.addAll(List.of("beacon", "compass"));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    private boolean isPointSub(String sub, CommandSender s) {
        return switch (sub.toLowerCase(Locale.ROOT)) {
            case "tp", "传送", "fav", "favorite", "收藏",
                 "unfav", "unfavorite", "取消收藏",
                 "pin", "置顶", "unpin", "取消置顶",
                 "moveup", "上移", "movedown", "下移" -> true;
            case "lock", "unlock", "锁定", "解锁" -> plugin.isAdmin(s);
            default -> false;
        };
    }
}
