// IHealDetectApiInterface.aidl
package com.senseauto.healthdetect;
import com.senseauto.healthdetect.IHealthDetectCallback;

// Declare any non-default types here with import statements

interface IHealDetectApiInterface {
    int startHealthDetect(String seat);
    int startAutoDetect(in String[] seats);
    void stopHealthDetect();
    void updateAutoDetectSeatList(in String[] seats);
    String getDetectingSeat();
    void registerCallback(in IHealthDetectCallback callback);
    void unregisterCallback(in IHealthDetectCallback callback);
    int startFaceCoordinateTracking(String seat);
    void stopFaceCoordinateTracking();
}
