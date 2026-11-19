package cn.willgao.teleportbeacon;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 传送点的可见标记（Java / 基岩版通用，基岩版经 Geyser）：
 *  - 信标上方的悬浮名牌（隐形 marker 盔甲架 + 自定义名字）
 *  - 普通传送点：淡青色 END_ROD 粒子；已锁定：一圈红色 DUST 粒子"屏障"
 *
 * 性能要点（相比旧版）：
 *  - 配置只在启动 / 重载时读一次并缓存，不再每 tick 反复查 config。
 *  - 只遍历"有玩家的世界"里的传送点（按世界分组索引），没人的世界零开销。
 *  - 每个世界的玩家坐标每次只取一次，不再"每个传送点复制一次玩家列表"。
 *  - 直接持有盔甲架引用（isValid() O(1)），不再 Bukkit.getEntity(uuid) 全服查找。
 *  - 名牌文字缓存，不再每 2 秒把 Component 序列化回字符串比较。
 *  - 粒子只发给可视范围内的玩家；锁定圈坐标预先算好。
 *  - 方块校验（信标是否还在）只在修复周期做，并顺带清理已消失的传送点。
 */
final class Markers implements Listener {
    private static final double SPACING = 0.45;
    private static final int TIMER_PERIOD = 5; // tick

    private final TeleportBeaconPlugin plugin;
    private final NamespacedKey tagKey;
    private final Map<UUID, ArmorStand> stands = new HashMap<>(); // 传送点 id -> 盔甲架
    private final Set<UUID> standIds = new HashSet<>();           // 盔甲架 uuid（O(1) 判断是否是我们登记的）
    private final Map<UUID, String> lastLabel = new HashMap<>();   // 传送点 id -> 当前名牌文字
    private BukkitTask task;
    private int tick;

    // ---- 缓存的配置
    private boolean label, particles, lockedRing;
    private String labelFmt, lockedFmt;
    private double labelHeight, viewDist2;
    private int pulseEvery;
    private double[] ringDx, ringDz;   // 锁定圈每个点的偏移（含层间错位，按层展开）
    private double[] ringDy;
    private Particle.DustOptions red;

