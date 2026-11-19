package cn.willgao.teleportbeacon;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 菜单（Java 箱子界面 / 基岩版表单）、文字输入、改名与改名申请。 */
final class Ui implements Listener {
    static final int PAGE_CHEST = 45, PAGE_FORM = 30;

    private final TeleportBeaconPlugin plugin;
    final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    record Prompt(Consumer<String> cb, BukkitTask timeout) { }

    Ui(TeleportBeaconPlugin plugin) {
        this.plugin = plugin;
        buildStaticButtons();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------ 文本工具
    static String worldLabel(String w) {
        World world = Bukkit.getWorld(w);
        if (world == null) return w + "（未加载）";
        return switch (world.getEnvironment()) {
            case NORMAL -> "主世界 (" + w + ")";
            case NETHER -> "下界 (" + w + ")";
            case THE_END -> "末地 (" + w + ")";
            default -> w;
        };
    }

    private static Material icon(Point p) {
        World w = Bukkit.getWorld(p.world);
        if (w == null) return Material.BEACON;
        return switch (w.getEnvironment()) {
            case NORMAL -> Material.GRASS_BLOCK;
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.BEACON;
        };
    }

    private static Component l(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------ 菜单
    void openMenu(Player p, String filter, int page, Point source) {
        // 旧版每次打开菜单都会全表扫描所有传送点并逐个读方块（cleanup），
        // 现在由 Markers 的定时修复顺带清理，打开菜单不再有这笔开销。
        if (source != null && plugin.store.byId.get(source.id) != source) source = null;
        String f = (filter == null || filter.isBlank()) ? null : filter.trim();
        List<Point> all = plugin.store.sortedFor(p.getUniqueId(), f);
        if (plugin.isBedrock(p)) {
            if (openForm(p, f, page, source, all)) return;
        }
        openChest(p, f, page, source, all);
    }

    private boolean canRenameDirect(Player p, Point src) {
        return plugin.isAdmin(p) || p.getUniqueId().equals(src.owner);
    }

    private boolean openForm(Player p, String f, int page, Point source, List<Point> all) {
        int pages = Math.max(1, (all.size() + PAGE_FORM - 1) / PAGE_FORM);
        int pg = Math.min(Math.max(0, page), pages - 1);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("§l查找传送点"); acts.add(() -> askSearch(p, source));
        if (f != null) { labels.add("清除搜索（显示全部）"); acts.add(() -> openMenu(p, null, 0, source)); }
        if (source != null) {
            labels.add(source.locked ? "§7重命名本信标（已锁定，解锁后才能改名）"
                    : canRenameDirect(p, source) ? "§l重命名本信标" : "§l申请重命名本信标");
            acts.add(() -> startRename(p, source));
        }
        if (source != null && plugin.isAdmin(p)) {
            labels.add(source.locked ? "§c解锁本信标" : "§c锁定本信标");
            acts.add(() -> setLocked(p, source, !source.locked));
        }
        labels.add("领取传送信标 / 罗盘"); acts.add(() -> plugin.ensureItems(p, true));
        labels.add("管理置顶"); acts.add(() -> openPinForm(p, 0, source));
        if (pg > 0) { labels.add("« 上一页"); acts.add(() -> openMenu(p, f, pg - 1, source)); }
        int from = pg * PAGE_FORM, to = Math.min(all.size(), from + PAGE_FORM);
        for (int i = from; i < to; i++) {
            Point pt = all.get(i);
            labels.add(formLabel(p, pt));
            acts.add(() -> plugin.teleporter.teleport(p, pt));
        }
        if (pg < pages - 1) { labels.add("下一页 »"); acts.add(() -> openMenu(p, f, pg + 1, source)); }
        int cd = plugin.teleporter.cooldownLeft(p);
        String content = (f == null ? "共 " + all.size() + " 个传送点" : "搜索「" + f + "」：找到 " + all.size() + " 个")
                + "，第 " + (pg + 1) + "/" + pages + " 页。点击名字即可传送。"
                + (f == null ? "置顶的会排在前面（按拼音/字母排序）。" : "搜索结果按相关度排序。")
                + (cd > 0 ? "\n§c传送冷却中：还需 " + cd + " 秒§r" : "")
                + (source != null ? "\n当前信标：" + source.displayName() : "");
        return plugin.bedrock.simple(p, "传送信标", content, labels, idx -> {
            if (idx >= 0 && idx < acts.size()) acts.get(idx).run();
        });
    }

    private void askSearch(Player p, Point source) {
        askText(p, "查找传送点", "输入要搜索的名字关键字（也匹配放置者、世界）", "", "", s -> {
            if (s.isBlank()) { openMenu(p, null, 0, source); return; }
            openMenu(p, s, 0, source);
        });
    }

    private String formLabel(Player p, Point pt) {
        boolean pinned = plugin.store.isPinned(p.getUniqueId(), pt.id);
        String tags = pinned ? "§e★ " : "";
        return tags + "§l" + pt.displayName() + "§r" + (pt.locked ? " §c[已锁定]§r" : "")
                + "\n§8" + worldLabel(pt.world) + " " + pt.coords() + " · " + pt.ownerName;
    }

    /** 基岩版：管理置顶（主列表点击仍是传送）。 */
    private void openPinForm(Player p, int page, Point source) {
        List<Point> all = plugin.store.sortedFor(p.getUniqueId(), null);
        int pages = Math.max(1, (all.size() + PAGE_FORM - 1) / PAGE_FORM);
        int pg = Math.min(Math.max(0, page), pages - 1);
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add("返回传送菜单"); acts.add(() -> openMenu(p, null, 0, source));
        if (pg > 0) { labels.add("« 上一页"); acts.add(() -> openPinForm(p, pg - 1, source)); }
        int from = pg * PAGE_FORM, to = Math.min(all.size(), from + PAGE_FORM);
        UUID uid = p.getUniqueId();
        for (int i = from; i < to; i++) {
            Point pt = all.get(i);
            boolean pinned = plugin.store.isPinned(uid, pt.id);
            labels.add((pinned ? "§e★ 取消置顶 · " : "置顶 · ") + "§l" + pt.displayName());
            acts.add(() -> {
                boolean now = plugin.store.togglePin(p, pt);
                plugin.msg(p, now ? "已置顶「§b" + pt.displayName() + "§f」。" : "已取消置顶「§b" + pt.displayName() + "§f」。");
                openPinForm(p, pg, source);
            });
        }
        if (pg < pages - 1) { labels.add("下一页 »"); acts.add(() -> openPinForm(p, pg + 1, source)); }
        String content = "点击传送点即可置顶或取消置顶，只对你自己生效。置顶的会排在传送列表最前面。\n第 "
                + (pg + 1) + "/" + pages + " 页，共 " + all.size() + " 个。";
        if (!plugin.bedrock.simple(p, "管理置顶", content, labels, idx -> {
            if (idx >= 0 && idx < acts.size()) acts.get(idx).run();
        })) openMenu(p, null, 0, source);
    }

    // Java 箱子界面
    static final class MenuHolder implements InventoryHolder {
        Inventory inv;
        String filter; int page, pages; Point source;
        final Map<Integer, Point> slots = new HashMap<>();
        final Map<Integer, Runnable> buttons = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    // 固定按钮只创建一次（setItem 会复制，复用是安全的）
    private ItemStack filler, searchBtn, clearBtn, getBeaconBtn, getCompassBtn, prevBtn, nextBtn;

    private void buildStaticButtons() {
        filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        searchBtn = button(Material.OAK_SIGN, "查找传送点", NamedTextColor.GREEN, "按名字搜索（也匹配放置者、世界）", "点击后在聊天栏输入关键字");
        clearBtn = button(Material.BARRIER, "清除搜索", NamedTextColor.RED);
        getBeaconBtn = button(Material.BEACON, "领取传送信标", NamedTextColor.AQUA, "数量不限，没有就补发");
        getCompassBtn = button(Material.COMPASS, "领取传送罗盘", NamedTextColor.GOLD, "数量不限，没有就补发");
        prevBtn = button(Material.ARROW, "« 上一页", NamedTextColor.YELLOW);
        nextBtn = button(Material.ARROW, "下一页 »", NamedTextColor.YELLOW);
    }

    private ItemStack button(Material m, String name, NamedTextColor c, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(l(name, c));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(s -> l(s, NamedTextColor.GRAY)).toList());
        it.setItemMeta(meta);
        return it;
    }

    private void openChest(Player p, String f, int page, Point source, List<Point> all) {
        int pages = Math.max(1, (all.size() + PAGE_CHEST - 1) / PAGE_CHEST);
        int pg = Math.min(Math.max(0, page), pages - 1);
        MenuHolder h = new MenuHolder();
        h.filter = f; h.page = pg; h.source = source; h.pages = pages;
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("传送信标 " + (pg + 1) + "/" + pages
                + (f != null ? " · 搜索:" + f : "")));
        h.inv = inv;
        fillChest(p, h, all);
        p.openInventory(inv);
    }

    /** 原地刷新已打开的箱子菜单（置顶切换后用），不重新创建、不重新打开界面。 */
    private void refreshChest(Player p, MenuHolder h) {
        if (h.source != null && plugin.store.byId.get(h.source.id) != h.source) h.source = null;
        List<Point> all = plugin.store.sortedFor(p.getUniqueId(), h.filter);
        int pages = Math.max(1, (all.size() + PAGE_CHEST - 1) / PAGE_CHEST);
        if (pages != h.pages || h.page >= pages) { // 页数变了，标题需要更新，只能重开
            openChest(p, h.filter, h.page, h.source, all);
            return;
        }
        h.inv.clear();
        h.slots.clear();
        h.buttons.clear();
        fillChest(p, h, all);
    }

    private void fillChest(Player p, MenuHolder h, List<Point> all) {
        Inventory inv = h.inv;
        String f = h.filter; int pg = h.page, pages = h.pages; Point source = h.source;
        UUID uid = p.getUniqueId();
        Store.Prefs prefs = plugin.store.prefs.get(uid);
        int from = pg * PAGE_CHEST, to = Math.min(all.size(), from + PAGE_CHEST);
        for (int i = from; i < to; i++) {
            Point pt = all.get(i);
            int slot = i - from;
            ItemStack it = new ItemStack(icon(pt));
            ItemMeta m = it.getItemMeta();
            boolean pinned = prefs != null && prefs.pinned.contains(pt.id);
            String title = (pinned ? "★ " : "") + pt.displayName();
            m.displayName(l(title, pinned ? NamedTextColor.YELLOW : NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true));
            if (pinned) m.setEnchantmentGlintOverride(true);
            List<Component> lore = new ArrayList<>();
            lore.add(l("世界：" + worldLabel(pt.world), NamedTextColor.GRAY));
            lore.add(l("坐标：" + pt.x + ", " + pt.y + ", " + pt.z, NamedTextColor.GRAY));
            lore.add(l("放置者：" + pt.ownerName, NamedTextColor.GRAY));
            if (pt.locked) lore.add(l("【已锁定】管理员锁定，无法被挖掉，也不能改名", NamedTextColor.RED));
            if (pinned) lore.add(l("已置顶（对你自己生效）", NamedTextColor.YELLOW));
            lore.add(Component.empty());
            lore.add(l("左键：传送", NamedTextColor.GREEN));
            lore.add(l("Shift+左键：" + (pinned ? "取消置顶" : "置顶"), NamedTextColor.YELLOW));
            m.lore(lore);
            it.setItemMeta(m);
            inv.setItem(slot, it);
            h.slots.put(slot, pt);
        }
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);
        if (pg > 0) { inv.setItem(45, prevBtn); h.buttons.put(45, () -> openMenu(p, f, pg - 1, source)); }
        inv.setItem(46, searchBtn);
        h.buttons.put(46, () -> askSearch(p, source));
        if (f != null) { inv.setItem(47, clearBtn); h.buttons.put(47, () -> openMenu(p, null, 0, source)); }
        if (source != null) {
            boolean direct = canRenameDirect(p, source);
            if (source.locked) {
                inv.setItem(48, button(Material.BARRIER, "重命名本信标（已锁定）", NamedTextColor.RED,
                        "当前名字：" + source.displayName(), "已被管理员锁定，任何人都不能改名", "解锁后才能改名"));
            } else {
                inv.setItem(48, button(Material.NAME_TAG, direct ? "重命名本信标" : "申请重命名本信标", NamedTextColor.GOLD,
                        "当前名字：" + source.displayName(), direct ? "点击后在聊天栏输入新名字" : "需要放置者(" + source.ownerName + ")同意"));
            }
            h.buttons.put(48, () -> startRename(p, source));
        }
        if (source != null && plugin.isAdmin(p)) {
            inv.setItem(52, button(source.locked ? Material.IRON_DOOR : Material.IRON_BARS,
                    source.locked ? "解锁本信标" : "锁定本信标", NamedTextColor.RED,
                    source.locked ? "当前：已锁定（无法被挖掉）" : "锁定后任何人都挖不掉，怪物和爆炸也破坏不了",
                    "仅管理员可见"));
            h.buttons.put(52, () -> setLocked(p, source, !source.locked));
        }
        String pageInfo = "第 " + (pg + 1) + "/" + pages + " 页 · 共 " + all.size() + " 个";
        int cd = plugin.teleporter.cooldownLeft(p);
        inv.setItem(49, cd > 0
                ? button(Material.CLOCK, pageInfo, NamedTextColor.WHITE, "传送冷却中：还需 " + cd + " 秒",
                        "为节省服务器资源，每次传送后需等待 " + plugin.teleporter.cooldownSeconds() + " 秒")
                : button(Material.PAPER, pageInfo, NamedTextColor.WHITE));
        inv.setItem(50, getBeaconBtn);
        h.buttons.put(50, () -> plugin.ensureItems(p, true));
        inv.setItem(51, getCompassBtn);
        h.buttons.put(51, () -> plugin.ensureItems(p, true));
        if (pg < pages - 1) { inv.setItem(53, nextBtn); h.buttons.put(53, () -> openMenu(p, f, pg + 1, source)); }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof MenuHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        Point pt = h.slots.get(slot);
        Runnable r = h.buttons.get(slot);
        if (pt != null) {
            ClickType click = e.getClick();
            if (click == ClickType.SHIFT_LEFT) {
                boolean now = plugin.store.togglePin(p, pt);
                plugin.msg(p, now ? "已置顶「§b" + pt.displayName() + "§f」。" : "已取消置顶「§b" + pt.displayName() + "§f」。");
                Bukkit.getScheduler().runTask(plugin, () -> reopenIfStillOpen(p, h));
            } else {
                // 冷却 / 正在传送 等检查都在 Teleporter 里：通过检查才关闭界面；冷却中菜单保留，聊天栏提示剩余秒数
                Bukkit.getScheduler().runTask(plugin, () -> plugin.teleporter.teleport(p, pt));
            }
        } else if (r != null) {
            // 先关闭再执行（执行里会重新打开界面/要求聊天输入）
            Bukkit.getScheduler().runTask(plugin, () -> { p.closeInventory(); r.run(); });
        }
    }

