package com.senseauto.healthdetect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.atomic.AtomicReference;

public class HealthDetectManager {
    private static final String TAG = "HealthDetectManager";

    public interface FaceCoordinateListener {
        void onFaceCoordinates(String seat, String coordinatesJson,
                               int frameWidth, int frameHeight, long timestamp);

        void onError(int code);
    }

    private static volatile HealthDetectManager instance;

    private final AtomicReference<SdkState> state =
            new AtomicReference<>(SdkState.IDLE);

    private final Context context;
    private volatile IHealDetectApiInterface service;


    private final Intent serviceIntent;

    private volatile FaceCoordinateListener faceCoordinateListener;
    private boolean faceCoordinateCallbackRegistered;

    private final IHealthDetectCallback faceCoordinateCallback =
            new IHealthDetectCallback.Stub() {
                @Override
                public void onHealthStateUpdate(String seat, String healthJson) {
                }

                @Override
                public void onFinishResult(String seat, String healthJson) {
                }

                @Override
                public void onError(String seat, int code, String message) {
                }

                @Override
                public void onFaceCoordinates(String seat, String coordinatesJson,
                                              int frameWidth, int frameHeight, long timestamp) {
                    FaceCoordinateListener listener = faceCoordinateListener;
                    if (listener != null) {
                        listener.onFaceCoordinates(seat, coordinatesJson,
                                frameWidth, frameHeight, timestamp);
                    }
                }

                @Override
                public void onFaceCoordinateError(int code) {
                    FaceCoordinateListener listener = faceCoordinateListener;
                    if (listener != null) {
                        listener.onError(code);
                    }
                }
            };

    private InitCallback initCallback;
    private static final long INIT_TIMEOUT_MS = 5_000;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public interface InitCallback {
        void onReady();
        void onError(int code, String msg);
    }

    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IHealDetectApiInterface.Stub.asInterface(binder);
            state.set(SdkState.READY);

            if (initCallback != null) {
                initCallback.onReady();
                initCallback = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            faceCoordinateCallbackRegistered = false;
            state.set(SdkState.IDLE);
        }
    };

    private HealthDetectManager(Context context) {
        this.context = context;

        serviceIntent = new Intent();
//        serviceIntent.setPackage(Constants.INTENT_PACKAGE_NAME);
//        serviceIntent.addCategory(Intent.CATEGORY_DEFAULT);
//        serviceIntent.setAction(Constants.INTENT_ACTION);
        serviceIntent.setComponent(new ComponentName(Constants.INTENT_PACKAGE_NAME, Constants.INTENT_CLASS_PATH));

    }

    public static HealthDetectManager getInstance(Context context) {
        if (instance == null) {
            synchronized (HealthDetectManager.class) {
                if (instance == null) {
                    instance = new HealthDetectManager(
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

        // 线程安全
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

    public int startDetect(String seat){
        if (state.get() != SdkState.READY || service == null){
            return 1001;
        }
        try {
            return service.startHealthDetect(seat);
        } catch (RemoteException e) {
            service = null;
            state.set(SdkState.IDLE);
            return 1004;
        }
    }

    public int startAutoDetect(String[] seats){
        if (state.get() != SdkState.READY || service == null){
            return 1001;
        }
        try {
            return service.startAutoDetect(seats);
        } catch (RemoteException e) {
            service = null;
            state.set(SdkState.IDLE);
            return 1004;
        }
    }

    public int updateAutoDetectSeatList(String[] seats){
        if (state.get() != SdkState.READY || service == null){
            return 1001;
        }
        try {
            service.updateAutoDetectSeatList(seats);
            return 0;
        } catch (RemoteException e) {
            service = null;
            state.set(SdkState.IDLE);
            return 1004;
        }
    }

    public int stopDetect(){
        if (state.get() != SdkState.READY || service == null){
            return 1001;
        }
        try {
            service.stopHealthDetect();
            return 0;
        } catch (RemoteException e) {
            service = null;
            state.set(SdkState.IDLE);
            return 1004;
        }
    }

    public void setFaceCoordinateListener(FaceCoordinateListener listener) {
        faceCoordinateListener = listener;
    }

    public int startFaceCoordinateTracking(String seat) {
        if (state.get() != SdkState.READY || service == null) {
            return 1001;
        }
        try {
            if (!faceCoordinateCallbackRegistered) {
                service.registerCallback(faceCoordinateCallback);
                faceCoordinateCallbackRegistered = true;
            }
            int result = service.startFaceCoordinateTracking(seat);
            if (result != 0 && faceCoordinateCallbackRegistered) {
                service.unregisterCallback(faceCoordinateCallback);
                faceCoordinateCallbackRegistered = false;
            }
            return result;
        } catch (RemoteException e) {
            Log.e(TAG, "startFaceCoordinateTracking failed", e);
            service = null;
            faceCoordinateCallbackRegistered = false;
            state.set(SdkState.IDLE);
            return 1004;
        }
    }

    public int stopFaceCoordinateTracking() {
        if (state.get() != SdkState.READY || service == null) {
            return 1001;
        }
        try {
            service.stopFaceCoordinateTracking();
            if (faceCoordinateCallbackRegistered) {
                service.unregisterCallback(faceCoordinateCallback);
                faceCoordinateCallbackRegistered = false;
            }
            return 0;
        } catch (RemoteException e) {
            Log.e(TAG, "stopFaceCoordinateTracking failed", e);
            service = null;
            faceCoordinateCallbackRegistered = false;
            state.set(SdkState.IDLE);
            return 1004;
        }
    }

    public String getDetectingSeat(){
        if (state.get() != SdkState.READY || service == null ){
            return null;
        }
        try {
            return service.getDetectingSeat();
        } catch (RemoteException e) {
            service = null;
            state.set(SdkState.IDLE);
            return null;
        }
    }

    public void release() {
        if (service != null) {
            try {
                if (faceCoordinateCallbackRegistered) {
                    service.unregisterCallback(faceCoordinateCallback);
                    faceCoordinateCallbackRegistered = false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "unregister face coordinate callback failed", e);
            } finally {
                state.set(SdkState.IDLE);
                try {
                    context.unbindService(mServiceConnection);
                } catch (IllegalArgumentException ignore) {
                }
                service = null;
            }
        }
    }
}
