package cn.willgao.beaconguard;

import org.bukkit.Material;

import java.util.Locale;

/**
 * 保护区里每一项可以单独开关的设置。值为 true = 禁止（开启这项保护），false = 允许。
 * protection=true 的是「方块保护」类：默认开启，主人只能开启，关闭需要管理员（可在配置里放开）。
 */
enum Flag {
    // ---- 方块保护 ----
    BREAK("break", "禁止玩家破坏方块", Material.DIAMOND_PICKAXE, true,
            "普通玩家不能用镐子、斧子、徒手等挖掉方块，", "也不能装桶舀水、去皮、锄地、踩坏耕地、改写告示牌"),
    CREEPER("creeper", "禁止苦力怕爆炸破坏", Material.CREEPER_HEAD, true, "苦力怕爆炸炸不坏方块"),
    TNT("tnt", "禁止 TNT 爆炸破坏", Material.TNT, true, "TNT、TNT 矿车炸不坏方块"),
    EXPLOSION("explosion", "禁止其它爆炸破坏", Material.FIRE_CHARGE, true,
            "恶魂火球、凋灵骷髅、末影水晶、", "床/重生锚爆炸、风弹等炸不坏方块"),
    MOB_GRIEF("mob-grief", "禁止怪物破坏方块", Material.ENDERMAN_SPAWN_EGG, true,
            "末影人搬方块、劫掠兽/凋灵/末影龙破坏、", "僵尸破门、生物踩坏耕地等"),
    FIRE_BURN("fire-burn", "禁止火烧毁方块", Material.CAMPFIRE, true, "火焰不会把木头、羊毛等烧掉"),
    DECORATION("decoration", "保护画/展示框/盔甲架", Material.ITEM_FRAME, true,
            "普通玩家、怪物、爆炸都破坏不了", "画、物品展示框和盔甲架"),
    OUTSIDE("outside", "禁止区外活塞/液体/发射器", Material.PISTON, true,
            "保护区外的活塞推拉、水和岩浆流入、", "发射器倒液体/射 TNT 影响不到区内"),
    // ---- 区域规则 ----
    PVP("pvp", "禁止玩家互相攻击", Material.IRON_SWORD, false, "包括弓箭、三叉戟、喷溅药水、火焰附加"),
    FIRE_SPREAD("fire-spread", "禁止火焰蔓延/点火", Material.FLINT_AND_STEEL, false,
            "火不会蔓延，闪电/岩浆不会点火，", "普通玩家不能用打火石"),
    MOB_SPAWN("mob-spawn", "禁止怪物生成", Material.ZOMBIE_HEAD, false, "怪物不会自然生成（刷怪笼、刷怪蛋不受影响）"),
    MOB_DAMAGE("mob-damage", "禁止怪物伤害玩家", Material.SHIELD, false, "怪物的攻击、箭、爆炸不会伤害玩家"),
    PLACE("place", "禁止普通玩家放置方块", Material.BRICKS, false, "普通玩家不能放方块、倒水倒岩浆、挂画"),
    INTERACT("interact", "禁止普通玩家使用方块", Material.CHEST, false,
            "普通玩家不能打开箱子/熔炉，不能用门、按钮、拉杆等", "（右键信标打开传送菜单不受影响）");

    final String key;
    final String title;
    final Material icon;
    final boolean protection;
    final String[] lore;

    Flag(String key, String title, Material icon, boolean protection, String... lore) {
        this.key = key; this.title = title; this.icon = icon; this.protection = protection; this.lore = lore;
    }

    static Flag parse(String s) {
        String k = s.toLowerCase(Locale.ROOT).replace('_', '-');
        for (Flag f : values()) if (f.key.equals(k)) return f;
        return switch (k) {
            case "破坏", "挖掘", "镐子", "pickaxe", "dig" -> BREAK;
            case "苦力怕" -> CREEPER;
            case "爆炸", "explode" -> EXPLOSION;
            case "怪物破坏", "griefing", "mobgrief" -> MOB_GRIEF;
            case "火烧", "burn" -> FIRE_BURN;
            case "装饰", "展示框", "画" -> DECORATION;
            case "活塞", "液体", "piston" -> OUTSIDE;
            case "攻击", "互相攻击", "pvp战斗" -> PVP;
            case "fire", "火", "火焰", "火焰蔓延" -> FIRE_SPREAD;
            case "spawn", "刷怪", "怪物生成" -> MOB_SPAWN;
            case "怪物伤害" -> MOB_DAMAGE;
            case "build", "放置", "建造" -> PLACE;
            case "use", "使用", "交互" -> INTERACT;
            default -> null;
        };
    }
}
