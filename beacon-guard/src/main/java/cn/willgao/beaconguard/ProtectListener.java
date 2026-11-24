package cn.willgao.beaconguard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.*;

/** 按保护区里每一项设置（{@link Flag}）分别拦截。管理员不受玩家类限制。 */
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

    /** 玩家行为：非管理员且该位置禁止此项时取消并提示。返回是否已取消。 */
    private boolean denyPlayer(Player p, Block b, Flag f, Cancellable e, String what) {
        if (admin(p)) return false;
        Region r = store.denying(b, f);
        if (r == null) return false;
        e.setCancelled(true);
        warnRegion(p, r, what);
        return true;
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

    // ================================================================ 玩家破坏 / 放置
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        denyPlayer(e.getPlayer(), e.getBlock(), Flag.BREAK, e, "只有管理员可以破坏方块。");
    }

    /** 中心信标被（管理员）挖掉后自动取消保护。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCenterBroken(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.BEACON) return;
        if (!plugin.getConfig().getBoolean("remove-when-beacon-broken", true)) return;
        Region r = store.centeredAt(b);
        if (r == null) return;
        if (!admin(e.getPlayer())) return; // 只有管理员能撤销保护
        store.remove(r);
        plugin.msg(e.getPlayer(), "§e保护区 #" + r.id + " 的中心信标已被挖掉，保护已取消。");
        if (r.owner != null && !r.owner.equals(e.getPlayer().getUniqueId())) {
            Player o = Bukkit.getPlayer(r.owner);
            if (o != null) plugin.msg(o, "§e你的保护区 #" + r.id + "（" + r.coords() + "）的信标被管理员 " + e.getPlayer().getName() + " 挖掉，保护已取消。");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        denyPlayer(e.getPlayer(), e.getBlockPlaced(), Flag.PLACE, e, "这里禁止普通玩家放置方块。");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        denyPlayer(e.getPlayer(), e.getBlock(), Flag.PLACE, e, "这里禁止普通玩家倒水/岩浆。");
    }

    /** 用桶舀走区内的水/岩浆/细雪也算破坏。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        denyPlayer(e.getPlayer(), e.getBlock(), Flag.BREAK, e, "只有管理员可以改动这里的方块。");
    }

    // ================================================================ 爆炸
    private Flag explosionFlag(Entity source) {
        if (source instanceof Creeper) return Flag.CREEPER;
        if (source instanceof TNTPrimed || source instanceof org.bukkit.entity.minecart.ExplosiveMinecart) return Flag.TNT;
        if (source instanceof EnderDragon) return Flag.MOB_GRIEF;
        return Flag.EXPLOSION;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        Flag f = explosionFlag(e.getEntity());
        e.blockList().removeIf(b -> store.denies(b, f));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> store.denies(b, Flag.EXPLOSION)); }

    // ================================================================ 生物 / 玩家改动方块
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Entity ent = e.getEntity();
        if (ent instanceof FallingBlock) return; // 区内沙子/砂砾正常下落
        Player p = playerOf(ent);
        if (p != null) { // 玩家去皮、锄地、射箭打碎陶罐/点燃 TNT 等
            denyPlayer(p, e.getBlock(), Flag.BREAK, e, "只有管理员可以改动这里的方块。");
            return;
        }
        // 村民种地、蜜蜂授粉、羊吃草属于正常行为，不拦截
        if (ent instanceof Villager || ent instanceof Bee || ent instanceof Sheep) return;
        // 末影人搬方块、凋灵/劫掠兽破坏、僵尸破门、蠹虫等
        if (store.denies(e.getBlock(), Flag.MOB_GRIEF)) e.setCancelled(true);
    }

    /** 玩家踩坏耕地 / 海龟蛋。 */
    @EventHandler(priority = EventPriority.LOW)
    public void onPhysical(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) return;
        Block b = e.getClickedBlock();
        if (b == null || !isTrampleable(b.getType())) return;
        if (!admin(e.getPlayer()) && store.denies(b, Flag.BREAK)) e.setCancelled(true);
    }

    /** 生物踩坏耕地 / 海龟蛋。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent e) {
        Block b = e.getBlock();
        if (isTrampleable(b.getType()) && store.denies(b, Flag.MOB_GRIEF)) e.setCancelled(true);
    }

    private static boolean isTrampleable(Material m) { return m == Material.FARMLAND || m == Material.TURTLE_EGG; }

    /** 已有告示牌被改写也算破坏。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignOpen(io.papermc.paper.event.player.PlayerOpenSignEvent e) {
        if (e.getCause() == io.papermc.paper.event.player.PlayerOpenSignEvent.Cause.PLACE) return;
        denyPlayer(e.getPlayer(), e.getSign().getBlock(), Flag.BREAK, e, "只有管理员可以修改这里的告示牌。");
    }

    // ================================================================ 火
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (store.denies(e.getBlock(), Flag.FIRE_BURN)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Player p = e.getPlayer();
        if (p != null) { denyPlayer(p, e.getBlock(), Flag.FIRE_SPREAD, e, "这里禁止点火。"); return; }
        if (store.denies(e.getBlock(), Flag.FIRE_SPREAD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        Material src = e.getSource().getType();
        if (src != Material.FIRE && src != Material.SOUL_FIRE) return;
        if (store.denies(e.getBlock(), Flag.FIRE_SPREAD)) e.setCancelled(true);
    }

    // ================================================================ 活塞 / 液体 / 发射器（区外影响区内）
    private int regionId(Block b) {
        Region r = store.at(b);
        return r == null ? -1 : r.id;
    }

    /** b 所在的保护区不是 home，且那个保护区开启了 OUTSIDE 保护。 */
    private boolean foreignProtected(Block b, int home) {
        Region r = store.at(b);
        return r != null && r.id != home && r.denies(Flag.OUTSIDE);
    }

    private boolean pistonCrossesBorder(Block piston, List<Block> blocks, BlockFace dir) {
        int home = regionId(piston);
        if (foreignProtected(piston.getRelative(dir), home)) return true;
        for (Block b : blocks) {
            if (foreignProtected(b, home)) return true;
            if (foreignProtected(b.getRelative(dir), home)) return true;
            if (foreignProtected(b.getRelative(dir.getOppositeFace()), home)) return true;
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
        if (foreignProtected(e.getToBlock(), regionId(e.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        Block b = e.getBlock();
        if (!(b.getBlockData() instanceof Directional d)) return;
        if (foreignProtected(b.getRelative(d.getFacing()), regionId(b))) e.setCancelled(true);
    }

    // ================================================================ 画 / 展示框 / 盔甲架
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (e.getCause() == HangingBreakEvent.RemoveCause.PHYSICS) return; // 背后的方块没了（它本身也受保护）
        Region r = store.denying(e.getEntity().getLocation(), Flag.DECORATION);
        if (r == null) return;
        Player p = e instanceof HangingBreakByEntityEvent be ? playerOf(be.getRemover()) : null;
        if (p != null && admin(p)) return;
        e.setCancelled(true);
        if (p != null) warnRegion(p, r, "只有管理员可以破坏这里的物品。");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        Player p = e.getPlayer();
        if (p != null) denyPlayer(p, e.getBlock(), Flag.PLACE, e, "这里禁止普通玩家放置物品。");
    }

    private static boolean isDecoration(Entity e) { return e instanceof ArmorStand || e instanceof Hanging; }

    // ================================================================ 伤害：装饰实体 / PVP / 怪物伤害
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        if (isDecoration(victim)) {
            Region r = store.denying(victim.getLocation(), Flag.DECORATION);
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
            if (store.denies(target.getLocation(), Flag.PVP) || store.denies(attacker.getLocation(), Flag.PVP)) {
                e.setCancelled(true);
                warn(attacker, "§c这个保护区禁止玩家互相攻击。");
            }
            return;
        }
        if (isMonster(damager) && store.denies(target.getLocation(), Flag.MOB_DAMAGE)) e.setCancelled(true);
    }

    /** 被火焰箭/火焰附加点燃也属于 PVP。 */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCombust(EntityCombustByEntityEvent e) {
        if (!(e.getEntity() instanceof Player target)) return;
        Player attacker = playerOf(e.getCombuster());
        if (attacker != null && !attacker.equals(target) && store.denies(target.getLocation(), Flag.PVP)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotion(PotionSplashEvent e) {
        Player attacker = playerOf(e.getPotion());
        if (attacker == null) return;
        boolean harmful = false;
        for (PotionEffect eff : e.getPotion().getEffects()) {
            if (eff.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL) { harmful = true; break; }
        }
        if (!harmful) return;
        for (LivingEntity le : e.getAffectedEntities()) {
            if (le instanceof Player t && !t.equals(attacker) && store.denies(t.getLocation(), Flag.PVP)) e.setIntensity(le, 0);
        }
    }

    /** 把伤害来源（箭、三叉戟、TNT、药水云…）追溯到玩家。 */
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
        return e instanceof Projectile pr && pr.getShooter() instanceof Enemy;
    }

    // ================================================================ 刷怪
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy)) return;
        if (!NATURAL_SPAWNS.contains(e.getSpawnReason())) return;
        if (store.denies(e.getLocation(), Flag.MOB_SPAWN)) e.setCancelled(true);
    }

    // ================================================================ 使用箱子/门/按钮/展示框/盔甲架
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() == Material.BEACON) return; // 信标交给传送信标等插件处理
        Player p = e.getPlayer();
        if (admin(p)) return;
        Region r = store.denying(b, Flag.INTERACT);
        if (r == null || !isUsable(b)) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        warnRegion(p, r, "这里禁止普通玩家使用此方块。");
    }

    private static boolean isUsable(Block b) {
        Material m = b.getType();
        if (Tag.BUTTONS.isTagged(m)) return true;
        BlockData d = b.getBlockData();
        if (d instanceof Openable || d instanceof Switch) return true;
        if (m == Material.REPEATER || m == Material.COMPARATOR || m == Material.NOTE_BLOCK
                || m == Material.DAYLIGHT_DETECTOR || m == Material.CAULDRON || m == Material.WATER_CAULDRON
                || m == Material.LAVA_CAULDRON || m == Material.POWDER_SNOW_CAULDRON
                || m == Material.COMPOSTER || m == Material.CAKE || m == Material.RESPAWN_ANCHOR
                || Tag.FLOWER_POTS.isTagged(m) || Tag.CANDLE_CAKES.isTagged(m) || Tag.BEDS.isTagged(m)) return true;
        return b.getState(false) instanceof InventoryHolder;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onUseEntity(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof ItemFrame) denyEntityUse(e.getPlayer(), e.getRightClicked(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) { denyEntityUse(e.getPlayer(), e.getRightClicked(), e); }

    private void denyEntityUse(Player p, Entity t, Cancellable e) {
        if (admin(p)) return;
        Region r = store.denying(t.getLocation(), Flag.INTERACT);
        if (r == null) return;
        e.setCancelled(true);
        warnRegion(p, r, "这里禁止普通玩家使用此物品。");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLecternTake(PlayerTakeLecternBookEvent e) {
        Player p = e.getPlayer();
        if (!admin(p) && store.denies(e.getLectern().getBlock(), Flag.INTERACT)) e.setCancelled(true);
    }
}
