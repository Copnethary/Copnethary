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
