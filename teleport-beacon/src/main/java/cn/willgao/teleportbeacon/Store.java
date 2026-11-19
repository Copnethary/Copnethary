package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** 数据存储：data.yml（变更后 2 秒合并写盘，异步写文件）。 */
final class Store {
    private final TeleportBeaconPlugin plugin;
    private final File file;
    final Map<String, Point> byKey = new HashMap<>();
    final Map<UUID, Point> byId = new HashMap<>();
    final Map<Integer, Request> requests = new LinkedHashMap<>();
    final Map<UUID, List<String>> notices = new HashMap<>();
    final Map<UUID, Prefs> prefs = new HashMap<>();
    private int nextReq = 1;
    private boolean dirty;

    /** 每个玩家自己的置顶 / 收藏 / 自定义排序（不影响其他人）。 */
    static final class Prefs {
        String name = "";
        final LinkedHashSet<UUID> pinned = new LinkedHashSet<>();
        final LinkedHashSet<UUID> favorites = new LinkedHashSet<>();
        final ArrayList<UUID> order = new ArrayList<>();

        boolean isEmpty() { return pinned.isEmpty() && favorites.isEmpty() && order.isEmpty(); }
    }

    Store(TeleportBeaconPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    void load() {
        byKey.clear(); byId.clear(); requests.clear(); notices.clear(); prefs.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextReq = y.getInt("next-request", 1);
        ConfigurationSection ps = y.getConfigurationSection("points");
        if (ps != null) for (String k : ps.getKeys(false)) {
            try {
                ConfigurationSection s = ps.getConfigurationSection(k);
                Point p = new Point(UUID.fromString(k), s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
                p.name = s.getString("name");
                String o = s.getString("owner");
                p.owner = o == null ? null : UUID.fromString(o);
                p.ownerName = s.getString("owner-name", "未知");
                p.created = s.getLong("created");
                p.prompted = s.getBoolean("prompted");
                p.locked = s.getBoolean("locked", false);
                byKey.put(p.key(), p); byId.put(p.id, p);
            } catch (Exception ex) {
                plugin.getLogger().warning("跳过损坏的传送点 " + k + ": " + ex);
            }
        }
        ConfigurationSection rs = y.getConfigurationSection("requests");
        long now = System.currentTimeMillis();
        if (rs != null) for (String k : rs.getKeys(false)) {
            try {
                ConfigurationSection s = rs.getConfigurationSection(k);
                Request r = new Request(Integer.parseInt(k), UUID.fromString(s.getString("point")),
                        UUID.fromString(s.getString("requester")), s.getString("requester-name"),
                        s.getString("new-name"), s.getLong("time"));
                if (byId.containsKey(r.point) && now - r.time < 30L * 86400_000L) requests.put(r.id, r);
            } catch (Exception ignored) { }
        }
        ConfigurationSection ns = y.getConfigurationSection("notices");
        if (ns != null) for (String k : ns.getKeys(false)) {
            try { notices.put(UUID.fromString(k), new ArrayList<>(ns.getStringList(k))); } catch (Exception ignored) { }
        }
        ConfigurationSection prs = y.getConfigurationSection("prefs");
        if (prs != null) for (String k : prs.getKeys(false)) {
            try {
                ConfigurationSection s = prs.getConfigurationSection(k);
                Prefs pr = new Prefs();
                pr.name = s.getString("name", "");
                for (String id : s.getStringList("pinned")) tryAdd(pr.pinned, id);
                for (String id : s.getStringList("favorites")) tryAdd(pr.favorites, id);
                for (String id : s.getStringList("order")) {
                    try {
                        UUID u = UUID.fromString(id);
                        if (!pr.order.contains(u)) pr.order.add(u);
                    } catch (Exception ignored) { }
                }
                prunePrefs(pr);
                if (!pr.isEmpty() || (pr.name != null && !pr.name.isEmpty())) prefs.put(UUID.fromString(k), pr);
            } catch (Exception ignored) { }
        }
    }

    private static void tryAdd(Set<UUID> set, String id) {
        try { set.add(UUID.fromString(id)); } catch (Exception ignored) { }
    }

    private void prunePrefs(Prefs pr) {
        pr.pinned.removeIf(id -> !byId.containsKey(id));
        pr.favorites.removeIf(id -> !byId.containsKey(id));
        pr.order.removeIf(id -> !byId.containsKey(id));
    }

    private String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-request", nextReq);
        for (Point p : byId.values()) {
            String b = "points." + p.id;
            y.set(b + ".world", p.world); y.set(b + ".x", p.x); y.set(b + ".y", p.y); y.set(b + ".z", p.z);
            y.set(b + ".name", p.name);
            y.set(b + ".owner", p.owner == null ? null : p.owner.toString());
            y.set(b + ".owner-name", p.ownerName);
            y.set(b + ".created", p.created);
            y.set(b + ".prompted", p.prompted);
            if (p.locked) y.set(b + ".locked", true);
        }
        for (Request r : requests.values()) {
            String b = "requests." + r.id;
            y.set(b + ".point", r.point.toString());
            y.set(b + ".requester", r.requester.toString());
            y.set(b + ".requester-name", r.requesterName);
            y.set(b + ".new-name", r.newName);
            y.set(b + ".time", r.time);
        }
        for (Map.Entry<UUID, List<String>> e : notices.entrySet()) {
            if (!e.getValue().isEmpty()) y.set("notices." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Prefs> e : prefs.entrySet()) {
            Prefs pr = e.getValue();
            prunePrefs(pr);
            if (pr.isEmpty() && (pr.name == null || pr.name.isEmpty())) continue;
            String b = "prefs." + e.getKey();
            if (pr.name != null && !pr.name.isEmpty()) y.set(b + ".name", pr.name);
            if (!pr.pinned.isEmpty()) y.set(b + ".pinned", ids(pr.pinned));
            if (!pr.favorites.isEmpty()) y.set(b + ".favorites", ids(pr.favorites));
            if (!pr.order.isEmpty()) y.set(b + ".order", ids(pr.order));
        }
        return y.saveToString();
    }

    private static List<String> ids(Collection<UUID> c) {
        List<String> out = new ArrayList<>(c.size());
        for (UUID u : c) out.add(u.toString());
        return out;
    }

    void markDirty() {
        if (dirty) return;
        dirty = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            dirty = false;
            String s = serialize();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(s));
        }, 40L);
    }

    /** 关服时同步写入。 */
    void flush() {
        write(serialize());
    }

    private synchronized void write(String s) {
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(file.getParentFile(), "data.yml.tmp");
            Files.writeString(tmp.toPath(), s, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 data.yml 失败: " + ex);
        }
    }

    Point at(Block b) { return byKey.get(Point.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())); }

    Point add(Block b, UUID owner, String ownerName) {
        Point p = new Point(UUID.randomUUID(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        p.owner = owner; p.ownerName = ownerName; p.created = System.currentTimeMillis();
        byKey.put(p.key(), p); byId.put(p.id, p);
        markDirty();
        return p;
    }

    void remove(Point p) {
        byKey.remove(p.key()); byId.remove(p.id);
        if (plugin.markers != null) plugin.markers.remove(p);
        requests.values().removeIf(r -> r.point.equals(p.id));
        for (Prefs pr : prefs.values()) {
            pr.pinned.remove(p.id);
            pr.favorites.remove(p.id);
            pr.order.remove(p.id);
        }
        markDirty();
    }

    boolean nameTaken(String name, Point except) {
        for (Point p : byId.values()) {
            if (p != except && p.displayName().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    Point byName(String name) {
        for (Point p : byId.values()) if (p.displayName().equalsIgnoreCase(name)) return p;
        return null;
    }

    Request addRequest(Point p, UUID requester, String requesterName, String newName) {
        // 同一个人对同一信标只保留最新一条
        requests.values().removeIf(r -> r.point.equals(p.id) && r.requester.equals(requester));
        Request r = new Request(nextReq++, p.id, requester, requesterName, newName, System.currentTimeMillis());
        requests.put(r.id, r);
        markDirty();
        return r;
    }

    void removeRequest(int id) { if (requests.remove(id) != null) markDirty(); }

    List<Request> requestsFor(UUID owner) {
        List<Request> out = new ArrayList<>();
        for (Request r : requests.values()) {
            Point p = byId.get(r.point);
            if (p != null && owner.equals(p.owner)) out.add(r);
        }
        return out;
    }

    void addNotice(UUID who, String msg) {
        List<String> l = notices.computeIfAbsent(who, k -> new ArrayList<>());
        l.add(msg);
        if (l.size() > 20) l.remove(0);
        markDirty();
    }

    List<String> takeNotices(UUID who) {
        List<String> l = notices.remove(who);
        if (l != null) markDirty();
        return l == null ? List.of() : l;
    }

    /** 默认排序：按名字（控制台或没有个人偏好时）。 */
    List<Point> sorted(String filter) {
        List<Point> out = filtered(filter);
        out.sort(Comparator.comparing((Point p) -> p.displayName().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** 个人排序：置顶 → 收藏 → 自定义顺序 → 默认名字。 */
    List<Point> sortedFor(UUID player, String filter) {
        List<Point> out = filtered(filter);
        Prefs pr = prefs.get(player);
        if (pr == null) {
            out.sort(Comparator.comparing((Point p) -> p.displayName().toLowerCase(Locale.ROOT)));
            return out;
        }
        Map<UUID, Integer> orderIdx = new HashMap<>();
        for (int i = 0; i < pr.order.size(); i++) orderIdx.put(pr.order.get(i), i);
        Map<UUID, Integer> pinIdx = new HashMap<>();
        int pi = 0;
        for (UUID id : pr.pinned) pinIdx.put(id, pi++);
        out.sort(Comparator
                .comparingInt((Point p) -> group(pr, p.id))
                .thenComparingInt(p -> group(pr, p.id) == 0
                        ? pinIdx.getOrDefault(p.id, Integer.MAX_VALUE)
                        : orderIdx.getOrDefault(p.id, Integer.MAX_VALUE))
                .thenComparing(p -> p.displayName().toLowerCase(Locale.ROOT)));
        return out;
    }

    private List<Point> filtered(String filter) {
        List<Point> out = new ArrayList<>();
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        for (Point p : byId.values()) {
            if (f != null && !(p.displayName().toLowerCase(Locale.ROOT).contains(f)
                    || p.ownerName.toLowerCase(Locale.ROOT).contains(f)
                    || p.world.toLowerCase(Locale.ROOT).contains(f))) continue;
            out.add(p);
        }
        return out;
    }

    Prefs prefsOf(UUID player) {
        return prefs.computeIfAbsent(player, k -> new Prefs());
    }

    boolean isPinned(UUID player, UUID point) {
        Prefs pr = prefs.get(player);
        return pr != null && pr.pinned.contains(point);
    }

    boolean isFavorite(UUID player, UUID point) {
        Prefs pr = prefs.get(player);
        return pr != null && pr.favorites.contains(point);
    }

    private void touch(Player p) {
        Prefs pr = prefsOf(p.getUniqueId());
        if (p.getName() != null && !p.getName().equals(pr.name)) pr.name = p.getName();
    }

    /** @return 收藏后为 true，取消收藏为 false */
    boolean toggleFavorite(Player p, Point pt) {
        touch(p);
        Prefs pr = prefsOf(p.getUniqueId());
        boolean now;
        if (pr.favorites.remove(pt.id)) {
            now = false;
        } else {
            pr.favorites.add(pt.id);
            // 新收藏尽量排在收藏分组前面
            pr.order.remove(pt.id);
            pr.order.add(0, pt.id);
            now = true;
        }
        markDirty();
        return now;
    }

    boolean setFavorite(Player p, Point pt, boolean fav) {
        touch(p);
        Prefs pr = prefsOf(p.getUniqueId());
        boolean changed;
        if (fav) {
            changed = pr.favorites.add(pt.id);
            if (changed) {
                pr.order.remove(pt.id);
                pr.order.add(0, pt.id);
            }
        } else {
            changed = pr.favorites.remove(pt.id);
        }
        if (changed) markDirty();
        return fav;
    }

    /** @return 置顶后为 true，取消置顶为 false */
    boolean togglePin(Player p, Point pt) {
        touch(p);
        Prefs pr = prefsOf(p.getUniqueId());
        boolean now;
        if (pr.pinned.remove(pt.id)) {
            now = false;
        } else {
            LinkedHashSet<UUID> next = new LinkedHashSet<>();
            next.add(pt.id);
            next.addAll(pr.pinned);
            pr.pinned.clear();
            pr.pinned.addAll(next);
            now = true;
        }
        markDirty();
        return now;
    }

    boolean setPinned(Player p, Point pt, boolean pin) {
        touch(p);
        Prefs pr = prefsOf(p.getUniqueId());
        boolean changed;
        if (pin) {
            if (pr.pinned.contains(pt.id)) {
                changed = false;
            } else {
                LinkedHashSet<UUID> next = new LinkedHashSet<>();
                next.add(pt.id);
                next.addAll(pr.pinned);
                pr.pinned.clear();
                pr.pinned.addAll(next);
                changed = true;
            }
        } else {
            changed = pr.pinned.remove(pt.id);
        }
        if (changed) markDirty();
        return pin;
    }

    private static int group(Prefs pr, UUID id) {
        if (pr.pinned.contains(id)) return 0;
        if (pr.favorites.contains(id)) return 1;
        return 2;
    }

    /**
     * 在同一分组内上移或下移。
     * @return false 表示已经在该组最前/最后，无法再移动
     */
    boolean move(Player p, Point pt, boolean up) {
        touch(p);
        Prefs pr = prefsOf(p.getUniqueId());
        List<Point> list = sortedFor(p.getUniqueId(), null);
        int i = -1;
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).id.equals(pt.id)) { i = k; break; }
        }
        if (i < 0) return false;
        int j = up ? i - 1 : i + 1;
        if (j < 0 || j >= list.size()) return false;
        if (group(pr, list.get(i).id) != group(pr, list.get(j).id)) return false;
        Collections.swap(list, i, j);
        LinkedHashSet<UUID> stillPinned = new LinkedHashSet<>(pr.pinned);
        pr.order.clear();
        pr.pinned.clear();
        for (Point x : list) {
            pr.order.add(x.id);
            if (stillPinned.contains(x.id)) pr.pinned.add(x.id);
        }
        markDirty();
        return true;
    }
}