    private void reopenIfStillOpen(Player p, MenuHolder h) {
        if (!p.isOnline()) return;
        if (p.getOpenInventory().getTopInventory().getHolder() == h) refreshChest(p, h);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------ 文字输入
    /** 基岩版：CustomForm 输入框；Java：聊天输入。回调在主线程。 */
    void askText(Player p, String title, String hint, String placeholder, String def, Consumer<String> cb) {
        if (plugin.isBedrock(p)) {
            if (plugin.bedrock.input(p, title, hint, placeholder, def, cb)) return;
        }
        cancelPrompt(p.getUniqueId());
        int secs = plugin.getConfig().getInt("input-timeout-seconds", 60);
        UUID u = p.getUniqueId();
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Prompt pr = prompts.remove(u);
            if (pr != null) { Player q = Bukkit.getPlayer(u); if (q != null) plugin.msg(q, "§7输入已超时，已取消。"); }
        }, secs * 20L);
        prompts.put(u, new Prompt(cb, t));
        plugin.msg(p, "§e" + title + "：§f" + hint);
        plugin.msg(p, "§7请直接在聊天栏发送内容（发送 §c取消§7 放弃，" + secs + " 秒内有效）");
    }

    private void cancelPrompt(UUID u) {
        Prompt old = prompts.remove(u);
        if (old != null) old.timeout().cancel();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        Prompt pr = prompts.remove(u);
        if (pr == null) return;
        e.setCancelled(true);
        pr.timeout().cancel();
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (text.equals("取消") || text.equalsIgnoreCase("cancel")) {
                plugin.msg(e.getPlayer(), "§7已取消。");
                return;
            }
            pr.cb().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancelPrompt(e.getPlayer().getUniqueId());
        plugin.teleporter.forget(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ 点击信标
    void onBeaconClick(Player p, Point pt) {
        if (!pt.locked && p.getUniqueId().equals(pt.owner) && pt.name() == null && !pt.prompted) {
            pt.prompted = true;
            plugin.store.markDirty();
            plugin.msg(p, "这是你刚放下的信标，先给它起个名字吧（以后再次右键即可打开传送菜单；发送 §c取消§f 则保留坐标名）。");
            startRename(p, pt);
            return;
        }
        openMenu(p, null, 0, pt);
    }

    // ------------------------------------------------------------ 改名
    String validate(String raw, Point self) {
        String n = clean(raw);
        int max = plugin.getConfig().getInt("max-name-length", 24);
        if (n.isEmpty()) return "名字不能为空。";
        if (n.codePointCount(0, n.length()) > max) return "名字太长了（最多 " + max + " 个字符）。";
        if (plugin.store.nameTaken(n, self)) return "已经有同名的传送点了，请换一个名字。";
        return null;
    }

    private static final java.util.regex.Pattern WS = java.util.regex.Pattern.compile("[\\r\\n\\t]");

    private static String clean(String raw) { return WS.matcher(raw.replace("§", "")).replaceAll(" ").trim(); }

    void startRename(Player p, Point pt) {
        if (pt.locked) {
            return; // 已锁定：静默拒绝改名
        }
        boolean direct = canRenameDirect(p, pt);
        String title = direct ? "重命名信标「" + pt.displayName() + "」" : "申请重命名信标「" + pt.displayName() + "」（放置者 " + pt.ownerName + "）";
        String hint = direct ? "输入新名字（最多 " + plugin.getConfig().getInt("max-name-length", 24) + " 字符，发送「重置」恢复为坐标名）"
                : "输入想改成的名字，将发给放置者确认";
        askText(p, title, hint, "新名字", pt.name() == null ? "" : pt.name(), text -> {
            Point cur = plugin.store.byId.get(pt.id);
            if (cur == null) { plugin.msg(p, "§c这个传送点已经不存在了。"); return; }
            if (cur.locked) return; // 输入期间被锁定：静默放弃
            if (text.isBlank()) { plugin.msg(p, "§7名字为空，已取消。"); return; }
            if (direct && text.equals("重置")) {
                plugin.store.rename(cur, null); plugin.markers.refresh(cur);
                plugin.msg(p, "已恢复为坐标名「§b" + cur.displayName() + "§f」。");
                return;
            }
            String err = validate(text, cur);
            if (err != null) { plugin.msg(p, "§c" + err); return; }
            String name = clean(text);
            if (direct) {
                String old = cur.displayName();
                cur.prompted = true;
                plugin.store.rename(cur, name.equalsIgnoreCase(cur.defaultName()) ? null : name);
                plugin.markers.refresh(cur);
                plugin.msg(p, "已将「" + old + "」重命名为「§b" + cur.displayName() + "§f」。");
                if (!p.getUniqueId().equals(cur.owner) && cur.owner != null) {
                    Player o = Bukkit.getPlayer(cur.owner);
                    String m = "管理员 " + p.getName() + " 把你的信标「" + old + "」重命名为「" + cur.displayName() + "」。";
                    if (o != null) plugin.msg(o, m); else plugin.store.addNotice(cur.owner, m);
                }
            } else {
                Request r = plugin.store.addRequest(cur, p.getUniqueId(), p.getName(), name);
                plugin.msg(p, "已向放置者 §b" + cur.ownerName + "§f 发送改名申请：「" + cur.displayName() + "」→「" + name + "」，对方同意后生效。");
                Player o = cur.owner == null ? null : Bukkit.getPlayer(cur.owner);
                if (o != null) deliverRequest(o, r);
            }
        });
    }

    /** 锁定/解锁（仅管理员）。 */
    void setLocked(CommandSender actor, Point pt, boolean lock) {
        if (!plugin.isAdmin(actor)) { plugin.msg(actor, "§c只有管理员可以锁定/解锁信标。"); return; }
        if (plugin.store.byId.get(pt.id) != pt) { plugin.msg(actor, "§c这个传送点已经不存在了。"); return; }
        if (pt.locked == lock) { plugin.msg(actor, "「" + pt.displayName() + "」本来就是" + (lock ? "已锁定" : "未锁定") + "状态。"); return; }
        pt.locked = lock;
        plugin.store.markDirty();
        plugin.markers.refresh(pt);
        if (!lock && pt.owner != null) { // 解锁后把锁定期间保留的改名申请递给在线的放置者
            Player o = Bukkit.getPlayer(pt.owner);
            if (o != null) {
                for (Request r : plugin.store.requestsFor(pt.owner)) {
                    if (r.point.equals(pt.id)) { deliverRequest(o, r); if (plugin.isBedrock(o)) break; }
                }
            }
        }
        plugin.msg(actor, "已" + (lock ? "§c锁定" : "§a解锁") + "§f「§b" + pt.displayName() + "§f」。");
    }

    // ------------------------------------------------------------ 改名申请
    /** 向（在线的）放置者递送一条申请。 */
    void deliverRequest(Player owner, Request r) {
        Point pt = plugin.store.byId.get(r.point);
        if (pt == null) return;
        String body = "玩家 " + r.requesterName + " 申请把你的信标「" + pt.displayName() + "」改名为「" + r.newName + "」";
        if (plugin.isBedrock(owner)) {
            plugin.msg(owner, "§e" + body + "。若未弹出窗口，输入 §b/tb requests§f 查看。");
            boolean ok = plugin.bedrock.modal(owner, "改名申请", body + "。\n是否同意？", "同意", "拒绝", yes -> {
                if (yes) accept(owner, r.id); else deny(owner, r.id);
                showNextForm(owner);
            });
            if (ok) return;
        }
        Component c = TeleportBeaconPlugin.text(TeleportBeaconPlugin.PREFIX + "§e" + body + " ")
                .append(Component.text("[同意]", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/tb accept " + r.id)))
                .append(Component.text(" "))
                .append(Component.text("[拒绝]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/tb deny " + r.id)));
        owner.sendMessage(c);
    }

    private void showNextForm(Player owner) {
        if (!owner.isOnline() || !plugin.isBedrock(owner)) return;
        List<Request> l = plugin.store.requestsFor(owner.getUniqueId());
        if (!l.isEmpty()) deliverRequest(owner, l.get(0));
    }

    /** 查看/处理我收到的待处理申请。 */
    void showRequests(Player owner) {
        List<Request> l = plugin.store.requestsFor(owner.getUniqueId());
        if (l.isEmpty()) { plugin.msg(owner, "没有待处理的改名申请。"); return; }
        if (plugin.isBedrock(owner)) { deliverRequest(owner, l.get(0)); return; }
        plugin.msg(owner, "你有 " + l.size() + " 条待处理的改名申请：");
        for (Request r : l) deliverRequest(owner, r);
    }

    /** 登录时：递送离线期间的通知和待处理申请。 */
    void onJoinDeliver(Player p) {
        for (String s : plugin.store.takeNotices(p.getUniqueId())) plugin.msg(p, "§e" + s);
        List<Request> l = plugin.store.requestsFor(p.getUniqueId());
        if (l.isEmpty()) return;
        plugin.msg(p, "你有 §e" + l.size() + "§f 条待处理的信标改名申请：");
        if (plugin.isBedrock(p)) deliverRequest(p, l.get(0));
        else for (Request r : l) deliverRequest(p, r);
    }

    private boolean checkActor(CommandSender actor, Point pt) {
        if (!(actor instanceof Player p)) return plugin.isAdmin(actor);
        return plugin.isAdmin(p) || p.getUniqueId().equals(pt.owner);
    }

    private void tell(Request r, String m) {
        Player q = Bukkit.getPlayer(r.requester);
        if (q != null) plugin.msg(q, m); else plugin.store.addNotice(r.requester, m);
    }

    void accept(CommandSender actor, int id) {
        Request r = plugin.store.requests.get(id);
        Point pt = r == null ? null : plugin.store.byId.get(r.point);
        if (r == null || pt == null) { plugin.msg(actor, "§c该申请已失效或已处理。"); if (r != null) plugin.store.removeRequest(id); return; }
        if (!checkActor(actor, pt)) { plugin.msg(actor, "§c只有该信标的放置者或管理员可以处理这条申请。"); return; }
        if (pt.locked) { // 申请保留，解锁后还可以再同意
            return;
        }
        plugin.store.removeRequest(id);
        if (plugin.store.nameTaken(r.newName, pt)) {
            plugin.msg(actor, "§c名字「" + r.newName + "」已被占用，无法改名。");
            tell(r, "你对信标「" + pt.displayName() + "」的改名申请未能生效：名字「" + r.newName + "」已被占用。");
            return;
        }
        String old = pt.displayName();
        pt.prompted = true;
        plugin.store.rename(pt, r.newName.equalsIgnoreCase(pt.defaultName()) ? null : r.newName);
        plugin.markers.refresh(pt);
        plugin.msg(actor, "已同意：「" + old + "」→「§b" + pt.displayName() + "§f」。");
        tell(r, "你的改名申请已被同意：「" + old + "」→「" + pt.displayName() + "」。");
    }

    void deny(CommandSender actor, int id) {
        Request r = plugin.store.requests.get(id);
        Point pt = r == null ? null : plugin.store.byId.get(r.point);
        if (r == null || pt == null) { plugin.msg(actor, "§c该申请已失效或已处理。"); if (r != null) plugin.store.removeRequest(id); return; }
        if (!checkActor(actor, pt)) { plugin.msg(actor, "§c只有该信标的放置者或管理员可以处理这条申请。"); return; }
        plugin.store.removeRequest(id);
        plugin.msg(actor, "已拒绝 " + r.requesterName + " 对「" + pt.displayName() + "」的改名申请。");
        tell(r, "你对信标「" + pt.displayName() + "」的改名申请（→「" + r.newName + "」）被拒绝了。");
    }
}
