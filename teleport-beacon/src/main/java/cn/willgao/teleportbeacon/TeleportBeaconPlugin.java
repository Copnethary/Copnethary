package cn.willgao.teleportbeacon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class TeleportBeaconPlugin extends JavaPlugin {
    static final String PREFIX = "§b[传送信标] §f";
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

    @Override
    public void onDisable() {
        if (markers != null) markers.stop();
        if (store != null) store.flush();
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
