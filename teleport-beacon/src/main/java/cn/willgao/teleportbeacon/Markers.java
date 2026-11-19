package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * 传送点的可见标记（Java / 基岩版通用，基岩版经 Geyser）：
 *  - 信标上方的悬浮名牌（隐形 marker 盔甲架 + 自定义名字，Geyser 对盔甲架名字支持稳定）
 *  - 普通传送点：淡青色 END_ROD 粒子；已锁定：一圈红色 DUST 粒子"屏障"
 * 盔甲架是非持久化实体（不写入存档），由定时任务按需创建/修复，因此不会重复，也不会残留。
 */
final class Markers implements Listener {
    private static final double SPACING = 0.45;
    private final TeleportBeaconPlugin plugin;
    private final NamespacedKey tagKey;
    private final Map<UUID, UUID> stands = new HashMap<>(); // 传送点 id -> 盔甲架 uuid
    private BukkitTask task;
    private int tick;

    Markers(TeleportBeaconPlugin plugin) {
        this.plugin = plugin;
        this.tagKey = new NamespacedKey(plugin, "marker");
    }

    // ---------------------------------------------------------------- 配置
    private boolean cfgBool(String k, boolean d) { return plugin.getConfig().getBoolean("marker." + k, d); }
    private double cfgNum(String k, double d) { return plugin.getConfig().getDouble("marker." + k, d); }
    private String cfgStr(String k, String d) { return plugin.getConfig().getString("marker." + k, d); }

    // ---------------------------------------------------------------- 生命周期
    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        purgeStray(null);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::onTick, 20L, 5L);
    }

    void stop() {
        if (task != null) task.cancel();
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
        boolean label = cfgBool("label", true);
        boolean heal = tick % 8 == 1; // 每 40 tick 检查/修复名牌
        int every = Math.max(1, (int) Math.round(cfgNum("particle-interval-ticks", 10) / 5.0));
        boolean pulse = tick % every == 0;
        if (!heal && !pulse) return;
        double view = cfgNum("particle-view-distance", 24);
        for (Point p : plugin.store.byId.values()) {
            World w = Bukkit.getWorld(p.world);
            if (w == null || !w.isChunkLoaded(p.x >> 4, p.z >> 4)) continue;
            if (w.getBlockAt(p.x, p.y, p.z).getType() != org.bukkit.Material.BEACON) { remove(p.id); continue; }
            if (heal) refresh(p);
            if (pulse && playerNear(w, p, view)) particles(w, p);
        }
        if (!label && heal) removeAll();
    }

    private boolean playerNear(World w, Point p, double view) {
        double r2 = view * view;
        for (Player pl : w.getPlayers()) {
            Location l = pl.getLocation();
            double dx = l.getX() - (p.x + 0.5), dy = l.getY() - p.y, dz = l.getZ() - (p.z + 0.5);
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 名牌
    String labelText(Point p) {
        String fmt = p.locked
                ? cfgStr("locked-label-format", "&c[已锁定] &6传送点：&f%name%")
                : cfgStr("label-format", "&b★ 传送点：&f%name%");
        return fmt.replace('&', '§').replace("%name%", p.displayName());
    }

    /** 创建 / 更新 / 修复某个传送点的名牌（只处理已加载区块）。 */
    void refresh(Point p) {
        if (!cfgBool("label", true)) { remove(p.id); return; }
        World w = Bukkit.getWorld(p.world);
        if (w == null || !w.isChunkLoaded(p.x >> 4, p.z >> 4)) return;
        if (w.getBlockAt(p.x, p.y, p.z).getType() != org.bukkit.Material.BEACON) { remove(p.id); return; }
        Entity e = stands.containsKey(p.id) ? Bukkit.getEntity(stands.get(p.id)) : null;
        ArmorStand as = e instanceof ArmorStand a && a.isValid() ? a : null;
        Location at = new Location(w, p.x + 0.5, p.y + cfgNum("label-height", 1.25), p.z + 0.5);
        if (as == null) {
            as = w.spawn(at, ArmorStand.class, s -> {
                s.setPersistent(false);
                s.setInvisible(true);
                s.setMarker(true);
                s.setSmall(true);
                s.setGravity(false);
                s.setInvulnerable(true);
                s.setSilent(true);
                s.setBasePlate(false);
                s.setArms(false);
                s.setCustomNameVisible(true);
                s.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, p.id.toString());
            });
            stands.put(p.id, as.getUniqueId());
        } else if (as.getLocation().distanceSquared(at) > 0.01) {
            as.teleport(at);
        }
        String txt = labelText(p);
        String cur = as.customName() == null ? null : TeleportBeaconPlugin.legacy(as.customName());
        if (!txt.equals(cur)) as.customName(TeleportBeaconPlugin.text(txt));
    }

    void remove(Point p) { remove(p.id); }

    void remove(UUID id) {
        UUID su = stands.remove(id);
        if (su == null) return;
        Entity e = Bukkit.getEntity(su);
        if (e != null) e.remove();
    }

    void removeAll() {
        for (UUID id : new ArrayList<>(stands.keySet())) remove(id);
    }

    boolean hasStand(Point p) {
        UUID su = stands.get(p.id);
        return su != null && Bukkit.getEntity(su) != null;
    }

    UUID standOf(Point p) { return stands.get(p.id); }

    // ---------------------------------------------------------------- 清理残留
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof ArmorStand && en.getPersistentDataContainer().has(tagKey, PersistentDataType.STRING)
                    && !stands.containsValue(en.getUniqueId())) en.remove();
        }
    }

    /** 移除所有带标记但不在登记表里的盔甲架（world 为 null = 所有世界）。返回数量。 */
    int purgeStray(World only) {
        int n = 0;
        for (World w : only != null ? List.of(only) : Bukkit.getWorlds()) {
            for (ArmorStand as : w.getEntitiesByClass(ArmorStand.class)) {
                if (as.getPersistentDataContainer().has(tagKey, PersistentDataType.STRING)
                        && !stands.containsValue(as.getUniqueId())) { as.remove(); n++; }
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 粒子
    private void particles(World w, Point p) {
        double cx = p.x + 0.5, cz = p.z + 0.5;
        if (p.locked) {
            if (!cfgBool("locked-ring", true)) return;
            double r = Math.max(0.6, cfgNum("locked-ring-radius", 1.5));
            int layers = Math.max(1, (int) cfgNum("locked-ring-layers", 3));
            int n = Math.max(8, (int) Math.round(2 * Math.PI * r / SPACING));
            Particle.DustOptions red = new Particle.DustOptions(Color.fromRGB(255, 20, 20), 1.3f);
            for (int l = 0; l < layers; l++) {
                double y = p.y + 0.1 + l * 0.5;
                double off = (l % 2) * Math.PI / n; // 层与层错开，更像一面墙
                for (int i = 0; i < n; i++) {
                    double a = 2 * Math.PI * i / n + off;
                    w.spawnParticle(Particle.DUST, cx + Math.cos(a) * r, y, cz + Math.sin(a) * r, 1, 0, 0, 0, 0, red);
                }
            }
        } else if (cfgBool("particles", true)) {
            Random rnd = new Random();
            for (int i = 0; i < 2; i++) {
                double a = rnd.nextDouble() * 2 * Math.PI;
                w.spawnParticle(Particle.END_ROD, cx + Math.cos(a) * 0.9, p.y + 0.2 + rnd.nextDouble() * 0.6, cz + Math.sin(a) * 0.9,
                        0, 0, 0.06, 0, 1.0);
            }
        }
    }
}
