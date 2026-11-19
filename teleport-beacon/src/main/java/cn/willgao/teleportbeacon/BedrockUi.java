package cn.willgao.teleportbeacon;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** 基岩版原生表单（仅当 Floodgate 存在时才会加载此类）。回调都切回主线程。 */
final class BedrockUi {
    private final TeleportBeaconPlugin plugin;

    BedrockUi(TeleportBeaconPlugin plugin) {
        this.plugin = plugin;
        FloodgateApi.getInstance(); // 触发类加载，失败则抛异常由调用方处理
    }

    boolean isBedrock(UUID u) {
        try { return FloodgateApi.getInstance().isFloodgatePlayer(u); } catch (Throwable t) { return false; }
    }

    private void sync(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    /** 列表表单；返回是否发送成功。 */
    boolean simple(Player p, String title, String content, List<String> buttons, IntConsumer onClick) {
        SimpleForm.Builder b = SimpleForm.builder().title(title).content(content);
        for (String s : buttons) b.button(s);
        b.validResultHandler(r -> { int id = r.clickedButtonId(); sync(() -> onClick.accept(id)); });
        try { return FloodgateApi.getInstance().sendForm(p.getUniqueId(), b.build()); } catch (Throwable t) { return false; }
    }

    boolean input(Player p, String title, String label, String placeholder, String def, Consumer<String> cb) {
        CustomForm.Builder b = CustomForm.builder().title(title).input(label, placeholder, def == null ? "" : def);
        b.validResultHandler(r -> {
            String s = r.asInput(0);
            sync(() -> cb.accept(s == null ? "" : s));
        });
        try { return FloodgateApi.getInstance().sendForm(p.getUniqueId(), b.build()); } catch (Throwable t) { return false; }
    }

    boolean modal(Player p, String title, String content, String yes, String no, Consumer<Boolean> cb) {
        ModalForm.Builder b = ModalForm.builder().title(title).content(content).button1(yes).button2(no);
        b.validResultHandler(r -> { boolean y = r.clickedFirst(); sync(() -> cb.accept(y)); });
        try { return FloodgateApi.getInstance().sendForm(p.getUniqueId(), b.build()); } catch (Throwable t) { return false; }
    }
}
