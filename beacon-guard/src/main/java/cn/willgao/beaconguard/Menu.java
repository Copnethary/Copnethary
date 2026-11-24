package cn.willgao.beaconguard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/** 保护设置菜单：Java 版箱子界面，基岩版原生表单。 */
final class Menu implements Listener {
    private final BeaconGuardPlugin plugin;
    private final RegionStore store;

    Menu(BeaconGuardPlugin plugin) { this.plugin = plugin; this.store = plugin.store; }

    /** 菜单打开的目标位置（通常是信标）。 */
    record Target(String world, int x, int y, int z, String title, Runnable back) { }

    static final class Holder implements InventoryHolder {
        Inventory inv;
        Target target;
        int regionId = -1;
        boolean confirmRemove;
        final Map<Integer, Runnable> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private static final Flag[] PROTECTIONS = Arrays.stream(Flag.values()).filter(f -> f.protection).toArray(Flag[]::new);
    private static final Flag[] RULES = Arrays.stream(Flag.values()).filter(f -> !f.protection).toArray(Flag[]::new);

    private Region regionFor(Target t) {
        Region r = store.centeredAt(t.world, t.x, t.y, t.z);
        return r != null ? r : store.at(t.world, t.x, t.y, t.z);
    }

    void open(Player p, String world, int x, int y, int z, String title, Runnable back) {
        Target t = new Target(world, x, y, z, title == null || title.isBlank() ? world + " " + x + "," + y + "," + z : title, back);
        if (plugin.isBedrock(p) && openForm(p, t)) return;
        openChest(p, t);
    }

    void open(Player p, Region r, Runnable back) { open(p, r.world, r.x, r.y, r.z, null, back); }

