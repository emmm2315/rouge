package com.phantomcorridor.config;

/**
 * 游戏数值配置（对应新需求 §9.1 config 层 · GameConfig，玩家属性、能量回复参数）。
 *
 * <p>数值来源（《双界行者》项目需求说明书）：
 * <ul>
 *   <li>§3.2 玩家形态 —— 移动速度、攻击方式；</li>
 *   <li>§3.4 相位能量 —— 能量范围（0~100）、初始满值、切换清空、回复方式。</li>
 * </ul>
 *
 * <p>部分数值当前为设计占位值（如相位碎片回收量、脱战回复速率），
 * 将在对应系统（第 4 天能量、第 7 天掉落）落地时校准。
 */
public final class GameConfig {

    // ---- 玩家半径与移动（§3.2 光/影形态，§8.4 形态差异待世界切换系统接入） ----
    /** 玩家碰撞半径（像素） */
    /** 角色碰撞半径，覆盖披风/武器的主要身体范围。 */
    public static final double PLAYER_RADIUS = 28.0;

    /** 玩家基础移动速度（像素/秒，占位值，后续形体差异在此扩展） */
    public static final double PLAYER_BASE_SPEED = 200.0;

    /** 影形态额外移动速度加成倍率（§3.2 影形态"移动略快"，占位） */
    public static final double SHADOW_SPEED_MULTIPLIER = 1.15;

    // ---- 闪避冲刺（空格） ----
    /**
     * 冲刺距离（像素）。
     *
     * <p>固定距离而不是固定速度：形体的移动速度加成（影形态 1.15 倍）只影响走路，
     * 冲刺距离在两种形态下保持一致，玩家才能凭手感判断"能不能从这一击里翻出去"。
     */
    public static final double DASH_DISTANCE = 150.0;

    /** 冲刺持续时间（秒）：冲完这段位移即恢复控制。 */
    public static final double DASH_DURATION = 0.18;

    /** 冲刺速度（像素/秒）：由固定距离与持续时间推出，约 833 像素/秒。 */
    public static final double DASH_SPEED = DASH_DISTANCE / DASH_DURATION;

    /**
     * 冲刺内置冷却（秒）：从冲刺结束开始计时，冷却中再次按空格不会触发。
     *
     * <p>冷却从"冲完"而不是"起手"开始算，这样 1 秒就是两次冲刺之间实际要等的间隔。
     */
    public static final double DASH_COOLDOWN = 1.0;

    /**
     * 冲刺残影（拖尾）单段存活时间（秒）。
     *
     * <p>略长于冲刺本身，冲刺结束后拖尾会自然消散，而不是跟着动作一起"啪"地消失。
     */
    public static final double DASH_TRAIL_LIFETIME = 0.28;

    /** 冲刺残影的段数上限：寿命内按帧记录也不会超过这个数，避免异常长帧堆积贴图。 */
    public static final int DASH_TRAIL_MAX = 32;

    /** 冲刺残影的最初不透明度：越接近玩家本体越淡，避免拖尾盖住角色。 */
    public static final double DASH_TRAIL_ALPHA = 0.34;

    // ---- 第 3 天：双形态攻击 ----
    public static final double LIGHT_PROJECTILE_SPEED = 560.0;
    public static final double LIGHT_PROJECTILE_RADIUS = 6.0;
    public static final double LIGHT_PROJECTILE_LIFETIME = 1.8;
    public static final double LIGHT_ATTACK_COOLDOWN = 0.45;
    public static final double SHADOW_MELEE_RANGE = 155.0;
    public static final double SHADOW_MELEE_ARC_DEGREES = 330.0;
    public static final double SHADOW_MELEE_VISIBLE_TIME = 0.13;
    public static final double SHADOW_ATTACK_COOLDOWN = 0.55;
    /**
     * 攻击充能（蓝条）上限：决定一段连射能打多少发，充能耗尽后按
     * {@link #ATTACK_CHARGE_RECOVERY_TIME} 逐发回复。
     *
     * <p>原先只有 5 发，房间里的敌人变多、又会整房索敌之后明显不够用，
     * 这里放宽到 10 发；后续道具（通用/双界）会在此之上再加容量。
     */
    public static final int ATTACK_CHARGE_MAX = 10;
    public static final int LIGHT_ATTACK_CHARGE_COST = 1;
    public static final int SHADOW_ATTACK_CHARGE_COST = 1;
    public static final double ATTACK_CHARGE_RECOVERY_TIME = 1.35;

