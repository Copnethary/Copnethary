package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** 安全传送：异步加载区块 → 校验信标还在 → 找安全落脚点 → teleportAsync。 */
final class Teleporter {
    private static final Set<Material> BAD_FEET = EnumSet.of(Material.FIRE, Material.SOUL_FIRE, Material.LAVA,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.COBWEB, Material.NETHER_PORTAL,
            Material.END_PORTAL, Material.END_GATEWAY, Material.WITHER_ROSE, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.MAGMA_BLOCK, Material.CACTUS, Material.POINTED_DRIPSTONE);
    private static final Set<Material> BAD_FLOOR = EnumSet.of(Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POINTED_DRIPSTONE, Material.POWDER_SNOW,
            Material.HONEY_BLOCK, Material.SLIME_BLOCK, Material.FIRE, Material.SOUL_FIRE);

    private final TeleportBeaconPlugin plugin;
    private final Map<UUID, Long> lastTp = new HashMap<>();

    Teleporter(TeleportBeaconPlugin plugin) { this.plugin = plugin; }

    void forget(UUID u) { lastTp.remove(u); }

    void teleport(Player p, Point pt) {
        long cdMs = plugin.getConfig().getLong("cooldown-seconds", 3) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastTp.get(p.getUniqueId());
        if (cdMs > 0 && last != null && now - last < cdMs && !plugin.isAdmin(p)) {
            plugin.msg(p, "§c传送冷却中，请 " + ((cdMs - (now - last) + 999) / 1000) + " 秒后再试。");
            return;
        }
        World w = Bukkit.getWorld(pt.world);
        if (w == null) { plugin.msg(p, "§c该传送点所在的世界「" + pt.world + "」当前未加载。"); return; }
        lastTp.put(p.getUniqueId(), now);
        plugin.msg(p, "正在传送到「§b" + pt.displayName() + "§f」…");

        int cx1 = (pt.x - 2) >> 4, cx2 = (pt.x + 2) >> 4, cz1 = (pt.z - 2) >> 4, cz2 = (pt.z + 2) >> 4;
        List<CompletableFuture<?>> fs = new ArrayList<>();
        for (int cx = cx1; cx <= cx2; cx++) for (int cz = cz1; cz <= cz2; cz++) fs.add(w.getChunkAtAsync(cx, cz));
        CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).whenComplete((v, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;
                    if (ex != null) { plugin.msg(p, "§c区块加载失败，请稍后再试。"); lastTp.remove(p.getUniqueId()); return; }
                    finish(p, pt, w);
                }));
    }

    private void finish(Player p, Point pt, World w) {
        if (plugin.store.byId.get(pt.id) != pt) { plugin.msg(p, "§c该传送点刚刚被撤销了。"); return; }
        Block b = w.getBlockAt(pt.x, pt.y, pt.z);
        if (b.getType() != Material.BEACON) {
            plugin.store.remove(pt);
            plugin.msg(p, "§c该传送点的信标已不存在，传送点已自动清理。");
            lastTp.remove(p.getUniqueId());
            return;
        }
        Location dest = findSafe(b);
        if (dest == null) {
            plugin.msg(p, "§c信标周围没有安全的落脚点（被方块堵住或有岩浆/火），无法传送。");
            lastTp.remove(p.getUniqueId());
            return;
        }
        p.leaveVehicle();
        p.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            if (ok) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    plugin.msg(p, "已到达「§b" + pt.displayName() + "§f」。");
                });
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> plugin.msg(p, "§c传送被取消了。"));
            }
        });
    }

    private Location findSafe(Block beacon) {
        World w = beacon.getWorld();
        int bx = beacon.getX(), by = beacon.getY(), bz = beacon.getZ();
        List<int[]> cands = new ArrayList<>();
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                cands.add(new int[]{dx, 0, dz});
                cands.add(new int[]{dx, 1, dz});
                cands.add(new int[]{dx, -1, dz});
            }
            if (r == 1) cands.add(new int[]{0, 1, 0}); // 信标顶上
        }
        for (int[] c : cands) {
            int fx = bx + c[0], fy = by + c[1], fz = bz + c[2];
            if (fy <= w.getMinHeight() + 1 || fy >= w.getMaxHeight() - 2) continue;
            Block feet = w.getBlockAt(fx, fy, fz);
            Block head = feet.getRelative(0, 1, 0);
            Block floor = feet.getRelative(0, -1, 0);
            if (!okSpace(feet) || !okSpace(head)) continue;
            Material fm = floor.getType();
            if (!fm.isSolid() || floor.isLiquid() || BAD_FLOOR.contains(fm) || !floor.getCollisionShape().getBoundingBoxes().iterator().hasNext()) continue;
            double dx = bx + 0.5 - (fx + 0.5), dz = bz + 0.5 - (fz + 0.5);
            float yaw = (dx == 0 && dz == 0) ? 0f : (float) Math.toDegrees(Math.atan2(-dx, dz));
            return new Location(w, fx + 0.5, fy, fz + 0.5, yaw, 10f);
        }
        return null;
    }

    private boolean okSpace(Block b) {
        Material m = b.getType();
        return b.isPassable() && !b.isLiquid() && !BAD_FEET.contains(m);
    }
}
