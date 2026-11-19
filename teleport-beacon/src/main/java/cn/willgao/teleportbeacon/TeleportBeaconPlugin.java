package cn.willgao.teleportbeacon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class TeleportBeaconPlugin extends JavaPlugin {
    static final String PREFIX = "§b[传送信标] §f";
    /** 默认 config.yml 里 teleport 节开头的注释行（追加缺失配置时用来定位，必须与 config.yml 完全一致）。 */
    private static final String TELEPORT_SECTION_MARKER = "# ---- 传送（1.1.6 新增）----";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    Store store;
    Items items;
    Ui ui;
    Teleporter teleporter;
    Markers markers;
    BedrockUi bedrock; // 没装 floodgate 时为 null

    static Component text(String legacy) { return LEGACY.deserialize(legacy); }

    static String legacy(Component c) { return LEGACY.serialize(c); }

    void msg(CommandSender s, String legacy) { s.sendMessage(text(PREFIX + legacy)); }

    boolean isBedrock(Player p) { return bedrock != null && bedrock.isBedrock(p.getUniqueId()); }

    boolean isAdmin(CommandSender s) { return s.hasPermission("teleportbeacon.admin"); }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        appendMissingConfig();
        getDataFolder().mkdirs();
        store = new Store(this);
        store.load();
        items = new Items(this);
        if (getServer().getPluginManager().getPlugin("floodgate") != null) {
            try {
                bedrock = new BedrockUi(this);
                getLogger().info("检测到 Floodgate，基岩版玩家将使用原生表单。");
            } catch (Throwable t) {
                getLogger().warning("Floodgate API 不可用，基岩版将使用箱子界面: " + t);
                bedrock = null;
            }
        } else {
            getLogger().info("未检测到 Floodgate，全部玩家使用箱子界面。");
        }
        teleporter = new Teleporter(this);
        getServer().getPluginManager().registerEvents(teleporter, this);
        ui = new Ui(this);
        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        markers = new Markers(this);
        markers.start();
        PluginCommand cmd = getCommand("beacon");
        Cmd c = new Cmd(this);
        cmd.setExecutor(c);
        cmd.setTabCompleter(c);
        getLogger().info("已加载 " + store.byId.size() + " 个传送点。");
    }

    /**
     * 服务器上已有的 config.yml 不会被 saveDefaultConfig 覆盖，新版增加的 teleport 节就看不到。
     * 这里只在缺少时把默认配置里的这一节原样追加到文件末尾（带注释），不改动已有内容。
     */
    private void appendMissingConfig() {
        File f = new File(getDataFolder(), "config.yml");
        if (!f.isFile() || getConfig().contains("teleport", true)) return;
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            String def = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int i = def.indexOf(TELEPORT_SECTION_MARKER);
            if (i < 0) return;
            Files.writeString(f.toPath(), "\n" + def.substring(i), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            reloadConfig();
            getLogger().info("config.yml 里没有 teleport 配置节，已追加到文件末尾（原有内容未改动）。");
        } catch (Exception ex) {
            getLogger().warning("追加 teleport 配置节失败，将使用默认值: " + ex);
        }
    }

    @Override
    public void onDisable() {
        if (teleporter != null) teleporter.shutdown();
        if (markers != null) markers.stop();
        if (store != null) store.shutdown();
    }

    /** 补发缺少的信标/罗盘。返回是否发放了东西。 */
    boolean ensureItems(Player p, boolean tellIfNothing) {
        boolean gaveB = false, gaveC = false, full = false;
        if (!items.has(p, true)) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(items.beacon(1));
            if (left.isEmpty()) gaveB = true; else full = true;
        }
        if (!items.has(p, false)) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(items.compass(1));
            if (left.isEmpty()) gaveC = true; else full = true;
        }
        if (gaveB || gaveC) {
            msg(p, "已补发" + (gaveB ? "「传送信标」" : "") + (gaveC ? "「传送罗盘」" : "") + "。数量不限，随时可用 §e/tb get §f重新领取。");
        }
        if (full) msg(p, "§c背包已满，腾出空位后用 §e/tb get §c领取传送信标/罗盘。");
        if (!gaveB && !gaveC && !full && tellIfNothing) msg(p, "你已经拥有传送信标和传送罗盘了。");
        return gaveB || gaveC;
    }
}