    // ---- 第 6 天：敌人与房间战斗 ----
    /** 普通战斗房的敌人数量（含可能替换其中一只的精英）。 */
    public static final int BATTLE_ENEMY_MIN = 5;
    public static final int BATTLE_ENEMY_MAX = 7;

    // ---- 索敌（敌人 AI 感知范围） ----
    /** 敌人生成 / 重新回到当前世界后的首次开火前摇（秒），玩家进房后有一段可反应的缓冲。 */
    public static final double ENEMY_ALERT_TIME = 0.6;

    /**
     * 索敌是否覆盖整个房间。
     *
     * <p>开启后，同界敌人只要与玩家同处一房就会主动接近并攻击：房间内不再存在
     * “站得太远所以完全不动”的敌人（旧版各物种只有 240～560 像素的索敌距离，
     * 房间对角线却有约 1600 像素，站在另一头的敌人永远不会参战）。
     * 关闭时退回到 {@link #ENEMY_DETECTION_RANGE} 的保守范围。
     */
    public static final boolean ENEMY_AGGRO_WHOLE_ROOM = true;

    /** 整房索敌半径（像素）：取房间外接矩形对角线，保证房间内任何位置都在索敌范围内。 */
    public static final double ENEMY_ROOM_AGGRO_RADIUS = Math.hypot(RoomConfig.ROOM_WIDTH, RoomConfig.ROOM_HEIGHT);

    /** 未开启整房索敌时使用的固定索敌半径（像素，沿用旧版最远的守望者数值）。 */
    public static final double ENEMY_DETECTION_RANGE = 560.0;

    // ---- 敌人站位与开火距离 ----
    /** 远程敌人（灯灵/法师/鸣钟者/守望者）的开火距离（像素）。 */
    public static final double ENEMY_RANGED_ATTACK_RANGE = 620.0;

    /** 近战敌人（影狼/傀儡/处刑者）的开火距离（像素）。 */
    public static final double ENEMY_MELEE_ATTACK_RANGE = 150.0;

    /** 远程敌人保持的站位距离（像素）。 */
    public static final double ENEMY_RANGED_STANDOFF_DISTANCE = 190.0;

    /** 近战敌人保持的站位距离（像素）。 */
    public static final double ENEMY_MELEE_STANDOFF_DISTANCE = 105.0;

    // ---- 敌人贴墙绕行（简易局部寻路） ----
    /** 选择绕行方向时，沿途探测障碍的步长（像素）。 */
    public static final double ENEMY_AVOIDANCE_PROBE_STEP = 12.0;

    /** 选择绕行方向时的最大探测距离（像素）。 */
    public static final double ENEMY_AVOIDANCE_PROBE_DISTANCE = 120.0;

    /** 沿选定一侧完全走不动多久后改走另一侧（秒）：避免敌人被障碍卡死。 */
    public static final double ENEMY_AVOIDANCE_FLIP_TIME = 1.0;

    /**
     * 绕行时允许的最小转角余弦值：0 表示最多转 90°，负值表示允许略大于 90°。
     *
     * <p>禁止掉头是关键——贴着墙时“直线朝玩家走一步、绕行再退回原处”会互相抵消，
     * 敌人会永远停在同一个点上，看上去完全僵住。
     */
    public static final double ENEMY_AVOIDANCE_MIN_TURN_COSINE = -0.1;

    // ---- 敌人脱困（防止被玩家贴在墙上磨死） ----
    /** 连续这么久没能更接近玩家，就视为被墙卡死并触发脱困。 */
    public static final double ENEMY_STUCK_TIME = 2.0;

    /** 判定“确实更接近了”所需的最小进步距离（像素）。 */
    public static final double ENEMY_STUCK_PROGRESS_STEP = 10.0;

    /** 判定“还在移动”所需的最小位移（像素/窗口）：正在绕路的敌人不该被当成卡死。 */
    public static final double ENEMY_STUCK_MOVE_STEP = 64.0;

    /** 脱困时向外搜索合法落点的最大半径（像素）。 */
    public static final double ENEMY_ESCAPE_SEARCH_RADIUS = 220.0;

