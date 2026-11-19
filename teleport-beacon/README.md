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
