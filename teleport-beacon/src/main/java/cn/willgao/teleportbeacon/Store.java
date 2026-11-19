package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.text.Collator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 数据存储：data.yml。
 * 性能要点：
 *  - 变更后 2 秒合并写盘；主线程只做一次"轻量快照"（复制字段），
 *    YAML 序列化和写文件都在独立的单线程里完成，不会卡主线程，也保证写入顺序。
 *  - 名字索引 / 按世界分组索引，查重、按名字查找、标记刷新都不再全表扫描。
 */
final class Store {
    private final TeleportBeaconPlugin plugin;
    private final File file;
    final Map<String, Point> byKey = new HashMap<>();
    final Map<UUID, Point> byId = new HashMap<>();
    final Map<Integer, Request> requests = new LinkedHashMap<>();
    final Map<UUID, List<String>> notices = new HashMap<>();
    final Map<UUID, Prefs> prefs = new HashMap<>();
    /** 小写显示名 -> 传送点（理论上唯一，旧数据里可能有重名，所以用 List）。 */
    private final Map<String, List<Point>> byName = new HashMap<>();
    /** 世界名 -> 该世界的传送点（给 Markers 用）。 */
    private final Map<String, List<Point>> byWorld = new HashMap<>();
    private int nextReq = 1;
    private boolean dirty;