    /** 脱困搜索的逐圈步长（像素）。 */
    public static final double ENEMY_ESCAPE_SEARCH_STEP = 16.0;

    // ---- 守望者的裂隙闪现（追不上就穿过裂隙贴到玩家附近） ----
    /** 落点离玩家的最近距离（像素）：不要直接叠在玩家身上。 */
    public static final double WATCHER_BLINK_MIN_DISTANCE = 150.0;

    /** 在玩家周围搜索落点的最大半径（像素）。 */
    public static final double WATCHER_BLINK_SEARCH_RADIUS = 420.0;

    /** 单次闪现的最大位移（像素）：设定上是“短距”。 */
    public static final double WATCHER_BLINK_RANGE = 560.0;

    /** 闪现落地后的起手时间（秒）：给玩家一个反应窗口，避免贴脸瞬狙。 */
    public static final double WATCHER_BLINK_WINDUP = 0.9;

    /** 两次闪现之间的冷却（秒）：卡住也不会连闪。 */
    public static final double WATCHER_BLINK_COOLDOWN = 4.0;

    /** 裂隙特效的显示时间（秒）。 */
    public static final double WATCHER_BLINK_FLASH_TIME = 0.55;

    // ---- 守望者的召唤（Boss 战增援） ----
    /**
     * 单次召唤开出的裂隙数量。
     *
     * <p>与 {@link #WATCHER_SUMMON_MAX_ALIVE} 一起卡住场面：一次最多两只，
     * 场上最多四只，清掉之后才会再来一波。
     */
    public static final int WATCHER_SUMMON_COUNT = 2;

    /** 场上同时存在的召唤物上限（含还没成型的裂隙）：满了就先等玩家清场。 */
    public static final int WATCHER_SUMMON_MAX_ALIVE = 4;

    /** 两次召唤之间的冷却（秒）；血量阶段召唤不受它限制。 */
    public static final double WATCHER_SUMMON_COOLDOWN = 12.0;

    /**
     * 开场先让玩家与首领单挑这么久（秒），之后才允许第一次增援召唤。
     *
     * <p>没有这段缓冲，首领一进房就拉着四只小怪一起扑上来，玩家连它的招式都看不清。
     */
    public static final double WATCHER_SUMMON_OPENING_DELAY = 6.0;

    /**
     * 连续这么久打不到玩家（没有开火视线）就把增援喊出来，把玩家从掩体后面逼出来。
     *
     * <p>小怪身位小、跑得快，能挤进首领自己进不去的缝隙——这正是召唤存在的意义。
     */
    public static final double WATCHER_SUMMON_PRESSURE_TIME = 5.0;

    /** 裂隙成型时间（秒）：这段时间就是给玩家看的预警窗口。 */
    public static final double WATCHER_SUMMON_RIFT_TIME = 1.2;

    /** 裂隙离首领的距离范围（像素）。 */
    public static final double WATCHER_SUMMON_RIFT_MIN_DISTANCE = 130.0;

    /** 裂隙离首领的最大距离（像素）。 */
    public static final double WATCHER_SUMMON_RIFT_MAX_DISTANCE = 250.0;

    /** 两个裂隙之间的最小间距（像素）：避免两只召唤物叠在同一个点上。 */
    public static final double WATCHER_SUMMON_RIFT_SPACING = 110.0;

    /** 裂隙离玩家的最小距离（像素）：召唤物不许直接压在玩家头上出生。 */
    public static final double WATCHER_SUMMON_PLAYER_CLEARANCE = 140.0;

    /**
     * 召唤物的生命倍率。
     *
     * <p>召唤物照样吃层数成长与难度倍率，但比同层的房间怪更脆：
     * 四只一起上时玩家还打得动，否则“清小怪”会变成比打首领更累的活。
     */
    public static final double WATCHER_SUMMON_HP_SCALE = 0.7;

    /** 召唤物成型后的起手时间（秒）：刚钻出裂隙不会立刻贴脸开火。 */
    public static final double WATCHER_SUMMON_ALERT = 0.8;

    public static final double ENEMY_PROJECTILE_SPEED = 190.0;

