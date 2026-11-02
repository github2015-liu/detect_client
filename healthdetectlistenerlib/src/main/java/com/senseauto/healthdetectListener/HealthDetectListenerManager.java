package com.senseauto.healthdetectListener;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.google.gson.Gson;
import com.senseauto.healthdetect.IHealDetectApiInterface;
import com.senseauto.healthdetect.IHealthDetectCallback;
import com.senseauto.healthdetectListener.SdkState;
import com.senseauto.healthdetectListener.bean.HealthResult;
import com.senseauto.healthdetectListener.bean.HealthState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public class HealthDetectListenerManager {
    private static final String TAG = "HealthDetectListenerManagerV2";

    private static volatile HealthDetectListenerManager instance;

    private final AtomicReference<SdkState> state =
            new AtomicReference<>(SdkState.IDLE);

    private final Context context;
    private volatile IHealDetectApiInterface service;


    private final Intent serviceIntent;

    private InitCallback initCallback;
    private static final long INIT_TIMEOUT_MS = 5_000;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<HealthDetectListenerManager.HealthDetectListener> listeners =
            new CopyOnWriteArrayList<>();

    private final IHealthDetectCallback remoteCallback =
            new IHealthDetectCallback.Stub() {

                @Override
                public void onHealthStateUpdate(String seat,String json) {
                    HealthState state = new Gson().fromJson(json, HealthState.class);
                    for (HealthDetectListenerManager.HealthDetectListener l : listeners) {
                        l.onHealthStateUpdate(seat,state);
                    }
                }

                @Override
                public void onFinishResult(String seat,String json) {
                    HealthResult result = new Gson().fromJson(json, HealthResult.class);
                    Log.i(TAG, "onFinishResult:"
                            + " 座位：" + seat
                            + "，心率：" + result.heartRate
                            + "，呼吸率：" + result.breathRate
                            + "，血氧：" + result.bloodOxygen
                            + "，压力值：" + result.stressScore
                            + "，收缩压：" + result.systolicPressure
                            + "，舒张压：" + result.diastolicPressure);
                    for (HealthDetectListenerManager.HealthDetectListener l : listeners) {
                        l.onFinishResult(seat,result);
                    }
                }

                @Override
                public void onError(int code, String message) {
                    for (HealthDetectListenerManager.HealthDetectListener l : listeners) {
                        l.onError(code, message);
                    }
                }
            };

    public interface InitCallback {
        void onReady();
        void onError(int code, String msg);
    }

    public interface HealthDetectListener{
        void onFinishResult(String seat, HealthResult result);

        void onHealthStateUpdate(String seat, HealthState healthStateJson);

        void onError(int code, String message);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IHealDetectApiInterface.Stub.asInterface(binder);

            try {
                service.registerCallback(remoteCallback);
                state.set(SdkState.READY);
                if (initCallback != null) {
                    initCallback.onReady();
                    initCallback = null;
                }
            } catch (RemoteException e) {
                service = null;
                state.set(SdkState.IDLE);
                if (initCallback != null) {
                    initCallback.onError(1004,"server RemoteException");
                    initCallback = null;
                }
            }

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "onServiceDisconnected");
            listeners.clear();
            service = null;
            state.set(SdkState.IDLE);

        }
    };

    private HealthDetectListenerManager(Context context) {
        this.context = context;

        serviceIntent = new Intent();
//        serviceIntent.setPackage(Constants.INTENT_PACKAGE_NAME);
//        serviceIntent.addCategory(Intent.CATEGORY_DEFAULT);
//        serviceIntent.setAction(Constants.INTENT_ACTION);
        serviceIntent.setComponent(new ComponentName(Constants.INTENT_PACKAGE_NAME, Constants.INTENT_CLASS_PATH));

    }

    public static HealthDetectListenerManager getInstance(Context context) {
        if (instance == null) {
            synchronized (HealthDetectListenerManager.class) {
                if (instance == null) {
                    instance = new HealthDetectListenerManager(
                            context.getApplicationContext()
                    );
                }
            }
        }
        return instance;
    }

    public void init(InitCallback callback) {

        SdkState cur = state.get();

        // 已经 ready
        if (cur == SdkState.READY) {
            callback.onReady();
            return;
        }

        // 正在 init，不允许并发
        if (cur == SdkState.INITING) {
            callback.onError(1002, "init already in progress");
            return;
        }

        // 尝试进入 INITING
        if (!state.compareAndSet(SdkState.IDLE, SdkState.INITING)) {

            return;
        }

        this.initCallback = callback;

        boolean bindSuccess = context.bindService(
                serviceIntent,
                mServiceConnection,
                Context.BIND_AUTO_CREATE
        );

        if (!bindSuccess) {
            state.set(SdkState.IDLE);
            this.initCallback = null;
            callback.onError(1003, "bindService failed");
            return;
        }

//        // 超时兜底
//        handler.postDelayed(() -> {
//            if (state.get() == SdkState.INITING) {
//                state.set(SdkState.IDLE);
//                if (initCallback != null) {
//                    initCallback.onError(1004, "bind timeout");
//                    initCallback = null;
//                }
//            }
//        }, INIT_TIMEOUT_MS);
    }

    public  void registerHealthDetectListener(HealthDetectListenerManager.HealthDetectListener listener){

        if (listener == null){
            Log.e(TAG, "registerEventListener failed, AutoEventListener is null");
            return;
        }

        if (listeners.contains(listener)){
            Log.e(TAG, "registerEventListener: listener is contains!");
            return;
        }

        listeners.add(listener);

    }

    public void unRegisterHealthDetectListener(HealthDetectListenerManager.HealthDetectListener listener){
        if (listener == null){
            Log.e(TAG, "unRegisterEventListener failed, AutoEventListener is null");
            return;
        }
        listeners.remove(listener);
    }


    public void release() {
        if (service != null) {
            try {
                context.unbindService(mServiceConnection);
            } catch (IllegalArgumentException ignore) {
            }
            service = null;
        }
    }
}
