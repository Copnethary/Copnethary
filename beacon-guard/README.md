# BeaconGuard（信标保护）

Paper 26.3 插件，与 TeleportBeacon（传送信标）联动，也支持普通原版信标。可选依赖 Floodgate（基岩版用原生表单）。

构建：`JAVA_HOME=<JDK 25> mvn -q package` → `target/BeaconGuard.jar`
部署：复制到 `plugins/` 后重启。数据：`plugins/BeaconGuard/regions.yml`，配置：`config.yml`。

## 三种使用方式
1. **放下信标**：聊天栏出现 `是否保护此地标？ [保护] [忽略]`，点 [保护] 即可。
2. **菜单**：右键传送信标或用传送罗盘打开传送菜单 → 「管理传送点」→ 选一个传送点 → 进入保护设置，每一项单独点击开关。
   也可以直接输入 `/guard menu`（当前所在保护区）或 `/guard menu <编号>`。
3. **聊天框指令**：`/guard protect <x> <y> <z>`、`/guard flag tnt on` 等。

**保护开启后只有管理员可以撤销**（`/guard remove`，或菜单里的「取消保护」，或管理员挖掉中心信标）。

## 每一项设置（默认半径 50 格）
on / 开启 = 禁止，off / 关闭 = 允许。

**方块保护**（默认全部开启；开启后只有管理员能关闭，可用 `owner-can-relax-protection` 放开）

| 设置名 | 菜单里的名字 |
|---|---|
| `break` | 禁止玩家破坏方块（镐子/斧子/徒手、装桶舀水、去皮、锄地、踩耕地、改告示牌） |
| `creeper` | 禁止苦力怕爆炸破坏 |
| `tnt` | 禁止 TNT / TNT 矿车爆炸破坏 |
| `explosion` | 禁止其它爆炸破坏（恶魂火球、凋灵骷髅、末影水晶、床、重生锚、风弹） |
| `mob-grief` | 禁止怪物破坏方块（末影人、劫掠兽、凋灵、末影龙、破门僵尸、生物踩耕地） |
| `fire-burn` | 禁止火烧毁方块 |
| `decoration` | 保护画 / 展示框 / 盔甲架 |
| `outside` | 禁止区外的活塞 / 液体 / 发射器影响区内 |

**区域规则**（默认全部关闭；主人和管理员都可以自由开关）

| 设置名 | 菜单里的名字 |
|---|---|
| `pvp` | 禁止玩家互相攻击（含弓箭、三叉戟、喷溅药水、火焰附加） |
| `fire-spread` | 禁止火焰蔓延 / 点火 |
| `mob-spawn` | 禁止怪物自然生成（刷怪笼、刷怪蛋不受影响） |
| `mob-damage` | 禁止怪物伤害玩家 |
| `place` | 禁止普通玩家放置方块 / 倒液体 / 挂画 |
| `interact` | 禁止普通玩家使用箱子、门、按钮等（右键信标不受影响） |

管理员（`beaconguard.admin`，默认 OP）不受「玩家」类限制。

## 指令（`/guard`，别名 `/bg`、`/保护`）
| 指令 | 说明 |
|---|---|
| `/guard menu [编号]` | 打开保护设置菜单 |
| `/guard protect` / `/guard protect <x> <y> <z> [世界]` | 保护所在位置 / 指定坐标 |
| `/guard flag <设置> <on\|off> [编号]` | 修改一项设置 |
| `/guard flags` | 查看所有设置名 |
| `/guard info [编号]` | 查看保护区（可点击开关） |
| `/guard list` | 列出自己的保护区 |
| `/guard remove [编号]` | 管理员：取消保护 |
| `/guard radius <半径> [编号]`、`/guard tp <编号>`、`/guard list all`、`/guard reload` | 管理员 |

不写编号时，默认是你当前站着的保护区。

## 给其它插件的接口
`cn.willgao.beaconguard.BeaconGuardApi`：`status(Location)`、`openMenu(Player, Location, String title, Runnable back)`。
TeleportBeacon 通过反射调用，不需要编译依赖。

## 1.1.0
- 保护拆分为 14 项，可分别开关（镐子破坏、苦力怕、TNT、其它爆炸、怪物破坏、火烧、装饰、区外活塞液体、PVP、火焰蔓延、怪物生成、怪物伤害、放置、使用）。
- 新增保护设置菜单（Java 箱子界面 / 基岩版表单），可从传送信标的「管理传送点」进入。
- 撤销保护改为仅管理员；方块保护类开启后主人不能关闭。
- 1.0.0 的 regions.yml 会自动转换。
