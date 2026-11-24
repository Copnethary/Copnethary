package cn.willgao.beaconguard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.projectiles.ProjectileSource;

import java.util.*;

/**
 * 保护区规则：
 *  - 非管理员不能以任何方式破坏方块（挖掘、装桶、踩坏耕地、斧子去皮等）。
 *  - 爆炸（苦力怕/TNT/恶魂火球/凋灵/床/重生锚/末影水晶…）、怪物改方块（末影人、凋灵、劫掠兽、破门僵尸）、
 *    火烧、活塞从外面推拉、外面的液体流进来，都无法破坏区内方块。
 *  - 画、物品展示框、盔甲架同样受保护。
 *  - pvp / fire-spread / mob-spawn / mob-damage / place / interact 可以按保护区单独开关。
 */
final class ProtectListener implements Listener {
    private final BeaconGuardPlugin plugin;
    private final RegionStore store;
    private final Map<UUID, Long> lastWarn = new HashMap<>();

    private static final Set<CreatureSpawnEvent.SpawnReason> NATURAL_SPAWNS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL, CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS, CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION, CreatureSpawnEvent.SpawnReason.RAID);

    ProtectListener(BeaconGuardPlugin plugin) {
        this.plugin = plugin;
        this.store = plugin.store;
    }

    private boolean admin(Player p) { return p.hasPermission("beaconguard.admin"); }

    private void warn(Player p, String msg) {
        long cd = plugin.getConfig().getLong("deny-message-cooldown-seconds", 2) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastWarn.get(p.getUniqueId());
        if (last != null && now - last < cd) return;
        lastWarn.put(p.getUniqueId(), now);
        plugin.msg(p, msg);
    }

    private void warnRegion(Player p, Region r, String what) {
        warn(p, "§c这里是保护区 §e#" + r.id + "§c（" + r.ownerName + " 的地标），" + what);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastWarn.remove(e.getPlayer().getUniqueId()); }

    // ================================================================ 放下信标 → 询问是否保护
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBeaconPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (b.getType() != Material.BEACON) return;
        if (!plugin.getConfig().getBoolean("prompt-on-beacon-place", true)) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("beaconguard.use")) return;
        Location loc = b.getLocation();
        // 晚 1 tick 发送，让提示出现在其它插件（如传送信标）的消息之后
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || loc.getBlock().getType() != Material.BEACON) return;
            plugin.sendProtectPrompt(p, loc.getBlock());
        });
    }

    // ================================================================ 方块：挖掘 / 放置
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Region r = store.at(e.getBlock());
        if (r == null || admin(e.getPlayer())) return;
        e.setCancelled(true);
        warnRegion(e.getPlayer(), r, "只有管理员可以破坏方块。");
    }

    /** 中心信标被（管理员）挖掉后自动取消保护。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCenterBroken(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.BEACON) return;
        if (!plugin.getConfig().getBoolean("remove-when-beacon-broken", true)) return;
        Region r = store.centeredAt(b);
        if (r == null) return;
        store.remove(r);
        plugin.msg(e.getPlayer(), "§e保护区 #" + r.id + " 的中心信标已被挖掉，保护已取消。");
        if (r.owner != null && !r.owner.equals(e.getPlayer().getUniqueId())) {
            Player o = Bukkit.getPlayer(r.owner);
            if (o != null) plugin.msg(o, "§e你的保护区 #" + r.id + "（" + r.coords() + "）的信标被 " + e.getPlayer().getName() + " 挖掉，保护已取消。");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (admin(p)) return;
        // 放置时替换掉的方块（如草、水）也算区内方块
        Region r = store.at(e.getBlockPlaced());
        if (r == null || r.allows(Flag.PLACE)) return;
        e.setCancelled(true);
        warnRegion(p, r, "这里禁止普通玩家放置方块。");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Player p = e.getPlayer();
        if (admin(p)) return;
        Region r = store.at(e.getBlock());
        if (r == null || r.allows(Flag.PLACE)) return;
        e.setCancelled(true);
        warnRegion(p, r, "这里禁止普通玩家倒水/岩浆。");
    }

    /** 用桶舀走区内的水/岩浆/细雪也算破坏。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Player p = e.getPlayer();
        if (admin(p)) return;
        Region r = store.at(e.getBlock());
        if (r == null) return;
        e.setCancelled(true);
        warnRegion(p, r, "只有管理员可以改动这里的方块。");
    }

    // ================================================================ 爆炸
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { e.blockList().removeIf(store::isProtected); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(store::isProtected); }

    // ================================================================ 生物 / 玩家改动方块
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Entity ent = e.getEntity();
        if (ent instanceof FallingBlock) return; // 区内沙子/砂砾正常下落
        Region r = store.at(e.getBlock());
        if (r == null) return;
        if (ent instanceof Player p) {
            if (admin(p)) return;
            e.setCancelled(true);
            warnRegion(p, r, "只有管理员可以改动这里的方块。");
            return;
        }
        // 村民种地、蜜蜂授粉、羊吃草属于正常行为，不拦截
        if (ent instanceof Villager || ent instanceof Bee || ent instanceof Sheep) return;
        // 末影人搬方块、凋灵/劫掠兽破坏、僵尸破门、蠹虫、射箭点燃 TNT/打碎饰纹陶罐 等
        e.setCancelled(true);
    }

    /** 玩家踩坏耕地 / 海龟蛋。 */
    @EventHandler(priority = EventPriority.LOW)
    public void onPhysical(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.PHYSICAL) return;
        Block b = e.getClickedBlock();
        if (b == null || !isTrampleable(b.getType())) return;
        if (store.isProtected(b) && !admin(e.getPlayer())) e.setCancelled(true);
    }

    /** 生物踩坏耕地 / 海龟蛋。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent e) {
        Block b = e.getBlock();
        if (isTrampleable(b.getType()) && store.isProtected(b)) e.setCancelled(true);
    }

    private static boolean isTrampleable(Material m) { return m == Material.FARMLAND || m == Material.TURTLE_EGG; }

    // ================================================================ 火
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (store.isProtected(e.getBlock())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Block b = e.getBlock();
        Region r = store.at(b);
        if (r == null || r.allows(Flag.FIRE_SPREAD)) return;
        if (e.getPlayer() != null && admin(e.getPlayer())) return;
        e.setCancelled(true);
        if (e.getPlayer() != null) warnRegion(e.getPlayer(), r, "这里禁止点火。");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getSource().getType() != Material.FIRE && e.getSource().getType() != Material.SOUL_FIRE) return;
        if (!store.allows(e.getBlock().getLocation(), Flag.FIRE_SPREAD)) e.setCancelled(true);
    }

    // ================================================================ 活塞 / 液体（防止从外面破坏）
    private int regionId(Block b) {
        Region r = store.at(b);
        return r == null ? -1 : r.id;
    }

    /** 活塞和它影响的所有方块必须处在同一个保护区（或都不在保护区），否则取消。 */
    private boolean pistonCrossesBorder(Block piston, List<Block> blocks, BlockFace dir) {
        int home = regionId(piston);
        if (regionId(piston.getRelative(dir)) != home) return true;
        for (Block b : blocks) {
            if (regionId(b) != home) return true;
            if (regionId(b.getRelative(dir)) != home) return true;
            if (regionId(b.getRelative(dir.getOppositeFace())) != home) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonCrossesBorder(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonCrossesBorder(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        int to = regionId(e.getToBlock());
        if (to != -1 && to != regionId(e.getBlock())) e.setCancelled(true);
    }

    /** 保护区外的发射器/投掷器不能往区内倒水、岩浆、放火、射 TNT。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        Block b = e.getBlock();
        if (!(b.getBlockData() instanceof org.bukkit.block.data.Directional d)) return;
        int target = regionId(b.getRelative(d.getFacing()));
        if (target != -1 && target != regionId(b)) e.setCancelled(true);
    }

    // ================================================================ 画 / 展示框 / 盔甲架
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        Region r = store.at(e.getEntity().getLocation());
        if (r == null) return;
        if (e instanceof HangingBreakByEntityEvent be) {
            Player p = playerOf(be.getRemover());
            if (p != null && admin(p)) return;
            e.setCancelled(true);
            if (p != null) warnRegion(p, r, "只有管理员可以破坏这里的物品。");
            return;
        }
        if (e.getCause() != HangingBreakEvent.RemoveCause.PHYSICS) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        Player p = e.getPlayer();
        if (p == null || admin(p)) return;
        Region r = store.at(e.getEntity().getLocation());
        if (r != null && !r.allows(Flag.PLACE)) { e.setCancelled(true); warnRegion(p, r, "这里禁止普通玩家放置物品。"); }
    }

    private static boolean isDecoration(Entity e) {
        return e instanceof ArmorStand || e instanceof Hanging;
    }

    // ================================================================ 伤害：装饰实体 / PVP / 怪物伤害
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        if (isDecoration(victim)) {
            Region r = store.at(victim.getLocation());
            if (r == null) return;
            Player p = e instanceof EntityDamageByEntityEvent be ? playerOf(be.getDamager()) : null;
            if (p != null && admin(p)) return;
            e.setCancelled(true);
            if (p != null) warnRegion(p, r, "只有管理员可以破坏这里的物品。");
            return;
        }
        if (!(victim instanceof Player target) || !(e instanceof EntityDamageByEntityEvent be)) return;
        Entity damager = be.getDamager();

        Player attacker = playerOf(damager);
        if (attacker != null) {
            if (attacker.equals(target)) return;
            if (!store.allows(target.getLocation(), Flag.PVP) || !store.allows(attacker.getLocation(), Flag.PVP)) {
                e.setCancelled(true);
                warn(attacker, "§c这个保护区禁止玩家互相攻击。");
            }
            return;
        }
        if (isMonster(damager) && !store.allows(target.getLocation(), Flag.MOB_DAMAGE)) e.setCancelled(true);
    }

    /** 被火焰箭/火焰附加点燃也属于 PVP。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent e) {
        if (!(e.getEntity() instanceof Player target)) return;
        Player attacker = playerOf(e.getCombuster());
        if (attacker != null && !attacker.equals(target) && !store.allows(target.getLocation(), Flag.PVP)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotion(PotionSplashEvent e) {
        Player attacker = playerOf(e.getPotion());
        if (attacker == null) return;
        boolean harmful = false;
        for (var eff : e.getPotion().getEffects()) {
            var c = eff.getType().getEffectCategory();
            if (c == org.bukkit.potion.PotionEffectType.Category.HARMFUL) { harmful = true; break; }
        }
        if (!harmful) return;
        for (LivingEntity le : e.getAffectedEntities()) {
            if (le instanceof Player t && !t.equals(attacker) && !store.allows(t.getLocation(), Flag.PVP)) e.setIntensity(le, 0);
        }
    }

    /** 把伤害来源（箭、三叉戟、TNT、药水…）追溯到玩家。 */
    private static Player playerOf(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            if (s instanceof Player p) return p;
        }
        if (e instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (e instanceof AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        return null;
    }

    private static boolean isMonster(Entity e) {
        if (e instanceof Enemy) return true;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Enemy) return true;
        return false;
    }

    // ================================================================ 刷怪
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy)) return;
        if (!NATURAL_SPAWNS.contains(e.getSpawnReason())) return;
        if (!store.allows(e.getLocation(), Flag.MOB_SPAWN)) e.setCancelled(true);
    }

    // ================================================================ 使用箱子/门/按钮/展示框/盔甲架
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() == Material.BEACON) return; // 信标交给传送信标等插件处理
        Player p = e.getPlayer();
        if (admin(p)) return;
        Region r = store.at(b);
        if (r == null || r.allows(Flag.INTERACT)) return;
        if (!isUsable(b)) return;
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        warnRegion(p, r, "这里禁止普通玩家使用此方块。");
    }

    private static boolean isUsable(Block b) {
        Material m = b.getType();
        if (Tag.BUTTONS.isTagged(m)) return true;
        BlockData d = b.getBlockData();
        if (d instanceof Openable || d instanceof Switch) return true;
        if (m == Material.REPEATER || m == Material.COMPARATOR || m == Material.NOTE_BLOCK
                || m == Material.DAYLIGHT_DETECTOR || m == Material.CAULDRON || m == Material.WATER_CAULDRON
                || m == Material.LAVA_CAULDRON || m == Material.POWDER_SNOW_CAULDRON || m == Material.FLOWER_POT
                || m == Material.COMPOSTER || m == Material.CAKE || m == Material.RESPAWN_ANCHOR
                || Tag.FLOWER_POTS.isTagged(m) || Tag.CANDLE_CAKES.isTagged(m) || Tag.BEDS.isTagged(m)) return true;
        return b.getState(false) instanceof InventoryHolder;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUseEntity(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (!(t instanceof ItemFrame)) return;
        denyEntityUse(e.getPlayer(), t, e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) { denyEntityUse(e.getPlayer(), e.getRightClicked(), e); }

    private void denyEntityUse(Player p, Entity t, org.bukkit.event.Cancellable e) {
        if (admin(p)) return;
        Region r = store.at(t.getLocation());
        if (r == null || r.allows(Flag.INTERACT)) return;
        e.setCancelled(true);
        warnRegion(p, r, "这里禁止普通玩家使用此物品。");
    }

    /** 已有告示牌被改写也算破坏。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignOpen(io.papermc.paper.event.player.PlayerOpenSignEvent e) {
        if (e.getCause() == io.papermc.paper.event.player.PlayerOpenSignEvent.Cause.PLACE) return;
        Player p = e.getPlayer();
        if (admin(p)) return;
        Region r = store.at(e.getSign().getBlock());
        if (r == null) return;
        e.setCancelled(true);
        warnRegion(p, r, "只有管理员可以修改这里的告示牌。");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLecternTake(PlayerTakeLecternBookEvent e) {
        Player p = e.getPlayer();
        if (admin(p)) return;
        Region r = store.at(e.getLectern().getBlock());
        if (r == null || r.allows(Flag.INTERACT)) return;
        e.setCancelled(true);
    }
}
