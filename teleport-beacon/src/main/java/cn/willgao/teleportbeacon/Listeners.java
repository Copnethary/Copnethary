package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Listeners implements Listener {
    private final TeleportBeaconPlugin plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    Listeners(TeleportBeaconPlugin plugin) { this.plugin = plugin; }

    private Point tracked(Block b) { return b.getType() == Material.BEACON ? plugin.store.at(b) : null; }

    private boolean debounce(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastClick.put(p.getUniqueId(), now);
        return last == null || now - last > 350;
    }

    // ---------------------------------------------------------------- 物品发放
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (plugin.getConfig().getBoolean("auto-give", true)) plugin.ensureItems(p, false);
            plugin.ui.onJoinDeliver(p);
        }, 60L);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        // 死亡不掉落这两种物品（反正可以重新领取）
        e.getDrops().removeIf(plugin.items::isAny);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && plugin.getConfig().getBoolean("auto-give", true)) plugin.ensureItems(p, false);
        }, 5L);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) { lastClick.remove(e.getPlayer().getUniqueId()); }

    // ---------------------------------------------------------------- 放置
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlacePerm(BlockPlaceEvent e) {
        if (!plugin.items.isBeacon(e.getItemInHand())) return;
        if (!e.getPlayer().hasPermission("teleportbeacon.place")) {
            e.setCancelled(true);
            plugin.msg(e.getPlayer(), "§c你没有放置传送信标的权限。");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack hand = e.getItemInHand();
        if (!plugin.items.isBeacon(hand)) return;
        Player p = e.getPlayer();
        Block b = e.getBlockPlaced();
        if (b.getType() != Material.BEACON) return;
        Point pt = plugin.store.add(b, p.getUniqueId(), p.getName());
        plugin.markers.refresh(pt);
        plugin.msg(p, "已创建传送点「§b" + pt.displayName() + "§f」！§7右键这个信标可命名并打开传送菜单；挖掉它即可撤销。");

        // 无限：放下后自动补回（生存模式下方块物品会被消耗）
        int amount = hand.getAmount();
        int slot = e.getHand() == EquipmentSlot.OFF_HAND ? 40 : p.getInventory().getHeldItemSlot();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            ItemStack cur = p.getInventory().getItem(slot);
            if (cur == null || cur.getType().isAir() || (plugin.items.isBeacon(cur) && cur.getAmount() < amount)) {
                p.getInventory().setItem(slot, plugin.items.beacon(amount));
            }
        });
    }

    // ---------------------------------------------------------------- 挖掉
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Point pt = tracked(e.getBlock());
        if (pt == null) return;
        if (pt.locked) {
            e.setCancelled(true);
            if (plugin.isAdmin(e.getPlayer())) plugin.msg(e.getPlayer(), "§c这个传送信标「" + pt.displayName() + "」已被锁定，无法挖掉。要挖请先解锁：对准它输入 §e/tb unlock§c，或在传送菜单里点「解锁本信标」。");
            else plugin.msg(e.getPlayer(), "§c这个传送信标已被管理员锁定，无法破坏。");
            return;
        }
        e.setDropItems(false);
        e.setExpToDrop(0);
        plugin.store.remove(pt);
        Player p = e.getPlayer();
        plugin.msg(p, "已撤销传送点「§b" + pt.displayName() + "§f」。");
        if (pt.owner != null && !pt.owner.equals(p.getUniqueId())) {
            Player o = Bukkit.getPlayer(pt.owner);
            String m = "你的传送点「" + pt.displayName() + "」被 " + p.getName() + " 挖掉了。";
            if (o != null) plugin.msg(o, "§e" + m); else plugin.store.addNotice(pt.owner, m);
        }
    }

    // ---------------------------------------------------------------- 防护：爆炸 / 活塞 / 其它方块变更
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> tracked(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> tracked(b) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) { if (anyTracked(e.getBlocks())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) { if (anyTracked(e.getBlocks())) e.setCancelled(true); }

    private boolean anyTracked(List<Block> l) {
        for (Block b : l) if (tracked(b) != null) return true;
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (tracked(e.getBlock()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (tracked(e.getBlock()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) { if (tracked(e.getBlock()) != null) e.setCancelled(true); }

    // ---------------------------------------------------------------- 右键
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.RIGHT_CLICK_AIR) return;
        Player p = e.getPlayer();
        ItemStack item = e.getItem();
        Block b = e.getClickedBlock();

        if (b != null) {
            Point pt = tracked(b);
            if (pt != null) {
                boolean vanillaPlace = p.isSneaking() && item != null && !item.getType().isAir();
                if (!vanillaPlace) {
                    e.setCancelled(true); // 不打开原版信标界面
                    if (e.getHand() != EquipmentSlot.HAND) return;
                    if (!debounce(p)) return;
                    if (!p.hasPermission("teleportbeacon.use")) { plugin.msg(p, "§c你没有使用传送信标的权限。"); return; }
                    plugin.ui.onBeaconClick(p, pt);
                    return;
                }
            }
        }
        if (item != null && plugin.items.isCompass(item)) {
            e.setCancelled(true);
            if (!debounce(p)) return;
            if (!p.hasPermission("teleportbeacon.use")) { plugin.msg(p, "§c你没有使用传送罗盘的权限。"); return; }
            plugin.ui.openMenu(p, null, 0, null);
        }
    }
}
