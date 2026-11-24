package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * 与 BeaconGuard（信标保护）插件联动。通过反射调用它的 BeaconGuardApi，
 * 不需要编译依赖；没装或没启用 BeaconGuard 时「管理传送点」按钮不显示。
 */
final class GuardHook {
    private final TeleportBeaconPlugin plugin;
    private Plugin cached;
    private Method status, open;

    GuardHook(TeleportBeaconPlugin plugin) { this.plugin = plugin; }

    boolean available() {
        Plugin bg = Bukkit.getPluginManager().getPlugin("BeaconGuard");
        if (bg == null || !bg.isEnabled()) return false;
        if (bg != cached) {
            cached = bg;
            try {
                Class<?> c = Class.forName("cn.willgao.beaconguard.BeaconGuardApi", true, bg.getClass().getClassLoader());
                status = c.getMethod("status", Location.class);
                open = c.getMethod("openMenu", Player.class, Location.class, String.class, Runnable.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                status = open = null;
                plugin.getLogger().warning("BeaconGuard 版本不兼容，无法联动: " + e);
            }
        }
        return open != null;
    }

    private static Location loc(Point pt) {
        World w = Bukkit.getWorld(pt.world);
        return w == null ? null : new Location(w, pt.x, pt.y, pt.z);
    }

    /** 带颜色码的保护状态，不可用时返回空字符串。 */
    String status(Point pt) {
        Location l = loc(pt);
        if (l == null || !available()) return "";
        try { return (String) status.invoke(null, l); } catch (ReflectiveOperationException | RuntimeException e) { return ""; }
    }

    void open(Player p, Point pt, Runnable back) {
        Location l = loc(pt);
        if (l == null) { plugin.msg(p, "§c传送点所在的世界未加载。"); return; }
        if (!available()) { plugin.msg(p, "§c服务器没有安装信标保护插件（BeaconGuard）。"); return; }
        try { open.invoke(null, p, l, pt.displayName(), back); }
        catch (ReflectiveOperationException | RuntimeException e) {
            plugin.msg(p, "§c打开保护设置失败。");
            plugin.getLogger().warning("调用 BeaconGuard 失败: " + e);
        }
    }
}
