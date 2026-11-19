package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 传送流程（1.1.6）：
 * <ol>
 *   <li>检查冷却、是否已经在传送中。</li>
 *   <li>目标信标所在区块若没加载，用 Paper 的异步加载（紧急优先级）去读盘，主线程不等待；
 *       旧版在主线程直接读目标方块，会触发<b>同步区块加载</b>，整个服务器卡一下，
 *       玩家随后就会出现"动不了 / 交互没反应 / 移动被判定非法（moved too quickly）"。
 *       区块已加载时当场继续，不多等一个 tick。</li>
 *   <li>区块就绪后（主线程）：确认信标还在 → 找落脚点（优先站在信标正上方）→
 *       调用原版 {@code /tp} 指令：{@code execute in <维度> run minecraft:tp <玩家UUID> x y z}。
 *       指令用静默执行者发出，不会刷屏给 OP / 控制台；万一指令不可用，自动退回插件 API 传送（效果相同）。</li>
 *   <li>成功后进入冷却（默认 30 秒）并提示玩家；到达后 3 秒内不给该玩家发信标粒子，把带宽留给地形加载。</li>
 * </ol>
 * 只在主线程使用，不需要并发容器。
 */
final class Teleporter implements Listener {
    static final String BYPASS_PERM = "teleportbeacon.bypass-cooldown";
    /** 到达后这段时间内不向该玩家发送信标粒子。 */
    private static final long ARRIVAL_QUIET_MS = 3000L;

    private static final Set<Material> BAD_FEET = EnumSet.of(Material.FIRE, Material.SOUL_FIRE, Material.LAVA,
            Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.COBWEB, Material.NETHER_PORTAL,
            Material.END_PORTAL, Material.END_GATEWAY, Material.WITHER_ROSE, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.MAGMA_BLOCK, Material.CACTUS, Material.POINTED_DRIPSTONE);
    private static final Set<Material> BAD_FLOOR = EnumSet.of(Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POINTED_DRIPSTONE, Material.POWDER_SNOW,
            Material.HONEY_BLOCK, Material.SLIME_BLOCK, Material.FIRE, Material.SOUL_FIRE);

