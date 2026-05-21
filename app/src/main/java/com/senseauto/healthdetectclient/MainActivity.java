package com.senseauto.healthdetectclient;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.senseauto.healthdetect.HealthDetectManager;
import com.senseauto.healthdetect.Seat;
import com.senseauto.subgroup.autocameralib.CameraFactory;
import com.senseauto.subgroup.autocameralib.callback.CameraImageCallback;
import com.senseauto.subgroup.autocameralib.model.CameraOptions;

public class MainActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "MainActivity";
    private static final String PREVIEW_TRACE_TAG = "PreviewTrace";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int DEFAULT_PREVIEW_CAMERA_ID = 13;
    private static final boolean MIRROR_PREVIEW = true;
    private static final int PREVIEW_WIDTH = 1920;
    private static final int PREVIEW_HEIGHT = 1080;

    private Surface previewSurface;
    private TextureView previewTextureView;
    private EditText previewCameraIdInput;
    private EditText updateSeatInput;
    private CameraFactory previewCameraFactory;
    private boolean isPreviewOpen = false;
    private boolean pendingOpenPreview = false;

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

        previewTextureView = findViewById(R.id.sv_preview);
        previewCameraIdInput = findViewById(R.id.et_preview_camera_id);
        updateSeatInput = findViewById(R.id.et_update_seat);
        previewTextureView.setSurfaceTextureListener(this);
        previewTextureView.setScaleX(MIRROR_PREVIEW ? -1f : 1f);
        ensureCameraPermission();

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
            HealthDetectManager.getInstance(MainActivity.this).init(new HealthDetectManager.InitCallback() {
                @Override
                public void onReady() {
                    int result = HealthDetectManager.getInstance(MainActivity.this).startDetect(Seat.FRONT_RIGHT);
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
            HealthDetectManager.getInstance(MainActivity.this).init(new HealthDetectManager.InitCallback() {
                @Override
                public void onReady() {
                    int result = HealthDetectManager.getInstance(MainActivity.this).startAutoDetect(new String[]{Seat.FRONT_RIGHT});
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
        CameraOptions cameraOptions = new CameraOptions();
        cameraOptions.setImgWidth(PREVIEW_WIDTH);
        cameraOptions.setImgHeight(PREVIEW_HEIGHT);
        cameraOptions.setCameraId(previewCameraId);
        Log.i(
                PREVIEW_TRACE_TAG,
                "openPreview createCameraV2Proxy"
                        + " | cameraId=" + previewCameraId
                        + " | width=" + PREVIEW_WIDTH
                        + " | height=" + PREVIEW_HEIGHT
                        + " | viewWidth=" + (previewTextureView == null ? -1 : previewTextureView.getWidth())
                        + " | viewHeight=" + (previewTextureView == null ? -1 : previewTextureView.getHeight())
                        + " | surfaceHash=" + System.identityHashCode(previewSurface)
        );

        previewCameraFactory = CameraFactory.getInstance().createCameraV2Proxy(
                this,
                cameraOptions,
                new CameraImageCallback() {
                    @Override
                    public void onReadImageData(@Nullable byte[] bytes, int width, int height) {
                        Log.d(
                                PREVIEW_TRACE_TAG,
                                "onReadImageData width=" + width
                                        + " height=" + height
                                        + " bytes=" + (bytes == null ? -1 : bytes.length)
                        );
                    }

                    @Override
                    public void onException(@NonNull Exception e) {
                        Log.e(TAG, "preview camera exception", e);
                        Log.e(PREVIEW_TRACE_TAG, "onException message=" + e.getMessage(), e);
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this, "preview camera exception: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                    }

                    @Override
                    public void onCameraOpenStatus(boolean isOpen) {
                        isPreviewOpen = isOpen;
                        Log.i(TAG, "preview camera open status = " + isOpen);
                        logPreviewState("onCameraOpenStatus isOpen=" + isOpen);
                        runOnUiThread(() -> Toast.makeText(
                                MainActivity.this,
                                isOpen ? "preview opened" : "preview closed",
                                Toast.LENGTH_SHORT
                        ).show());
                    }
                },
                previewSurface
        );

        pendingOpenPreview = false;
        Log.i(TAG, "openPreview cameraId = " + previewCameraId);
        logPreviewState("openPreview before openCamera");
        previewCameraFactory.openCamera();
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
        previewCameraFactory.releaseCamera();
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
        if (updateSeatInput == null) {
            return Seat.FRONT_LEFT;
        }

        String inputSeat = updateSeatInput.getText().toString().trim().toLowerCase();
        if (TextUtils.isEmpty(inputSeat)) {
            updateSeatInput.setText(Seat.FRONT_LEFT);
            return Seat.FRONT_LEFT;
        }

        switch (inputSeat) {
            case Seat.FRONT_LEFT:
            case Seat.FRONT_RIGHT:
            case Seat.BACK_LEFT:
            case Seat.BACK_RIGHT:
                return inputSeat;
            default:
                updateSeatInput.setText(Seat.FRONT_LEFT);
                Toast.makeText(this, "座位无效，已使用默认值前排左侧", Toast.LENGTH_SHORT).show();
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
        if (previewTextureView == null) {
            Log.i(PREVIEW_TRACE_TAG, "clearPreviewSurface skipped: previewTextureView null");
            return;
        }
        if (!previewTextureView.isAvailable()) {
            Log.i(PREVIEW_TRACE_TAG, "clearPreviewSurface skipped: texture unavailable");
            return;
        }

        Canvas canvas = previewTextureView.lockCanvas();
        if (canvas == null) {
            Log.i(PREVIEW_TRACE_TAG, "clearPreviewSurface skipped: canvas null");
            return;
        }
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        previewTextureView.unlockCanvasAndPost(canvas);
        Log.i(PREVIEW_TRACE_TAG, "clearPreviewSurface success");
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        previewSurface = new Surface(surfaceTexture);
        Log.i(TAG, "surfaceTextureAvailable width=" + width + " height=" + height);
        Log.i(
                PREVIEW_TRACE_TAG,
                "surfaceTextureAvailable"
                        + " | surfaceValid=" + previewSurface.isValid()
                        + " | viewWidth=" + (previewTextureView == null ? -1 : previewTextureView.getWidth())
                        + " | viewHeight=" + (previewTextureView == null ? -1 : previewTextureView.getHeight())
                        + " | width=" + width
                        + " | height=" + height
                        + " | surfaceHash=" + System.identityHashCode(previewSurface)
        );
        logPreviewState("surfaceTextureAvailable state");
        if (pendingOpenPreview) {
            Log.i(TAG, "surfaceTextureAvailable: retry pending preview open");
            openPreview();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        Log.i(TAG, "surfaceTextureSizeChanged width=" + width + " height=" + height);
        Log.i(
                PREVIEW_TRACE_TAG,
                "surfaceTextureSizeChanged"
                        + " | width=" + width
                        + " | height=" + height
                        + " | viewWidth=" + (previewTextureView == null ? -1 : previewTextureView.getWidth())
                        + " | viewHeight=" + (previewTextureView == null ? -1 : previewTextureView.getHeight())
                        + " | surfaceValid=" + (previewSurface != null && previewSurface.isValid())
        );
        logPreviewState("surfaceTextureSizeChanged state");
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        Log.i(TAG, "surfaceTextureDestroyed");
        Log.i(
                PREVIEW_TRACE_TAG,
                "surfaceTextureDestroyed"
                        + " | surfaceValid=" + (previewSurface != null && previewSurface.isValid())
                        + " | surfaceHash=" + (previewSurface == null ? -1 : System.identityHashCode(previewSurface))
        );
        logPreviewState("surfaceTextureDestroyed before clear surface");
        clearPreviewSurface();
        closePreview();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
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
        closePreview();
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        super.onDestroy();
    }
}