    /** 单线程写盘：保证旧快照不会覆盖新快照。 */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TeleportBeacon-IO");
        t.setDaemon(true);
        return t;
    });

    /** 每个玩家的偏好。置顶仍会使用；favorites/order 只读写保留，不再用于排序或界面。 */
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

    // ================================================================ 索引
    private void index(Point p) {
        byKey.put(p.key(), p);
        byId.put(p.id, p);
        byName.computeIfAbsent(p.lowerName(), k -> new ArrayList<>(1)).add(p);
        byWorld.computeIfAbsent(p.world, k -> new ArrayList<>()).add(p);
    }

    private void unindex(Point p) {
        byKey.remove(p.key());
        byId.remove(p.id);
        removeFrom(byName, p.lowerName(), p);
        removeFrom(byWorld, p.world, p);
    }

    private static void removeFrom(Map<String, List<Point>> m, String k, Point p) {
        List<Point> l = m.get(k);
        if (l == null) return;
        l.remove(p);
        if (l.isEmpty()) m.remove(k);
    }

    /** 某个世界里的全部传送点（只读视图，不要修改）。 */
    List<Point> inWorld(String world) {
        List<Point> l = byWorld.get(world);
        return l == null ? List.of() : l;
    }

    /** 改名（null = 恢复坐标名），同时维护名字索引。 */
    void rename(Point p, String newName) {
        removeFrom(byName, p.lowerName(), p);
        p.setNameRaw(newName);
        byName.computeIfAbsent(p.lowerName(), k -> new ArrayList<>(1)).add(p);
        markDirty();
    }

    // ================================================================ 读盘
    void load() {
        byKey.clear(); byId.clear(); byName.clear(); byWorld.clear();
        requests.clear(); notices.clear(); prefs.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextReq = y.getInt("next-request", 1);
        ConfigurationSection ps = y.getConfigurationSection("points");
        if (ps != null) for (String k : ps.getKeys(false)) {
            try {
                ConfigurationSection s = ps.getConfigurationSection(k);
                Point p = new Point(UUID.fromString(k), s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
                p.setNameRaw(s.getString("name"));
                String o = s.getString("owner");
                p.owner = o == null ? null : UUID.fromString(o);
                p.ownerName = s.getString("owner-name", "未知");
                p.created = s.getLong("created");
                p.prompted = s.getBoolean("prompted");
                p.locked = s.getBoolean("locked", false);
                Point old = byKey.get(p.key());
                if (old != null) unindex(old); // 同一坐标的重复数据只保留后者
                index(p);
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
                Set<UUID> seen = new HashSet<>();
                for (String id : s.getStringList("order")) {
                    try {
                        UUID u = UUID.fromString(id);
                        if (seen.add(u)) pr.order.add(u);
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

    // ================================================================ 写盘
    /** 主线程上的轻量快照：只复制基础字段，不碰 YAML。 */
    private record PointSnap(String id, String world, int x, int y, int z, String name, String owner,
                             String ownerName, long created, boolean prompted, boolean locked) { }

    private record PrefSnap(String uuid, String name, List<String> pinned, List<String> favorites, List<String> order) { }

    private record Snapshot(int nextReq, List<PointSnap> points, List<Request> requests,
                            Map<String, List<String>> notices, List<PrefSnap> prefs) { }

    private Snapshot snapshot() {
        List<PointSnap> pts = new ArrayList<>(byId.size());
        for (Point p : byId.values()) {
            pts.add(new PointSnap(p.id.toString(), p.world, p.x, p.y, p.z, p.name(),
                    p.owner == null ? null : p.owner.toString(), p.ownerName, p.created, p.prompted, p.locked));
        }
        Map<String, List<String>> ns = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<String>> e : notices.entrySet()) {
            if (!e.getValue().isEmpty()) ns.put(e.getKey().toString(), new ArrayList<>(e.getValue()));
        }
        List<PrefSnap> prs = new ArrayList<>(prefs.size());
        for (Map.Entry<UUID, Prefs> e : prefs.entrySet()) {
            Prefs pr = e.getValue();
            boolean hasName = pr.name != null && !pr.name.isEmpty();
            if (pr.isEmpty() && !hasName) continue;
            prs.add(new PrefSnap(e.getKey().toString(), hasName ? pr.name : null,
                    ids(pr.pinned), ids(pr.favorites), ids(pr.order)));
        }
        return new Snapshot(nextReq, pts, new ArrayList<>(requests.values()), ns, prs);
    }

    /** 在 IO 线程执行：构建 YAML 文本。 */
    private static String serialize(Snapshot s) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-request", s.nextReq());
        for (PointSnap p : s.points()) {
            String b = "points." + p.id();
            y.set(b + ".world", p.world()); y.set(b + ".x", p.x()); y.set(b + ".y", p.y()); y.set(b + ".z", p.z());
            y.set(b + ".name", p.name());
            y.set(b + ".owner", p.owner());
            y.set(b + ".owner-name", p.ownerName());
            y.set(b + ".created", p.created());
            y.set(b + ".prompted", p.prompted());
            if (p.locked()) y.set(b + ".locked", true);
        }
        for (Request r : s.requests()) {
            String b = "requests." + r.id;
            y.set(b + ".point", r.point.toString());
            y.set(b + ".requester", r.requester.toString());
            y.set(b + ".requester-name", r.requesterName);
            y.set(b + ".new-name", r.newName);
            y.set(b + ".time", r.time);
        }
        for (Map.Entry<String, List<String>> e : s.notices().entrySet()) y.set("notices." + e.getKey(), e.getValue());
        for (PrefSnap pr : s.prefs()) {
            String b = "prefs." + pr.uuid();
            if (pr.name() != null) y.set(b + ".name", pr.name());
            if (!pr.pinned().isEmpty()) y.set(b + ".pinned", pr.pinned());
            if (!pr.favorites().isEmpty()) y.set(b + ".favorites", pr.favorites());
            if (!pr.order().isEmpty()) y.set(b + ".order", pr.order());
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
            Snapshot snap = snapshot();
            try {
                io.execute(() -> write(serialize(snap)));
            } catch (Exception ex) { // 关服时执行器已关闭
                write(serialize(snap));
            }
        }, 40L);
    }

    /** 同步写入（关服 / 重载前）。先等 IO 线程里排队的写入完成，再写最新数据。 */
    void flush() {
        dirty = false;
        String s = serialize(snapshot());
        try {
            io.submit(() -> write(s)).get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            write(s);
        }
    }

    /** 关服时调用：写最后一次并关闭 IO 线程。 */
    void shutdown() {
        flush();
        io.shutdown();
        try { io.awaitTermination(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private synchronized void write(String s) {
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(file.getParentFile(), "data.yml.tmp");
            Files.writeString(tmp.toPath(), s, StandardCharsets.UTF_8);
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().severe("保存 data.yml 失败: " + ex);
        }
    }

    // ================================================================ 传送点
    Point at(Block b) { return byKey.get(Point.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())); }

    Point add(Block b, UUID owner, String ownerName) {
        Point p = new Point(UUID.randomUUID(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        p.owner = owner; p.ownerName = ownerName; p.created = System.currentTimeMillis();
        Point old = byKey.get(p.key());
        if (old != null) remove(old);
        index(p);
        markDirty();
        return p;
    }

    void remove(Point p) {
        if (byId.get(p.id) != p) return;
        unindex(p);
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
        List<Point> l = byName.get(name.toLowerCase(Locale.ROOT));
        if (l == null) return false;
        for (Point p : l) if (p != except) return true;
        return false;
    }

    Point byName(String name) {
        List<Point> l = byName.get(name.toLowerCase(Locale.ROOT));
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    // ================================================================ 改名申请 / 通知
    Request addRequest(Point p, UUID requester, String requesterName, String newName) {
        // 同一个人对同一信标只保留最新一条
        requests.values().removeIf(r -> r.point.equals(p.id) && r.requester.equals(requester));
        Request r = new Request(nextReq++, p.id, requester, requesterName, newName, System.currentTimeMillis());
        requests.put(r.id, r);
        markDirty();
        return r;
    }

    void removeRequest(int id) { if (requests.remove(id) != null) markDirty(); }

    /** 待处理的申请（已锁定信标的申请先保留不显示，解锁后再递送）。 */
    List<Request> requestsFor(UUID owner) {
        if (requests.isEmpty()) return List.of();
        List<Request> out = new ArrayList<>();
        for (Request r : requests.values()) {
            Point p = byId.get(r.point);
            if (p != null && !p.locked && owner.equals(p.owner)) out.add(r);
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

    // ================================================================ 排序
    // Locale.CHINA：汉字按拼音，与拉丁字母混排；PRIMARY = 忽略大小写。数字/符号通常排在字母前。
    private static final Collator NAME_COLLATOR = Collator.getInstance(Locale.CHINA);
    static {
        NAME_COLLATOR.setStrength(Collator.PRIMARY);
    }
    private static final Comparator<Point> BY_NAME = (a, b) -> {
        int c = NAME_COLLATOR.compare(a.displayName(), b.displayName());
        return c != 0 ? c : a.id.compareTo(b.id);
    };

    /** 默认排序：按名字（拼音 / 字母）。搜索时按相关度。 */
    List<Point> sorted(String filter) {
        return sortedFor(null, filter);
    }

    /**
     * 无搜索：置顶（组内按名字）→ 其余按名字。
     * 有搜索：按相关度排序，置顶不压过相关度；同相关度内按名字。
     * 收藏和自定义顺序不参与排序（数据仍保留在 data.yml）。
     */
    List<Point> sortedFor(UUID player, String filter) {
        String f = (filter == null || filter.isBlank()) ? null : filter.trim().toLowerCase(Locale.ROOT);
        List<Point> out = filtered(f);
        if (f != null) {
            out.sort((a, b) -> {
                int ra = relevance(a, f), rb = relevance(b, f);
                if (ra != rb) return Integer.compare(ra, rb);
                return BY_NAME.compare(a, b);
            });
            return out;
        }
        Prefs pr = player == null ? null : prefs.get(player);
        if (pr == null || pr.pinned.isEmpty()) {
            out.sort(BY_NAME);
            return out;
        }
        List<Point> pinned = new ArrayList<>();
        List<Point> rest = new ArrayList<>();
        for (Point pt : out) {
            if (pr.pinned.contains(pt.id)) pinned.add(pt);
            else rest.add(pt);
        }
        pinned.sort(BY_NAME);
        rest.sort(BY_NAME);
        out.clear();
        out.addAll(pinned);
        out.addAll(rest);
        return out;
    }

    /** 0=名字完全匹配，1=名字前缀，2=名字包含，3=放置者/世界包含。 */
    private static int relevance(Point p, String f) {
        String n = p.lowerName();
        if (n.equals(f)) return 0;
        if (n.startsWith(f)) return 1;
        if (n.contains(f)) return 2;
        return 3;
    }

    private List<Point> filtered(String filter) {
        if (filter == null) return new ArrayList<>(byId.values());
        List<Point> out = new ArrayList<>();
        String f = filter.toLowerCase(Locale.ROOT);
        for (Point p : byId.values()) {
            if (p.lowerName().contains(f)
                    || (p.ownerName != null && p.ownerName.toLowerCase(Locale.ROOT).contains(f))
                    || p.world.toLowerCase(Locale.ROOT).contains(f)) out.add(p);
        }
        return out;
    }

    // ================================================================ 个人偏好
    Prefs prefsOf(UUID player) {
        return prefs.computeIfAbsent(player, k -> new Prefs());
    }

    boolean isPinned(UUID player, UUID point) {
        Prefs pr = prefs.get(player);
        return pr != null && pr.pinned.contains(point);
    }

    private Prefs touch(Player p) {
        Prefs pr = prefsOf(p.getUniqueId());
        if (p.getName() != null && !p.getName().equals(pr.name)) pr.name = p.getName();
        return pr;
    }

    private static void pinFirst(Prefs pr, UUID id) {
        LinkedHashSet<UUID> next = new LinkedHashSet<>();
        next.add(id);
        next.addAll(pr.pinned);
        pr.pinned.clear();
        pr.pinned.addAll(next);
    }

    /** @return 置顶后为 true，取消置顶为 false */
    boolean togglePin(Player p, Point pt) {
        Prefs pr = touch(p);
        boolean now;
        if (pr.pinned.remove(pt.id)) {
            now = false;
        } else {
            pinFirst(pr, pt.id);
            now = true;
        }
        markDirty();
        return now;
    }

    boolean setPinned(Player p, Point pt, boolean pin) {
        Prefs pr = touch(p);
        boolean changed;
        if (pin) {
            changed = !pr.pinned.contains(pt.id);
            if (changed) pinFirst(pr, pt.id);
        } else {
            changed = pr.pinned.remove(pt.id);
        }
        if (changed) markDirty();
        return pin;
    }
}
