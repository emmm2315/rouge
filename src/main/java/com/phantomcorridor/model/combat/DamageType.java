package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.WorldType;

/**
 * 伤害来源分类。
 *
 * <p>《双界行者》里的攻击分三类：光界能量、影界实体斩击，以及不带相位属性的纯物理冲击。
 * 分类本身不影响伤害数值（数值由出招方自己的 {@code damage} 决定），
 * 它的作用有两个：一是让受击反馈读得出“挨的是哪一种攻击”，二是给后续的
 * 抗性/易伤（例如“影界护盾吃光界伤害会更快碎”）留出挂点。
 */
public enum DamageType {

    /** 纯物理冲击：傀儡的践踏、处刑者的盾击、地裂等不依赖相位属性的招式。 */
    PHYSICAL("物理"),

    /** 光界能量：灯魇的追踪光球、法师的扇面弹、守望者的日光弹幕等。 */
    LIGHT("光"),

    /** 影界实体攻击：棱晶狼的撕咬、鸣钟者的暗波、守望者的裂隙长矛等。 */
    SHADOW("影");

    private final String displayName;

    DamageType(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }

    /** 按攻击所在世界归类：光界的攻击算光属性，影界的算影属性。 */
    public static DamageType ofWorld(WorldType world) {
        return world == WorldType.SHADOW ? SHADOW : LIGHT;
    }
}
