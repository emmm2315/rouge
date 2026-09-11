package com.phantomcorridor.model.entity;

/** 像素角色当前应呈现的动作。 */
public enum PlayerAnimationState {
    IDLE,
    MOVING,
    /** 闪避冲刺：冲刺期间无视方向输入，只播放冲刺动作并留下拖尾。 */
    DASHING,
    ATTACKING,
    SHIFTING,
    DOWN
}
