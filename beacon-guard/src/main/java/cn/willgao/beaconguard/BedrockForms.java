package cn.willgao.beaconguard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** 基岩版原生表单（仅当 Floodgate 存在时才会加载此类）。回调都切回主线程。 */
final class BedrockForms {
    private final BeaconGuardPlugin plugin;

    BedrockForms(BeaconGuardPlugin plugin) {
        this.plugin = plugin;
        FloodgateApi.getInstance(); // 触发类加载，失败则抛异常由调用方处理
    }

    boolean isBedrock(UUID u) {
        try { return FloodgateApi.getInstance().isFloodgatePlayer(u); } catch (Throwable t) { return false; }
    }

    private void sync(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    boolean simple(Player p, String title, String content, List<String> buttons, IntConsumer onClick) {
        SimpleForm.Builder b = SimpleForm.builder().title(title).content(content);
        for (String s : buttons) b.button(s);
        b.validResultHandler(r -> { int id = r.clickedButtonId(); sync(() -> onClick.accept(id)); });
        try { return FloodgateApi.getInstance().sendForm(p.getUniqueId(), b.build()); } catch (Throwable t) { return false; }
    }

    boolean modal(Player p, String title, String content, String yes, String no, Consumer<Boolean> cb) {
        ModalForm.Builder b = ModalForm.builder().title(title).content(content).button1(yes).button2(no);
        b.validResultHandler(r -> { boolean y = r.clickedFirst(); sync(() -> cb.accept(y)); });
        try { return FloodgateApi.getInstance().sendForm(p.getUniqueId(), b.build()); } catch (Throwable t) { return false; }
    }

    /**
     * 一组开关。headers[i] 不为 null 时在第 i 个开关前插入一行说明文字。
     * 回调收到每个开关的结果（顺序与 labels 相同）。
     */
    boolean toggles(Player p, String title, List<String> headers, List<String> labels, List<Boolean> values,
                    Consumer<List<Boolean>> cb) {
        CustomForm.Builder b = CustomForm.builder().title(title);
        for (int i = 0; i < labels.size(); i++) {
            String h = headers.get(i);
            if (h != null) b.label(h);
            b.toggle(labels.get(i), values.get(i));
        }
        int n = labels.size();
        b.validResultHandler(r -> {
            List<Boolean> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(r.asToggle()); // 依次读取，自动跳过说明文字
            sync(() -> cb.accept(out));
        });
        try { return FloodgateApi.getInstance().sendForm(p.getUniqueId(), b.build()); } catch (Throwable t) { return false; }
    }
}
