package cn.willgao.teleportbeacon;

import java.util.UUID;

/** 一个传送点（对应世界里一个已放置的信标方块）。 */
final class Point {
    final UUID id;
    final String world;
    final int x, y, z;
    String name;          // null = 使用坐标名
    UUID owner;
    String ownerName;
    long created;
    boolean prompted;     // 放置者是否已被提示过命名
    boolean locked;       // 管理员锁定：任何人都不能挖，也不能随意改名

    Point(UUID id, String world, int x, int y, int z) {
        this.id = id; this.world = world; this.x = x; this.y = y; this.z = z;
    }

    String key() { return key(world, x, y, z); }

    static String key(String world, int x, int y, int z) { return world + ";" + x + ";" + y + ";" + z; }

    String coords() { return x + "," + y + "," + z; }

    String defaultName() { return world + " " + coords(); }

    String displayName() { return name != null ? name : defaultName(); }
}