    /** 落脚点候选（相对信标）：先是信标正上方，再是周围一圈、两圈（同高 / 高一格 / 低一格）。 */
    private static final int[][] CANDIDATES;
    /** 候选点最远离信标几格（决定传送前必须加载哪些区块）。 */
    private static final int REACH = 2;
    static {
        List<int[]> c = new ArrayList<>();
        c.add(new int[]{0, 1, 0});
        for (int r = 1; r <= REACH; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                c.add(new int[]{dx, 0, dz});
                c.add(new int[]{dx, 1, dz});
                c.add(new int[]{dx, -1, dz});
            }
        }
        CANDIDATES = c.toArray(new int[0][]);
    }

    private enum Result { OK, CANCELLED, FAILED }

    private static final class Pending {
        final Point point;
        BukkitTask timeout;

        Pending(Point point) { this.point = point; }
    }

    private final TeleportBeaconPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Long> quietUntil = new HashMap<>();
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    // ---- 缓存的配置
    private int cooldownSec, warmRadius, timeoutSec;
    private boolean vanillaTp;

    // ---- 原版 /tp
    private CommandSender silent;
    private boolean silentUnavailable, warnedFallback;
    /** 原版 /tp 执行期间观察到的 PlayerTeleportEvent（同步执行，主线程字段即可）。 */
    private UUID watching;
    private boolean sawEvent, eventCancelled;

    Teleporter(TeleportBeaconPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    void reloadSettings() {
        FileConfiguration c = plugin.getConfig();
        cooldownSec = Math.max(0, c.getInt("teleport.cooldown-seconds", 30));
        vanillaTp = c.getBoolean("teleport.use-vanilla-tp", true);
        warmRadius = Math.max(0, Math.min(2, c.getInt("teleport.preload-radius", 1)));
        timeoutSec = Math.max(3, c.getInt("teleport.load-timeout-seconds", 10));
    }

    int cooldownSeconds() { return cooldownSec; }

    /** 剩余冷却秒数（向上取整）；0 = 现在可以传送。 */
    int cooldownLeft(Player p) {
        if (cooldownSec <= 0 || cooldownUntil.isEmpty()) return 0;
        UUID u = p.getUniqueId();
        Long until = cooldownUntil.get(u);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        if (left <= 0 || p.hasPermission(BYPASS_PERM)) {
            cooldownUntil.remove(u);
            return 0;
        }
        return (int) ((left + 999) / 1000);
    }

    /** 刚传送到达的几秒内为 true（Markers 据此暂不给该玩家发粒子）。 */
    boolean isQuiet(Player p) {
        if (quietUntil.isEmpty()) return false;
        Long until = quietUntil.get(p.getUniqueId());
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        quietUntil.remove(p.getUniqueId());
        return false;
    }

    /** 玩家下线：取消进行中的传送。冷却保留（防止重新登录绕过），过期后自动清掉。 */
    void forget(UUID u) {
        cancel(u);
        quietUntil.remove(u);
        lastNotice.remove(u);
        Long until = cooldownUntil.get(u);
        if (until != null && until <= System.currentTimeMillis()) cooldownUntil.remove(u);
    }

    void onDeath(Player p) { cancel(p.getUniqueId()); }

    void shutdown() {
        for (Pending pd : pending.values()) if (pd.timeout != null) pd.timeout.cancel();
        pending.clear();
        cooldownUntil.clear();
        quietUntil.clear();
        lastNotice.clear();
    }

    private void cancel(UUID u) {
        Pending pd = pending.remove(u);
        if (pd != null && pd.timeout != null) pd.timeout.cancel();
    }

    /** 冷却中连续狂点时，拒绝提示每秒最多发一次，避免刷屏。 */
    private boolean noticeAllowed(UUID u) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(u);
        if (last != null && now - last < 1000L) return false;
        lastNotice.put(u, now);
        return true;
    }

    // ================================================================ 入口
    /** 传送到某个信标（菜单点击、基岩版表单、/tb tp 都走这里，必须在主线程调用）。 */
    void teleport(Player p, Point pt) {
        UUID u = p.getUniqueId();
        if (pending.containsKey(u)) {
            if (noticeAllowed(u)) plugin.msg(p, "§7正在传送中，请稍候…");
            return;
        }
        int left = cooldownLeft(p);
        if (left > 0) {
            if (noticeAllowed(u)) {
                plugin.msg(p, "§c传送冷却中，还需 §e" + left + " §c秒才能再次传送。§7（为节省服务器资源，每次传送后需等待 "
                        + cooldownSec + " 秒）");
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            }
            return;
        }
        if (plugin.store.byId.get(pt.id) != pt) {
            plugin.msg(p, "§c该传送点刚刚被撤销了。");
            return;
        }
        World w = Bukkit.getWorld(pt.world);
        if (w == null) {
            plugin.msg(p, "§c该传送点所在的世界「" + pt.world + "」当前未加载。");
            return;
        }
        p.closeInventory();

        Pending req = new Pending(pt);
        pending.put(u, req);

        // 1) 找落脚点要读的区块（信标 ±2 格，最多 2x2 个）：没加载的异步加载并等待
        int minCx = (pt.x - REACH) >> 4, maxCx = (pt.x + REACH) >> 4;
        int minCz = (pt.z - REACH) >> 4, maxCz = (pt.z + REACH) >> 4;
        List<CompletableFuture<Chunk>> need = null;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (w.isChunkLoaded(cx, cz)) continue;
                if (need == null) need = new ArrayList<>(4);
                need.add(w.getChunkAtAsync(cx, cz, false, true));
            }
        }
        // 2) 周围一圈只"预热"、不等待：玩家落地后附近地形能马上显示（gen=false：不会生成新地形）
        int ccx = pt.chunkX(), ccz = pt.chunkZ();
        for (int cx = ccx - warmRadius; cx <= ccx + warmRadius; cx++) {
            for (int cz = ccz - warmRadius; cz <= ccz + warmRadius; cz++) {
                if (cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz) continue;
                if (!w.isChunkLoaded(cx, cz)) w.getChunkAtAsync(cx, cz, false, false);
            }
        }

        if (need == null) { // 目标区块已在内存里：当场传送
            finish(p, req, w);
            return;
        }
        p.sendActionBar(TeleportBeaconPlugin.text("§b正在传送到「" + pt.displayName() + "」…"));
        req.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.get(u) != req) return;
            pending.remove(u);
            Player q = Bukkit.getPlayer(u);
            if (q != null) plugin.msg(q, "§c目标区域加载超时，本次传送已取消（不计冷却），请稍后再试。");
        }, timeoutSec * 20L);
        CompletableFuture.allOf(need.toArray(new CompletableFuture<?>[0])).whenComplete((ok, ex) -> onMain(() -> {
            if (pending.get(u) != req) return; // 已超时 / 下线 / 死亡
            if (ex != null) {
                cancel(u);
                plugin.getLogger().warning("加载传送点「" + pt.displayName() + "」所在区块失败: " + ex);
                plugin.msg(p, "§c目标区域加载失败，请稍后再试。");
                return;
            }
            finish(p, req, w);
        }));
    }

    private void onMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    /** 区块已就绪（主线程）：校验 → 找落脚点 → 传送 → 冷却。这里读的方块都在已加载区块里，不会卡服。 */
    private void finish(Player p, Pending req, World w) {
        UUID u = p.getUniqueId();
        cancel(u);
        Point pt = req.point;
        if (!p.isOnline() || p.isDead()) return;
        if (plugin.store.byId.get(pt.id) != pt) {
            plugin.msg(p, "§c该传送点刚刚被撤销了。");
            return;
        }
        if (!w.isChunkLoaded(pt.chunkX(), pt.chunkZ())) {
            plugin.msg(p, "§c目标区域暂时无法加载，请稍后再试。");
            return;
        }
        Block b = w.getBlockAt(pt.x, pt.y, pt.z);
        if (b.getType() != Material.BEACON) {
            plugin.store.remove(pt);
            plugin.msg(p, "§c该传送点的信标已不存在，传送点已自动清理。");
            return;
        }
        Location dest = findSafe(w, pt);
        if (dest == null) {
            plugin.msg(p, "§c信标周围没有安全的落脚点（被方块堵住或有岩浆/火），无法传送。");
            return;
        }
        // 站在信标正上方时保持玩家原来的朝向；站在旁边时面向信标
        boolean keepRotation = dest.getBlockX() == pt.x && dest.getBlockZ() == pt.z;

        Result res = doTeleport(p, w, dest, keepRotation);
        if (res == Result.CANCELLED) {
            plugin.msg(p, "§c传送被取消了。");
            return;
        }
        if (res == Result.FAILED) {
            plugin.msg(p, "§c传送失败，请稍后再试。");
            return;
        }

        long now = System.currentTimeMillis();
        if (quietUntil.size() > 64) quietUntil.values().removeIf(t -> t <= now);
        quietUntil.put(u, now + ARRIVAL_QUIET_MS);
        boolean cd = cooldownSec > 0 && !p.hasPermission(BYPASS_PERM);
        if (cd) {
            if (cooldownUntil.size() > 64) cooldownUntil.values().removeIf(t -> t <= now);
            cooldownUntil.put(u, now + cooldownSec * 1000L);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        plugin.msg(p, "已到达「§b" + pt.displayName() + "§f」。"
                + (cd ? "§7为节省服务器资源，§e" + cooldownSec + " 秒§7内不能再次传送。" : ""));
    }

    // ================================================================ 执行传送
    private Result doTeleport(Player p, World w, Location dest, boolean keepRotation) {
        if (vanillaTp) {
            Result r = vanillaTp(p, w, dest, keepRotation);
            if (r != Result.FAILED) return r;
            if (!warnedFallback) {
                warnedFallback = true;
                plugin.getLogger().warning("原版 /tp 指令没有生效，已自动改用插件 API 传送（效果相同）。");
            }
        }
        if (p.isInsideVehicle()) p.leaveVehicle();
        if (keepRotation) {
            Location cur = p.getLocation();
            dest.setYaw(cur.getYaw());
            dest.setPitch(cur.getPitch());
        }
        return p.teleport(dest, PlayerTeleportEvent.TeleportCause.COMMAND) ? Result.OK : Result.CANCELLED;
    }

    /**
     * 调用原版 /tp：{@code minecraft:execute in <维度> run minecraft:tp <UUID> x y z [yaw pitch]}。
     * 用 minecraft: 前缀，不会被 Essentials 等插件的同名 /tp 覆盖；用 UUID，基岩版玩家名带前缀也没问题。
     */
    private Result vanillaTp(Player p, World w, Location dest, boolean keepRotation) {
        CommandSender s = silentSender();
        if (s == null) return Result.FAILED;
        StringBuilder cmd = new StringBuilder(160)
                .append("minecraft:execute in ").append(w.getKey().asString())
                .append(" run minecraft:tp ").append(p.getUniqueId())
                .append(' ').append(num(dest.getX()))
                .append(' ').append(num(dest.getY()))
                .append(' ').append(num(dest.getZ()));
        if (!keepRotation) cmd.append(' ').append(num(dest.getYaw())).append(' ').append(num(dest.getPitch()));
        watching = p.getUniqueId();
        sawEvent = false;
        eventCancelled = false;
        try {
            Bukkit.dispatchCommand(s, cmd.toString());
        } catch (Throwable t) {
            plugin.getLogger().warning("执行原版 /tp 出错: " + t);
            return Result.FAILED;
        } finally {
            watching = null;
        }
        if (sawEvent) return eventCancelled ? Result.CANCELLED : Result.OK;
        Location l = p.getLocation();
        return w.equals(l.getWorld()) && l.distanceSquared(dest) < 0.25 ? Result.OK : Result.FAILED;
    }

    /** 静默的指令执行者：权限同控制台，但指令反馈不发给 OP、不写控制台。 */
    private CommandSender silentSender() {
        if (silent == null && !silentUnavailable) {
            try {
                silent = Bukkit.createCommandSender(feedback -> { });
            } catch (Throwable t) {
                silentUnavailable = true;
                plugin.getLogger().warning("无法创建静默指令执行者，将使用插件 API 传送: " + t);
            }
        }
        return silent;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleportEvent(PlayerTeleportEvent e) {
        if (watching != null && watching.equals(e.getPlayer().getUniqueId())) {
            sawEvent = true;
            eventCancelled = e.isCancelled();
        }
    }

    /** 指令里的数字：始终带小数点、不用科学计数法（原版会把不带小数点的 x/z 自动 +0.5）。 */
    private static String num(double d) {
        return BigDecimal.valueOf(d).toPlainString();
    }

    // ================================================================ 落脚点
    private static Location findSafe(World w, Point pt) {
        int bx = pt.x, by = pt.y, bz = pt.z;
        int minY = w.getMinHeight() + 1, maxY = w.getMaxHeight() - 2;
        for (int[] c : CANDIDATES) {
            int fx = bx + c[0], fy = by + c[1], fz = bz + c[2];
            if (fy <= minY || fy >= maxY) continue;
            if (!w.isChunkLoaded(fx >> 4, fz >> 4)) continue; // 绝不在主线程同步加载区块
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
