package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.EnemyKind;

import java.util.Arrays;
import java.util.List;

/**
 * 敌人技能的游戏内索引。字段与素材包（{@code monster_pack_v1} 的 combat_profiles.json、
 * {@code monster_expansion_v2} 的 combat_profiles.json）一一对应到本体动作与独立特效。
 *
 * <p>每条技能都显式携带自己的伤害、起手 / 释放 / 收招时长与冷却：伤害跟着招式走，
 * 而不是所有攻击共用物种的基础撞击伤害（纯位移、召唤、筑墙显式写 0）。
 * 判定几何（扇面角度、安全缺口、标记数量、延迟时刻）也逐条写在技能上，
 * 由 {@link EnemySystem} 在释放阶段生成对应的 {@link EnemyTelegraph} 或 {@link EnemyAttack}。
 *
 * <p>时序字段：{@code windup} 前摇、{@code active} 本体释放阶段占用时间（不等于攻击实体寿命）、
 * {@code recovery} 收招、{@code cooldown} 从收招结束开始计时的冷却。
 */
public enum EnemySkill {
    // ================= 旧包 v1 =================
    LANTERN_SEEKER(spec(EnemyKind.LANTERN, WorldType.LIGHT, "attack", "seeker_orb", Pattern.PROJECTILE)
            .timing(.70, .16, .85).cooldown(2.35).range(450).radius(14).count(1).speed(175).damage(5.0)),
    LANTERN_NEEDLES(spec(EnemyKind.LANTERN, WorldType.SHADOW, "attack", "dusk_needle", Pattern.SPREAD)
            .timing(.82, .16, 1.05).cooldown(2.65).range(410).radius(10).count(3).speed(275).damage(4.0)),
    WOLF_POUNCE(spec(EnemyKind.WOLF, WorldType.LIGHT, "attack", "bite_flash", Pattern.DASH)
            .timing(.72, .16, .78).cooldown(2.25).range(290).radius(54).speed(0).damage(5.0)),
    WOLF_DASH_BITE(spec(EnemyKind.WOLF, WorldType.SHADOW, "attack", "bite_arc", Pattern.DASH)
            .timing(.55, .16, .88).cooldown(2.05).range(340).radius(58).speed(0).damage(6.0)),
    GOLEM_CRACK(spec(EnemyKind.GOLEM, WorldType.LIGHT, "attack", "ground_crack", Pattern.CRACK)
            .timing(.95, .16, 1.25).cooldown(3.45).range(195).radius(48).count(3).damage(6.0)),
    GOLEM_SLAM(spec(EnemyKind.GOLEM, WorldType.SHADOW, "attack", "slam_sector", Pattern.ARC)
            .timing(.90, .16, 1.35).cooldown(3.35).range(180).radius(125).damage(7.5)),
    MAGE_FAN(spec(EnemyKind.MAGE, WorldType.LIGHT, "attack", "fan_pellet", Pattern.FAN)
            .timing(.75, .16, .82).cooldown(2.45).range(500).radius(11).count(5).speed(230).damage(4.0)),
    /** 法师光界第二招：两轮扇面连射，第二轮整体转过一个角度，玩家不能只躲第一波。 */
    MAGE_TWIN_FAN(spec(EnemyKind.MAGE, WorldType.LIGHT, "attack", "fan_pellet", Pattern.BARRAGE)
            .timing(.70, .16, 1.00).cooldown(5.6).range(500).radius(11).count(5).spread(17.5)
            .volleys(2, 0.45).angleStep(18).speed(245).damage(3.5).telegraph("sector")),
    MAGE_MIRROR(spec(EnemyKind.MAGE, WorldType.SHADOW, "attack", "mirror_arc", Pattern.ARC)
            .timing(.90, .16, 1.15).cooldown(2.85).range(205).radius(130).damage(6.5)),
    /** 法师影界第二招：三枚镜片碰墙反弹两次，走廊里会一直追着玩家跑。 */
    MAGE_RICOCHET(spec(EnemyKind.MAGE, WorldType.SHADOW, "attack", "mirror_arc", Pattern.PROJECTILE)
            .timing(.85, .16, 1.20).cooldown(6.2).range(560).radius(13).count(3).spread(26).speed(250)
            .bounces(2).damage(4.0).telegraph("sector")),
    EXECUTIONER_SPEAR(spec(EnemyKind.EXECUTIONER, WorldType.LIGHT, "attack", "spear_projectile", Pattern.PROJECTILE)
            .timing(.85, .16, .90).cooldown(2.85).range(500).radius(16).count(1).speed(360).damage(9.0)),
    EXECUTIONER_BASH(spec(EnemyKind.EXECUTIONER, WorldType.LIGHT, "shield_bash", "shield_sector", Pattern.ARC)
            .timing(.78, .16, .90).cooldown(2.55).range(165).radius(120).damage(8.0)),
    EXECUTIONER_COMBO(spec(EnemyKind.EXECUTIONER, WorldType.SHADOW, "attack", "cleave_arc", Pattern.DOUBLE_ARC)
            .timing(.78, .16, 1.15).cooldown(2.95).range(205).radius(130).damage(10.0)),
    EXECUTIONER_APPROACH(spec(EnemyKind.EXECUTIONER, WorldType.SHADOW, "approach", "dash_dust", Pattern.DASH_NO_DAMAGE)
            .timing(.65, .16, .45).cooldown(2.15).range(370).radius(0).damage(2.0)),
    BELL_RING(spec(EnemyKind.BELL, WorldType.LIGHT, "attack", "bell_pellet", Pattern.RING)
            .timing(1.00, .16, 1.15).cooldown(3.85).range(400).radius(10).count(9).speed(175).damage(4.0)),
    /** 鸣钟者光界第三招：钟波连鸣三轮，每轮整体转过 22.5°，把整间房都铺满弹幕。 */
    BELL_TOLLING(spec(EnemyKind.BELL, WorldType.LIGHT, "attack", "bell_pellet", Pattern.BARRAGE)
            .timing(.95, .16, 1.30).cooldown(6.8).range(430).radius(10).count(9).ring()
            .volleys(3, 0.55).angleStep(22.5).speed(180).damage(3.5).telegraph("ring_gap")),
    BELL_LIGHT_MARK(spec(EnemyKind.BELL, WorldType.LIGHT, "ground_mark", "pillar_impact", Pattern.MARK)
            .timing(.95, .16, .95).cooldown(3.35).range(520).radius(92).damage(10.0)),
    BELL_ANNULAR(spec(EnemyKind.BELL, WorldType.SHADOW, "attack", "annular_burst", Pattern.RING_AREA)
            .timing(1.00, .16, 1.40).cooldown(3.75).range(245).radius(135).damage(8.5)),
    BELL_SHADOW_MARK(spec(EnemyKind.BELL, WorldType.SHADOW, "ground_mark", "pillar_impact", Pattern.MARK)
            .timing(.95, .16, .95).cooldown(3.35).range(500).radius(92).damage(10.0)),
    WATCHER_BARRAGE(spec(EnemyKind.WATCHER, WorldType.LIGHT, "attack", "sun_pellet", Pattern.DOUBLE_RING)
            .timing(.80, .16, 1.05).cooldown(3.10).range(540).radius(13).count(16).speed(205).damage(3.0)),
    /**
     * 守望者光界的全图弹幕：日轮连射——每轮 12 发环形，连发三轮且每轮整体转过 15°。
     *
     * <p>环形弹幕本身就是「全图」级别：单轮 12 发已经覆盖 360°，三轮错开 15° 之后
     * 缝隙在不停移动，玩家不能靠记住第一轮的空档站桩，必须边走边找新缝。
     */
    WATCHER_SUN_CASCADE(spec(EnemyKind.WATCHER, WorldType.LIGHT, "attack", "sun_pellet", Pattern.BARRAGE)
            .timing(.75, 1.20, 1.10).cooldown(7.0).range(620).radius(12).count(12).ring()
            .volleys(3, 0.5).angleStep(15).speed(215).damage(3.5).telegraph("ring_gap")),
    WATCHER_JUDGMENT(spec(EnemyKind.WATCHER, WorldType.LIGHT, "judgment", "pillar_impact", Pattern.TRIPLE_MARK)
            .timing(.85, .16, 1.10).cooldown(3.35).range(580).radius(82).count(3).damage(12.0)),
    WATCHER_SPEAR(spec(EnemyKind.WATCHER, WorldType.LIGHT, "rift_spear", "rift_spear", Pattern.PROJECTILE)
            .timing(.90, .16, 1.15).cooldown(3.55).range(610).radius(20).count(1).speed(350).damage(11.0)),
    /**
     * 光界的增援召唤：素材包里守望者的光形态没有 summon_* 本体动作，
     * 这里用它的 transform（相位收拢）配合两侧都有的 summon_portal 特效，
     * 读起来就是“把光界的造物从裂隙里拉出来”。
     */
    WATCHER_CALL(spec(EnemyKind.WATCHER, WorldType.LIGHT, "transform", "summon_portal", Pattern.SUMMON)
            .timing(1.15, .16, 1.40).cooldown(12.0).range(620).damage(0.0).hitPolicy(HitPolicy.NONE)),
    WATCHER_DOUBLE_SLASH(spec(EnemyKind.WATCHER, WorldType.SHADOW, "attack", "slash_arc", Pattern.DOUBLE_ARC)
            .timing(.80, .16, 1.20).cooldown(3.15).range(235).radius(155).damage(13.0)),
    /**
     * 守望者影界的全图弹幕：裂影回弹——10 发环形弹幕，每发碰墙能反弹两次。
     *
     * <p>和光界「日轮连射」是两种解法：那一招逼玩家在缝隙里穿行，这一招逼玩家把弹体算进走位——
     * 贴墙绕圈会被反弹回来的弹体截住，必须往房间中间转。
     */
    WATCHER_RIFT_RICOCHET(spec(EnemyKind.WATCHER, WorldType.SHADOW, "attack", "slash_arc", Pattern.PROJECTILE)
            .timing(.75, .16, 1.30).cooldown(7.5).range(560).radius(14).count(10).ring().speed(230)
            .bounces(2).damage(4.5).telegraph("ring_gap")),
    WATCHER_DASH(spec(EnemyKind.WATCHER, WorldType.SHADOW, "dash", "dash_trail", Pattern.DASH)
            .timing(.80, .16, 1.20).cooldown(3.05).range(470).radius(92).damage(11.0)),
    WATCHER_SUMMON(spec(EnemyKind.WATCHER, WorldType.SHADOW, "summon", "summon_portal", Pattern.SUMMON)
            .timing(1.20, .16, 1.50).cooldown(12.0).range(540).damage(0.0).hitPolicy(HitPolicy.NONE)),

