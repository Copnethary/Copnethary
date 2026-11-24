package cn.willgao.beaconguard;

import java.util.Locale;

/** 保护区内可以开关的规则。true = 允许，false = 禁止。 */
enum Flag {
    PVP("pvp", "玩家互相攻击"),
    FIRE_SPREAD("fire-spread", "火焰蔓延/点火"),
    MOB_SPAWN("mob-spawn", "怪物自然生成"),
    MOB_DAMAGE("mob-damage", "怪物伤害玩家"),
    PLACE("place", "普通玩家放置方块/倒液体"),
    INTERACT("interact", "普通玩家使用箱子/门/按钮等");

    final String key;
    final String desc;

    Flag(String key, String desc) { this.key = key; this.desc = desc; }

    static Flag parse(String s) {
        String k = s.toLowerCase(Locale.ROOT).replace('_', '-');
        for (Flag f : values()) if (f.key.equals(k)) return f;
        switch (k) {
            case "pvp战斗", "攻击", "互相攻击" -> { return PVP; }
            case "fire", "火", "火焰", "火焰蔓延" -> { return FIRE_SPREAD; }
            case "spawn", "刷怪", "怪物生成" -> { return MOB_SPAWN; }
            case "怪物伤害" -> { return MOB_DAMAGE; }
            case "build", "放置", "建造" -> { return PLACE; }
            case "use", "使用", "交互" -> { return INTERACT; }
            default -> { return null; }
        }
    }
}
