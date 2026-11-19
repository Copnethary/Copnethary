package cn.willgao.teleportbeacon;

import java.text.CollationKey;
import java.util.Locale;
import java.util.UUID;

/** 一个传送点（对应世界里一个已放置的信标方块）。 */
final class Point {
    final UUID id;
    final String world;
    final int x, y, z;
    final String key;     // 预先算好，避免每次查找都拼字符串
    private String name;  // null = 使用坐标名
    UUID owner;
    String ownerName;
    long created;
    boolean prompted;     // 放置者是否已被提示过命名
    boolean locked;       // 管理员锁定：任何人都不能挖，也不能随意改名

    // 缓存：显示名 / 小写显示名（排序、搜索、查重时不再反复 toLowerCase）
    private String display;
    private String lowerDisplay;
    /** 拼音/字母排序键：改名时算一次，排序时只比较字节，不再每次比较都跑 Collator。 */
    private CollationKey sortKey;

    Point(UUID id, String world, int x, int y, int z) {
        this.id = id; this.world = world; this.x = x; this.y = y; this.z = z;
        this.key = key(world, x, y, z);
        recompute();
    }

    String key() { return key; }

    static String key(String world, int x, int y, int z) { return world + ";" + x + ";" + y + ";" + z; }

    int chunkX() { return x >> 4; }
    int chunkZ() { return z >> 4; }

    String coords() { return x + "," + y + "," + z; }

    String defaultName() { return world + " " + coords(); }

    String name() { return name; }

    /** 只能由 Store.rename / Store.load 调用，以保证名字索引同步。 */
    void setNameRaw(String n) { this.name = n; recompute(); }

    private void recompute() {
        display = name != null ? name : defaultName();
        lowerDisplay = display.toLowerCase(Locale.ROOT);
        sortKey = Store.NAME_COLLATOR.getCollationKey(display);
    }

    CollationKey sortKey() { return sortKey; }

    String displayName() { return display; }

    String lowerName() { return lowerDisplay; }
}