    // ================= v2 小怪 =================
    /** 铲角直顶：抬角预警后沿锁定直线顶撞 130 px；前摇期间甲壳正面减伤 25%。 */
    BEETLE_GORE(spec(EnemyKind.BEETLE, WorldType.LIGHT, "attack", "horn_arc", Pattern.CHARGE)
            .timing(.65, .22, .75).cooldown(2.4).range(150).distance(130).width(52).damage(6.0)
            .stopOnWall().guard(.25, 120).telegraph("line")),
    /** 短掘破土：沿地面裂痕掘进至已锁定的 160 px 内合法点，破土刺击。 */
    BEETLE_BURROW(spec(EnemyKind.BEETLE, WorldType.SHADOW, "attack", "dig_dust", Pattern.BURROW)
            .timing(.90, .25, .85).cooldown(3.2).range(190).distance(160).radius(58).damage(7.0)
            .stopOnWall().telegraph("circle")),

    /** 迟绽晨孢：吐出单枚慢孢子，撞墙/落地后延迟 0.8 秒绽放一次。 */
    SPORE_BLOOM(spec(EnemyKind.SPORE, WorldType.LIGHT, "attack", "spore_projectile", Pattern.PROJECTILE)
            .timing(.75, .20, .80).cooldown(3.1).range(420).radius(62).count(1).speed(180).damage(5.0)
            .noContactDamage().delayedBurst(0.8).telegraph("circle")),
    /** 暮孢滞留：暗孢落地形成 2.1 秒小雾区，0 / 0.8 / 1.6 秒各脉冲一次。 */
    SPORE_FIELD(spec(EnemyKind.SPORE, WorldType.SHADOW, "attack", "spore_projectile", Pattern.PROJECTILE)
            .timing(.80, .20, .90).cooldown(3.6).range(400).radius(60).count(1).speed(160).damage(3.0)
            .noContactDamage().pulses(0, 0.8, 1.6).hitPolicy(HitPolicy.ONCE_PER_PULSE).telegraph("circle")),

