package com.phantomcorridor.model.combat;

import com.phantomcorridor.model.WorldType;
import com.phantomcorridor.model.entity.EnemyKind;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 攻击实体与延迟判定的全局命中标识分配器。
 *
 * <p>多段招式必须能区分「同一次施法里的第几段」与「这是不是同一段」：二连斩的两刀各算一次，
 * 而聚棱炮的弹体与撞墙爆炸只算一次。标识只用于去重，不参与任何数值计算，
 * 因此用一个进程内单调递增的计数器即可，不需要持久化或可复现。
 */
public final class HitIds {
    private static final AtomicLong COUNTER = new AtomicLong(1L);

    private HitIds() { }

    /** 取一个新的命中标识（永不返回 0；0 保留表示“不去重”）。 */
    public static long next() { return COUNTER.getAndIncrement(); }
}
