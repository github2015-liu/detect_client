// IHealthDetectCallback.aidl
package com.senseauto.healthdetect;

// Declare any non-default types here with import statements

interface IHealthDetectCallback {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */

    void onHealthStateUpdate(String seat, String healthJson);

    void onFinishResult(String seat, String healthJson);

    void onError(int code, String message);
}