    /** 折翼投羽：绕侧翼后发射一枚直线晶羽，只在有视线时开火。 */
    RAYBAT_FEATHER(spec(EnemyKind.RAYBAT, WorldType.LIGHT, "attack", "feather_projectile", Pattern.PROJECTILE)
            .timing(.55, .12, .65).cooldown(2.1).range(500).radius(12).count(1).speed(290).damage(4.0)
            .telegraph("line")),
    /** 钩尾俯冲：蓄翼后沿 190 px 直线俯冲，尾钩只判定一次，撞墙立即停下。 */
    RAYBAT_DIVE(spec(EnemyKind.RAYBAT, WorldType.SHADOW, "attack", "dive_trail", Pattern.CHARGE)
            .timing(.70, .30, .90).cooldown(2.8).range(260).distance(190).width(50).damage(6.0)
            .stopOnWall().telegraph("line")),

    // ================= v2 Boss · 棱镜炮蟹 =================
    /** 聚棱炮：大晶炮弹直线 620 px；撞墙即爆，弹体与爆炸共享一次命中。 */
    CRAB_SHELL(spec(EnemyKind.PRISM_CRAB, WorldType.LIGHT, "attack", "prism_shell", Pattern.PROJECTILE)
            .timing(.80, .20, 1.10).cooldown(3.8).range(620).radius(65).count(1).speed(300).damage(9.0)
            .explodeOnImpact().telegraph("line")),
    /** 棱镜连装：三轮扇形齐射，每轮整体转过 8°，把正前方变成一条持续的火力走廊。 */
    CRAB_VOLLEY(spec(EnemyKind.PRISM_CRAB, WorldType.LIGHT, "skill3", "prism_shell", Pattern.BARRAGE)
            .timing(.85, 1.30, 1.20).cooldown(7.2).range(620).radius(26).count(5).spread(46)
            .volleys(3, 0.45).angleStep(8).speed(330).damage(4.5).telegraph("sector")),
    /** 折潮三炮：同时发射三枚夹角共 42° 的暗炮，命中共用一次结算标识。 */
    CRAB_TRIPLE_SHELL(spec(EnemyKind.PRISM_CRAB, WorldType.SHADOW, "attack", "prism_shell", Pattern.PROJECTILE)
            .timing(.85, .20, 1.20).cooldown(4.2).range(620).radius(22).count(3).spread(42).speed(250).damage(4.0)
            .telegraph("sector")),
    /** 蚀潮回弹：九发暗炮环形铺开并碰墙反弹两次，专治「贴着墙绕圈躲炮」。 */
    CRAB_RICOCHET(spec(EnemyKind.PRISM_CRAB, WorldType.SHADOW, "skill3", "prism_shell", Pattern.PROJECTILE)
            .timing(.85, .20, 1.40).cooldown(7.5).range(560).radius(20).count(9).ring().speed(245)
            .bounces(2).damage(5.0).telegraph("ring_gap")),
    /** 闭钳震波：近身时两钳向前闭合，120° 扇面、半径 160 px。 */
    CRAB_PINCER(spec(EnemyKind.PRISM_CRAB, WorldType.LIGHT, "skill2", "pincer_wave", Pattern.SECTOR)
            .timing(.75, .18, 1.30).cooldown(4.5).range(190).radius(160).angle(120).damage(10.0)
            .telegraph("sector")),
    /** 开钳双岸：左右各扫一条扇区，中间留下 50° 正前安全楔形。 */
    CRAB_TWO_SHORES(spec(EnemyKind.PRISM_CRAB, WorldType.SHADOW, "skill2", "pincer_wave", Pattern.SECTOR_SPLIT)
            .timing(.80, .18, 1.40).cooldown(4.8).range(190).radius(180).angle(65).safeGapDegrees(50).damage(10.0)
            .telegraph("split_sector")),
    /** 三点校射：锁定三个地面圆点，依次在释放后 0 / 0.75 / 1.5 秒爆炸。 */
    CRAB_BOMBARD(spec(EnemyKind.PRISM_CRAB, WorldType.LIGHT, "skill3", "bombard_field", Pattern.MULTI_MARK)
            .timing(.95, 1.65, 1.50).cooldown(8.0).range(620).radius(68).marks(3, 3)
            .offsets(0, 0.75, 1.5).spacing(145).damage(12.0)
            .hitPolicy(HitPolicy.ONCE_PER_MARK).telegraph("triple_circle")),
    /** 蚀潮封线：前方三段爆破带同时起爆，中间固定留 120 px 缺口。 */
    CRAB_LINE_BARRAGE(spec(EnemyKind.PRISM_CRAB, WorldType.SHADOW, "skill3", "bombard_field", Pattern.BAND)
            .timing(1.00, .20, 1.60).cooldown(8.5).range(560).distance(200).bandLength(420).width(70)
            .safeGap(120).segments(3).damage(11.0).telegraph("broken_line")),

