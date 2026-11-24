package cn.willgao.beaconguard;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 给其它插件（如 TeleportBeacon）调用的接口。对方通过反射调用，不需要编译依赖。
 * 插件未启用时所有方法都安全返回。
 */
public final class BeaconGuardApi {
    private BeaconGuardApi() { }

    public static boolean isEnabled() { return BeaconGuardPlugin.instance != null; }

    /** 某个信标位置的保护状态，带颜色码，例如「§a已保护 #3（开启 9/14 项）」。 */
    public static String status(Location beacon) {
        BeaconGuardPlugin pl = BeaconGuardPlugin.instance;
        if (pl == null || beacon.getWorld() == null) return "";
        String w = beacon.getWorld().getName();
        int x = beacon.getBlockX(), y = beacon.getBlockY(), z = beacon.getBlockZ();
        Region r = pl.store.centeredAt(w, x, y, z);
        if (r != null) return "§a已保护 #" + r.id + "（开启 " + r.enabledCount() + "/" + Flag.values().length + " 项）";
        r = pl.store.at(w, x, y, z);
        if (r != null) return "§e位于保护区 #" + r.id + "（" + r.ownerName + "）内";
        return "§7未保护";
    }

    /**
     * 打开某个信标的保护设置菜单。
     * @param title 显示用的名字（例如传送点名字）
     * @param back  点击「返回」时执行，可为 null
     */
    public static void openMenu(Player p, Location beacon, String title, Runnable back) {
        BeaconGuardPlugin pl = BeaconGuardPlugin.instance;
        if (pl == null || beacon.getWorld() == null) return;
        pl.menu.open(p, beacon.getWorld().getName(), beacon.getBlockX(), beacon.getBlockY(), beacon.getBlockZ(), title, back);
    }
}
