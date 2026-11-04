package com.senseauto.healthdetectclient;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraSharedCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.SharedSessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import android.hardware.camera2.TotalCaptureResult;

import java.util.Collections;
import java.util.concurrent.Executor;

final class SharedPreviewController {
    private static final String TAG = "SharedPreviewController";
    private static final int SURFACE_TYPE_SURFACE_VIEW = 0;
    private static final long REOPEN_DELAY_MS = 1000L;
    private static final int MAX_REOPEN_ATTEMPTS = 5;
    private static final long NO_FRAME_TIMEOUT_MS = 3000L;
    private static final long NO_FRAME_CHECK_INTERVAL_MS = 1000L;
    private static final long RELEASE_READY_TIMEOUT_MS = 1000L;
    private static final long RELEASE_CLOSED_TIMEOUT_MS = 1000L;

    interface Callback {
        void onOpenStatus(boolean isOpen);
        void onError(Exception exception);
        void onCameraServiceError();
        void onPreviewStalled();
    }

    private final Context context;
    private final String cameraId;
    private final SurfaceHolder surfaceHolder;
    private final Callback callback;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Executor cameraExecutor;
    private CameraDevice cameraDevice;
    private CameraSharedCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Surface previewSurface;
    private boolean primary;
    private boolean openReported;
    private boolean released;
    private boolean releaseFinished;
    private boolean reopenScheduled;
    private int reopenAttempts;
    private long lastCaptureCompletedAtMs;
    private boolean stallReported;

    SharedPreviewController(
            Context context, int cameraId, SurfaceHolder surfaceHolder, Callback callback) {
        this.context = context.getApplicationContext();
        this.cameraId = String.valueOf(cameraId);
        this.surfaceHolder = surfaceHolder;
        this.callback = callback;
    }

    void open() {
        if (cameraThread != null) {
            return;
        }
        released = false;
        releaseFinished = false;
        cameraThread = new HandlerThread("SharedPreview-" + cameraId);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraExecutor = cameraHandler::post;
        mainHandler.post(this::configureSurfaceAndOpen);
    }

    void release() {
        if (released) {
            return;
        }
        released = true;
        reopenScheduled = false;
        reopenAttempts = 0;
        Handler handler = cameraHandler;
        if (handler == null) {
            return;
        }
        handler.removeCallbacks(noFrameWatchdog);
        handler.post(this::beginRelease);
    }