    // ================= v2 Boss · 双镰螳爵 =================
    /** 曦刃二连：0 与 0.8 秒两段 110° 镰斩，第二段提前重画独立预警。 */
    MANTIS_DOUBLE_SLASH(spec(EnemyKind.MANTIS, WorldType.LIGHT, "attack", "scythe_arc", Pattern.SECTOR)
            .timing(.75, .95, 1.20).cooldown(3.8).range(175).radius(160).angle(110).damage(8.0)
            .offsets(0, 0.8).hitPolicy(HitPolicy.ONCE_PER_SWING).telegraph("sector")),
    /** 曦刃回旋：环身甩出八枚旋刃，碰到墙会反弹两次——近战首领也能把场地铺满。 */
    MANTIS_WHIRL(spec(EnemyKind.MANTIS, WorldType.LIGHT, "skill3", "scythe_arc", Pattern.PROJECTILE)
            .timing(.70, .20, 1.30).cooldown(6.8).range(520).radius(15).count(8).ring().speed(275)
            .bounces(1).damage(5.5).telegraph("ring_gap")),
    /** 逆手追镰：先横切再反手，第二段改变扇面方向但仍独立预警。 */
    MANTIS_REVERSE_SLASH(spec(EnemyKind.MANTIS, WorldType.SHADOW, "attack", "scythe_arc", Pattern.SECTOR)
            .timing(.80, .95, 1.30).cooldown(4.1).range(190).radius(175).angle(105).damage(8.0)
            .offsets(0, 0.8).secondAngleOffset(75).hitPolicy(HitPolicy.ONCE_PER_SWING).telegraph("sector")),
    /** 夜刃连斩波：四轮四向斩波，每轮整体转过 20°，越躲缝越窄。 */
    MANTIS_NIGHT_WAVES(spec(EnemyKind.MANTIS, WorldType.SHADOW, "skill3", "scythe_arc", Pattern.BARRAGE)
            .timing(.70, 1.10, 1.35).cooldown(7.0).range(520).radius(14).count(4).ring()
            .volleys(4, 0.42).angleStep(20).speed(290).damage(5.0).telegraph("ring_gap")),
    /** 穿庭突刺：沿固定 270 px 直线突进，撞墙停下，尾迹只装饰不伤害。 */
    MANTIS_LUNGE(spec(EnemyKind.MANTIS, WorldType.LIGHT, "skill2", "dash_trail", Pattern.CHARGE)
            .timing(.75, .28, 1.40).cooldown(5.0).range(300).distance(270).width(66).damage(10.0)
            .stopOnWall().telegraph("line")),
    /** 折步掠杀：先侧步 70 px，再沿提前显示的折线路径突刺 220 px。 */
    MANTIS_BENT_LUNGE(spec(EnemyKind.MANTIS, WorldType.SHADOW, "skill2", "dash_trail", Pattern.CHARGE)
            .timing(.90, .35, 1.55).cooldown(5.8).range(300).distance(220).sideStep(70).width(66).damage(11.0)
            .stopOnWall().telegraph("bent_line")),
    /** 架镰反切：架镰时正面减伤 60%；仍等完整预警结束才反切。 */
    MANTIS_COUNTER(spec(EnemyKind.MANTIS, WorldType.LIGHT, "skill3", "counter_arc", Pattern.SECTOR)
            .timing(.85, .20, 1.60).cooldown(7.0).range(185).radius(165).angle(100).damage(10.0)
            .guard(.60, 120).telegraph("sector")),
    /** 空架诱斩：假架势不减伤，随后固定大横斩；长收招暴露胸甲。 */
    MANTIS_EMPTY_COUNTER(spec(EnemyKind.MANTIS, WorldType.SHADOW, "skill3", "counter_arc", Pattern.SECTOR)
            .timing(.95, .20, 1.70).cooldown(7.5).range(225).radius(205).angle(150).damage(12.0)
            .telegraph("sector")),

    // ================= v2 Boss · 织巢蛛后 =================
    /** 织光束丝：发射一束丝矢，命中只伤害、不禁锢。 */
    WEAVER_SILK(spec(EnemyKind.WEAVER, WorldType.LIGHT, "attack", "silk_projectile", Pattern.PROJECTILE)
            .timing(.80, .15, 1.00).cooldown(3.4).range(560).radius(14).count(1).speed(260).damage(8.0)
            .telegraph("line")),
    /** 网丝环射：十二枚丝梭环形铺满整间房，缝隙只是暂时的。 */
    WEAVER_SILK_RING(spec(EnemyKind.WEAVER, WorldType.LIGHT, "skill3", "silk_projectile", Pattern.PROJECTILE)
            .timing(.85, .15, 1.35).cooldown(6.8).range(560).radius(13).count(12).ring().speed(255)
            .damage(4.0).telegraph("ring_gap")),
    /** 叉影丝梭：三枚丝梭以 36° 扇形射出，共享一次命中；丝线不牵引玩家。 */
    WEAVER_TRISHOT(spec(EnemyKind.WEAVER, WorldType.SHADOW, "attack", "silk_projectile", Pattern.PROJECTILE)
            .timing(.85, .15, 1.15).cooldown(3.7).range(560).radius(13).count(3).spread(36).speed(240).damage(4.0)
            .telegraph("sector")),
    /** 夜网回弹：八枚丝梭环射并反弹两次，把她的网铺满整间房。 */
    WEAVER_NIGHT_RICOCHET(spec(EnemyKind.WEAVER, WorldType.SHADOW, "skill3", "silk_projectile", Pattern.PROJECTILE)
            .timing(.85, .15, 1.40).cooldown(7.0).range(560).radius(13).count(8).ring().speed(225)
            .bounces(2).damage(5.0).telegraph("ring_gap")),
    /** 孵辉巢：打开两处巢门，召唤曜甲虫与晨露孢囊。 */
    WEAVER_BROOD_LIGHT(spec(EnemyKind.WEAVER, WorldType.LIGHT, "skill2", "spawn_portal", Pattern.SUMMON)
            .timing(.95, .15, 1.25).cooldown(12.0).range(620).damage(0.0).hitPolicy(HitPolicy.NONE)
            .telegraph("spawn")),
    /** 孵影巢：打开两处巢门，召唤掘影虫与夜钩蝠。 */
    WEAVER_BROOD_SHADOW(spec(EnemyKind.WEAVER, WorldType.SHADOW, "skill2", "spawn_portal", Pattern.SUMMON)
            .timing(.95, .15, 1.25).cooldown(12.0).range(620).damage(0.0).hitPolicy(HitPolicy.NONE)
            .telegraph("spawn")),
    /** 留门丝阵：半径 210 px 的网环只爆发一次，留下朝向固定的 100° 缺口。 */
    WEAVER_WEB_RING(spec(EnemyKind.WEAVER, WorldType.LIGHT, "skill3", "web_field", Pattern.RING_GAP)
            .timing(.95, .20, 1.45).cooldown(7.0).range(210).innerRadius(120).outerRadius(210)
            .safeGapDegrees(100).damage(9.0).telegraph("ring_gap")),
    /** 交错夜网：两条网带相隔 0.85 秒依次收紧，各留 120 px 缺口。 */
    WEAVER_NIGHT_WEB(spec(EnemyKind.WEAVER, WorldType.SHADOW, "skill3", "web_field", Pattern.BAND)
            .timing(1.00, 1.00, 1.50).cooldown(7.8).range(400).distance(190).bandLength(380).width(64)
            .safeGap(120).segments(2).offsets(0, 0.85).hitPolicy(HitPolicy.ONCE_PER_BAND)
            .damage(5.0).telegraph("cross_gap")),