    // ================================================================ 物品工具
    private static ItemStack item(Material m, String name, boolean glint, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(BeaconGuardPlugin.legacy(name).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) meta.lore(lore.stream().map(s -> BeaconGuardPlugin.legacy(s)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
        if (glint) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack item(Material m, String name, String... lore) { return item(m, name, false, List.of(lore)); }

    // ================================================================ Java 箱子界面
    private void openChest(Player p, Target t) {
        Holder h = new Holder();
        h.target = t;
        Region r = regionFor(t);
        int size = r == null ? 27 : 54;
        h.inv = Bukkit.createInventory(h, size, BeaconGuardPlugin.legacy(r == null ? "保护设置 · 未保护" : "保护设置 · #" + r.id));
        fill(p, h);
        p.openInventory(h.inv);
    }

    private void fill(Player p, Holder h) {
        Target t = h.target;
        Region r = regionFor(t);
        if ((r == null) != (h.inv.getSize() == 27)) { openChest(p, t); return; } // 保护状态变了，换界面
        h.inv.clear();
        h.actions.clear();
        h.regionId = r == null ? -1 : r.id;
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < h.inv.getSize(); i++) h.inv.setItem(i, filler);

        if (r == null) {
            h.inv.setItem(4, item(Material.BEACON, "§b" + t.title, "§7位置：" + t.world + " " + t.x + ", " + t.y + ", " + t.z, "§c当前未受保护"));
            if (p.hasPermission("beaconguard.use")) {
                h.inv.setItem(13, item(Material.LIME_CONCRETE, "§a§l保护此地标",
                        "§7以这里为中心 " + plugin.radius() + " 格内：",
                        "§7只有管理员能破坏方块，爆炸、苦力怕、", "§7TNT、怪物、火烧都无法破坏建筑。",
                        "", "§c注意：保护后只有管理员能撤销", "", "§e点击保护"));
                h.actions.put(13, () -> {
                    World w = Bukkit.getWorld(t.world);
                    if (w == null) { plugin.msg(p, "§c世界 " + t.world + " 未加载。"); return; }
                    plugin.protect(p, w, t.x, t.y, t.z);
                    refresh(p, h);
                });
            }
            addBack(p, h, 18);
            return;
        }

        boolean manage = plugin.canManage(p, r);
        boolean center = r.isCenter(t.world, t.x, t.y, t.z);
        List<String> info = new ArrayList<>();
        info.add("§7保护区：§e#" + r.id + (center ? "" : " §7（此位置位于该保护区内）"));
        info.add("§7主人：§f" + r.ownerName);
        info.add("§7中心：§f" + r.coords());
        info.add("§7半径：§f" + r.radius + " 格" + (store.circle ? "（圆形）" : "（正方形）"));
        info.add("§7已开启：§a" + r.enabledCount() + "§7/" + Flag.values().length + " 项");
        info.add("");
        info.add(manage ? "§a你可以修改这些设置" : "§8只有主人或管理员可以修改");
        if (manage && !plugin.isAdmin(p) && !plugin.getConfig().getBoolean("owner-can-relax-protection", false))
            info.add("§8方块保护类设置开启后只有管理员能关闭");
        h.inv.setItem(4, item(Material.BEACON, "§b" + t.title, true, info));

        h.inv.setItem(0, item(Material.IRON_PICKAXE, "§6§l方块保护 ↓", "§7防止区内建筑被破坏"));
        h.inv.setItem(27, item(Material.WRITABLE_BOOK, "§6§l区域规则 ↓", "§7区内玩家与怪物的行为"));
        for (int i = 0; i < PROTECTIONS.length; i++) placeFlag(p, h, r, PROTECTIONS[i], 9 + i, manage);
        for (int i = 0; i < RULES.length; i++) placeFlag(p, h, r, RULES[i], 28 + i, manage);

        addBack(p, h, 45);
        if (manage) {
            h.inv.setItem(47, item(Material.SHIELD, "§a全部开启方块保护", "§7一键开启所有方块保护类设置"));
            h.actions.put(47, () -> {
                for (Flag f : PROTECTIONS) plugin.setFlag(p, r, f, true);
                plugin.msg(p, "§a保护区 #" + r.id + " 已开启全部方块保护。");
                refresh(p, h);
            });
        }
        if (plugin.isAdmin(p)) {
            if (h.confirmRemove) {
                h.inv.setItem(53, item(Material.TNT, "§4§l再次点击确认取消保护", "§7保护区 #" + r.id + " 将被删除"));
                h.actions.put(53, () -> {
                    store.remove(r);
                    plugin.msg(p, "§e已取消保护区 #" + r.id + "（" + r.coords() + "）。");
                    h.confirmRemove = false;
                    refresh(p, h);
                });
            } else {
                h.inv.setItem(53, item(Material.BARRIER, "§c取消保护", "§7仅管理员可用", "§7点击后需要再点一次确认"));
                h.actions.put(53, () -> { h.confirmRemove = true; refresh(p, h); });
            }
        }
    }

    /** 图标放在 iconSlot，状态玻璃板放在它下面一行。 */
    private void placeFlag(Player p, Holder h, Region r, Flag f, int iconSlot, boolean manage) {
        boolean on = r.denies(f);
        List<String> lore = new ArrayList<>();
        for (String s : f.lore) lore.add("§7" + s);
        lore.add("");
        lore.add("当前：" + (on ? "§a已开启（禁止）" : "§c已关闭（允许）"));
        if (!manage) lore.add("§8只有主人或管理员可以修改");
        else if (on && !plugin.canRelax(p, f)) lore.add("§8开启后只有管理员可以关闭");
        else lore.add("§e点击" + (on ? "关闭" : "开启"));
        h.inv.setItem(iconSlot, item(f.icon, (on ? "§a" : "§7") + f.title, on, lore));
        h.inv.setItem(iconSlot + 9, item(on ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                on ? "§a已开启 · " + f.title : "§c已关闭 · " + f.title, false, lore));
        if (!manage) return;
        Runnable toggle = () -> {
            String err = plugin.setFlag(p, r, f, !r.denies(f));
            if (err != null) plugin.msg(p, "§c" + err);
            refresh(p, h);
        };
        h.actions.put(iconSlot, toggle);
        h.actions.put(iconSlot + 9, toggle);
    }

    private void addBack(Player p, Holder h, int slot) {
        Runnable back = h.target.back;
        if (back == null) return;
        h.inv.setItem(slot, item(Material.ARROW, "§e« 返回"));
        h.actions.put(slot, () -> { p.closeInventory(); back.run(); });
    }

    private void refresh(Player p, Holder h) {
        if (!p.isOnline()) return;
        if (p.getOpenInventory().getTopInventory().getHolder() == h) fill(p, h);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Runnable r = h.actions.get(e.getRawSlot());
        if (r == null) return;
        if (e.getRawSlot() != 53) h.confirmRemove = false;
        // 保护区在菜单打开期间被删除/替换时，先刷新再说
        Region cur = regionFor(h.target);
        if ((cur == null ? -1 : cur.id) != h.regionId) { Bukkit.getScheduler().runTask(plugin, () -> refresh(p, h)); return; }
        Bukkit.getScheduler().runTask(plugin, r);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    // ================================================================ 基岩版表单
    private boolean openForm(Player p, Target t) {
        BedrockForms f = plugin.bedrock;
        Region r = regionFor(t);
        if (r == null) {
            String content = "「" + t.title + "」\n" + t.world + " " + t.x + ", " + t.y + ", " + t.z + "\n\n当前未受保护。\n"
                    + "保护后，以这里为中心 " + plugin.radius() + " 格内只有管理员能破坏方块，爆炸、苦力怕、TNT、怪物、火烧都无法破坏建筑。\n"
                    + "注意：保护后只有管理员能撤销。";
            return f.modal(p, "保护设置", content, "保护此地标", t.back != null ? "返回" : "关闭", yes -> {
                if (yes) {
                    World w = Bukkit.getWorld(t.world);
                    if (w == null) { plugin.msg(p, "§c世界 " + t.world + " 未加载。"); return; }
                    if (plugin.protect(p, w, t.x, t.y, t.z) != null) openForm(p, t);
                } else if (t.back != null) t.back.run();
            });
        }
        boolean manage = plugin.canManage(p, r);
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(t.title).append("」\n保护区 #").append(r.id).append(" · 主人 ").append(r.ownerName)
                .append("\n中心 ").append(r.coords()).append(" · 半径 ").append(r.radius).append(" 格\n\n§l方块保护§r\n");
        for (Flag fl : PROTECTIONS) sb.append(r.denies(fl) ? "§a✔ " : "§c✘ ").append(fl.title).append("§r\n");
        sb.append("\n§l区域规则§r\n");
        for (Flag fl : RULES) sb.append(r.denies(fl) ? "§a✔ " : "§c✘ ").append(fl.title).append("§r\n");
        if (!manage) sb.append("\n只有主人或管理员可以修改。");

        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        if (manage) {
            labels.add("§l修改保护设置"); acts.add(() -> openToggles(p, t, r));
            labels.add("全部开启方块保护"); acts.add(() -> {
                for (Flag fl : PROTECTIONS) plugin.setFlag(p, r, fl, true);
                plugin.msg(p, "§a保护区 #" + r.id + " 已开启全部方块保护。");
                openForm(p, t);
            });
        }
        if (plugin.isAdmin(p)) {
            labels.add("§c取消保护（管理员）"); acts.add(() -> f.modal(p, "确认取消保护", "确定要删除保护区 #" + r.id + " 吗？", "确定删除", "返回", yes -> {
                if (yes && store.get(r.id) == r) {
                    store.remove(r);
                    plugin.msg(p, "§e已取消保护区 #" + r.id + "（" + r.coords() + "）。");
                }
                openForm(p, t);
            }));
        }
        if (t.back != null) { labels.add("返回"); acts.add(t.back); }
        return f.simple(p, "保护设置 #" + r.id, sb.toString(), labels, i -> { if (i >= 0 && i < acts.size()) acts.get(i).run(); });
    }

    private void openToggles(Player p, Target t, Region r) {
        Flag[] all = Flag.values();
        List<String> headers = new ArrayList<>(), labels = new ArrayList<>();
        List<Boolean> values = new ArrayList<>();
        boolean relaxNote = !plugin.isAdmin(p) && !plugin.getConfig().getBoolean("owner-can-relax-protection", false);
        for (Flag fl : all) {
            headers.add(fl == PROTECTIONS[0] ? "§l方块保护§r" + (relaxNote ? "\n§7（开启后只有管理员可以关闭）" : "")
                    : fl == RULES[0] ? "§l区域规则" : null);
            labels.add(fl.title);
            values.add(r.denies(fl));
        }
        boolean ok = plugin.bedrock.toggles(p, "保护设置 #" + r.id, headers, labels, values, res -> {
            if (store.get(r.id) != r) { plugin.msg(p, "§c保护区 #" + r.id + " 已不存在。"); return; }
            int changed = 0;
            for (int i = 0; i < all.length; i++) {
                boolean want = res.get(i);
                if (r.denies(all[i]) == want) continue;
                String err = plugin.setFlag(p, r, all[i], want);
                if (err != null) plugin.msg(p, "§c" + err); else changed++;
            }
            if (changed > 0) plugin.msg(p, "§a已保存保护区 #" + r.id + " 的 " + changed + " 项设置。");
            openForm(p, t);
        });
        if (!ok) openChest(p, t);
    }
}