    Markers(TeleportBeaconPlugin plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "marker");
        reloadSettings();
    }

    void reloadSettings() {
        FileConfiguration c = plugin.getConfig();
        label = c.getBoolean("marker.label", true);
        labelFmt = c.getString("marker.label-format", "&b★ 传送点：&f%name%").replace('&', '§');
        lockedFmt = c.getString("marker.locked-label-format", "&c[已锁定] &6传送点：&f%name%").replace('&', '§');
        labelHeight = c.getDouble("marker.label-height", 1.25);
        particles = c.getBoolean("marker.particles", true);
        lockedRing = c.getBoolean("marker.locked-ring", true);
        double view = Math.max(4, c.getDouble("marker.particle-view-distance", 24));
        viewDist2 = view * view;
        int interval = Math.max(TIMER_PERIOD, c.getInt("marker.particle-interval-ticks", 10));
        pulseEvery = Math.max(1, Math.round(interval / (float) TIMER_PERIOD));

        double r = Math.max(0.6, c.getDouble("marker.locked-ring-radius", 1.5));
        int layers = Math.max(1, Math.min(6, c.getInt("marker.locked-ring-layers", 3)));
        int n = Math.max(8, Math.min(64, (int) Math.round(2 * Math.PI * r / SPACING)));
        ringDx = new double[n * layers]; ringDz = new double[n * layers]; ringDy = new double[n * layers];
        int k = 0;
        for (int l = 0; l < layers; l++) {
            double off = (l % 2) * Math.PI / n; // 层与层错开，更像一面墙
            for (int i = 0; i < n; i++, k++) {
                double a = 2 * Math.PI * i / n + off;
                ringDx[k] = Math.cos(a) * r;
                ringDz[k] = Math.sin(a) * r;
                ringDy[k] = 0.1 + l * 0.5;
            }
        }
        red = new Particle.DustOptions(Color.fromRGB(255, 20, 20), 1.3f);
        lastLabel.clear(); // 格式可能变了，下次修复时重写名牌
    }

    // ---------------------------------------------------------------- 生命周期
    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        purgeStray(null);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::onTick, 20L, TIMER_PERIOD);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        removeAll();
        purgeStray(null);
    }

    /** 重载后：删掉已不存在的传送点的名牌，其余刷新。 */
    void syncAll() {
        for (UUID id : new ArrayList<>(stands.keySet())) if (!plugin.store.byId.containsKey(id)) remove(id);
        for (Point p : plugin.store.byId.values()) refresh(p);
    }

    private void onTick() {
        tick++;
        boolean heal = tick % 8 == 1; // 每 40 tick 检查/修复名牌
        boolean pulse = (particles || lockedRing) && tick % pulseEvery == 0;
        if (!heal && !pulse) return;
        if (heal && !label && !stands.isEmpty()) removeAll();

        List<Point> dead = null;
        for (World w : Bukkit.getWorlds()) {
            List<Point> pts = plugin.store.inWorld(w.getName());
            if (pts.isEmpty()) continue;
            List<Player> players = w.getPlayers(); // 每个世界只取一次
            if (players.isEmpty()) continue;       // 没人的世界什么都不做

            // 预取玩家坐标，避免每个传送点都 getLocation()（每次都会 new Location）
            int pc = players.size();
            double[] px = new double[pc], py = new double[pc], pz = new double[pc];
            for (int i = 0; i < pc; i++) {
                Location l = players.get(i).getLocation();
                px[i] = l.getX(); py[i] = l.getY(); pz[i] = l.getZ();
            }

            for (Point p : pts) {
                if (!w.isChunkLoaded(p.chunkX(), p.chunkZ())) continue;
                if (heal) {
                    if (w.getBlockAt(p.x, p.y, p.z).getType() != Material.BEACON) {
                        if (dead == null) dead = new ArrayList<>();
                        dead.add(p);
                        continue;
                    }
                    if (label) refresh(w, p);
                }
                if (pulse) {
                    double cx = p.x + 0.5, cz = p.z + 0.5;
                    for (int i = 0; i < pc; i++) {
                        double dx = px[i] - cx, dy = py[i] - p.y, dz = pz[i] - cz;
                        if (dx * dx + dy * dy + dz * dz <= viewDist2) particles(players.get(i), p);
                    }
                }
            }
        }
        if (dead != null) {
            for (Point p : dead) {
                plugin.getLogger().info("清理已消失的传送点: " + p.displayName());
                plugin.store.remove(p); // 会顺带移除名牌
            }
        }
    }

    // ---------------------------------------------------------------- 名牌
    String labelText(Point p) {
        return (p.locked ? lockedFmt : labelFmt).replace("%name%", p.displayName());
    }

    /** 创建 / 更新 / 修复某个传送点的名牌（只处理已加载区块）。 */
    void refresh(Point p) {
        if (!label) { remove(p.id); return; }
        World w = Bukkit.getWorld(p.world);
        if (w == null || !w.isChunkLoaded(p.chunkX(), p.chunkZ())) return;
        if (w.getBlockAt(p.x, p.y, p.z).getType() != Material.BEACON) { remove(p.id); return; }
        refresh(w, p);
    }

    private void refresh(World w, Point p) {
        ArmorStand as = stands.get(p.id);
        if (as != null && !as.isValid()) { // 区块卸载后非持久化实体会被丢弃
            standIds.remove(as.getUniqueId());
            stands.remove(p.id);
            lastLabel.remove(p.id);
            as = null;
        }
        double ax = p.x + 0.5, ay = p.y + labelHeight, az = p.z + 0.5;
        String txt = labelText(p);
        if (as == null) {
            Component name = TeleportBeaconPlugin.text(txt);
            as = w.spawn(new Location(w, ax, ay, az), ArmorStand.class, s -> {
                s.setPersistent(false);
                s.setInvisible(true);
                s.setMarker(true);
                s.setSmall(true);
                s.setGravity(false);
                s.setInvulnerable(true);
                s.setSilent(true);
                s.setBasePlate(false);
                s.setArms(false);
                s.customName(name);
                s.setCustomNameVisible(true);
                s.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, p.id.toString());
            });
            stands.put(p.id, as);
            standIds.add(as.getUniqueId());
            lastLabel.put(p.id, txt);
            return;
        }
        Location cur = as.getLocation();
        double dx = cur.getX() - ax, dy = cur.getY() - ay, dz = cur.getZ() - az;
        if (dx * dx + dy * dy + dz * dz > 0.01) as.teleport(new Location(w, ax, ay, az));
        if (!txt.equals(lastLabel.get(p.id))) {
            as.customName(TeleportBeaconPlugin.text(txt));
            lastLabel.put(p.id, txt);
        }
    }

    void remove(Point p) { remove(p.id); }

    void remove(UUID id) {
        lastLabel.remove(id);
        ArmorStand as = stands.remove(id);
        if (as == null) return;
        standIds.remove(as.getUniqueId());
        if (as.isValid()) as.remove();
    }

    void removeAll() {
        for (ArmorStand as : stands.values()) if (as.isValid()) as.remove();
        stands.clear();
        standIds.clear();
        lastLabel.clear();
    }

    boolean hasStand(Point p) {
        ArmorStand as = stands.get(p.id);
        return as != null && as.isValid();
    }

    UUID standOf(Point p) {
        ArmorStand as = stands.get(p.id);
        return as == null ? null : as.getUniqueId();
    }

    // ---------------------------------------------------------------- 清理残留
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof ArmorStand && !standIds.contains(en.getUniqueId())
                    && en.getPersistentDataContainer().has(tagKey, PersistentDataType.STRING)) en.remove();
        }
    }

    /** 移除所有带标记但不在登记表里的盔甲架（world 为 null = 所有世界）。返回数量。 */
    int purgeStray(World only) {
        int n = 0;
        for (World w : only != null ? List.of(only) : Bukkit.getWorlds()) {
            for (ArmorStand as : w.getEntitiesByClass(ArmorStand.class)) {
                if (!standIds.contains(as.getUniqueId())
                        && as.getPersistentDataContainer().has(tagKey, PersistentDataType.STRING)) { as.remove(); n++; }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 粒子（只发给这个玩家）
    private void particles(Player viewer, Point p) {
        double cx = p.x + 0.5, cz = p.z + 0.5;
        if (p.locked) {
            if (!lockedRing) return;
            for (int i = 0; i < ringDx.length; i++) {
                viewer.spawnParticle(Particle.DUST, cx + ringDx[i], p.y + ringDy[i], cz + ringDz[i], 1, 0, 0, 0, 0, red);
            }
        } else if (particles) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < 2; i++) {
                double a = rnd.nextDouble() * 2 * Math.PI;
                viewer.spawnParticle(Particle.END_ROD, cx + Math.cos(a) * 0.9, p.y + 0.2 + rnd.nextDouble() * 0.6,
                        cz + Math.sin(a) * 0.9, 0, 0, 0.06, 0, 1.0);
            }
        }
    }
}