    /**
     * 敌方弹体寿命（秒）：由最大开火距离反推，留 30% 余量。
     *
     * <p>旧值固定 3.0 秒只够飞 570 像素，从最远处开火的弹体会在半路自行消失；
     * 索敌范围扩大后这个数值必须跟着开火距离一起走。
     */
    public static final double ENEMY_PROJECTILE_LIFETIME = ENEMY_RANGED_ATTACK_RANGE * 1.3 / ENEMY_PROJECTILE_SPEED;

    public static final double ENEMY_PROJECTILE_RADIUS = 12.0;

    // ---- 玩家受击反应 ----
    /**
     * 受击后的无敌时间（秒）。
     *
     * <p>这段时间避免一帧内被重叠弹幕重复扣血；同时不许往回走太多，
     * 否则"贴着打"会变成完全打不中。
     */
    public static final double PLAYER_HIT_INVULNERABILITY = 0.65;

    /** 受击击退距离（像素）：沿伤害来源的反方向被推开。 */
    public static final double PLAYER_HIT_KNOCKBACK = 25.0;

    /** 受击贴图变淡的持续时间（秒）：比无敌时间短，是"挨了一下"的即时反馈。 */
    public static final double PLAYER_HIT_FLASH_TIME = 0.25;

    /**
     * 受击变淡的最大强度（0~1）：越大越接近被打白。
     *
     * <p>渲染层会按这个强度叠两遍 SCREEN，所以 0.9 已经能把角色洗到接近白色——
     * 再高一点就只剩轮廓，看不出是哪只角色、朝哪边了。
     */
    public static final double PLAYER_HIT_FLASH_STRENGTH = 0.9;

    // ---- 玩家生命 ----
    /**
     * 玩家最大生命值。
     *
     * <p>从 5 点改为 100 点：旧刻度下最小伤害就是 1 点（掉 20% 血），
     * 想区分“傀儡的践踏”和“灯魇的小弹”根本没有余量——任何差异都会被四舍五入吃掉。
     * 100 点刻度下每次受击大约掉 4～13 点，既能读出轻重，也够铺开护盾、
     * 吸血与后续道具加成。
     */
    public static final int PLAYER_MAX_HP = 100;

    /**
     * 一点「生命恢复药剂」回复的生命值（占最大生命的 10%）。
     *
     * <p>拾取物的 {@code amount} 是“几瓶药剂”，不是“几点血”，
     * 因此回血量随最大生命一起写在这里，避免把 2 点血这种旧刻度的数字漏在拾取逻辑里。
     */
    public static final int PLAYER_HEAL_PER_PICKUP = 10;

    // ---- 护盾（临时生命值） ----
    /**
     * 护盾容量。
     *
     * <p>约等于“半条命 + 一次普通小怪的伤害”：能稳定吃掉一次小怪的普通攻击，
     * 但吃不下精英或首领的一记重招——护盾的价值是把容错从 100 点拉到 130 点左右，
     * 而不是免死金牌。
     */
    public static final double PLAYER_SHIELD_CAPACITY = 30.0;

    // ---- HUD：生命 / 护盾条 ----
    /**
     * 血条与护盾条的像素尺寸。
     *
     * <p>刻意不占满 HUD 面板：生命条长 200 像素，右端到面板边缘还留着约 200 像素，
     * 一是给同排的层数/难度文字让位，二是血条一旦铺满整行就再也看不出“还差多少才满”，
     * 护盾条也要能叠在血条上而不出框。
     */
    public static final double HUD_HEALTH_BAR_WIDTH = 200.0;
    public static final double HUD_HEALTH_BAR_HEIGHT = 15.0;

    /**
     * 血条按每格多少点生命分段。
     *
     * <p>100 点生命配 20 点一格正好 5 格，和旧版“5 颗心”的读法对得上，
     * 但又保留了每格内部的小数进度——掉 7 点血看得出来，不必再靠猜。
     */
    public static final int HUD_HEALTH_SEGMENT_VALUE = 20;

    /** 护盾条高度：比血条略薄，画在血条下沿，避免两条叠在一起分不清。 */
    public static final double HUD_SHIELD_BAR_HEIGHT = 7.0;


    // ---- 相位能量（§3.4） ----
    /** 相位能量上限 */
    public static final double PHASE_ENERGY_MAX = 100.0;

    /** 初始相位能量（满格） */
    public static final double PHASE_ENERGY_INITIAL = 100.0;

    /** 每次切换世界所需能量（必须达到上限，切换后清空） */
    public static final double PHASE_ENERGY_PER_SWITCH = 100.0;