    // ================= v2 Boss · 根冠古树 =================
    /** 循迹根刺：沿锁定直线长出三根根刺，整条线路共享一次命中。 */
    ROOTKING_SPIKES(spec(EnemyKind.ROOTKING, WorldType.LIGHT, "attack", "root_spike", Pattern.LINE)
            .timing(.85, .20, 1.20).cooldown(4.0).range(300).distance(270).width(74).segments(3).damage(10.0)
            .telegraph("line")),
    /** 冠落连播：三轮晶籽环射，每轮转过 12°，弹幕会像落叶一样持续压下来。 */
    ROOTKING_SEED_STORM(spec(EnemyKind.ROOTKING, WorldType.LIGHT, "skill3", "seed_projectile", Pattern.BARRAGE)
            .timing(.90, 1.20, 1.45).cooldown(7.0).range(540).radius(12).count(9).ring()
            .volleys(3, 0.5).angleStep(12).speed(180).damage(4.0).telegraph("ring_gap")),
    /** 回生暗根：先近后远两条根带，间隔 0.85 秒，第二条始终有独立预警。 */
    ROOTKING_BANDS(spec(EnemyKind.ROOTKING, WorldType.SHADOW, "attack", "root_spike", Pattern.BAND)
            .timing(.95, 1.00, 1.30).cooldown(4.7).range(350).distance(150).laneSpacing(160).bandLength(340)
            .width(70).segments(1).offsets(0, 0.85).hitPolicy(HitPolicy.ONCE_PER_BAND).damage(10.0)
            .telegraph("parallel_lines")),
    /** 枯籽回响：八枚枯籽环形撒出并反弹两次，逼玩家离开墙边。 */
    ROOTKING_SEED_RICOCHET(spec(EnemyKind.ROOTKING, WorldType.SHADOW, "skill3", "seed_projectile", Pattern.PROJECTILE)
            .timing(.90, .20, 1.45).cooldown(7.2).range(540).radius(13).count(8).ring().speed(200)
            .bounces(2).damage(5.0).telegraph("ring_gap")),
    /** 生篱分庭：长出两段持续 4 秒的根篱，留 140 px 通路；根篱只阻挡，不接触掉血。 */
    ROOTKING_WALLS(spec(EnemyKind.ROOTKING, WorldType.LIGHT, "skill2", "root_wall", Pattern.WALL)
            .timing(1.10, .20, 1.30).cooldown(9.0).range(400).distance(170).wallDuration(4.0).safeGap(140)
            .damage(0.0).hitPolicy(HitPolicy.NONE).telegraph("broken_line")),
    /** 枯篱错门：先撤旧篱，再在不同位置长新篱；至少有 1 秒旧新墙都不阻挡的过渡。 */
    ROOTKING_SHIFT_WALLS(spec(EnemyKind.ROOTKING, WorldType.SHADOW, "skill2", "root_wall", Pattern.WALL)
            .timing(1.20, .20, 1.45).cooldown(9.5).range(400).distance(170).wallDuration(4.0).safeGap(140)
            .wallTransition(1.0).damage(0.0).hitPolicy(HitPolicy.NONE).telegraph("broken_line")),
    /** 冠落晶籽：环向发射 8 枚低速晶籽，保留 90° 缺口。 */
    ROOTKING_SEEDS(spec(EnemyKind.ROOTKING, WorldType.LIGHT, "skill3", "seed_projectile", Pattern.PROJECTILE)
            .timing(.95, .15, 1.40).cooldown(6.5).range(400).radius(12).count(8).speed(170).safeGapDegrees(90)
            .damage(4.0).telegraph("ring_gap")),
    /** 枯籽迟爆：向三处已标记地面撒籽，落地后再等 0.9 秒同时爆发；种子飞行时不伤害。 */
    ROOTKING_SEED_MARKS(spec(EnemyKind.ROOTKING, WorldType.SHADOW, "skill3", "seed_projectile", Pattern.MULTI_MARK)
            .timing(1.00, .20, 1.50).cooldown(7.5).range(620).radius(68).marks(3, 3).spacing(150)
            .delayedBurst(0.9).damage(11.0).telegraph("triple_circle")),

    // ================= v2 Boss · 镜砂术士 =================
    /** 映昼镜矢：本体与两幅无碰撞幻象一起抬手，仅本体对应的矢伤害。 */
    HOURGLASS_SHARD(spec(EnemyKind.HOURGLASS, WorldType.LIGHT, "attack", "mirror_shard", Pattern.PROJECTILE)
            .timing(.80, .18, 1.05).cooldown(3.6).range(620).radius(16).count(1).speed(300).damage(9.0)
            .illusions(2).telegraph("line")),
    /** 千镜连照：两轮十向镜矢，第二轮转过 18°，连同幻象一起把整间房变成镜厅。 */
    HOURGLASS_MIRROR_HALL(spec(EnemyKind.HOURGLASS, WorldType.LIGHT, "skill3", "mirror_shard", Pattern.BARRAGE)
            .timing(.80, 1.05, 1.30).cooldown(6.6).range(620).radius(15).count(10).ring()
            .volleys(2, 0.5).angleStep(18).speed(285).damage(4.5).illusions(2).telegraph("ring_gap")),
    /** 逆砂回响：首矢后 0.8 秒沿原角度补一枚回响；第二条预警独立倒计时，不重新追踪。 */
    HOURGLASS_ECHO(spec(EnemyKind.HOURGLASS, WorldType.SHADOW, "attack", "mirror_shard", Pattern.PROJECTILE)
            .timing(.85, .95, 1.20).cooldown(4.2).range(620).radius(15).count(1).speed(280).damage(8.0)
            .offsets(0, 0.8).hitPolicy(HitPolicy.ONCE_PER_PROJECTILE).telegraph("line")),
    /** 镜砂乱弹：九枚镜片环形铺开并反弹两次——「真假」这回交给弹道去讲。 */
    HOURGLASS_SHARD_RICOCHET(spec(EnemyKind.HOURGLASS, WorldType.SHADOW, "skill3", "mirror_shard", Pattern.PROJECTILE)
            .timing(.80, .20, 1.35).cooldown(6.8).range(620).radius(14).count(9).ring().speed(265)
            .bounces(2).damage(5.0).telegraph("ring_gap")),
    /** 三镜校时：出现三个圈，只有实线带齿圆圈伤害；另外两个为虚线空心假圈。 */
    HOURGLASS_TRUE_CIRCLES(spec(EnemyKind.HOURGLASS, WorldType.LIGHT, "skill2", "echo_ripple", Pattern.MULTI_MARK)
            .timing(1.00, .20, 1.40).cooldown(7.0).range(560).radius(78).marks(3, 1).spacing(150)
            .damage(10.0).telegraph("triple_circle")),
    /** 错秒真影：两个真圈依次在 0 / 0.8 秒爆发，一个假圈从头到尾是假。 */
    HOURGLASS_FALSE_CIRCLES(spec(EnemyKind.HOURGLASS, WorldType.SHADOW, "skill2", "echo_ripple", Pattern.MULTI_MARK)
            .timing(1.10, .95, 1.50).cooldown(7.8).range(560).radius(76).marks(3, 2).spacing(150)
            .offsets(0, 0.8).hitPolicy(HitPolicy.ONCE_PER_MARK).damage(10.0).telegraph("triple_circle")),
    /** 映面换位：起终点同时打开镜门，换到 180～360 px 外合法点。 */
    HOURGLASS_GATE(spec(EnemyKind.HOURGLASS, WorldType.LIGHT, "skill3", "rift_portal", Pattern.TELEPORT)
            .timing(.90, .20, 1.10).cooldown(6.0).range(600).teleport(180, 360).damage(0.0).crossWalls()
            .hitPolicy(HitPolicy.NONE).telegraph("spawn")),
    /** 逆砂遁形：起终点镜门外额外留下两幅假影；假影不攻击、不挡路。 */
    HOURGLASS_SHADOW_GATE(spec(EnemyKind.HOURGLASS, WorldType.SHADOW, "skill3", "rift_portal", Pattern.TELEPORT)
            .timing(.90, .20, 1.10).cooldown(6.0).range(600).teleport(180, 360).illusions(2).crossWalls()
            .damage(0.0).hitPolicy(HitPolicy.NONE).telegraph("spawn"));

