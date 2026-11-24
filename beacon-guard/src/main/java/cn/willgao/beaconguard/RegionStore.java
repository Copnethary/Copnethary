package cn.willgao.beaconguard;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

final class RegionStore {
    private final BeaconGuardPlugin plugin;
    private final File file;
    private final Map<Integer, Region> byId = new TreeMap<>();
    private final Map<String, List<Region>> byWorld = new HashMap<>();
    private int nextId = 1;

    // 形状设置（从 config 读取，reload 时刷新）
    boolean circle;
    int halfHeight = -1;

    RegionStore(BeaconGuardPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "regions.yml");
    }

    void readShape() {
        circle = "circle".equalsIgnoreCase(plugin.getConfig().getString("shape", "square"));
        String h = plugin.getConfig().getString("height", "full");
        try { halfHeight = "full".equalsIgnoreCase(h) ? -1 : Math.max(0, Integer.parseInt(h.trim())); }
        catch (NumberFormatException e) { halfHeight = -1; }
    }

    // ------------------------------------------------------------ 查询
    Region at(String world, int x, int y, int z) {
        List<Region> l = byWorld.get(world);
        if (l == null) return null;
        for (Region r : l) if (r.contains(x, y, z, circle, halfHeight)) return r;
        return null;
    }

    Region at(Block b) { return at(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()); }

    Region at(Location l) { return at(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()); }

    boolean isProtected(Block b) { return at(b) != null; }

    /** 该位置所有覆盖它的保护区都允许此开关时返回 true（不在保护区内也返回 true）。 */
    boolean allows(Location loc, Flag f) {
        List<Region> l = byWorld.get(loc.getWorld().getName());
        if (l == null) return true;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        for (Region r : l) if (!r.allows(f) && r.contains(x, y, z, circle, halfHeight)) return false;
        return true;
    }

    Region get(int id) { return byId.get(id); }

    Collection<Region> all() { return byId.values(); }

    List<Region> ownedBy(UUID id) {
        List<Region> out = new ArrayList<>();
        for (Region r : byId.values()) if (id.equals(r.owner)) out.add(r);
        return out;
    }

    Region centeredAt(Block b) {
        List<Region> l = byWorld.get(b.getWorld().getName());
        if (l == null) return null;
        for (Region r : l) if (r.isCenter(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) return r;
        return null;
    }

    /** 返回与给定范围重叠、且主人不是 who 的第一个保护区。 */
    Region overlapping(String world, int x, int z, int radius, UUID who) {
        List<Region> l = byWorld.get(world);
        if (l == null) return null;
        for (Region r : l) if (r.overlaps(world, x, z, radius) && (who == null || !who.equals(r.owner))) return r;
        return null;
    }

    // ------------------------------------------------------------ 修改
    Region create(String world, int x, int y, int z, int radius, UUID owner, String ownerName) {
        Region r = new Region(nextId++, world, x, y, z, radius, owner, ownerName, System.currentTimeMillis());
        ConfigurationSection def = plugin.getConfig().getConfigurationSection("default-flags");
        for (Flag f : Flag.values()) r.flags.put(f, def == null || def.getBoolean(f.key, true));
        index(r);
        save();
        return r;
    }

    void remove(Region r) {
        byId.remove(r.id);
        List<Region> l = byWorld.get(r.world);
        if (l != null) { l.remove(r); if (l.isEmpty()) byWorld.remove(r.world); }
        save();
    }

    private void index(Region r) {
        byId.put(r.id, r);
        byWorld.computeIfAbsent(r.world, k -> new ArrayList<>()).add(r);
    }

    // ------------------------------------------------------------ 读写
    void load() {
        byId.clear(); byWorld.clear(); nextId = 1;
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, y.getInt("next-id", 1));
        ConfigurationSection sec = y.getConfigurationSection("regions");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(k);
            if (s == null) continue;
            try {
                int id = Integer.parseInt(k);
                String ow = s.getString("owner");
                Region r = new Region(id, s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z"),
                        s.getInt("radius", 50), ow == null || ow.isEmpty() ? null : UUID.fromString(ow),
                        s.getString("owner-name", "服务器"), s.getLong("created"));
                ConfigurationSection fs = s.getConfigurationSection("flags");
                for (Flag f : Flag.values()) r.flags.put(f, fs == null || fs.getBoolean(f.key, true));
                index(r);
                nextId = Math.max(nextId, id + 1);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "跳过损坏的保护区记录 " + k, ex);
            }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Region r : byId.values()) {
            String p = "regions." + r.id + ".";
            y.set(p + "world", r.world);
            y.set(p + "x", r.x); y.set(p + "y", r.y); y.set(p + "z", r.z);
            y.set(p + "radius", r.radius);
            y.set(p + "owner", r.owner == null ? "" : r.owner.toString());
            y.set(p + "owner-name", r.ownerName);
            y.set(p + "created", r.created);
            for (Flag f : Flag.values()) y.set(p + "flags." + f.key, r.allows(f));
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "保存 regions.yml 失败", e);
        }
    }
}