    /** 脱战后自动回复速率（点/秒，占位；§3.4"脱战一段时间后缓慢自动回复"） */
    public static final double PHASE_ENERGY_REGEN_PER_SEC = 8.0;

    /** 攻击命中获得充能（点/次，占位；§3.4"攻击/被击也能少量充能"） */
    public static final double PHASE_ENERGY_ON_ATTACK = 2.0;

    /** 被击获得充能（点/次，占位） */
    public static final double PHASE_ENERGY_ON_HIT = 4.0;

    /** 相位碎片拾取回复能量（点/个，占位；§3.4 与 §8.3 掉落） */
    public static final double PHASE_ENERGY_PER_FRAGMENT = 25.0;

    /** 切换世界后的冷却时间（秒，占位；§3.3"切换后进入冷却恢复期"） */
    public static final double WORLD_SWITCH_COOLDOWN = 0.6;

    /** 切界脉冲清除玩家周围敌方弹幕的半径。 */
    public static final double PHASE_PULSE_RADIUS = 118.0;

    /** 切界脉冲圆环的显示时间。 */
    public static final double PHASE_PULSE_VISIBLE_TIME = 0.32;

    // ---- 切界后的短时强化（§2.1 相位切换） ----
    /**
     * 切界后攻击充能（蓝条）恢复速度翻倍的持续时间（秒）。
     *
     * <p>切界会清空相位能量，玩家常常在没攻击充能时急着切界回能；给攻击充能一个短暂的
     * 双倍回复，让这段「切界等回能」的空窗也能更快攒出下一波攻击。
     */
    public static final double WORLD_SWITCH_CHARGE_BOOST_DURATION = 2.0;

    /** 切界后攻击充能恢复速度的倍率（恢复速度翻倍）。 */
    public static final double WORLD_SWITCH_CHARGE_BOOST_MULTIPLIER = 2.0;

    /**
     * 当前世界没有检测到敌人时，相位能量恢复速度的倍率。
     *
     * <p>玩家不小心切到一个已经没有敌人的世界后，只能干等相位能量自然回满；
     * 没有敌人时把回能速度翻倍，缩短这段白等的时间。
     */
    public static final double PHASE_ENERGY_EMPTY_WORLD_REGEN_MULTIPLIER = 2.0;

    // ---- 第 8 天：五层推进 ----
    /** 一局的总层数：打通最后一层的首领并走进传送门即通关。 */
    public static final int TOTAL_FLOORS = 5;

    /** 敌人生命值随层数的增长倍率：第 N 层为基础值的 {@code 1 + (N-1) * 该值} 倍。 */
    public static final double ENEMY_HP_GROWTH_PER_FLOOR = 0.25;

    /** 敌人防御（每次受击减免的伤害）随层数的增量。 */
    public static final int ENEMY_DEFENSE_PER_FLOOR = 1;

    /** 敌人防御上限：留出上限，避免高层的普通敌人硬到普通攻击打不动。 */
    public static final int ENEMY_DEFENSE_MAX = 3;

    /** 每层地图种子相对上一层的偏移：同一个种子下每层地图可复现、但互不相同。 */
    public static final long FLOOR_SEED_STEP = 7919L;

    // ---- 第 7 天：房间内容与商店 ----
    /** 玩家与拾取物/宝箱/事件的交互距离（像素）：提示与实际生效共用同一半径。 */
    public static final double INTERACT_RADIUS = 96.0;

    /**
     * 商店里已选中、等待二次确认的商品在多远之后自动取消选择（像素）。
     *
     * <p>比 {@link #INTERACT_RADIUS} 略大一点点形成回差，避免玩家在边界上微动就丢掉选择；
     * 但也不能大太多，否则玩家明明已经走开、回来时还停在“确认购买”上。
     */
    public static final double SHOP_CONFIRM_RESET_RADIUS = INTERACT_RADIUS + 16.0;

    /** 每个商店房上架的商品件数。 */
    public static final int SHOP_OFFER_COUNT = 2;

    /** 商店同屏商品之间的摆放间距（像素）：必须大于两倍交互半径，玩家才需要走近某一件才能选中它。 */
    public static final double SHOP_OFFER_SPACING = 240.0;

    /** 工具类：不允许实例化 */
    private GameConfig() {
    }
}