    /** 出招图案：决定释放阶段生成什么样的攻击实体。 */    public enum Pattern {
        PROJECTILE, SPREAD, FAN, RING, DOUBLE_RING, ARC, DOUBLE_ARC, DASH, DASH_NO_DAMAGE, CRACK,
        MARK, TRIPLE_MARK, RING_AREA, SUMMON,
        /** 本体沿锁定直线冲撞，路径上留下一次命中的伤害带（甲虫顶撞、螳爵突刺、翼蝠俯冲）。 */
        CHARGE,
        /** 掘进到锁定落点后破土，出一圈判定（甲虫暗形态）。 */
        BURROW,
        /** 精确扇面；{@code safeGapDegrees} 不为 0 时表示正前留安全楔。 */
        SECTOR,
        /** 安全楔两侧各扫一条扇区（炮蟹暗形态「开钳双岸」）。 */
        SECTOR_SPLIT,
        /** 从本体向锁定方向伸出的矩形长条（根刺、根带）。 */
        LINE,
        /** 内 / 外半径的环带，留下固定角度缺口（留门丝阵）。 */
        RING_GAP,
        /** 横向带状判定，可留固定宽度缺口、可按 hitOffsets 多段（封线、夜网）。 */
        BAND,
        /** 若干地面标记，其中 realMarkCount 个是真伤害（炮蟹校射、古树枯籽、术士真假圈）。 */
        MULTI_MARK,
        /** 镜门换位：本体瞬移到合法落点，不造成伤害。 */
        TELEPORT,
        /** 根篱：临时阻挡地形，不造成伤害。 */
        WALL,
        /**
         * 多轮弹幕：一次施法按 {@code volleyInterval} 连发 {@code volleys} 轮，
         * 每轮 {@code count} 发、按 {@code angleDegrees} 铺开，且整轮再转过 {@code angleStep}。
         *
         * <p>这是「全图弹幕」的实现方式：单轮环形已经覆盖 360°，多轮错开角度之后
         * 缝隙一直在移动，玩家没法记住一个安全点站着不动。
         */
        BARRAGE
    }

    /**
     * 命中结算策略：同一招式内的多段攻击用哪种去重标识。
     *
     * <p>全局 0.65 秒受击保护之外还要这层去重，是因为多段招式的间隔被刻意拉到 0.75～0.85 秒
     * （不被保护帧吃掉），如果共用标识就只算一段，如果各算各的又会比设计多打一次。
     */
    public enum HitPolicy {
        /** 不造成伤害（纯位移、召唤、筑墙）。 */
        NONE,
        /** 整次施法只结算一次（弹体 + 爆炸、多根根刺、封线三段都共用同一个标识）。 */
        ONCE_PER_CAST,
        /** 每一下挥砍各结算一次（二连斩）。 */
        ONCE_PER_SWING,
        /** 每条带各结算一次（交错夜网、回生暗根）。 */
        ONCE_PER_BAND,
        /** 每个标记各结算一次（三点校射、错秒真影）。 */
        ONCE_PER_MARK,
        /** 每枚弹体各结算一次（逆砂回响）。 */
        ONCE_PER_PROJECTILE,
        /** 每次脉冲各结算一次（暮孢滞留的雾区）。 */
        ONCE_PER_PULSE
    }

    private final EnemyKind kind;
    private final WorldType world;
    private final String actionBase;
    private final String effect;
    private final Pattern pattern;
    private final double windup, active, recovery, cooldown, range, radius, speed, damage;
    private final double angleDegrees, safeGapDegrees, distance, width, bandLength, laneSpacing, safeGap;
    private final double innerRadius, outerRadius, sideStep, secondAngleOffset;
    private final double delayedBurstSeconds, wallDuration, wallTransition, teleportMin, teleportMax, clearance;
    private final int count, markCount, realMarkCount, segmentCount, illusions;
    /** 弹体撞墙反弹次数；0 表示撞墙即消失。 */
    private final int bounces;
    /** 多轮弹幕的轮数、轮间隔与每轮的整体旋转角。 */
    private final int volleys;
    private final double volleyInterval, angleStep;
    private final double[] hitOffsets;
    private final double[] pulses;
    private final HitPolicy hitPolicy;
    private final boolean stopOnWall, contactDamage, explodeOnImpact, crossWalls, pulseClearable;
    private final double guardReduction, guardAngleDegrees;
    private final String telegraph;

