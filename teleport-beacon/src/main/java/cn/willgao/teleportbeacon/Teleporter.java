package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 安全传送：加载区块 → 校验信标还在 → 找安全落脚点 → teleportAsync。
 *
 * 性能 / 体验要点（相比旧版）：
 *  - 目标区块已加载时直接同步完成，不再"等异步回调 + 再排队一个 tick"。
 *  - 未加载时用 Paper 的"紧急"优先级异步加载（urgent = true），玩家传送不再排在
 *    普通区块加载队列后面，服务器忙的时候也能很快传过去。
 *  - 只加载落脚点真正会用到的区块（通常 1 个，最多 4 个）。
 *  - 同一玩家传送进行中时忽略重复点击，避免连点导致排一堆区块加载任务。
 *  - 回调已经在主线程时不再额外 runTask，少一个 tick 延迟。
 */
final class Teleporter {
    private static final Set<Material> BAD_FEET = EnumSet.of(Material.FIRE, Material.SOUL_FIRE, Material.LAVA,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.COBWEB, Material.NETHER_PORTAL,
            Material.END_PORTAL, Material.END_GATEWAY, Material.WITHER_ROSE, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.MAGMA_BLOCK, Material.CACTUS, Material.POINTED_DRIPSTONE);
    private static final Set<Material> BAD_FLOOR = EnumSet.of(Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POINTED_DRIPSTONE, Material.POWDER_SNOW,
            Material.HONEY_BLOCK, Material.SLIME_BLOCK, Material.FIRE, Material.SOUL_FIRE);

    /** 候选落脚点（相对信标的偏移），按优先级排好，只算一次。 */
    private static final int[][] CANDIDATES;
    static {
        List<int[]> c = new ArrayList<>();
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                c.add(new int[]{dx, 0, dz});
                c.add(new int[]{dx, 1, dz});
                c.add(new int[]{dx, -1, dz});
            }
            if (r == 1) c.add(new int[]{0, 1, 0}); // 信标顶上
        }
        CANDIDATES = c.toArray(new int[0][]);
    }

    private final TeleportBeaconPlugin plugin;
    private final Map<UUID, Long> lastTp = new HashMap<>();
    private final Map<UUID, Long> pending = new HashMap<>(); // 玩家 -> 开始时间（15 秒后视为失效，防止卡死）
    private long cooldownMs;

    Teleporter(TeleportBeaconPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    void reloadSettings() { cooldownMs = Math.max(0, plugin.getConfig().getLong("cooldown-seconds", 3)) * 1000L; }

    void forget(UUID u) { lastTp.remove(u); pending.remove(u); }

    private void runSync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run(); else Bukkit.getScheduler().runTask(plugin, r);
    }

    void teleport(Player p, Point pt) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long since = pending.get(u);
        if (since != null && now - since < 15_000L) { plugin.msg(p, "§7正在传送中，请稍候…"); return; }
        Long last = lastTp.get(u);
        if (cooldownMs > 0 && last != null && now - last < cooldownMs && !plugin.isAdmin(p)) {
            plugin.msg(p, "§c传送冷却中，请 " + ((cooldownMs - (now - last) + 999) / 1000) + " 秒后再试。");
            return;
        }
        World w = Bukkit.getWorld(pt.world);
        if (w == null) { plugin.msg(p, "§c该传送点所在的世界「" + pt.world + "」当前未加载。"); return; }
        lastTp.put(u, now);

        int cx1 = (pt.x - 2) >> 4, cx2 = (pt.x + 2) >> 4, cz1 = (pt.z - 2) >> 4, cz2 = (pt.z + 2) >> 4;
        List<CompletableFuture<Chunk>> fs = null;
        for (int cx = cx1; cx <= cx2; cx++) for (int cz = cz1; cz <= cz2; cz++) {
            if (w.isChunkLoaded(cx, cz)) continue;
            if (fs == null) fs = new ArrayList<>(4);
            fs.add(w.getChunkAtAsync(cx, cz, true, true)); // urgent：玩家传送优先加载
        }
        if (fs == null) { // 区块都已加载：立刻传送
            finish(p, pt, w);
            return;
        }

        plugin.msg(p, "正在传送到「§b" + pt.displayName() + "§f」…");
        pending.put(u, System.currentTimeMillis());
        CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).whenComplete((v, ex) -> runSync(() -> {
            pending.remove(u);
            if (!p.isOnline()) return;
            if (ex != null) { plugin.msg(p, "§c区块加载失败，请稍后再试。"); lastTp.remove(u); return; }
            finish(p, pt, w);
        }));
    }

    private void finish(Player p, Point pt, World w) {
        UUID u = p.getUniqueId();
        if (plugin.store.byId.get(pt.id) != pt) { plugin.msg(p, "§c该传送点刚刚被撤销了。"); lastTp.remove(u); return; }
        Block b = w.getBlockAt(pt.x, pt.y, pt.z);
        if (b.getType() != Material.BEACON) {
            plugin.store.remove(pt);
            plugin.msg(p, "§c该传送点的信标已不存在，传送点已自动清理。");
            lastTp.remove(u);
            return;
        }
        Location dest = findSafe(b);
        if (dest == null) {
            plugin.msg(p, "§c信标周围没有安全的落脚点（被方块堵住或有岩浆/火），无法传送。");
            lastTp.remove(u);
            return;
        }
        if (p.isInsideVehicle()) p.leaveVehicle();
        pending.put(u, System.currentTimeMillis());
        p.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, ex) -> runSync(() -> {
            pending.remove(u);
            if (!p.isOnline()) return;
            if (ex == null && Boolean.TRUE.equals(ok)) {
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                plugin.msg(p, "已到达「§b" + pt.displayName() + "§f」。");
            } else {
                lastTp.remove(u);
                plugin.msg(p, "§c传送被取消了。");
            }
        }));
    }

    private Location findSafe(Block beacon) {
        World w = beacon.getWorld();
        int bx = beacon.getX(), by = beacon.getY(), bz = beacon.getZ();
        int minY = w.getMinHeight() + 1, maxY = w.getMaxHeight() - 2;
        for (int[] c : CANDIDATES) {
            int fx = bx + c[0], fy = by + c[1], fz = bz + c[2];
            if (fy <= minY || fy >= maxY) continue;
            Block feet = w.getBlockAt(fx, fy, fz);
            if (!okSpace(feet)) continue;
            Block head = feet.getRelative(0, 1, 0);
            if (!okSpace(head)) continue;
            Block floor = feet.getRelative(0, -1, 0);
            Material fm = floor.getType();
            if (!fm.isSolid() || BAD_FLOOR.contains(fm) || floor.isLiquid()
                    || !floor.getCollisionShape().getBoundingBoxes().iterator().hasNext()) continue;
            double dx = bx + 0.5 - (fx + 0.5), dz = bz + 0.5 - (fz + 0.5);
            float yaw = (dx == 0 && dz == 0) ? 0f : (float) Math.toDegrees(Math.atan2(-dx, dz));
            return new Location(w, fx + 0.5, fy, fz + 0.5, yaw, 10f);
        }
        return null;
    }

    private static boolean okSpace(Block b) {
        Material m = b.getType();
        return !BAD_FEET.contains(m) && b.isPassable() && !b.isLiquid();
    }
}
