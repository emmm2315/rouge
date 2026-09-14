package com.phantomcorridor.model.room;

import com.phantomcorridor.model.Pickup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个房间里可交互内容的持久状态：地面拾取物、宝箱与事件。
 *
 * <p>这份状态挂在 {@link Room} 上、跟着地图走，所以玩家反复进出房间既不会重刷内容，
 * 也不会把没拿走的东西清空——旧实现把拾取物存在会话层并在换房时清表，
 * 结果“进商店看一眼、出门再回来”货架就空了。
 */
public final class RoomLoot {
    private final List<Pickup> pickups = new ArrayList<>();
    private boolean rolled;
    private boolean chestOpened;
    private boolean chestRewardGranted;
    private boolean eventPending;
    private boolean hiddenRewardGranted;

    /** 是否已经按种子生成过本房内容（生成一次即固定，不再随进出重刷）。 */
    public boolean isRolled() { return rolled; }

    public void markRolled() { rolled = true; }

    /** 房间地面上的拾取物（可直接增删，顺序即生成顺序）。 */
    public List<Pickup> pickups() { return pickups; }

    public List<Pickup> pickupsView() { return Collections.unmodifiableList(pickups); }

    public void addPickup(Pickup pickup) { pickups.add(pickup); }

    public boolean removePickup(Pickup pickup) { return pickups.remove(pickup); }

    public boolean isChestOpened() { return chestOpened; }

    public void openChest() { chestOpened = true; }

    public boolean isChestRewardGranted() { return chestRewardGranted; }

    public void grantChestReward() { chestRewardGranted = true; }

    /** 事件房是否还有待触发的选择。 */
    public boolean isEventPending() { return eventPending; }

    public void setEventPending(boolean pending) { this.eventPending = pending; }

    public boolean isHiddenRewardGranted() { return hiddenRewardGranted; }
    public void grantHiddenReward() { hiddenRewardGranted = true; }

    /** 房间是否还有玩家没拿走的东西（供小地图提示）。 */
    public boolean hasContent() { return !pickups.isEmpty() || eventPending; }
}
