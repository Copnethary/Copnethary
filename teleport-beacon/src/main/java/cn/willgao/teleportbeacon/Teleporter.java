package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 传送等同于原版 /tp：关闭界面后于下一 tick 同步 player.teleport(..., COMMAND) 一次。
 * 不做异步预加载、加载画面、冷却、移动冻结、药水效果或到达无敌。
 */
final class Teleporter {
    private static final Set<Material> BAD_FEET = EnumSet.of(Material.FIRE, Material.SOUL_FIRE, Material.LAVA,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.COBWEB, Material.NETHER_PORTAL,
            Material.END_PORTAL, Material.END_GATEWAY, Material.WITHER_ROSE, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.MAGMA_BLOCK, Material.CACTUS, Material.POINTED_DRIPSTONE);
    private static final Set<Material> BAD_FLOOR = EnumSet.of(Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POINTED_DRIPSTONE, Material.POWDER_SNOW,
            Material.HONEY_BLOCK, Material.SLIME_BLOCK, Material.FIRE, Material.SOUL_FIRE);

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
            if (r == 1) c.add(new int[]{0, 1, 0});
        }
        CANDIDATES = c.toArray(new int[0][]);
    }

    private final TeleportBeaconPlugin plugin;
    private final Set<UUID> queued = java.util.concurrent.ConcurrentHashMap.newKeySet();

    Teleporter(TeleportBeaconPlugin plugin) {
        this.plugin = plugin;
    }

    void forget(UUID u) { queued.remove(u); }

    void onDeath(Player p) { queued.remove(p.getUniqueId()); }

    void shutdown() { queued.clear(); }

    /**
     * 关闭打开的界面，下一 tick 做一次与原版 /tp 相同的同步传送。
     */
    void teleport(Player p, Point pt) {
        UUID u = p.getUniqueId();
        if (!queued.add(u)) return;
        p.closeInventory();
        World w = Bukkit.getWorld(pt.world);
        if (w == null) {
            queued.remove(u);
            plugin.msg(p, "§c该传送点所在的世界「" + pt.world + "」当前未加载。");
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                runVanillaTp(p, pt, w);
            } finally {
                queued.remove(u);
            }
        });
    }

    private void runVanillaTp(Player p, Point pt, World w) {
        if (!p.isOnline()) return;
        if (plugin.store.byId.get(pt.id) != pt) {
            plugin.msg(p, "§c该传送点刚刚被撤销了。");
            return;
        }
        Block b = w.getBlockAt(pt.x, pt.y, pt.z);
        if (b.getType() != Material.BEACON) {
            plugin.store.remove(pt);
            plugin.msg(p, "§c该传送点的信标已不存在，传送点已自动清理。");
            return;
        }
        Location dest = findSafe(b);
        if (dest == null) {
            plugin.msg(p, "§c信标周围没有安全的落脚点（被方块堵住或有岩浆/火），无法传送。");
            return;
        }
        if (p.isInsideVehicle()) p.leaveVehicle();
        // 与原版 /tp 相同：同步传送，原因 COMMAND
        boolean ok = p.teleport(dest, PlayerTeleportEvent.TeleportCause.COMMAND);
        if (!ok) {
            plugin.msg(p, "§c传送被取消了。");
            return;
        }
        p.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        plugin.msg(p, "已到达「§b" + pt.displayName() + "§f」。");
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
            return new Location(w, fx + 0.5, fy, fz + 0.5, yaw, 0f);
        }
        return null;
    }

    private static boolean okSpace(Block b) {
        Material m = b.getType();
        return !BAD_FEET.contains(m) && b.isPassable() && !b.isLiquid();
    }
}