    private EnemySkill(Spec s) {
        this.kind = s.kind;
        this.world = s.world;
        this.actionBase = s.actionBase;
        this.effect = s.effect;
        this.pattern = s.pattern;
        this.windup = s.windup;
        this.active = s.active;
        this.recovery = s.recovery;
        this.cooldown = s.cooldown;
        this.range = s.range;
        this.radius = s.radius;
        this.speed = s.speed;
        this.damage = s.damage;
        this.angleDegrees = s.angleDegrees;
        this.safeGapDegrees = s.safeGapDegrees;
        this.distance = s.distance;
        this.width = s.width;
        this.bandLength = s.bandLength;
        this.laneSpacing = s.laneSpacing;
        this.safeGap = s.safeGap;
        this.innerRadius = s.innerRadius;
        this.outerRadius = s.outerRadius;
        this.sideStep = s.sideStep;
        this.secondAngleOffset = s.secondAngleOffset;
        this.delayedBurstSeconds = s.delayedBurstSeconds;
        this.wallDuration = s.wallDuration;
        this.wallTransition = s.wallTransition;
        this.teleportMin = s.teleportMin;
        this.teleportMax = s.teleportMax;
        this.clearance = s.clearance;
        this.count = s.count;
        this.markCount = s.markCount;
        this.realMarkCount = s.realMarkCount;
        this.segmentCount = s.segmentCount;
        this.illusions = s.illusions;
        this.volleys = Math.max(1, s.volleys);
        this.volleyInterval = s.volleyInterval;
        this.angleStep = s.angleStep;
        this.hitOffsets = s.hitOffsets == null ? new double[0] : s.hitOffsets.clone();
        this.pulses = s.pulses == null ? new double[0] : s.pulses.clone();
        // 多轮弹幕与反弹弹体默认「每枚弹体各算一次」：一发一发的弹幕如果共用同一个命中标识，
        // 整轮 36 发里就只有第一发能打中玩家，后面的等于白放。
        this.bounces = Math.min(com.phantomcorridor.config.GameConfig.ENEMY_PROJECTILE_MAX_BOUNCES,
                Math.max(0, s.bounces));
        this.hitPolicy = s.hitPolicy != null ? s.hitPolicy
                : (s.pattern == Pattern.BARRAGE || this.bounces > 0
                        ? HitPolicy.ONCE_PER_PROJECTILE : HitPolicy.ONCE_PER_CAST);
        this.stopOnWall = s.stopOnWall;
        this.contactDamage = s.contactDamage;
        this.explodeOnImpact = s.explodeOnImpact;
        this.crossWalls = s.crossWalls;
        this.pulseClearable = s.pulseClearable;
        this.guardReduction = s.guardReduction;
        this.guardAngleDegrees = s.guardAngleDegrees;
        this.telegraph = s.telegraph;
    }

    public EnemyKind kind() { return kind; }
    public WorldType world() { return world; }
    public String actionBase() { return actionBase; }
    public String effect() { return effect; }
    public Pattern pattern() { return pattern; }
    public double windup() { return windup; }
    /** 本体释放阶段占用时间（秒）：不等于攻击实体寿命。 */
    public double active() { return active; }
    public double recovery() { return recovery; }
    public double cooldown() { return cooldown; }
    public double range() { return range; }
    public double radius() { return radius; }
    public int count() { return count; }
    public double speed() { return speed; }
    public double angleDegrees() { return angleDegrees; }
    public double safeGapDegrees() { return safeGapDegrees; }
    public double distance() { return distance; }
    public double width() { return width; }
    public double bandLength() { return bandLength; }
    public double laneSpacing() { return laneSpacing; }
    public double safeGap() { return safeGap; }
    public double innerRadius() { return innerRadius; }
    public double outerRadius() { return outerRadius; }
    public double sideStep() { return sideStep; }
    public double secondAngleOffset() { return secondAngleOffset; }
    public double delayedBurstSeconds() { return delayedBurstSeconds; }
    public double wallDuration() { return wallDuration; }
    public double wallTransition() { return wallTransition; }
    public double teleportMin() { return teleportMin; }
    public double teleportMax() { return teleportMax; }
    public double clearance() { return clearance; }
    public int markCount() { return markCount; }
    public int realMarkCount() { return realMarkCount == 0 ? markCount : realMarkCount; }
    public int segmentCount() { return Math.max(1, segmentCount); }
    public int illusions() { return illusions; }
    /** 弹体撞墙反弹次数；0 表示撞墙即消失。 */
    public int bounces() { return bounces; }
    /** 是否是需要碰墙反弹的弹体。 */
    public boolean bouncing() { return bounces > 0; }
    /** 多轮弹幕的轮数（非 BARRAGE 图案恒为 1）。 */
    public int volleys() { return volleys; }
    /** 多轮弹幕的轮间隔（秒）。 */
    public double volleyInterval() { return volleyInterval; }
    /** 多轮弹幕每一轮整体旋转的角度（度）。 */
    public double angleStep() { return angleStep; }
    public double[] hitOffsets() { return hitOffsets.clone(); }
    public double[] pulses() { return pulses.clone(); }
    public HitPolicy hitPolicy() { return hitPolicy; }
    public boolean stopOnWall() { return stopOnWall; }
    /** 飞行中的弹体是否造成接触伤害（孢子、枯籽只有落地后的区域伤害）。 */
    public boolean contactDamage() { return contactDamage; }
    /** 撞墙 / 耗尽时是否立刻炸开一次范围伤害（聚棱炮）。 */
    public boolean explodeOnImpact() { return explodeOnImpact; }
    public boolean crossWalls() { return crossWalls; }
    public boolean pulseClearable() { return pulseClearable; }
    public double guardReduction() { return guardReduction; }
    public double guardAngleDegrees() { return guardAngleDegrees; }
    public String telegraph() { return telegraph; }

    /** 是否是多段伤害：渲染层据此画分段预警，测试据此校验每段的命中标识。 */
    public boolean multiSegment() { return hitOffsets.length > 1; }

    /** 是否是环形弹幕（把 count 发均匀铺满整圈，而不是沿一个小夹角排成一排）。 */
    public boolean isRing() {
        return angleDegrees >= com.phantomcorridor.config.GameConfig.ENEMY_RING_COVERAGE - 1.0;
    }

    /** 该招式是否会在前摇期间提供正面减伤。 */
    public boolean guardsDuringWindup() { return guardReduction > 0.0; }

    public static List<EnemySkill> forEnemy(EnemyKind kind, WorldType world) {
        return Arrays.stream(values()).filter(skill -> skill.kind == kind && skill.world == world).toList();
    }

    /** 该攻击造成的伤害类型：按招式所在的世界归类。 */
    public DamageType damageType() { return DamageType.ofWorld(world); }

