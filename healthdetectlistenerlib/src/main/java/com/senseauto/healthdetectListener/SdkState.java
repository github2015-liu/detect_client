package com.senseauto.healthdetectListener;

public enum SdkState {
    IDLE,       // 未初始化
    INITING,    // 正在 bind
    READY,      // 可用
    ERROR       // 初始化失败
}
