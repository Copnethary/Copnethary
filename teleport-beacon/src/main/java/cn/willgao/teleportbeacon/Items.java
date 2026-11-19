package cn.willgao.teleportbeacon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** 传送信标 / 传送罗盘 两种物品（用 PDC 标记）。 */
final class Items {
    private final NamespacedKey key;

    Items(TeleportBeaconPlugin plugin) { this.key = new NamespacedKey(plugin, "item"); }

    private static Component line(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    ItemStack beacon(int amount) {
        ItemStack it = new ItemStack(Material.BEACON, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.displayName(line("传送信标", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true));
        m.lore(List.of(
                line("放在重要的地方，即可创建全服共享的传送点", NamedTextColor.GRAY),
                line("右键已放置的信标：打开传送菜单 / 命名", NamedTextColor.GRAY),
                line("挖掉信标 = 撤销该传送点", NamedTextColor.GRAY),
                line("放下后上方会出现「传送点」名牌；被管理员锁定的会有红色屏障圈", NamedTextColor.GRAY),
                line("数量无限，丢了可用 /tb get 重新领取", NamedTextColor.DARK_GRAY)));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, "beacon");
        it.setItemMeta(m);
        return it;
    }

    ItemStack compass(int amount) {
        ItemStack it = new ItemStack(Material.COMPASS, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.displayName(line("传送罗盘", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true));
        m.lore(List.of(
                line("手持右键：打开全服传送点列表", NamedTextColor.GRAY),
                line("可点选传送，也可按名字搜索", NamedTextColor.GRAY),
                line("右键收藏，Shift+左键置顶，可自定义排序", NamedTextColor.GRAY),
                line("丢了可用 /tb get 重新领取", NamedTextColor.DARK_GRAY)));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, "compass");
        it.setItemMeta(m);
        return it;
    }

    private String kind(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    boolean isBeacon(ItemStack it) { return "beacon".equals(kind(it)); }
    boolean isCompass(ItemStack it) { return "compass".equals(kind(it)); }
    boolean isAny(ItemStack it) { return kind(it) != null; }

    boolean has(Player p, boolean beacon) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (beacon ? isBeacon(it) : isCompass(it)) return true;
        }
        ItemStack cur = p.getItemOnCursor();
        return beacon ? isBeacon(cur) : isCompass(cur);
    }
}