    /**
     * 该招式打中玩家一次造成的伤害（玩家 100 点生命刻度）。
     *
     * <p><b>为什么逐招配数值</b>：旧实现里所有伤害都在结算处写死成 1 点，
     * 于是傀儡的践踏和灯魇的一发小弹打掉的血一模一样——怪物的压迫感完全没有差别，
     * 玩家也读不出“这一下很疼、那一下可以硬吃”。现在伤害跟着招式走：
     * 起手长、范围大、看得见的重招打得更疼，廉价的小弹只削一层皮。
     *
     * <p>数值分档（玩家满血 100）：
     * <ul>
     *   <li>3～4 —— 低威胁小弹（暗针、扇面弹、钟波、丝梭、晶籽）；</li>
     *   <li>5～7.5 —— 普通单发 / 近身（追踪光球、镜面斩、地裂、扑咬、孢子绽放、甲虫顶撞）；</li>
     *   <li>8～10 —— 精英与首领的正经招式（长矛 / 盾击、光柱、炮击、镰斩、根刺）；</li>
     *   <li>11～13 —— 压轴重招（裂隙斩、日审判、冲锋、三点校射、枯籽迟爆）。</li>
     * </ul>
     */
    public double damage() { return damage; }

    /** 起始构建器：枚举常量的参数表。 */
    private static Spec spec(EnemyKind kind, WorldType world, String actionBase, String effect, Pattern pattern) {
        return new Spec(kind, world, actionBase, effect, pattern);
    }

    /**
     * 技能参数表：字段多且大半可选，用链式赋值比位置参数更不容易写错，
     * 也让「哪一条参数属于哪一招」在源码里一眼可读。
     */
    private static final class Spec {
        private final EnemyKind kind;
        private final WorldType world;
        private final String actionBase;
        private final String effect;
        private final Pattern pattern;
        private double windup = .6, active = .16, recovery = .6, cooldown = 2.5, range = 200;
        private double radius = 40, speed, damage;
        /**
         * 相邻弹体之间的夹角（度）。
         *
         * <p>注意这是**每一跳**的角度，不是整片扇面的总跨度：5 发 × 17.5° 得到 70° 宽的一排。
         * 想打一整圈请用 {@link #ring()}——直接写 360 只会让相邻两发隔了整整一圈，
         * 结果所有弹体都朝着同一个方向飞，而且是背着玩家的那一边。
         */
        private double angleDegrees;
        private double safeGapDegrees, distance, width, bandLength, laneSpacing, safeGap;
        private double innerRadius, outerRadius, sideStep, secondAngleOffset;
        private double delayedBurstSeconds, wallDuration, wallTransition, teleportMin, teleportMax;
        private double clearance = 100;
        private int count = 1, markCount, realMarkCount, segmentCount, illusions, bounces;
        private int volleys = 1;
        private double volleyInterval = .45, angleStep;
        private double[] hitOffsets, pulses;
        /** 不显式指定时按图案推导：多轮弹幕与反弹弹体各自结算，其余整次施法一次。 */
        private HitPolicy hitPolicy;
        private boolean stopOnWall, contactDamage = true, explodeOnImpact, crossWalls, pulseClearable = true;
        private double guardReduction, guardAngleDegrees = 120;
        private String telegraph = "circle";

        private Spec(EnemyKind kind, WorldType world, String actionBase, String effect, Pattern pattern) {
            this.kind = kind;
            this.world = world;
            this.actionBase = actionBase;
            this.effect = effect;
            this.pattern = pattern;
        }
        private Spec timing(double windup, double active, double recovery) {
            this.windup = windup; this.active = active; this.recovery = recovery; return this;
        }
        private Spec cooldown(double value) { cooldown = value; return this; }
        private Spec range(double value) { range = value; return this; }
        private Spec radius(double value) { radius = value; return this; }
        private Spec speed(double value) { speed = value; return this; }
        private Spec damage(double value) { damage = value; return this; }
        private Spec count(int value) { count = value; return this; }
        private Spec spread(double degrees) { angleDegrees = degrees; return this; }
        /** 环形弹幕：{@code count} 发均匀铺满 360°，其中一发正对锁定方向。 */
        private Spec ring() { angleDegrees = com.phantomcorridor.config.GameConfig.ENEMY_RING_COVERAGE; return this; }
        private Spec angle(double degrees) { angleDegrees = degrees; return this; }
        private Spec safeGap(double value) { safeGap = value; return this; }
        private Spec safeGapDegrees(double value) { safeGapDegrees = value; return this; }
        private Spec distance(double value) { distance = value; return this; }
        private Spec width(double value) { width = value; return this; }
        private Spec bandLength(double value) { bandLength = value; return this; }
        private Spec laneSpacing(double value) { laneSpacing = value; return this; }
        private Spec innerRadius(double value) { innerRadius = value; return this; }
        private Spec outerRadius(double value) { outerRadius = value; return this; }
        private Spec sideStep(double value) { sideStep = value; return this; }
        private Spec secondAngleOffset(double value) { secondAngleOffset = value; return this; }
        private Spec delayedBurst(double value) { delayedBurstSeconds = value; return this; }
        private Spec wallDuration(double value) { wallDuration = value; return this; }
        private Spec wallTransition(double value) { wallTransition = value; return this; }
        private Spec teleport(double min, double max) { teleportMin = min; teleportMax = max; return this; }
        private Spec marks(int total, int real) { markCount = total; realMarkCount = real; return this; }
        private Spec segments(int value) { segmentCount = value; return this; }
        private Spec illusions(int value) { illusions = value; return this; }
        /** 弹体撞墙反弹几次（超过上限会被 GameConfig 截断）。 */
        private Spec bounces(int value) { bounces = value; return this; }
        /** 多轮弹幕：轮数 + 轮间隔（秒）。 */
        private Spec volleys(int countValue, double intervalSeconds) {
            volleys = countValue; volleyInterval = intervalSeconds; return this;
        }
        /** 每一轮整体旋转的角度（度），用来让缝隙移动。 */
        private Spec angleStep(double degrees) { angleStep = degrees; return this; }
        private Spec spacing(double value) { safeGap = value; return this; }
        private Spec offsets(double... values) { hitOffsets = values; return this; }
        private Spec pulses(double... values) { pulses = values; return this; }
        private Spec hitPolicy(HitPolicy value) { hitPolicy = value; return this; }
        private Spec stopOnWall() { stopOnWall = true; return this; }
        private Spec noContactDamage() { contactDamage = false; return this; }
        private Spec explodeOnImpact() { explodeOnImpact = true; return this; }
        private Spec crossWalls() { crossWalls = true; return this; }
        private Spec guard(double reduction, double angleDegrees) {
            guardReduction = reduction; guardAngleDegrees = angleDegrees; return this;
        }
        private Spec telegraph(String value) { telegraph = value; return this; }
    }
}
