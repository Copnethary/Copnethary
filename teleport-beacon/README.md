# TeleportBeacon (传送信标)

Paper 26.3 plugin. Build: `. ./env.sh && mvn -q package` -> target/TeleportBeacon.jar
Deploy: copy to plugins/, restart. Data: plugins/TeleportBeacon/data.yml, config.yml.
Local test server (with fake-player self-test): /workspace/tools/testsrv + /workspace/tools/tbtest (adds `/tb selftest`).
Classes: Store(data.yml, 含每人 prefs) Items(PDC items) Ui(chest menu, chat prompts, rename/requests) BedrockUi(Floodgate Cumulus forms)
Teleporter(safe tp) Listeners(place/break/protect/interact/join) Cmd(/tb).

## 1.1.0 个人排序
每位玩家可收藏、置顶、自定义顺序（只对自己生效，存在 data.yml 的 prefs.<uuid>）。
列表顺序：置顶 → 收藏 → 自定义顺序 → 默认按名字。
命令：`/tb fav|unfav|pin|unpin|moveup|movedown <名字>`（无参数的 fav/pin 查看自己的列表）。
Java 菜单：左键传送，右键收藏，Shift+左键置顶，Shift+右键上移，Q 下移。
基岩版：主列表点击仍是传送；另有「管理收藏 / 置顶 / 排序」表单。

## 1.1.1 性能优化
- 存档：主线程只做轻量快照，YAML 序列化 + 写盘放到单独的 IO 线程（按顺序写，旧数据不会覆盖新数据）。
- 名牌/粒子定时任务：配置缓存；只处理有玩家的世界；玩家坐标每个世界只取一次；直接持有盔甲架引用，不再 `Bukkit.getEntity` 查找；名牌文字缓存；粒子只发给可视范围内的玩家；锁定圈坐标预计算。
- 打开菜单不再全表扫描所有传送点读方块（清理工作并入名牌定时任务）。
- 箱子菜单收藏/置顶/排序后原地刷新，不再关掉重开；固定按钮只创建一次。
- 传送：目标区块已加载则立即传送；未加载时用 Paper 紧急优先级异步加载；传送进行中忽略重复点击；少一个 tick 的回调延迟。
- 名字索引 / 按世界索引：按名字查找、查重 O(1)；排序预计算键。
- 锁定规则：被管理员锁定的信标，任何人（包括管理员）都不能改名，也不能同意改名申请；必须先解锁。锁定期间收到的申请会保留，解锁后自动递给在线的放置者。

## 1.1.2 传送加载画面与卡顿修复
传送前用 `getChunkAtAsyncUrgently` 预加载目标附近区块，再 `teleportAsync` 恰好一次，完成后立刻解除占用，不再二次传送。
加载画面（可关）：黑屏（失明+黑暗+标题）显示「正在加载地图… N%」，期间无敌、不能移动/攻击/交互。
超时或失败：送回起点，提示「传送失败，地图加载超时，请稍等片刻再试」，并进入可配置的失败冷却（默认 15 秒）。
新配置节 `loading-screen` 只会在缺失时追加，不覆盖已有项。

## 1.1.3 原版传送、到达保护、列表与查找
- 传送改为关闭界面后于下一 tick 执行一次同步 `player.teleport(loc, COMMAND)`，与原版 `/tp` 相同。不再预加载区块、不再显示加载画面、不冻结移动、不加药水效果。
- 到达后给予可配置的短暂无敌（`arrival-protection-seconds`，默认 60 秒）：不受伤害、敌对生物不会以该玩家为目标，但不限制移动和操作。攻击其他实体会提前结束。倒计时显示在动作栏「传送保护：剩余 N 秒」，结束时聊天提示。退出、死亡或再次传送会清除。
- 默认关闭 `loading-screen.enabled`（该节保留但不再生效）。
- 取消收藏和自定义排序（`fav` / `unfav` / `moveup` / `movedown` 及对应界面操作）。已有 `prefs` 数据仍保留在 data.yml，不会丢失。
- 保留置顶：`/tb pin|unpin`（`置顶` / `取消置顶`），Java 菜单 Shift+左键切换，基岩版「管理置顶」。无搜索时置顶的排在最前（组内按拼音/字母），其余同样按名字排。
- 查找：`/tb search|查找 <关键字>` 打开过滤后的菜单；Java 菜单有查找按钮（聊天输入）；基岩版表单有查找输入框。搜索结果按相关度排序（完全匹配 → 前缀 → 包含名字 → 放置者/世界），置顶不压过相关度。
- 名字排序使用 `Collator` + `Locale.CHINA`（汉字按拼音，与拉丁字母混排，忽略大小写；数字/符号通常在前）。

## 1.1.4 精简
已完全移除加载画面/黑屏、传送冷却、失败冷却、到达无敌，以及收藏/自定义排序的命令与界面逻辑。
传送仍为下一 tick 的同步 `player.teleport(..., COMMAND)`（安全落点、跨世界）。保留锁定、置顶、按相关度查找、拼音/字母排序、基岩版表单。
`data.yml` 里已有的 `prefs.*.favorites` / `prefs.*.order` 仍会照常读写，不会丢数据。
