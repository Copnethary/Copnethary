package cn.willgao.beaconguard;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

final class Region {
    final int id;
    final String world;
    final int x, y, z;
    int radius;
    final UUID owner;       // null = 控制台/服务器创建
    final String ownerName;
    final long created;
    /** true = 禁止（这项保护开启）。 */
    final Map<Flag, Boolean> deny = new EnumMap<>(Flag.class);

    Region(int id, String world, int x, int y, int z, int radius, UUID owner, String ownerName, long created) {
        this.id = id; this.world = world; this.x = x; this.y = y; this.z = z;
        this.radius = radius; this.owner = owner; this.ownerName = ownerName; this.created = created;
    }

    boolean denies(Flag f) { return deny.getOrDefault(f, f.protection); }

    int enabledCount() {
        int n = 0;
        for (Flag f : Flag.values()) if (denies(f)) n++;
        return n;
    }

    /** circle=true 圆形，否则正方形；halfHeight<0 表示整列。 */
    boolean contains(int bx, int by, int bz, boolean circle, int halfHeight) {
        int dx = bx - x, dz = bz - z;
        if (dx > radius || dx < -radius || dz > radius || dz < -radius) return false;
        if (circle && (long) dx * dx + (long) dz * dz > (long) radius * radius) return false;
        return halfHeight < 0 || Math.abs(by - y) <= halfHeight;
    }

    /** 两个保护区的水平范围是否可能重叠（保守判断，按正方形算）。 */
    boolean overlaps(String w, int ox, int oz, int oradius) {
        return world.equals(w) && Math.abs(ox - x) <= radius + oradius && Math.abs(oz - z) <= radius + oradius;
    }

    boolean isCenter(String w, int bx, int by, int bz) { return world.equals(w) && bx == x && by == y && bz == z; }

    String coords() { return world + " " + x + ", " + y + ", " + z; }
}