    private void configureSurfaceAndOpen() {
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) {
                throw new IllegalStateException("camera service unavailable");
            }
            if (!cameraManager.isCameraDeviceSharingSupported(cameraId)) {
                throw new IllegalStateException("camera " + cameraId + " does not support shared mode");
            }

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            SharedSessionConfiguration sharedConfig = characteristics.get(
                    CameraCharacteristics.SHARED_SESSION_CONFIGURATION);
            SharedSessionConfiguration.SharedOutputConfiguration previewOutput =
                    findPreviewOutput(sharedConfig);
            surfaceHolder.setFormat(PixelFormat.OPAQUE);
            surfaceHolder.setFixedSize(
                    previewOutput.getSize().getWidth(), previewOutput.getSize().getHeight());
            Handler handler = cameraHandler;
            if (!released && handler != null) {
                handler.post(this::openInternal);
            }
        } catch (Exception e) {
            notifyError(e);
        }
    }

    private void openInternal() {
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) {
                throw new IllegalStateException("camera service unavailable");
            }
            if (!cameraManager.isCameraDeviceSharingSupported(cameraId)) {
                throw new IllegalStateException("camera " + cameraId + " does not support shared mode");
            }
            previewSurface = surfaceHolder.getSurface();
            if (previewSurface == null || !previewSurface.isValid()) {
                throw new IllegalStateException("preview surface is not valid");
            }
            cameraManager.openSharedCamera(cameraId, cameraExecutor, stateCallback);
        } catch (Exception e) {
            notifyError(e);
            if (reopenAttempts > 0) {
                scheduleReopen();
            }
        }
    }

    private SharedSessionConfiguration.SharedOutputConfiguration findPreviewOutput(
            SharedSessionConfiguration sharedConfig) {
        if (sharedConfig == null) {
            throw new IllegalStateException("shared session config is missing for camera " + cameraId);
        }
        for (SharedSessionConfiguration.SharedOutputConfiguration output
                : sharedConfig.getOutputStreamsInformation()) {
            if (output.getSurfaceType() == SURFACE_TYPE_SURFACE_VIEW) {
                return output;
            }
        }
        throw new IllegalStateException("shared session config has no SurfaceView output for camera "
                + cameraId);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            camera.close();
            notifyError(new IllegalStateException("camera opened outside shared mode"));
        }

        @Override
        public void onOpenedInSharedMode(@NonNull CameraDevice camera, boolean isPrimary) {
            if (released) {
                camera.close();
                return;
            }
            cameraDevice = camera;
            primary = isPrimary;
            Log.i(TAG, "camera " + cameraId + " opened in shared mode, primary=" + primary);
            createSharedSession();
        }

        @Override
        public void onClientSharedAccessPriorityChanged(
                @NonNull CameraDevice camera, boolean isPrimary) {
            boolean wasPrimary = primary;
            primary = isPrimary;
            Log.i(TAG, "camera " + cameraId + " shared priority changed, primary=" + primary);
            if (captureSession != null && wasPrimary != primary) {
                stopStreaming(wasPrimary);
                startStreaming();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            if (released) {
                camera.close();
                finishRelease();
                return;
            }
            handleCameraFailure(camera,
                    new IllegalStateException("camera " + cameraId + " disconnected"));
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            if (released) {
                camera.close();
                finishRelease();
                return;
            }
            if (error == CameraDevice.StateCallback.ERROR_CAMERA_SERVICE) {
                handleCameraServiceFailure(camera,
                        new IllegalStateException("camera " + cameraId + " error=" + error));
                return;
            }
            handleCameraFailure(camera,
                    new IllegalStateException("camera " + cameraId + " error=" + error));
        }
    };

    private void createSharedSession() {
        try {
            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_SHARED,
                    Collections.singletonList(new OutputConfiguration(previewSurface)),
                    cameraExecutor,
                    sessionCallback);
            if (primary) {
                prepareCaptureRequest();
                sessionConfig.setSessionParameters(captureRequestBuilder.build());
            }
            cameraDevice.createCaptureSession(sessionConfig);
        } catch (Exception e) {
            notifyError(e);
        }
    }

    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (released) {
                        session.close();
                        return;
                    }
                    captureSession = (CameraSharedCaptureSession) session;
                    startStreaming();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    session.close();
                    if (released) {
                        finishRelease();
                        return;
                    }
                    IllegalStateException exception = new IllegalStateException(
                            "shared preview session configuration failed for camera " + cameraId);
                    CameraDevice camera = cameraDevice;
                    if (camera != null) {
                        handleCameraFailure(camera, exception);
                    } else {
                        notifyError(exception);
                    }
                }

                @Override
                public void onReady(@NonNull CameraCaptureSession session) {
                    if (released && captureSession == session) {
                        closeSessionForRelease();
                    }
                }

                @Override
                public void onClosed(@NonNull CameraCaptureSession session) {
                    if (released) {
                        finishRelease();
                    }
                }
            };

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {
                    lastCaptureCompletedAtMs = SystemClock.elapsedRealtime();
                }
            };

    private void startStreaming() {
        if (captureSession == null || previewSurface == null || released) {
            return;
        }
        try {
            if (primary) {
                prepareCaptureRequest();
                captureSession.setRepeatingRequest(
                        captureRequestBuilder.build(), captureCallback, cameraHandler);
            } else {
                captureSession.startStreaming(
                        Collections.singletonList(previewSurface), cameraExecutor, captureCallback);
            }
            reopenAttempts = 0;
            lastCaptureCompletedAtMs = SystemClock.elapsedRealtime();
            stallReported = false;
            scheduleNoFrameWatchdog();
            if (!openReported) {
                openReported = true;
                callback.onOpenStatus(true);
            }
        } catch (Exception e) {
            notifyError(e);
        }
    }

    private void stopStreaming() {
        stopStreaming(primary);
    }

    private void prepareCaptureRequest() throws Exception {
        if (captureRequestBuilder == null) {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(previewSurface);
        }
    }

    private void stopStreaming(boolean wasPrimary) {
        if (captureSession == null) {
            return;
        }
        try {
            if (wasPrimary) {
                captureSession.stopRepeating();
            } else {
                captureSession.stopStreaming();
            }
        } catch (Exception e) {
            Log.w(TAG, "stop shared preview failed for camera " + cameraId, e);
        }
    }

    private final Runnable noFrameWatchdog = new Runnable() {
        @Override
        public void run() {
            Handler handler = cameraHandler;
            if (released || handler == null || captureSession == null || stallReported) {
                return;
            }
            long noFrameDurationMs = SystemClock.elapsedRealtime() - lastCaptureCompletedAtMs;
            if (noFrameDurationMs >= NO_FRAME_TIMEOUT_MS) {
                stallReported = true;
                Log.e(TAG, "camera " + cameraId + " preview stalled for "
                        + noFrameDurationMs + " ms");
                callback.onPreviewStalled();
                return;
            }
            handler.postDelayed(this, NO_FRAME_CHECK_INTERVAL_MS);
        }
    };

    private void scheduleNoFrameWatchdog() {
        Handler handler = cameraHandler;
        if (handler == null || released) {
            return;
        }
        handler.removeCallbacks(noFrameWatchdog);
        handler.postDelayed(noFrameWatchdog, NO_FRAME_CHECK_INTERVAL_MS);
    }

    private void beginRelease() {
        openReported = false;
        if (captureSession == null) {
            finishRelease();
            return;
        }
        stopStreaming();
        Handler handler = cameraHandler;
        if (handler != null) {
            handler.postDelayed(this::closeSessionForRelease, RELEASE_READY_TIMEOUT_MS);
        }
    }

    private void closeSessionForRelease() {
        CameraSharedCaptureSession session = captureSession;
        if (session == null) {
            finishRelease();
            return;
        }
        captureSession = null;
        session.close();
        Handler handler = cameraHandler;
        if (handler != null) {
            handler.postDelayed(this::finishRelease, RELEASE_CLOSED_TIMEOUT_MS);
        }
    }

    private void finishRelease() {
        if (releaseFinished) {
            return;
        }
        releaseFinished = true;
        captureSession = null;
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        captureRequestBuilder = null;
        HandlerThread thread = cameraThread;
        cameraHandler = null;
        cameraExecutor = null;
        cameraThread = null;
        if (thread != null) {
            thread.quitSafely();
        }
        Log.i(TAG, "camera " + cameraId + " release finished");
    }

    private void handleCameraFailure(CameraDevice camera, Exception exception) {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        camera.close();
        cameraDevice = null;
        captureRequestBuilder = null;
        primary = false;
        notifyError(exception);
        scheduleReopen();
    }

    private void handleCameraServiceFailure(CameraDevice camera, Exception exception) {
        reopenScheduled = false;
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        camera.close();
        cameraDevice = null;
        captureRequestBuilder = null;
        primary = false;
        notifyError(exception);
        if (!released) {
            callback.onCameraServiceError();
        }
    }

    private void scheduleReopen() {
        Handler handler = cameraHandler;
        if (released || handler == null || reopenScheduled
                || reopenAttempts >= MAX_REOPEN_ATTEMPTS) {
            return;
        }
        reopenScheduled = true;
        reopenAttempts++;
        Log.i(TAG, "schedule camera " + cameraId + " reopen attempt=" + reopenAttempts);
        handler.postDelayed(() -> {
            reopenScheduled = false;
            if (released || cameraHandler == null) {
                return;
            }
            Surface surface = surfaceHolder.getSurface();
            if (surface == null || !surface.isValid()) {
                return;
            }
            openInternal();
        }, REOPEN_DELAY_MS);
    }

    private void notifyError(Exception exception) {
        if (!released) {
            openReported = false;
            callback.onOpenStatus(false);
            callback.onError(exception);
        }
    }
}
