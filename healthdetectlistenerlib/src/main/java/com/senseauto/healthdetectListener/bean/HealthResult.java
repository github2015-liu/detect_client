package com.senseauto.healthdetectListener.bean;

public class HealthResult {
    //检测时间
    public int detectTime;
    //心率结果
    public float heartRate;
    //心率状态（心动过缓，心率正常，心动过速）
    public String heartRateState;
    //呼吸结果
    public int breathRate;
    //呼吸状态（呼吸过缓，呼吸正常，呼吸过速）
    public String breathRateState;
    //心率变异性结果
    public float heartRateVariability;
    //心率变异性状态（愉悦，平静，不愉悦）
    public String heartRateVariabilityState;
    //收缩压结果
    public int systolicPressure;
    //收缩压状态（收缩压过低，收缩压正常，收缩压过高）
    public String systolicPressureState;
    //舒张压结果
    public int diastolicPressure;
    //舒张压状态（舒张压过低，舒张压正常，舒张压过高）
    public String diastolicPressureState;
    //血氧结果
    public int bloodOxygen;
    //血氧状态（血氧正常，血氧异常）
    public String bloodOxygenState;

    //压力结果
    public float stressScore;
    //压力状态
    public String stressScoreState;


}
