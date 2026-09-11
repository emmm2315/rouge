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
     * <p>数值重构（战斗刻度 ×10）之后敌人生命值整体上了一个量级，如果充能上限还停在 10 发，
     * 打空一管蓝条就只够蹭掉一只小怪的血皮，房间会退化成「打五秒、等十秒」的换弹游戏。
     * 因此容量与回复速度一起放宽：满管 16 发、每 0.7 秒回一发，
     * 持续输出从 0.74 发/秒提到 1.43 发/秒，大血量目标才打得动。
     */
    public static final int ATTACK_CHARGE_MAX = 16;
    public static final int LIGHT_ATTACK_CHARGE_COST = 1;
    public static final int SHADOW_ATTACK_CHARGE_COST = 1;
    public static final double ATTACK_CHARGE_RECOVERY_TIME = 0.5;

    // ---- 装备专属效果（《新增 15 件装备与攻击特效设计》§五） ----
    /** 相位陀螺：成功切界后的攻速窗口时长（秒）与间隔倍率。 */
    public static final double PHASE_GYROSCOPE_WINDOW = 2.0;
    /** 夜行披风：影界攻击释放后的加速时长（秒）与移速倍率。 */
    public static final double NIGHTSTEP_CLOAK_DURATION = 0.45;
    public static final double NIGHTSTEP_CLOAK_SPEED = 1.18;
    /** 曜纹披肩：光界受击减伤比例与冷却（秒）。 */
    public static final double SUNWEAVE_MANTLE_REDUCTION = 0.30;
    public static final double SUNWEAVE_MANTLE_COOLDOWN = 6.0;
    /** 猎影牙饰：目标生命比例高于该值时才增伤。 */
    public static final double HUNTERS_FANG_HP_THRESHOLD = 0.70;
    public static final double HUNTERS_FANG_BONUS = 0.25;
    /** 余震指环：每第 N 轮攻击触发一次震波。 */
    public static final int RESONANCE_RING_INTERVAL = 4;
    public static final double RESONANCE_RING_LIGHT_RADIUS = 60.0;
    public static final double RESONANCE_RING_LIGHT_COEFFICIENT = 0.30;
    public static final double RESONANCE_RING_SHADOW_RANGE_SCALE = 0.75;
    public static final double RESONANCE_RING_SHADOW_COEFFICIENT = 0.25;
    /** 炽核权杖：爆裂半径与命中后的范围伤害系数。 */
    public static final double SOLAR_BURST_RADIUS = 80.0;
    /** 折镜法球：每次弹射的搜索半径与单段最大飞行距离。 */
    public static final double MIRROR_ORB_BOUNCE_RADIUS = 180.0;
    /** 归影双刃：往返的飞出行程时间（秒）与单段系数。 */
    public static final double RETURNING_FANG_FLIGHT_TIME = 0.22;
    public static final double RETURNING_FANG_COEFFICIENT = 0.55;

    // ---- 第 6 天：敌人与房间战斗 ----
    /** 普通战斗房的敌人数量（含可能替换其中一只的精英）。 */
    public static final int BATTLE_ENEMY_MIN = 5;
    public static final int BATTLE_ENEMY_MAX = 7;

    /** 每往下一层，战斗房多刷几只怪：第 N 层 = 基础数量 + (N-1) × 该值。 */
    public static final int BATTLE_ENEMY_PER_FLOOR = 1;

    /** 单间战斗房的敌人数量硬上限：再多就会把房间挤成一团、也打不动。 */
    public static final int BATTLE_ENEMY_MAX_CAP = 12;

    /** 精英替换普通怪的起始层数：第 1 层只出普通怪，让玩家先认熟基础招式。 */
    public static final int BATTLE_ELITE_MIN_FLOOR = 2;

    /** 精英替换概率 = 基础 + (层数-1) × 每层增量，再封顶。 */
    public static final double BATTLE_ELITE_CHANCE_BASE = 0.20;
    public static final double BATTLE_ELITE_CHANCE_PER_FLOOR = 0.10;
    public static final double BATTLE_ELITE_CHANCE_CAP = 0.60;

    /**
     * 战斗房在第 N 层应当刷多少只怪（已封顶）。
     *
     * @param floor 层数（从 1 开始）
     * @param extra 随机附加值（{@code 0 .. BATTLE_ENEMY_MAX - BATTLE_ENEMY_MIN}）
     */
    public static int battleEnemyCount(int floor, int extra) {
        int base = BATTLE_ENEMY_MIN + Math.max(0, extra)
                + (Math.max(1, floor) - 1) * BATTLE_ENEMY_PER_FLOOR;
        return Math.min(BATTLE_ENEMY_MAX_CAP, base);
    }

    /** 第 N 层战斗房里出现一只精英的概率。 */
    public static double battleEliteChance(int floor) {
        if (floor < BATTLE_ELITE_MIN_FLOOR) return 0.0;
        return Math.min(BATTLE_ELITE_CHANCE_CAP,
                BATTLE_ELITE_CHANCE_BASE + (floor - 1) * BATTLE_ELITE_CHANCE_PER_FLOOR);
    }

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

    /**
     * 每一波召唤的怪物数量，按层取（下标 0 对应第 1 层）。
     *
     * <p>越往深处走，首领一次叫出来的东西越多：第 1～2 层两只（与旧版一致），
     * 第 3～4 层三只，第 5 层四只。超出层数时取最后一个值。
     */
    public static final int[] SUMMON_WAVE_BY_FLOOR = {2, 2, 3, 3, 4};

    /** 场上同时存在的召唤物上限，按层取（含还没成型的裂隙）。 */
    public static final int[] SUMMON_ALIVE_BY_FLOOR = {4, 4, 5, 5, 6};

    /** 首领跌破最后一个血量阶段之后，每一波再追加几只——「血量越低越疯狂」。 */
    public static final int SUMMON_WAVE_LOW_HEALTH_BONUS = 1;

    /** 从第几层开始，首领的召唤物里会出现一只精英。 */
    public static final int SUMMON_ELITE_FROM_FLOOR = 3;

    /** 第 N 层一波召唤的数量。 */
    public static int summonWaveSize(int floor) {
        return SUMMON_WAVE_BY_FLOOR[Math.min(SUMMON_WAVE_BY_FLOOR.length - 1, Math.max(0, floor - 1))];
    }

    /** 第 N 层场上召唤物上限。 */
    public static int summonAliveCap(int floor) {
        return SUMMON_ALIVE_BY_FLOOR[Math.min(SUMMON_ALIVE_BY_FLOOR.length - 1, Math.max(0, floor - 1))];
    }

    /** 第 N 层的召唤物里是否已经会混入精英。 */
    public static boolean summonIncludesElites(int floor) {
        return floor >= SUMMON_ELITE_FROM_FLOOR;
    }

    // ---- 敌方弹幕：碰墙反弹 ----
    /** 反弹弹体的撞墙反弹次数上限（防止在窄缝里无限弹射）。 */
    public static final int ENEMY_PROJECTILE_MAX_BOUNCES = 3;

    /** 反弹弹体的存活时间加成（秒/次反弹）：弹得越久，寿命也要跟着放长。 */
    public static final double ENEMY_BOUNCE_LIFETIME_BONUS = 1.6;

    /** 反弹弹体的单次最小位移，避免贴墙时法线判定抖动。 */
    public static final double ENEMY_BOUNCE_MIN_STEP = 1.0;

    /**
     * 环形弹幕的标记角度：技能的「相邻弹体夹角」等于它时，改成把 count 发均匀铺满整圈。
     *
     * <p>放在 GameConfig 而不是 EnemySkill 里，是因为枚举常量在静态初始化时就会用到它——
     * 写在枚举自己的静态字段里会读到默认值 0（经典的前向引用坑）。
     */
    public static final double ENEMY_RING_COVERAGE = 360.0;

    // ---- 敌人素材渲染（v1 与 v2 两套素材共用同一缩放刻度） ----
    /**
     * 怪物本体帧画布的显示缩放。
     *
     * <p>两套素材包都按「画布 1:1 承载目标身高」导出（普通怪 256、精英 320、首领 448/640），
     * 于是用同一个系数缩放整张画布，就能自动保持各物种的相对体型：孢子比甲虫高、
     * 螳爵比炮蟹高，而画布留白（树冠、镰臂、炮管）不会被误当成碰撞体积。
     */
    public static final double MONSTER_RENDER_SCALE = 0.70;

    /** 首领半血换形的收招时间（秒）：无伤害，但期间不出招，给玩家读新形态的窗口。 */
    public static final double BOSS_TRANSFORM_LOCK = 1.2;

    /** 预警判定之后残留显示的默认时间（秒），用来播放爆开 / 命中特效。 */
    public static final double TELEGRAPH_RESIDUAL_TIME = 0.34;

    // ---- v2 扩展包：召唤细节补充 ----
    /** 召唤型首领的开场缓冲（秒）：与 {@link #WATCHER_SUMMON_OPENING_DELAY} 同义，供档案复用。 */
    public static final double WATCHER_SUMMON_OPENING_GRACE = WATCHER_SUMMON_OPENING_DELAY;

    // ---- v2 扩展包：根篱（临时阻挡地形） ----
    /** 根篱的可达性校验网格步长（像素）：越细越准，越粗越省。 */
    public static final double ROOT_WALL_GRID_STEP = 40.0;

    /** 根篱校验时允许保留的最小可达面积比例：低于它说明这一招会把房间封死。 */
    public static final double ROOT_WALL_MIN_REACHABLE_RATIO = 0.55;

    /** 根篱落点必须留出的玩家活动半径（像素）：不许长在玩家脚下。 */
    public static final double ROOT_WALL_PLAYER_CLEARANCE = 100.0;

    /** 单道根篱的总跨度上限（像素）：太长会把整间房一分为二。 */
    public static final double ROOT_WALL_MAX_SPAN = 520.0;

    // ---- v2 扩展包：换位（镜砂术士的镜门） ----
    /** 换位落点与玩家的最小距离（像素）。 */
    public static final double TELEPORT_PLAYER_CLEARANCE = 160.0;

    /** 搜索换位落点的圈数上限。 */
    public static final int TELEPORT_SEARCH_RINGS = 6;

    /** 换位落地后的收招（秒）：落地立刻起手会变成贴脸瞬狙。 */
    public static final double TELEPORT_LANDING_RECOVERY = 1.1;

    // ---- v2 扩展包：幻象 ----
    /** 幻象本体的存活时间（秒）。 */
    public static final double ILLUSION_LIFETIME = 1.1;

    /** 幻象与本体之间的距离（像素）。 */
    public static final double ILLUSION_DISTANCE = 120.0;

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
