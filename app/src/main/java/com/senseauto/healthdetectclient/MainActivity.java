package com.senseauto.healthdetectclient;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.senseauto.healthdetect.Constants;
import com.senseauto.healthdetect.HealthDetectManager;
import com.senseauto.healthdetect.IHealDetectApiInterface;
import com.senseauto.healthdetect.IHealthDetectCallback;
import com.senseauto.healthdetect.Seat;

import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "MainActivity";
    private static final String PREVIEW_TRACE_TAG = "PreviewTrace";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    // Keep preview aligned with the service's car front-camera default. The
    // device must also expose this camera in shared_session_config.xml.
    private static final int DEFAULT_PREVIEW_CAMERA_ID = 31;
    private static final boolean MIRROR_PREVIEW = true;
    private Surface previewSurface;
    private SurfaceView previewSurfaceView;
    private EditText previewCameraIdInput;
    private Spinner updateSeatSpinner;
    private TextView healthResultTextView;
    private SharedPreviewController previewCameraFactory;
    private IHealDetectApiInterface resultService;
    private boolean resultServiceBound = false;
    private boolean isPreviewOpen = false;
    private boolean pendingOpenPreview = false;

    private final IHealthDetectCallback resultCallback = new IHealthDetectCallback.Stub() {
        @Override
        public void onHealthStateUpdate(String seat, String healthJson) {
            String resultText = formatHealthVitals(R.string.health_result_realtime, healthJson, true);
            Log.i(TAG, "onHealthStateUpdate: " + resultText);
            runOnUiThread(() -> healthResultTextView.setText(resultText));
        }

        @Override
        public void onFinishResult(String seat, String healthJson) {
            String resultText = formatHealthVitals(R.string.health_result_final, healthJson, false);
            Log.i(TAG, "onFinishResult:\n" + resultText);
            runOnUiThread(() -> {
                healthResultTextView.setVisibility(View.VISIBLE);
                healthResultTextView.bringToFront();
                healthResultTextView.setText(resultText);
                Log.i(TAG, "health result view updated: width="
                        + healthResultTextView.getWidth()
                        + ", height=" + healthResultTextView.getHeight());
            });
        }

        @Override
        public void onError(int code, String message) {
            runOnUiThread(() -> healthResultTextView.setText(
                    getString(R.string.health_result_error, code, message)
            ));
        }
    };

    private String formatHealthVitals(int formatResource, String healthJson, boolean realtime) {
        try {
            JSONObject result = new JSONObject(healthJson);
            return getString(
                    formatResource,
                    result.optString(realtime ? "heart_rate" : "heartRate",
                            result.optString("heartRate", "--")),
                    result.optString(realtime ? "breath_rate" : "breathRate",
                            result.optString("breathRate", "--"))
            );
        } catch (JSONException e) {
            Log.e(TAG, "parse health result failed", e);
            return getString(R.string.health_result_parse_failed);
        }
    }

    private final ServiceConnection resultServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            resultService = IHealDetectApiInterface.Stub.asInterface(service);
            try {
                resultService.registerCallback(resultCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "register result callback failed", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            resultService = null;
        }
    };

    private void logPreviewState(String event) {
        Log.i(
                PREVIEW_TRACE_TAG,
                event
                        + " | surface=" + (previewSurface != null)
                        + " | factory=" + (previewCameraFactory != null)
                        + " | isPreviewOpen=" + isPreviewOpen
                        + " | pendingOpenPreview=" + pendingOpenPreview
        );
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewSurfaceView = findViewById(R.id.sv_preview);
        previewCameraIdInput = findViewById(R.id.et_preview_camera_id);
        updateSeatSpinner = findViewById(R.id.spinner_update_seat);
        healthResultTextView = findViewById(R.id.tv_health_result);
        healthResultTextView.setVisibility(View.VISIBLE);
        healthResultTextView.bringToFront();
        Log.i(TAG, "health result view initialized: visible="
                + (healthResultTextView.getVisibility() == View.VISIBLE));
        healthResultTextView.post(() -> Log.i(
                TAG,
                "health result view laid out: width=" + healthResultTextView.getWidth()
                        + ", height=" + healthResultTextView.getHeight()
                        + ", visibility=" + healthResultTextView.getVisibility()
                        + ", text=" + healthResultTextView.getText()
        ));
        previewSurfaceView.getHolder().addCallback(this);
        previewSurfaceView.setScaleX(MIRROR_PREVIEW ? -1f : 1f);
        ensureCameraPermission();
        bindResultService();

        Button bindButton = findViewById(R.id.bt_bind);
        bindButton.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int[] location = new int[2];
                bindButton.getLocationOnScreen(location);
                Log.i(TAG, "onLayoutChange: " + location[0] + " " + location[1]);
            }
        });

        findViewById(R.id.bt_bind).setOnClickListener(v -> {
            bindResultService();
            HealthDetectManager.getInstance(MainActivity.this).init(new HealthDetectManager.InitCallback() {
                @Override
                public void onReady() {
                    int result = HealthDetectManager.getInstance(MainActivity.this).startDetect(getUpdateSeat());
                    if (result == 0) {
                        Log.i(TAG, "start success");
                    }
                    Log.i(TAG, "start result: " + result);
                }

                @Override
                public void onError(int code, String msg) {
                    Log.i(TAG, "onError: msg = " + msg);
                }
            });
        });

        findViewById(R.id.bt_auto).setOnClickListener(v -> {
            bindResultService();
            HealthDetectManager.getInstance(MainActivity.this).init(new HealthDetectManager.InitCallback() {
                @Override
                public void onReady() {
                    int result = HealthDetectManager.getInstance(MainActivity.this).startAutoDetect(new String[]{getUpdateSeat()});
                    if (result == 0) {
                        Log.i(TAG, "auto start success");
                    }
                    Log.i(TAG, "auto start result: " + result);
                }

                @Override
                public void onError(int code, String msg) {
                    Log.i(TAG, "onError: msg = " + msg);
                }
            });
        });

        findViewById(R.id.bt_update_seat).setOnClickListener(v -> {
            String targetSeat = getUpdateSeat();
            HealthDetectManager.getInstance(MainActivity.this).updateAutoDetectSeatList(new String[]{targetSeat});
            Log.i(TAG, "update seat to " + targetSeat);
            Toast.makeText(MainActivity.this, "已切换更新座位到: " + getSeatLabel(targetSeat), Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.bt_stop_detect).setOnClickListener(v ->
                HealthDetectManager.getInstance(MainActivity.this).stopDetect());

        findViewById(R.id.bt_unbind).setOnClickListener(v -> {
            HealthDetectManager.getInstance(MainActivity.this).stopDetect();
            HealthDetectManager.getInstance(MainActivity.this).release();
            releaseResultService();
        });

        findViewById(R.id.bt_toast).setOnClickListener(v -> {
            String seat = HealthDetectManager.getInstance(MainActivity.this).getDetectingSeat();
            Log.i(TAG, "seat result: " + seat);
            Toast.makeText(MainActivity.this, "当前检测座位: " + getSeatLabel(seat), Toast.LENGTH_LONG).show();
        });

        findViewById(R.id.bt_open_preview).setOnClickListener(v -> {
            logPreviewState("click open preview");
            openPreview();
        });
        findViewById(R.id.bt_close_preview).setOnClickListener(v -> {
            logPreviewState("click close preview");
            closePreview();
        });
        findViewById(R.id.bt_exit).setOnClickListener(v -> {
            HealthDetectManager.getInstance(MainActivity.this).stopDetect();
            HealthDetectManager.getInstance(MainActivity.this).release();
            releaseResultService();
            closePreview();
            finishAndRemoveTask();
        });
    }

    private void bindResultService() {
        if (resultServiceBound) {
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(Constants.INTENT_PACKAGE_NAME, Constants.INTENT_CLASS_PATH));
        resultServiceBound = bindService(intent, resultServiceConnection, Context.BIND_AUTO_CREATE);
        if (!resultServiceBound) {
            healthResultTextView.setText(R.string.health_result_bind_failed);
        }
    }

    private void releaseResultService() {
        if (resultService != null) {
            try {
                resultService.unregisterCallback(resultCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "unregister result callback failed", e);
            }
            resultService = null;
        }
        if (resultServiceBound) {
            unbindService(resultServiceConnection);
            resultServiceBound = false;
        }
    }

    private void ensureCameraPermission() {
        logPreviewState("ensureCameraPermission enter");
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.i(PREVIEW_TRACE_TAG, "ensureCameraPermission request CAMERA permission");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
            return;
        }
        Log.i(PREVIEW_TRACE_TAG, "ensureCameraPermission already granted");
    }

    private void openPreview() {
        logPreviewState("openPreview enter");
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ensureCameraPermission();
            Log.w(PREVIEW_TRACE_TAG, "openPreview blocked: camera permission missing");
            Toast.makeText(this, "camera permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        if (previewSurface == null) {
            pendingOpenPreview = true;
            Log.w(TAG, "openPreview skipped: preview surface not ready yet");
            logPreviewState("openPreview blocked: preview surface not ready");
            Toast.makeText(this, "preview surface not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isPreviewOpen) {
            logPreviewState("openPreview skipped: preview already open");
            Toast.makeText(this, "preview already open", Toast.LENGTH_SHORT).show();
            return;
        }

        releasePreviewCamera();
        logPreviewState("openPreview after release old factory");

        int previewCameraId = getPreviewCameraId();
        Log.i(
                PREVIEW_TRACE_TAG,
                "openPreview create shared camera"
                        + " | cameraId=" + previewCameraId
                        + " | viewWidth=" + (previewSurfaceView == null ? -1 : previewSurfaceView.getWidth())
                        + " | viewHeight=" + (previewSurfaceView == null ? -1 : previewSurfaceView.getHeight())
                        + " | surfaceHash=" + System.identityHashCode(previewSurface)
        );

        previewCameraFactory = new SharedPreviewController(
                this,
                previewCameraId,
                previewSurfaceView.getHolder(),
                new SharedPreviewController.Callback() {
                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "preview camera exception", e);
                        Log.e(PREVIEW_TRACE_TAG, "onException message=" + e.getMessage(), e);
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this, "preview camera exception: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onOpenStatus(boolean isOpen) {
                        isPreviewOpen = isOpen;
                        Log.i(TAG, "preview camera open status = " + isOpen);
                        logPreviewState("onCameraOpenStatus isOpen=" + isOpen);
                        runOnUiThread(() -> Toast.makeText(
                                MainActivity.this,
                                isOpen ? "preview opened" : "preview closed",
                                Toast.LENGTH_SHORT
                        ).show());
                    }
                }
        );

        pendingOpenPreview = false;
        Log.i(TAG, "openPreview cameraId = " + previewCameraId);
        logPreviewState("openPreview before openCamera");
        previewCameraFactory.open();
        logPreviewState("openPreview after openCamera");
    }

    private void closePreview() {
        logPreviewState("closePreview enter");
        releasePreviewCamera();
        clearPreviewSurface();
        isPreviewOpen = false;
        pendingOpenPreview = false;
        Log.i(TAG, "preview closed");
        logPreviewState("closePreview exit");
    }

    private void releasePreviewCamera() {
        if (previewCameraFactory == null) {
            logPreviewState("releasePreviewCamera skipped: factory null");
            return;
        }
        logPreviewState("releasePreviewCamera before release");
        previewCameraFactory.release();
        previewCameraFactory = null;
        logPreviewState("releasePreviewCamera after release");
    }

    private int getPreviewCameraId() {
        if (previewCameraIdInput == null) {
            return DEFAULT_PREVIEW_CAMERA_ID;
        }

        String cameraIdText = previewCameraIdInput.getText().toString().trim();
        if (TextUtils.isEmpty(cameraIdText)) {
            previewCameraIdInput.setText(String.valueOf(DEFAULT_PREVIEW_CAMERA_ID));
            Log.i(PREVIEW_TRACE_TAG, "getPreviewCameraId use default: empty input");
            return DEFAULT_PREVIEW_CAMERA_ID;
        }

        try {
            int cameraId = Integer.parseInt(cameraIdText);
            Log.i(PREVIEW_TRACE_TAG, "getPreviewCameraId parsed=" + cameraId);
            return cameraId;
        } catch (NumberFormatException e) {
            previewCameraIdInput.setText(String.valueOf(DEFAULT_PREVIEW_CAMERA_ID));
            Log.w(PREVIEW_TRACE_TAG, "getPreviewCameraId invalid input=" + cameraIdText + ", fallback to default");
            Toast.makeText(this, "摄像头ID无效，已使用默认值13", Toast.LENGTH_SHORT).show();
            return DEFAULT_PREVIEW_CAMERA_ID;
        }
    }

    private String getUpdateSeat() {
        if (updateSeatSpinner == null) {
            return Seat.FRONT_LEFT;
        }
        switch (updateSeatSpinner.getSelectedItemPosition()) {
            case 1:
                return Seat.FRONT_RIGHT;
            case 2:
                return Seat.BACK_LEFT;
            case 3:
                return Seat.BACK_RIGHT;
            default:
                return Seat.FRONT_LEFT;
        }
    }

    private String getSeatLabel(String seat) {
        if (Seat.FRONT_LEFT.equals(seat)) {
            return "前排左侧";
        }
        if (Seat.FRONT_RIGHT.equals(seat)) {
            return "前排右侧";
        }
        if (Seat.BACK_LEFT.equals(seat)) {
            return "后排左侧";
        }
        if (Seat.BACK_RIGHT.equals(seat)) {
            return "后排右侧";
        }
        return seat == null ? "未知" : seat;
    }

    private void clearPreviewSurface() {
        if (previewSurfaceView == null || previewSurface == null || !previewSurface.isValid()) {
            Log.i(PREVIEW_TRACE_TAG, "clearPreviewSurface skipped: surface unavailable");
            return;
        }
        Canvas canvas = previewSurfaceView.getHolder().lockCanvas();
        if (canvas == null) {
            Log.i(PREVIEW_TRACE_TAG, "clearPreviewSurface skipped: canvas null");
            return;
        }
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        previewSurfaceView.getHolder().unlockCanvasAndPost(canvas);
        Log.i(PREVIEW_TRACE_TAG, "clearPreviewSurface success");
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        previewSurface = holder.getSurface();
        Log.i(TAG, "surfaceCreated");
        Log.i(
                PREVIEW_TRACE_TAG,
                "surfaceCreated"
                        + " | surfaceValid=" + previewSurface.isValid()
                        + " | viewWidth=" + (previewSurfaceView == null ? -1 : previewSurfaceView.getWidth())
                        + " | viewHeight=" + (previewSurfaceView == null ? -1 : previewSurfaceView.getHeight())
                        + " | surfaceHash=" + System.identityHashCode(previewSurface)
        );
        logPreviewState("surfaceCreated state");
        if (pendingOpenPreview) {
            Log.i(TAG, "surfaceCreated: retry pending preview open");
            openPreview();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged width=" + width + " height=" + height);
        Log.i(
                PREVIEW_TRACE_TAG,
                "surfaceChanged"
                        + " | width=" + width
                        + " | height=" + height
                        + " | viewWidth=" + (previewSurfaceView == null ? -1 : previewSurfaceView.getWidth())
                        + " | viewHeight=" + (previewSurfaceView == null ? -1 : previewSurfaceView.getHeight())
                        + " | surfaceValid=" + (previewSurface != null && previewSurface.isValid())
        );
        logPreviewState("surfaceChanged state");
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        Log.i(
                PREVIEW_TRACE_TAG,
                "surfaceDestroyed"
                        + " | surfaceValid=" + (previewSurface != null && previewSurface.isValid())
                        + " | surfaceHash=" + (previewSurface == null ? -1 : System.identityHashCode(previewSurface))
        );
        logPreviewState("surfaceDestroyed before close preview");
        releasePreviewCamera();
        isPreviewOpen = false;
        pendingOpenPreview = false;
        previewSurface = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "camera permission granted = " + granted);
            Log.i(PREVIEW_TRACE_TAG, "onRequestPermissionsResult granted=" + granted);
            logPreviewState("onRequestPermissionsResult state");
        }
    }

    @Override
    protected void onDestroy() {
        logPreviewState("onDestroy enter");
        releaseResultService();
        closePreview();
        previewSurface = null;
        super.onDestroy();
    }
}
