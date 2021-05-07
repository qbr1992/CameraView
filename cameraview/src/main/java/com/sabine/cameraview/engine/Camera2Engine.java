package com.sabine.cameraview.engine;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Rational;
import android.util.SizeF;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.sabine.cameraview.CameraException;
import com.sabine.cameraview.CameraOptions;
import com.sabine.cameraview.PictureResult;
import com.sabine.cameraview.SensorController;
import com.sabine.cameraview.VideoResult;
import com.sabine.cameraview.controls.Facing;
import com.sabine.cameraview.controls.Flash;
import com.sabine.cameraview.controls.Hdr;
import com.sabine.cameraview.controls.Mode;
import com.sabine.cameraview.controls.PictureFormat;
import com.sabine.cameraview.controls.WhiteBalance;
import com.sabine.cameraview.engine.action.Action;
import com.sabine.cameraview.engine.action.ActionHolder;
import com.sabine.cameraview.engine.action.Actions;
import com.sabine.cameraview.engine.action.BaseAction;
import com.sabine.cameraview.engine.action.CompletionCallback;
import com.sabine.cameraview.engine.action.LogAction;
import com.sabine.cameraview.engine.mappers.Camera2Mapper;
import com.sabine.cameraview.engine.meter.MeterAction;
import com.sabine.cameraview.engine.meter.MeterResetAction;
import com.sabine.cameraview.engine.offset.Axis;
import com.sabine.cameraview.engine.offset.Reference;
import com.sabine.cameraview.engine.options.Camera2Options;
import com.sabine.cameraview.engine.orchestrator.CameraState;
import com.sabine.cameraview.frame.Frame;
import com.sabine.cameraview.frame.FrameManager;
import com.sabine.cameraview.frame.ImageFrameManager;
import com.sabine.cameraview.gesture.Gesture;
import com.sabine.cameraview.internal.CropHelper;
import com.sabine.cameraview.internal.OrientationHelper;
import com.sabine.cameraview.metering.MeteringRegions;
import com.sabine.cameraview.picture.Full2PictureRecorder;
import com.sabine.cameraview.picture.Snapshot2PictureRecorder;
import com.sabine.cameraview.preview.CameraPreview;
import com.sabine.cameraview.preview.GlCameraPreview;
import com.sabine.cameraview.preview.RendererCameraPreview;
import com.sabine.cameraview.size.AspectRatio;
import com.sabine.cameraview.size.Size;
import com.sabine.cameraview.video.Full2VideoRecorder;
import com.sabine.cameraview.video.SnapshotVideoRecorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP;
import static android.hardware.camera2.CameraMetadata.*;
import static com.sabine.cameraview.controls.Facing.FRONT;

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Engine extends CameraBaseEngine implements
        ImageReader.OnImageAvailableListener,
        ActionHolder {

    private static final int FRAME_PROCESSING_FORMAT = ImageFormat.YUV_420_888;
    @VisibleForTesting static final long METER_TIMEOUT = 5000;
    private static final long METER_TIMEOUT_SHORT = 2500;

    private final CameraManager mManager;
    //ADD:增加当CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE = SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN 时 Camera Sensor的时间戳与SystemClock.elapsedRealtimeNanos时间戳的误差值
    private long mSensorTimestampOffset = 0L;
    //人脸跟踪，记录最后一次认定的人脸
    private android.hardware.camera2.params.Face mTargetFace;
    private float mAspectRatio = 9.0f/16.0f;
    private int mFacesLastCount = 0;

    public class CameraValues {
        private String mCameraId;
        private CameraDevice mCamera;
        private CameraCharacteristics mCameraCharacteristics;
        private CameraCaptureSession mSession;
        private CaptureRequest.Builder mRepeatingRequestBuilder;
        private TotalCaptureResult mLastRepeatingResult;
        private Camera2Options mCameraOptions;
        // Preview
        private Surface mPreviewStreamSurface = null;
        private int mSurfaceLayoutRectIndex = 0;

        private boolean mAutoExposure = true;

        private Flash mFlash;
        private Location mLocation;
        private boolean mAntishakeOn = false;
        private WhiteBalance mWhiteBalance;
        private Hdr mHdr;
        private float mZoomValue;
        private float mExposureCorrectionValue;
        private int mPreviewFrameRate;

        private int mSupportsFaceDetection = 0;
        private boolean mUsingFaceDetection = false;
        private int mSceneMode = CameraMetadata.CONTROL_SCENE_MODE_DISABLED;
        private FaceDetectionListener mFaceDetectionListener;
        private int mLastFacesDetected = -1;
        private int mCharacteristicsSensorOrientation;
        private Rect mSensorRect;

        public String getCameraId() {
            return mCameraId;
        }

        public void setCameraId(String cameraId) {
            this.mCameraId = cameraId;
        }

        public CameraDevice getCamera() {
            return mCamera;
        }

        public void setCamera(CameraDevice camera) {
            this.mCamera = camera;
        }

        public CameraCharacteristics getCameraCharacteristics() {
            return mCameraCharacteristics;
        }

        public void setCameraCharacteristics(CameraCharacteristics cameraCharacteristics) {
            this.mCameraCharacteristics = cameraCharacteristics;
            if (mCameraCharacteristics != null) {
                mSupportsFaceDetection = supportFaceDetection(mCameraCharacteristics);
                mCharacteristicsSensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            }
        }

        public CameraCaptureSession getSession() {
            return mSession;
        }

        public void setSession(CameraCaptureSession session) {
            this.mSession = session;
        }

        public CaptureRequest.Builder getRepeatingRequestBuilder() {
            return mRepeatingRequestBuilder;
        }

        public void setRepeatingRequestBuilder(CaptureRequest.Builder repeatingRequestBuilder) {
            this.mRepeatingRequestBuilder = repeatingRequestBuilder;
        }

        public TotalCaptureResult getLastRepeatingResult() {
            return mLastRepeatingResult;
        }

        public void setLastRepeatingResult(TotalCaptureResult lastRepeatingResult) {
            this.mLastRepeatingResult = lastRepeatingResult;
        }

        public boolean isAutoExposure() {
            return mAutoExposure;
        }

        public void setAutoExposure(boolean autoExposure) {
            this.mAutoExposure = autoExposure;
        }

        public Camera2Options getCameraOptions() {
            return mCameraOptions;
        }

        public void setCameraOptions(Camera2Options cameraOptions) {
            this.mCameraOptions = cameraOptions;
        }

        public Surface getPreviewStreamSurface() {
            return mPreviewStreamSurface;
        }

        public void setPreviewStreamSurface(Surface previewStreamSurface) {
            this.mPreviewStreamSurface = previewStreamSurface;
        }

        public int getSurfaceLayoutRectIndex() {
            return mSurfaceLayoutRectIndex;
        }

        public void setSurfaceLayoutRectIndex(int surfaceLayoutRectIndex) {
            this.mSurfaceLayoutRectIndex = surfaceLayoutRectIndex;
        }

        public RectF getLayoutRect() {
            return mPreview.getSurfaceLayoutRect(mSurfaceLayoutRectIndex/*Math.abs(mCameraValues.indexOf(this)-mFirstCameraIndex)*/);
        }

//        public void setLayoutRect(RectF layoutRect) {
//            this.mLayoutRect = layoutRect;
//        }

        public float getZoomValue() {
            return mZoomValue;
        }

        public void setZoomValue(float zoomValue) {
            this.mZoomValue = zoomValue;
        }

        public float getExposureCorrectionValue() {
            return mExposureCorrectionValue;
        }

        public void setExposureCorrectionValue(float exposureCorrectionValue) {
            this.mExposureCorrectionValue = exposureCorrectionValue;
        }

        public CameraCaptureSession.CaptureCallback getRepeatingRequestCallback() {
            return mRepeatingRequestCallback;
        }

        public int getCharacteristicsSensorOrientation() {
            return mCharacteristicsSensorOrientation;
        }

        public int getSupportsFaceDetection() {
            return mSupportsFaceDetection;
        }

        private final CameraCaptureSession.CaptureCallback mRepeatingRequestCallback
                = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                         @NonNull CaptureRequest request,
                                         long timestamp,
                                         long frameNumber) {
                for (Action action : mActions) {
                    action.onCaptureStarted(Camera2Engine.this, request);
                }
            }

            @Override
            public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureResult partialResult) {
                for (Action action : mActions) {
                    action.onCaptureProgressed(Camera2Engine.this, request, partialResult);
                }
            }

            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                           @NonNull CaptureRequest request,
                                           @NonNull TotalCaptureResult result) {
                mLastRepeatingResult = result;
                for (Action action : mActions) {
                    action.onCaptureCompleted(Camera2Engine.this, request, result);
                }
                handleFaceDetection(result);
            }
        };

        public void applyAllParameters(@Nullable CaptureRequest.Builder oldBuilder) {
            LOG.i("applyAllParameters:", "called for tag", mRepeatingRequestBuilder.build().getTag());
            mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            applyDefaultFocus();
            applyFlash(Flash.OFF);
            applyLocation(null);
            applyWhiteBalance(WhiteBalance.AUTO);
            applyHdr(Hdr.OFF);
            applyZoom(0F);
            applyExposureCorrection(0F);
            applyPreviewFrameRate(mPreviewFrameRate);

            setAntishake(mAntishakeOn);
            if (oldBuilder != null) {
                // We might be in a metering operation, or the old builder might have some special
                // metering parameters. Copy these special keys over to the new builder.
                // These are the keys changed by metering.Parameters, or by us in applyFocusForMetering.
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                        oldBuilder.get(CaptureRequest.CONTROL_AF_REGIONS));
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS,
                        oldBuilder.get(CaptureRequest.CONTROL_AE_REGIONS));
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AWB_REGIONS,
                        oldBuilder.get(CaptureRequest.CONTROL_AWB_REGIONS));
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                        oldBuilder.get(CaptureRequest.CONTROL_AF_MODE));
                // Do NOT copy exposure or focus triggers!
            }
        }

        @EngineThread
        private void applyRepeatingRequestBuilder(boolean checkStarted, int errorReason) {
            if ((getState() == CameraState.PREVIEW && !isChangingState()) || !checkStarted) {
                try {
                    if (mSession != null) mSession.setRepeatingRequest(mRepeatingRequestBuilder.build(),
                            mRepeatingRequestCallback, null);
                } catch (CameraAccessException e) {
                    throw new CameraException(e, errorReason);
                } catch (IllegalStateException|IllegalArgumentException e) {
                    // mSession is invalid - has been closed. This is extremely worrying because
                    // it means that the session state and getPreviewState() are not synced.
                    // This probably signals an error in the setup/teardown synchronization.
                    LOG.e("applyRepeatingRequestBuilder: session is invalid!", e,
                            "checkStarted:", checkStarted,
                            "currentThread:", Thread.currentThread().getName(),
                            "state:", getState(),
                            "targetState:", getTargetState());
//                    throw new CameraException(CameraException.REASON_DISCONNECTED);
                }
            }
        }

        private void applyDefaultFocus() {
            int[] modesArray = readCharacteristic(mCameraCharacteristics, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                    new int[]{});
            List<Integer> modes = new ArrayList<>();
            for (int mode : modesArray) { modes.add(mode); }
            if (getMode() == Mode.VIDEO &&
                    modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                if (SensorController.getInstance(getCallback().getContext()).isFocusLocked()) {

                } else {
                    // 解决华为手机点击屏幕后，无法自动对焦问题
                    mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                }
                return;
            }

            if (modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                return;
            }

            if (modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
                return;
            }

            if (modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                mRepeatingRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0F);
                //noinspection UnnecessaryReturnStatement
                return;
            }
        }

        public void setFlash(@NonNull final Flash flash) {
            boolean shouldApply = applyFlash(flash);
            boolean needsWorkaround = getState() == CameraState.PREVIEW;
            if (needsWorkaround) {
                // Runtime changes to the flash value are not correctly handled by the
                // driver. See https://stackoverflow.com/q/53003383/4288782 for example.
                // For this reason, we go back to OFF, capture once, then go to the new one.
                applyFlash(Flash.OFF);
                try {
                    if (mSession != null) mSession.capture(mRepeatingRequestBuilder.build(), null,
                            null);
                } catch (CameraAccessException e) {
                    throw createCameraException(e);
                } catch (IllegalStateException|IllegalArgumentException ignore) {

                }

                applyFlash(flash);
                mFlash = flash;
                applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);

            } else if (shouldApply) {
                mFlash = flash;
                applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);
            }
        }

        /**
         * This sets the CONTROL_AE_MODE to either:
         * - {@link CaptureRequest#CONTROL_AE_MODE_ON}
         * - {@link CaptureRequest#CONTROL_AE_MODE_ON_AUTO_FLASH}
         * - {@link CaptureRequest#CONTROL_AE_MODE_ON_ALWAYS_FLASH}
         *
         * The API offers a high level control through {@link CaptureRequest#CONTROL_AE_MODE},
         * which is what the mapper looks at. It will trigger (if specified) flash only for
         * still captures which is exactly what we want.
         *
         * However, we set CONTROL_AE_MODE to ON/OFF (depending
         * on which is available) with both {@link Flash#OFF} and {@link Flash#TORCH}.
         *
         * When CONTROL_AE_MODE is ON or OFF, the low level control, called
         * {@link CaptureRequest#FLASH_MODE}, becomes effective, and that's where we can actually
         * distinguish between a turned off flash and a torch flash.
         */
        private boolean applyFlash(@NonNull Flash flash) {
            if (mCameraOptions.supports(flash)) {
                int[] availableAeModesArray = readCharacteristic(mCameraCharacteristics,
                        CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES, new int[]{});
                List<Integer> availableAeModes = new ArrayList<>();
                for (int mode : availableAeModesArray) { availableAeModes.add(mode); }

                List<Pair<Integer, Integer>> pairs = mMapper.mapFlash(flash);
                for (Pair<Integer, Integer> pair : pairs) {
                    if (availableAeModes.contains(pair.first)) {
                        LOG.i("applyFlash: setting CONTROL_AE_MODE to", pair.first);
                        LOG.i("applyFlash: setting FLASH_MODE to", pair.second);
                        mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, pair.first);
                        mRepeatingRequestBuilder.set(CaptureRequest.FLASH_MODE, pair.second);
                        return true;
                    }
                }
            }
            return false;
        }

        public void setLocation(@Nullable final Location location) {
            final Location old = mLocation;
            mLocation = location;
            if (applyLocation(old)) {
                applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);
            }
        }

        private boolean applyLocation(@SuppressWarnings("unused") @Nullable Location oldLocation) {
            if (mLocation != null) {
                mRepeatingRequestBuilder.set(CaptureRequest.JPEG_GPS_LOCATION, mLocation);
            }
            return true;
        }

        public boolean supportAntishake() {
            return mCameraOptions.isStabSupported();
        }

        public void setAntishake(boolean antishakeOn) {
            if (supportAntishake()) {
                mAntishakeOn = antishakeOn;
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, antishakeOn?CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON:CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
            }
        }

        public void setWhiteBalance(@NonNull final WhiteBalance whiteBalance) {
            if (applyWhiteBalance(whiteBalance)) {
                mWhiteBalance = whiteBalance;
                applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);
            }
        }

        protected boolean applyWhiteBalance(@NonNull WhiteBalance whiteBalance) {
            if (mCameraOptions.supports(whiteBalance)) {
                int newWhiteBalance = mMapper.mapWhiteBalance(whiteBalance);
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, newWhiteBalance);
                return true;
            }
            return false;
        }

        public void setHdr(@NonNull final Hdr hdr) {
            if (applyHdr(hdr)) {
                mHdr = hdr;
                applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);
            }
        }

        private boolean applyHdr(@NonNull Hdr hdr) {
            if (mCameraOptions.supports(hdr)) {
                int newHdr = mMapper.mapHdr(hdr);
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, newHdr);
                return true;
            }
            return false;
        }

        public void setZoom(final float zoom, final @Nullable PointF[] points, final boolean notify) {
            if (zoom == mZoomValue)
                return;

            if (applyZoom(zoom)) {
                mZoomValue = zoom;
                applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);
                mSensorRect = getViewableRect();
                if (notify) {
                    getCallback().dispatchOnZoomChanged(zoom, points);
                }
            }
        }

        @SuppressWarnings("WeakerAccess")
        protected boolean applyZoom(float zoom) {
            if (mCameraOptions.isZoomSupported()) {
                float maxZoom = readCharacteristic(mCameraCharacteristics,
                        CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 1F);
                // converting 0.0f-1.0f zoom scale to the actual camera digital zoom scale
                // (which will be for example, 1.0-10.0)
                float calculatedZoom = (zoom * (maxZoom - 1.0f)) + 1.0f;
                Rect newRect = getZoomRect(calculatedZoom, maxZoom);
                mRepeatingRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, newRect);
                return true;
            }
            return false;
        }

        @NonNull
        private Rect getZoomRect(float zoomLevel, float maxDigitalZoom) {
            Rect activeRect = readCharacteristic(mCameraCharacteristics, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                    new Rect());
            int minW = (int) (activeRect.width() / maxDigitalZoom);
            int minH = (int) (activeRect.height() / maxDigitalZoom);
            int difW = activeRect.width() - minW;
            int difH = activeRect.height() - minH;

            // When zoom is 1, we want to return new Rect(0, 0, width, height).
            // When zoom is maxZoom, we want to return a centered rect with minW and minH
            int cropW = (int) (difW * (zoomLevel - 1) / (maxDigitalZoom - 1) / 2F);
            int cropH = (int) (difH * (zoomLevel - 1) / (maxDigitalZoom - 1) / 2F);
            return new Rect(cropW, cropH, activeRect.width() - cropW,
                    activeRect.height() - cropH);
        }

        public void setExposureCorrection(final float EVvalue,
                                          @NonNull final float[] bounds,
                                          @Nullable final PointF[] points,
                                          final boolean notify) {
            cancelAutoFocus();

            mExposureCorrectionTask = getOrchestrator().scheduleStateful(
                    "exposure correction (" + EVvalue + ")",
                    CameraState.ENGINE,
                    new Runnable() {
                        @Override
                        public void run() {
                            if (applyExposureCorrection(EVvalue)) {
                                mExposureCorrectionValue = EVvalue;
                                applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);
                                if (notify) {
                                    getCallback().dispatchOnExposureCorrectionChanged(EVvalue, bounds, points);
                                }
                            }
                        }
                    });
        }

        private boolean applyExposureCorrection(float evValue) {
            if (mCameraOptions.isExposureCorrectionSupported() && mCameraCharacteristics != null) {
                Rational exposureCorrectionStep = readCharacteristic(mCameraCharacteristics,
                        CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
                        new Rational(1, 1));
                int exposureCorrectionSteps = Math.round(evValue
                        * exposureCorrectionStep.floatValue());
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCorrectionSteps);
                return true;
            }
            return false;
        }

        public void setPreviewFrameRate(float previewFrameRate) {
            int newPreviewFrameRate = (int)previewFrameRate;
            if (applyPreviewFrameRate(newPreviewFrameRate)) {
                mPreviewFrameRate = newPreviewFrameRate;
                applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);
            }
        }

        private boolean applyPreviewFrameRate(int previewFrameRate) {
            //noinspection unchecked
            Range<Integer>[] fallback = new Range[]{};
            Range<Integer>[] fpsRanges = readCharacteristic(mCameraCharacteristics,
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                    fallback);
            for (Range<Integer> fpsRange : fpsRanges) {
                LOG.i("applyPreviewFrameRate: fpsRange = " + fpsRange);
            }
            if (previewFrameRate == 0F) {
                // 0F is a special value. Fallback to a reasonable default.
                for (Range<Integer> fpsRange : fpsRanges) {
                    if (fpsRange.contains(30)) {
                        mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                        return true;
                    }
                }
            } else {
                Range<Integer> fpsRange = new Range<>(previewFrameRate, previewFrameRate);
                LOG.i( "applyPreviewFrameRate: mPreviewFrameRate fpsRange = " + fpsRange);
                mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                return true;
            }
            return false;
        }

        public void startAutoFocus(@Nullable final Gesture gesture,
                                   @NonNull final MeteringRegions regions,
                                   @NonNull final PointF legacyPoint) {
            // This will only work when we have a preview, since it launches the preview
            // in the end. Even without this it would need the bind state at least,
            // since we need the preview size.
            if (mCameraCharacteristics == null || mUsingFaceDetection)
                return;

            cancelAutoFocus();

            getOrchestrator().scheduleStateful("autofocus (" + gesture + ")",
                    CameraState.PREVIEW,
                    new Runnable() {
                        @Override
                        public void run() {
                            // The camera options API still has the auto focus API but it really
                            // refers to "3A metering to a specific point". Since we have a point, check.
                            if (!mCameraOptions.isAutoFocusSupported()) return;

                            // Create the meter and start.
                            getCallback().dispatchOnFocusStart(gesture, legacyPoint);

                            // 重新计算曝光/焦点位置
                            int viewHeight = mPreview.getView().getHeight();
                            int viewWidth = mPreview.getView().getWidth();
                            RectF layoutRect = getLayoutRect();
                            float x = 0.0f, y = 0.0f;
                            int rotation = getAngles().offset(Reference.BASE, Reference.VIEW, Axis.ABSOLUTE);
                            if (mPreview.getDualInputTextureMode() == CameraPreview.DualInputTextureMode.PIP_MODE && layoutRect.height() != viewHeight) {
                                x = (legacyPoint.x - layoutRect.left) / layoutRect.width();
                                y = (legacyPoint.y - layoutRect.top) / layoutRect.height();
                            } else {
                                switch (rotation) {
                                    case 90:
                                    case 270:{
                                        x = (legacyPoint.x - layoutRect.left + (viewWidth - layoutRect.width()) / 2) / viewWidth;
                                        y = legacyPoint.y / viewHeight;
                                        break;
                                    }
                                    default: {
                                        x = legacyPoint.x / viewWidth;
                                        y = (legacyPoint.y - layoutRect.top + (viewHeight - layoutRect.height()) / 2) / viewHeight;
                                        break;
                                    }
                                }
//                                x = legacyPoint.x / mPreview.getView().getWidth();
//                                y = (legacyPoint.y - layoutRect.top + (viewHeight - layoutRect.height()) / 2) / mPreview.getView().getHeight();
                            }

                            //TODO:打开2个摄像头时判断对焦的是前置还是后置摄像头
                            switch (mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING)) {
                                case LENS_FACING_FRONT: {
                                    float aux = x;
                                    switch (rotation) {
                                        case 90: {
                                            y = 1 - y;
                                            break;
                                        }
                                        case 270:{
                                            x = 1 - x;
                                            break;
                                        }
                                        default: {
                                            x = 1 - y;
                                            y = 1 - aux;
                                            break;
                                        }
                                    }
                                }
                                break;
                                default: {
                                    int displayRotation = ((Activity)getCallback().getContext()).getWindowManager().getDefaultDisplay().getRotation();
                                    switch (displayRotation) {
                                        case Surface.ROTATION_0:
                                            float aux = x;
                                            x = y;
                                            y = 1 - aux;
                                            break;
                                        case Surface.ROTATION_270:
                                            x = 1 - x;
                                            y = 1 - y;
                                            break;
                                        default:
                                    }
                                }
                                break;
                            }
                            // we assume the device supports at least one metering area.
                            mMeteringAreas = new MeteringRectangle[1];
                            Range<Float> activeRange = new Range<>(0.1f,0.9f);
                            x = activeRange.clamp(x);
                            y = activeRange.clamp(y);
                            RectF rect = new RectF(x - 0.1f, y - 0.1f, x + 0.1f, y + 0.1f);
                            Rect activeArraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                            mMeteringAreas[0] = convertRectToMeteringRectangle(rect, activeArraySize, 1000);

                            final MeterAction action = createMeterAction(regions);
                            Action wrapper = Actions.timeout(METER_TIMEOUT, action);
                            wrapper.start(Camera2Engine.this);
                            wrapper.addCallback(new CompletionCallback() {
                                @Override
                                protected void onActionCompleted(@NonNull Action a) {
                                    getCallback().dispatchOnFocusEnd(gesture,
                                            action.isSuccessful(), legacyPoint);
                                    getOrchestrator().remove("reset metering");
                                    if (shouldResetAutoFocus()) {
                                        getOrchestrator().scheduleStatefulDelayed("reset metering",
                                                CameraState.PREVIEW,
                                                getAutoFocusResetDelay(),
                                                new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        unlockAndResetMetering();
                                                    }
                                                });
                                    }
                                }
                            });
                        }
                    });
        }

        public void cancelAutoFocus() {
            LOG.i("cancelAutoFocus");
            if( mCamera == null || mCameraCharacteristics == null || mSession == null) {
                LOG.i("no camera or capture session");
                return;
            }
            mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            // Camera2Basic does a capture then sets a repeating request - do the same here just to be safe
            try {
                LOG.e("applyBuilder: kye=" + CaptureRequest.CONTROL_AF_TRIGGER + ", value=" + CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                if (mSession != null && mSession.isReprocessable()) mSession.capture(mRepeatingRequestBuilder.build(), mRepeatingRequestCallback, null);
            }
            catch(CameraAccessException|IllegalStateException|IllegalArgumentException e) {
                LOG.e("failed to cancel autofocus [capture]");
//                LOG.e("reason: " + e.getReason());
                LOG.e("message: " + e.getMessage());
                e.printStackTrace();
            }
            mRepeatingRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            LOG.e("applyRepeatingRequestBuilder: kye=" + CaptureRequest.CONTROL_AF_TRIGGER + ", value=" + CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);
        }

        public boolean supportsAutoFocus() {
            if( mRepeatingRequestBuilder == null )
                return false;
            Integer focus_mode = mRepeatingRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE);
            if( focus_mode != null && (focus_mode == CaptureRequest.CONTROL_AF_MODE_AUTO || focus_mode == CaptureRequest.CONTROL_AF_MODE_MACRO) )
                return true;
            return false;
        }

        public void addFaceDetectionListener(FaceDetectionListener listener) {
            mFaceDetectionListener = listener;
            mLastFacesDetected = -1;
        }

        public boolean setFaceDetection(boolean usingFaceDetection) {
            if (mRepeatingRequestBuilder != null && mSupportsFaceDetection != 0) {
                if (mUsingFaceDetection == usingFaceDetection)
                    return true;

                if (applyFaceDetection(mRepeatingRequestBuilder, mSupportsFaceDetection, usingFaceDetection)) {
                    applyRepeatingRequestBuilder(true, CameraException.REASON_DISCONNECTED);
                    mUsingFaceDetection = usingFaceDetection;
                    return true;
                }
            }
            return false;
        }

        @SuppressWarnings("WeakerAccess")
        protected boolean applyFaceDetection(@NonNull CaptureRequest.Builder builder,
                                             @NonNull int supportsFaceDetection,
                                             @NonNull boolean usingFaceDetection) {
            if (!usingFaceDetection) {
                if (builder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != null && builder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) == CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                    LOG.i("face detection already disabled");
                    return false;
                }
                builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF);
            }
            else {
                if (builder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != null && builder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE) != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF) {
                    LOG.i("face detection already enabled");
                    return false;
                }
                mTargetFace = null;
                mSensorRect = getViewableRect();
                builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, supportsFaceDetection);
            }
            setSceneMode(builder, usingFaceDetection);
            return true;
        }

        private boolean setSceneMode(CaptureRequest.Builder builder, boolean usingFaceDetection) {
            Integer current_scene_mode = builder.get(CaptureRequest.CONTROL_SCENE_MODE);
            if( usingFaceDetection ) {
                // face detection mode overrides scene mode
                if( current_scene_mode == null || current_scene_mode != CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY ) {
                    LOG.i("setting scene mode for face detection");
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
                    builder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY);
                    return true;
                }
            }
            else if( current_scene_mode == null || current_scene_mode != mSceneMode ) {
                if( mSceneMode == CameraMetadata.CONTROL_SCENE_MODE_DISABLED ) {
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                }
                else {
                    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_USE_SCENE_MODE);
                }
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, mSceneMode);
                return true;
            }
            return false;
        }

        private void handleFaceDetection(CaptureResult result) {
            if( mFaceDetectionListener != null && mRepeatingRequestBuilder != null ) {
                Integer face_detect_mode = mRepeatingRequestBuilder.get(CaptureRequest.STATISTICS_FACE_DETECT_MODE);
                if( face_detect_mode != null && face_detect_mode != CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF && mSensorRect != null) {
//                    Rect sensor_rect = getViewableRect();
                    android.hardware.camera2.params.Face [] camera_faces = result.get(CaptureResult.STATISTICS_FACES);
                    if( camera_faces != null ) {
                        if( camera_faces.length == 0 && mLastFacesDetected == 0 ) {
                        }
                        else {
                            mLastFacesDetected = camera_faces.length;
//                            Face [] faces = new Face[camera_faces.length];
//                            for(int i=0;i<camera_faces.length;i++) {
//                                faces[i] = convertFromCameraFace(sensor_rect, camera_faces[i]);
//                            }
//                            mFaceDetectionListener.onFaceDetection(faces);

                            Face [] faces = new Face[camera_faces.length > 0 ? 1 : 0];
                            if (faces.length > 0) {
                                if (mTargetFace == null || camera_faces.length == 1)
                                    mTargetFace = camera_faces[0];
                                else {
                                    int targetFaceIndex = 0;
                                    double minDistance = Math.sqrt(Math.abs(mTargetFace.getBounds().centerX() - camera_faces[0].getBounds().centerX())+Math.abs(mTargetFace.getBounds().centerY() - camera_faces[0].getBounds().centerY()));
                                    for (int index = 1; index < camera_faces.length; index++) {
                                        double curDistance = Math.sqrt(Math.abs(mTargetFace.getBounds().centerX() - camera_faces[index].getBounds().centerX())+Math.abs(mTargetFace.getBounds().centerY() - camera_faces[index].getBounds().centerY()));
                                        if (minDistance > curDistance) {
                                            minDistance = curDistance;
                                            targetFaceIndex = index;
                                        }
                                    }
                                    mTargetFace = camera_faces[targetFaceIndex];
                                }
                                faces[0] = convertFromCameraFace(mSensorRect, mTargetFace);
//                                android.hardware.camera2.params.Face maxFace = camera_faces[0];
//                                for (int index = 1; index < camera_faces.length; index++) {
//                                    if (camera_faces[index].getBounds().width() > maxFace.getBounds().width() && camera_faces[index].getBounds().height() > maxFace.getBounds().height())
//                                        maxFace = camera_faces[index];
//                                }
//
//                                LOG.e("handleFaceDetection", "sensor_rect:", mSensorRect, "maxFace:", maxFace);
//                                faces[0] = convertFromCameraFace(mSensorRect, maxFace);
                            }
                            boolean isChanged = mFacesLastCount != camera_faces.length;
                            mFacesLastCount = camera_faces.length;
                            mFaceDetectionListener.onFaceDetection(faces, isChanged);
                        }
                    }
                }
            }
        }

        private Rect getViewableRect() {
            if( mRepeatingRequestBuilder != null ) {
                Rect crop_rect = mRepeatingRequestBuilder.get(CaptureRequest.SCALER_CROP_REGION);
                if( crop_rect != null ) {
                    return crop_rect;
                }
            }
            Rect sensor_rect = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            sensor_rect.right -= sensor_rect.left;
            sensor_rect.left = 0;
            sensor_rect.bottom -= sensor_rect.top;
            sensor_rect.top = 0;
            return sensor_rect;
        }

        private Face convertFromCameraFace(Rect sensor_rect, android.hardware.camera2.params.Face camera2_face) {
            Rect area_rect = convertRectFromCamera2(sensor_rect, camera2_face.getBounds());
            return new Face(camera2_face.getScore(), area_rect);
        }

        private Rect convertRectFromCamera2(Rect crop_rect, Rect camera2_rect) {
            // inverse of convertRectToCamera2()
            if (mAspectRatio < (crop_rect.height() * 1.0f) / crop_rect.width()) {
                int cropHeight = (int) ((crop_rect.height() - (crop_rect.width() * mAspectRatio)) / 2);
                crop_rect = new Rect(crop_rect.left, crop_rect.top+cropHeight, crop_rect.right, crop_rect.bottom-cropHeight);
            } else {
                int cropWidth = (int) ((crop_rect.width() - (crop_rect.height() / mAspectRatio)) / 2);
                crop_rect = new Rect(crop_rect.left+cropWidth, crop_rect.top, crop_rect.right-cropWidth, crop_rect.bottom);
            }
            double left_f = (camera2_rect.left-crop_rect.left)/(double)(crop_rect.width()-1);
            double top_f = (camera2_rect.top-crop_rect.top)/(double)(crop_rect.height()-1);
            double right_f = (camera2_rect.right-crop_rect.left)/(double)(crop_rect.width()-1);
            double bottom_f = (camera2_rect.bottom-crop_rect.top)/(double)(crop_rect.height()-1);
            int left = (int)(left_f * 2000) - 1000;
            int right = (int)(right_f * 2000) - 1000;
            int top = (int)(top_f * 2000) - 1000;
            int bottom = (int)(bottom_f * 2000) - 1000;

            left = Math.max(left, -1000);
            right = Math.max(right, -1000);
            top = Math.max(top, -1000);
            bottom = Math.max(bottom, -1000);
            left = Math.min(left, 1000);
            right = Math.min(right, 1000);
            top = Math.min(top, 1000);
            bottom = Math.min(bottom, 1000);

            return new Rect(left, top, right, bottom);
        }

        private int supportFaceDetection(CameraCharacteristics characteristics) {
            int [] face_modes = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
            int supportsFaceDetection = 0;
            for(int face_mode : face_modes) {
                if( face_mode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_SIMPLE || face_mode == CameraCharacteristics.STATISTICS_FACE_DETECT_MODE_FULL) {
                    supportsFaceDetection = face_mode;
                    LOG.i("supports face detection mode is ", face_mode);
                }
            }
            if( supportsFaceDetection != 0 ) {
                int face_count = characteristics.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
                if( face_count <= 0 ) {
                    LOG.i("can't support face detection, as zero max face count");
                    supportsFaceDetection = 0;
                }
            }
            if( supportsFaceDetection != 0 ) {
                // check we have scene mode CONTROL_SCENE_MODE_FACE_PRIORITY
                int [] values2 = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
                boolean has_face_priority = false;
                for(int value2 : values2) {
                    if( value2 == CameraMetadata.CONTROL_SCENE_MODE_FACE_PRIORITY ) {
                        has_face_priority = true;
                        break;
                    }
                }
                LOG.i("has_face_priority: ", has_face_priority);
                if( !has_face_priority ) {
                    LOG.i("can't support face detection, as no CONTROL_SCENE_MODE_FACE_PRIORITY");
                    supportsFaceDetection = 0;
                }
            }
            return supportsFaceDetection;
        }
    }
    private List<CameraValues> mCameraValues = new ArrayList<>();
    private List<String> mCameraIds = new ArrayList<>();
    private int mCurrentCamera = 0;
    private final Camera2Mapper mMapper = Camera2Mapper.get();

    // Frame processing
    private ImageReader mFrameProcessingReader; // need this or the reader surface is collected
    private Surface mFrameProcessingSurface;

//    // Preview
//    private Surface mPreviewStreamSurface;

    // Video recording
    // When takeVideo is called, we restart the session.
    private VideoResult.Stub mFullVideoPendingStub;

    // Picture capturing
    private ImageReader mPictureReader;
    private final boolean mPictureCaptureStopsPreview = false; // can be configurable at some point

    // Actions
    // Use COW to properly synchronize the list. We'll iterate much more than mutate
    private final List<Action> mActions = new CopyOnWriteArrayList<>();
    private MeterAction mMeterAction;
    private String mWideCameraId;        // 广角cameraID
    private String mTeleCameraId;        // 长焦cameraID
    private MeteringRectangle[] mMeteringAreas;      // 曝光/焦点区域

    public Camera2Engine(Callback callback) {
        super(callback);
        mManager = (CameraManager) getCallback().getContext()
                .getSystemService(Context.CAMERA_SERVICE);
        new LogAction().start(this);
    }

    //region Utilities

    @VisibleForTesting
    @NonNull
    <T> T readCharacteristic(@NonNull CameraCharacteristics.Key<T> key,
                             @NonNull T fallback) {
        if (mCurrentCamera >= mCameraValues.size())
            mCurrentCamera = 0;
        return readCharacteristic(mCameraValues.get(mCurrentCamera).getCameraCharacteristics(), key, fallback);
    }

    @NonNull
    private <T> T readCharacteristic(@NonNull CameraCharacteristics characteristics,
                             @NonNull CameraCharacteristics.Key<T> key,
                             @NonNull T fallback) {
        T value = characteristics.get(key);
        return value == null ? fallback : value;
    }

    @NonNull
    private CameraException createCameraException(@NonNull CameraAccessException exception) {
        int reason;
        switch (exception.getReason()) {
            case CameraAccessException.CAMERA_DISABLED:
            case CameraAccessException.CAMERA_IN_USE:
            case CameraAccessException.MAX_CAMERAS_IN_USE: {
                reason = CameraException.REASON_FAILED_TO_CONNECT;
                break;
            }
            case CameraAccessException.CAMERA_ERROR:
            case CameraAccessException.CAMERA_DISCONNECTED: {
                reason = CameraException.REASON_DISCONNECTED;
                break;
            }
            default: {
                reason = CameraException.REASON_UNKNOWN;
                break;
            }
        }
        return new CameraException(exception, reason);
    }

    @NonNull
    private CameraException createCameraException(int stateCallbackError) {
        int reason;
        switch (stateCallbackError) {
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED: // Device policy
            case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE: // Fatal error
            case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE: // Fatal error, might have to
                // restart the device
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE: {
                reason = CameraException.REASON_FAILED_TO_CONNECT;
                break;
            }
            default: {
                reason = CameraException.REASON_UNKNOWN;
                break;
            }
        }
        return new CameraException(reason);
    }

    /**
     * When creating a new builder, we want to
     * - set it to {@link #mCameraValues mRepeatingRequestBuilder}, the current one
     * - add a tag for the template just in case
     * - apply all the current parameters
     */
    @SuppressWarnings("UnusedReturnValue")
    @NonNull
    private CaptureRequest.Builder createRepeatingRequestBuilder(CameraValues cameraValues, int template)
            throws CameraAccessException {
        CaptureRequest.Builder oldBuilder = cameraValues.getRepeatingRequestBuilder();
        cameraValues.setRepeatingRequestBuilder(cameraValues.getCamera().createCaptureRequest(template));
        cameraValues.getRepeatingRequestBuilder().setTag(template);
        cameraValues.applyAllParameters(oldBuilder);
        return cameraValues.getRepeatingRequestBuilder();
    }

    /**
     * Sets up the repeating request builder with default surfaces and extra ones
     * if needed (like a video recording surface).
     */
    private void addRepeatingRequestBuilderSurfaces(@NonNull CameraValues cameraValues, @NonNull Surface... extraSurfaces) {
        cameraValues.getRepeatingRequestBuilder().addTarget(cameraValues.getPreviewStreamSurface());
        if (mFrameProcessingSurface != null) {
            cameraValues.getRepeatingRequestBuilder().addTarget(mFrameProcessingSurface);
        }
        for (Surface extraSurface : extraSurfaces) {
            if (extraSurface == null) {
                throw new IllegalArgumentException("Should not add a null surface.");
            }
            cameraValues.getRepeatingRequestBuilder().addTarget(extraSurface);
        }
    }

    /**
     * Removes default surfaces from the repeating request builder.
     */
    private void removeRepeatingRequestBuilderSurfaces(@NonNull CameraValues cameraValues) {
        cameraValues.getRepeatingRequestBuilder().removeTarget(cameraValues.getPreviewStreamSurface());
        if (mFrameProcessingSurface != null) {
            cameraValues.getRepeatingRequestBuilder().removeTarget(mFrameProcessingSurface);
        }
    }

    /**
     * Applies the repeating request builder to the preview, assuming we actually have a preview
     * running. Can be called after changing parameters to the builder.
     *
     * To apply a new builder (for example switch between TEMPLATE_PREVIEW and TEMPLATE_RECORD)
     * it should be set before calling this method, for example by calling
     * {@link #createRepeatingRequestBuilder(CameraValues, int)}.
     */
    @EngineThread
    @SuppressWarnings("WeakerAccess")
    protected void applyRepeatingRequestBuilder() {
        applyRepeatingRequestBuilder(getCurrentCamera(), true, CameraException.REASON_DISCONNECTED);
    }

    @EngineThread
    private void applyRepeatingRequestBuilder(CameraValues cameraValues, boolean checkStarted, int errorReason) {
        if (cameraValues != null && ((getState() == CameraState.PREVIEW && !isChangingState()) || !checkStarted)) {
            try {
                if (cameraValues.getSession() != null) cameraValues.getSession().setRepeatingRequest(cameraValues.getRepeatingRequestBuilder().build(),
                        cameraValues.getRepeatingRequestCallback(), null);
            } catch (CameraAccessException e) {
                throw new CameraException(e, errorReason);
            } catch (IllegalStateException|IllegalArgumentException e) {
                // mSession is invalid - has been closed. This is extremely worrying because
                // it means that the session state and getPreviewState() are not synced.
                // This probably signals an error in the setup/teardown synchronization.
                LOG.e("applyRepeatingRequestBuilder: session is invalid!", e,
                        "checkStarted:", checkStarted,
                        "currentThread:", Thread.currentThread().getName(),
                        "state:", getState(),
                        "targetState:", getTargetState());
//                throw new CameraException(CameraException.REASON_DISCONNECTED);
            }
        }
    }

//    private final CameraCaptureSession.CaptureCallback mRepeatingRequestCallback
//            = new CameraCaptureSession.CaptureCallback() {
//        @Override
//        public void onCaptureStarted(@NonNull CameraCaptureSession session,
//                                     @NonNull CaptureRequest request,
//                                     long timestamp,
//                                     long frameNumber) {
//            for (Action action : mActions) {
//                action.onCaptureStarted(Camera2Engine.this, request);
//            }
//        }
//
//        @Override
//        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
//                                        @NonNull CaptureRequest request,
//                                        @NonNull CaptureResult partialResult) {
//            for (Action action : mActions) {
//                action.onCaptureProgressed(Camera2Engine.this, request, partialResult);
//            }
//        }
//
//        @Override
//        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
//                                       @NonNull CaptureRequest request,
//                                       @NonNull TotalCaptureResult result) {
//            mLastRepeatingResult = result;
//            for (Action action : mActions) {
//                action.onCaptureCompleted(Camera2Engine.this, request, result);
//            }
//        }
//    };

    //endregion

    //region Protected APIs

    @EngineThread
    @NonNull
    @Override
    protected List<Size> getPreviewStreamAvailableSizes() {
        try {
            List<Size> candidates = null;
            for (CameraValues cameraValues : mCameraValues) {
                CameraCharacteristics characteristics = mManager.getCameraCharacteristics(cameraValues.getCameraId());
                StreamConfigurationMap streamMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (streamMap == null) {
                    throw new RuntimeException("StreamConfigurationMap is null. Should not happen.");
                }
                // This works because our previews return either a SurfaceTexture or a SurfaceHolder,
                // which are accepted class types by the getOutputSizes method.
                android.util.Size[] sizes = streamMap.getOutputSizes(mPreview.getOutputClass());
                if (candidates == null) {
                    candidates = new ArrayList<>(sizes.length);
                    for (android.util.Size size : sizes) {
                        Size add = new Size(size.getWidth(), size.getHeight());
                        if (!candidates.contains(add)) candidates.add(add);
                    }
                } else {
                    List<Size> candidates1 = new ArrayList<>(sizes.length);
                    for (android.util.Size size : sizes) {
                        Size add = new Size(size.getWidth(), size.getHeight());
                        if (!candidates1.contains(add)) candidates1.add(add);
                    }
                    int nIndex = 0;
                    while (nIndex < candidates.size()) {
                        if (!candidates1.contains(candidates.get(nIndex))) candidates.remove(nIndex);
                        else nIndex++;
                    }
                }
            }
            return candidates;
        } catch (CameraAccessException e) {
            throw createCameraException(e);
        }
    }

    @EngineThread
    @NonNull
    @Override
    protected List<Size> getFrameProcessingAvailableSizes() {
        try {
            List<Size> candidates = null;
            for (CameraValues cameraValues : mCameraValues) {
                CameraCharacteristics characteristics = mManager.getCameraCharacteristics(cameraValues.getCameraId());
                StreamConfigurationMap streamMap =
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (streamMap == null) {
                    throw new RuntimeException("StreamConfigurationMap is null. Should not happen.");
                }
                android.util.Size[] sizes = streamMap.getOutputSizes(mFrameProcessingFormat);
                if (candidates == null) {
                    candidates = new ArrayList<>(sizes.length);
                    for (android.util.Size size : sizes) {
                        Size add = new Size(size.getWidth(), size.getHeight());
                        if (!candidates.contains(add)) candidates.add(add);
                    }
                } else {
                    List<Size> candidates1 = new ArrayList<>(sizes.length);
                    for (android.util.Size size : sizes) {
                        Size add = new Size(size.getWidth(), size.getHeight());
                        if (!candidates1.contains(add)) candidates1.add(add);
                    }
                    for (Size size : candidates) {
                        if (!candidates1.contains(size)) candidates.remove(size);
                    }
                }
            }
            return candidates;
        } catch (CameraAccessException e) {
            throw createCameraException(e);
        }
    }

    @EngineThread
    @Override
    protected void onPreviewStreamSizeChanged() {
        LOG.e("onPreviewStreamSizeChanged:", "Calling restartBind().");
        restartBind();
    }

    @EngineThread
    @Override
    protected final boolean collectCameraInfo(@NonNull Facing... facings) {
        boolean result = false;
        String[] cameraIds = null;
        try {
            cameraIds = mManager.getCameraIdList();
        } catch (CameraAccessException e) {
            // This should never happen, I don't see how it could crash here.
            // However, let's launch an unrecoverable exception.
            throw createCameraException(e);
        }
//        supportDuoCamera = false;
        if (supportDuoCamera == -1) {
            int backCameraIds = 0;
            int frontCameraIds = 0;
            for (String cameraId : cameraIds) {
                try {
                    Log.e(TAG, "collectCameraInfo: cameraId == " + cameraId);
                    CameraCharacteristics characteristics = mManager.getCameraCharacteristics(cameraId);
                    if (LENS_FACING_BACK == readCharacteristic(characteristics,
                            CameraCharacteristics.LENS_FACING, -99)) {
                        backCameraIds++;
                    } else {
                        backCameraIds++;
                    }
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
            Log.e(TAG, "collectCameraInfo: backCameraIds === " + backCameraIds + ", frontCameraIds === " + frontCameraIds);
            // Android版本大于28，避免低版本api支持不规范完整问题
            if (backCameraIds >= 4 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                supportDuoCamera = 1;
            } else
                supportDuoCamera = 0;
        }

        mCameraIds.clear();
        for (Facing facing:facings) {
            int internalFacing = mMapper.mapFacing(facing);     // 长焦/广角/后置映射为后置相机，前置相机映射为前置相机
            LOG.e(TAG, "collectCameraInfo: facing = " + facing + ", internalFacing = " + internalFacing);

            for (String cameraId : cameraIds) {
                try {
                    CameraCharacteristics characteristics = mManager.getCameraCharacteristics(cameraId);
                    if (internalFacing == readCharacteristic(characteristics,
                            CameraCharacteristics.LENS_FACING, -99)) {
                        // 切换后置摄像头时，需判断普通/长焦/广角
                        if (facing != Facing.FRONT) {
                            if (supportDuoCamera == 1) {
                                switch (facing) {
                                    case BACK_NORMAL:
                                        mCameraIds.add(cameraIds[0]);
                                        break;
                                    case BACK_WIDE:
                                        mCameraIds.add(getWideCameraId(cameraIds));
                                        break;
                                    case BACK_TELE:
                                        mCameraIds.add(getTeleCameraId(cameraIds));
                                        break;
                                    default:
                                        mCameraIds.add(cameraIds[0]);
                                        break;
                                }
                            } else {
                                mCameraIds.add(cameraId);
                            }
                            // 切换前置摄像头时，无需判断摄像头类型
                        } else {
                            mCameraIds.add(cameraId);
                        }
                        //TODO:需要注意2个摄像头时下边这两行代码是否有问题
                        int sensorOffset = readCharacteristic(characteristics,
                                CameraCharacteristics.SENSOR_ORIENTATION, 0);
                        LOG.e(TAG, "collectCameraInfo: facing = " + facing + ", internalFacing = " + internalFacing + ", sensorOffset = " + sensorOffset);
                        getAngles().setSensorOffset(facing, sensorOffset);
                        result = true;
                        break;
                    }
                } catch (CameraAccessException ignore) {
                }
            }
        }
        return result;
    }

    /**
     * 获取广角cameraId
     * @param cameraIds
     * @return
     * @throws CameraAccessException
     */
    private String getWideCameraId(String[] cameraIds) throws CameraAccessException {
        if (mWideCameraId != null) {
            return mWideCameraId;
        }
        float bigFovSize = 0;           // 记录的大的逻辑camera device FovSize
        // 华为nova,i=0时计算得为广角，所以从1开始计算
        for (int i = 1; i < cameraIds.length; i++) {
            String cameraId = cameraIds[i];
            CameraCharacteristics characteristics = mManager.getCameraCharacteristics(cameraId);
            int cOrientation = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (cOrientation == CameraCharacteristics.LENS_FACING_BACK) {
                float[] maxFocus = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                Log.e(TAG, "getWideCameraId: " + Arrays.toString(maxFocus));
                SizeF size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                float w = size.getWidth();
                float h = size.getHeight();
                float horizontalAngle = (float) (2*Math.atan(w/(maxFocus[0]*2)));
                float verticalAngle = (float) (2*Math.atan(h/(maxFocus[0]*2)));
                float currentFovSize = horizontalAngle * verticalAngle;
                Log.e(TAG, "getWideCameraId: cameraId === " + cameraId + ", horizontalAngle === " + horizontalAngle + ", verticalAngle === " + verticalAngle + ", currentFovSize === " + currentFovSize) ;
                // 适配mate30pro，有的cameraId支持的流没有mPictureFormat类型
                StreamConfigurationMap streamMap = characteristics.get(SCALER_STREAM_CONFIGURATION_MAP);
                if (streamMap == null) {
                    throw new RuntimeException("StreamConfigurationMap is null. Should not happen.");
                }
                int[] pictureFormats = streamMap.getOutputFormats();
                int format;
                switch (mPictureFormat) {
                    case JPEG: format = ImageFormat.JPEG; break;
                    case DNG: format = ImageFormat.RAW_SENSOR; break;
                    default: throw new IllegalArgumentException("Unknown format:"+ mPictureFormat);
                }
                boolean hasPictureFormat = false;
                for (int picFormat : pictureFormats) {
                    if (picFormat == format) {
                        hasPictureFormat = true;
                        break;
                    }
                }
                if (currentFovSize > bigFovSize && hasPictureFormat) {
                    mWideCameraId = cameraId;
                    bigFovSize = currentFovSize;
                }
            }
        }
        Log.e(TAG, "getWideCameraId: " + mWideCameraId);
        return mWideCameraId;
    }

    /**
     * 获取长焦cameraId
     * @param cameraIds
     * @return
     * @throws CameraAccessException
     */
    private String getTeleCameraId(String[] cameraIds) throws CameraAccessException {
        if (mTeleCameraId != null) {
            return mTeleCameraId;
        }
//        float diffFocalLength = 0;           // 记录的焦距的范围
        float tempMaxLength = 0;                // 暂存最大焦距
        for (int i = 0; i < cameraIds.length; i++) {
            String cameraId = cameraIds[i];
            CameraCharacteristics characteristics = mManager.getCameraCharacteristics(cameraId);
            int cOrientation = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (cOrientation == CameraCharacteristics.LENS_FACING_BACK) {
                float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
//                float minLength = focalLengths[0];
                float currentMaxLength = focalLengths[focalLengths.length-1];
//                float currentDiffFocalLength = currentMaxLength- minLength;
                Log.e(TAG, "getTeleCameraId: cameraId === " + cameraId + ", currentMaxLength === " + currentMaxLength);
                if (currentMaxLength > tempMaxLength) {
                    mTeleCameraId = cameraId;
                    tempMaxLength = currentMaxLength;
                }
            }
        }
        Log.e(TAG, "getTeleCameraId: " + mTeleCameraId);
        return mTeleCameraId;
    }

    //endregion

    //region Start

    @EngineThread
    @SuppressLint("MissingPermission")
    @NonNull
    @Override
    protected Task<CameraOptions> onStartEngine() {
        final TaskCompletionSource<CameraOptions> task = new TaskCompletionSource<>();
        try {
            // We have a valid camera for this Facing. Go on.
            mSensorTimestampOffset = 0L;
            for (final String cameraId : mCameraIds) {
                mManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        CameraValues cameraValues = new CameraValues();
                        cameraValues.setCameraId(cameraId);
                        cameraValues.setCamera(camera);
                        Log.e(TAG, "collectCameraInfo onOpened: " + camera.getId());

                        // Set parameters that might have been set before the camera was opened.
                        try {
                            LOG.e("onStartEngine:", "Opened camera device", cameraId);
                            CameraCharacteristics cameraCharacteristics = mManager.getCameraCharacteristics(cameraId);
                            //ADD:判断CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE是SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN时，计算时间戳误差值
                            if (mSensorTimestampOffset == 0 && cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN) {
                                mSensorTimestampOffset = System.nanoTime() - SystemClock.elapsedRealtimeNanos();
                            }
                            LOG.e(TAG, "SENSOR_INFO_TIMESTAMP_SOURCE == " + cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));
                            cameraValues.setCameraCharacteristics(cameraCharacteristics);

                            StreamConfigurationMap map = cameraCharacteristics
                                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                            supportHighSpeed = false;
                            if (map != null) {
                                if (mVideoSize != null && mVideoSize.hasHighSpeedCamcorder(CameraMetadata.LENS_FACING_FRONT)) {
                                    supportHighSpeed = true;
                                }
                            }

                            boolean flip = getAngles().flip(Reference.SENSOR, Reference.VIEW);
                            int format;
                            switch (mPictureFormat) {
                                case JPEG:
                                    format = ImageFormat.JPEG;
                                    break;
                                case DNG:
                                    format = ImageFormat.RAW_SENSOR;
                                    break;
                                default:
                                    throw new IllegalArgumentException("Unknown format:"
                                            + mPictureFormat);
                            }
                            Camera2Options cameraOptions = new Camera2Options(mManager, cameraId, flip, format);
                            cameraValues.setCameraOptions(cameraOptions);
                            mCameraValues.add(cameraValues);
//                            LOG.e("collectCameraInfo opened camera size: " + mCameraValues.size(), "last camera id:", mCameraIds.get(mCameraIds.size()-1));
                            //TODO:注意打开2个摄像头时使用mCameraOptions的地方
                            if (mCameraOptions == null) mCameraOptions = cameraOptions;
                            createRepeatingRequestBuilder(cameraValues, CameraDevice.TEMPLATE_PREVIEW);
                        } catch (CameraAccessException e) {
                            task.trySetException(createCameraException(e));
                            return;
                        }
                        //TODO:注意打开2个摄像头时task.trySetResult的值
                        if (cameraId == mCameraIds.get(mCameraIds.size()-1)) {
                            task.trySetResult(mCameraOptions);
                        }
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        // Not sure if this is called INSTEAD of onOpened() or can be called after
                        // as well. Cover both cases with an unrecoverable exception so that the
                        // engine is properly destroyed.
//                    CameraException exception
//                            = new CameraException(CameraException.REASON_DISCONNECTED);
//                    if (!task.getTask().isComplete()) {
//                        task.trySetException(exception);
//                    } else {
//                        LOG.i("CameraDevice.StateCallback reported disconnection.");
//                        throw exception;
//                    }
                        getCallback().dispatchOnCameraClosed();
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        if (!task.getTask().isComplete()) {
                            task.trySetException(createCameraException(error));
                        } else {
                            // This happened while the engine is running. Throw unrecoverable exception
                            // so that engine is properly destroyed.
                            LOG.e("CameraDevice.StateCallback reported an error:", error);
                            throw new CameraException(CameraException.REASON_DISCONNECTED);
                        }
                    }
                }, null);
            }
        } catch (CameraAccessException e) {
            throw createCameraException(e);
        }
        return task.getTask();
    }

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStartBind() {
        LOG.i("onStartBind:", "Started");
        final TaskCompletionSource<Void> task = new TaskCompletionSource<>();

        // Compute sizes.
        // TODO preview stream should never be bigger than 1920x1080 as per
        //  CameraDevice.createCaptureSession. This should be probably be applied
        //  before all the other external selectors, to treat it as a hard limit.
        //  OR: pass an int into these functions to be able to take smaller dims
        //  when session configuration fails
        //  OR: both.
        mCaptureSize = computeCaptureSize();
        mPreviewStreamSize = computePreviewStreamSize();

        mPreview.resetOutputTextureDrawer();
        mPreview.setSensorTimestampOffset(mSensorTimestampOffset);
//        int inputSurfaceTextureCount = mPreview.getInputSurfaceTextureCount();
//        if (inputSurfaceTextureCount>mCameraValues.size()) {
//            for (int i = inputSurfaceTextureCount; i > mCameraValues.size(); i--)
//                mPreview.removeInputSurfaceTexture(i-1);
//        }

//        if (mFirstCameraIndex != 0 && mCameraValues.size() > 1 && mFirstCameraIndex < mCameraValues.size()) {
//            for (int i = 0; i < mFirstCameraIndex; i++) {
//                mCameraValues.add(mCameraValues.get(0));
//                mCameraValues.remove(0);
//            }
//            mFirstCameraIndex = 0;
//        }

        if (mCameraValues.size() == 0) {
            task.trySetResult(null);
            return task.getTask();
        }

        mFirstCameraIndex = mFirstCameraIndex % mCameraValues.size();
        if (mCameraValues.size() > 1 && mCameraValues.get(mFirstCameraIndex).getCameraCharacteristics().get(CameraCharacteristics.LENS_FACING) == LENS_FACING_FRONT) {
            mPreview.setFrontIsFirst(true);
        }
        else {
            mPreview.setFrontIsFirst(false);
        }
        int cameraIndex = 0;
        for (; cameraIndex < mCameraValues.size(); cameraIndex++) {
//        for (final CameraValues cameraValues : mCameraValues) {
            final CameraValues cameraValues = mCameraValues.get((mFirstCameraIndex+cameraIndex)%mCameraValues.size());
            final Object output = mPreview.getOutput(cameraIndex);
            if (output == null) {

                mCameraValues.remove(cameraValues);
                if (cameraIndex < mCameraValues.size()) {
                    cameraIndex--;
                    continue;
                }
                else {
                    task.trySetResult(null);
                    break;
                }
            }

            // Deal with surfaces.
            // In Camera2, instead of applying the size to the camera params object,
            // we must resize our own surfaces and configure them before opening the session.
            List<Surface> outputSurfaces = new ArrayList<>();

            // 1. PREVIEW
            // Create a preview surface with the correct size.
            final Class outputClass = mPreview.getOutputClass();
//            final Object output = mPreview.getOutput(cameraIndex);
            Surface previewStreamSurface = null;
            if (outputClass == SurfaceHolder.class) {
                try {
                    // This must be called from the UI thread...
                    Tasks.await(Tasks.call(new Callable<Void>() {
                        @Override
                        public Void call() {
                            ((SurfaceHolder) output).setFixedSize(
                                    mPreviewStreamSize.getWidth(),
                                    mPreviewStreamSize.getHeight());
                            return null;
                        }
                    }));
                } catch (ExecutionException | InterruptedException e) {
                    throw new CameraException(e, CameraException.REASON_FAILED_TO_CONNECT);
                }
                previewStreamSurface = ((SurfaceHolder) output).getSurface();
            } else if (outputClass == SurfaceTexture.class) {
                if (output == null) {
                    getCallback().dispatchOnCameraClosed();
                    task.trySetResult(null);
                    return task.getTask();
                }
                ((SurfaceTexture) output).setDefaultBufferSize(
                        mPreviewStreamSize.getWidth(),
                        mPreviewStreamSize.getHeight());
                previewStreamSurface = new Surface((SurfaceTexture) output);
            } else {
                throw new RuntimeException("Unknown CameraPreview output class.");
            }
            cameraValues.setPreviewStreamSurface(previewStreamSurface);
            cameraValues.setSurfaceLayoutRectIndex(cameraIndex);
            //TODO:打开2个摄像头时要重新调整显示的位置信息
//            cameraValues.setLayoutRect(new RectF(0.0f, (cameraIndex-1)*(mPreview.getView().getHeight()/mCameraValues.size()), mPreview.getView().getWidth(), cameraIndex*(mPreview.getView().getHeight()/mCameraValues.size())));
            outputSurfaces.add(previewStreamSurface);

            // 2. VIDEO RECORDING
            if (getMode() == Mode.VIDEO) {
                if (mFullVideoPendingStub != null) {
                    if (mVideoRecorder == null) {
                        Full2VideoRecorder recorder = new Full2VideoRecorder(this, cameraValues.getCameraId());
                        try {
                            outputSurfaces.add(recorder.createInputSurface(mFullVideoPendingStub));
                        } catch (Full2VideoRecorder.PrepareException e) {
                            throw new CameraException(e, CameraException.REASON_FAILED_TO_CONNECT);
                        }
                        mVideoRecorder = recorder;
                    } else {
                        Full2VideoRecorder recorder = (Full2VideoRecorder) mVideoRecorder;
                        outputSurfaces.add(recorder.getInputSurface());
                    }
                }
            }

            // 3. PICTURE RECORDING
            // Format is supported, or it would have thrown in Camera2Options constructor.
            if (getMode() == Mode.PICTURE) {
                int format;
                switch (mPictureFormat) {
                    case JPEG:
                        format = ImageFormat.JPEG;
                        break;
                    case DNG:
                        format = ImageFormat.RAW_SENSOR;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown format:" + mPictureFormat);
                }
                if (mPictureReader != null)
                    mPictureReader = ImageReader.newInstance(
                            mCaptureSize.getWidth(),
                            mCaptureSize.getHeight(),
                            format, 2);
                outputSurfaces.add(mPictureReader.getSurface());
            }

            // 4. FRAME PROCESSING
            if (hasFrameProcessors()) {
                mFrameProcessingSize = computeFrameProcessingSize();
                // Hard to write down why, but in Camera2 we need a number of Frames that's one less
                // than the number of Images. If we let all Images be part of Frames, thus letting all
                // Images be used by processor at any given moment, the Camera2 output breaks.
                // In fact, if there are no Images available, the sensor BLOCKS until it finds one,
                // which is a big issue because processor times become a bottleneck for the preview.
                // This is a design flaw in the ImageReader / sensor implementation, as they should
                // simply DROP frames written to the surface if there are no Images available.
                // Since this is not how things work, we ensure that one Image is always available here.
                if (mFrameProcessingReader == null) {
                    mFrameProcessingReader = ImageReader.newInstance(
                            mFrameProcessingSize.getWidth(),
                            mFrameProcessingSize.getHeight(),
                            mFrameProcessingFormat,
                            getFrameProcessingPoolSize() + 1);
                    mFrameProcessingReader.setOnImageAvailableListener(this,
                            null);
                    mFrameProcessingSurface = mFrameProcessingReader.getSurface();
                }
                outputSurfaces.add(mFrameProcessingSurface);
            } else {
                mFrameProcessingReader = null;
                mFrameProcessingSize = null;
                mFrameProcessingSurface = null;
            }

            try {
                // null handler means using the current looper which is totally ok.
                cameraValues.getCamera().createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        cameraValues.setSession(session);
                        LOG.i(cameraValues.getCameraId(), " onStartBind:", "Completed");
                        if (mCameraValues.size() > 0 && cameraValues == mCameraValues.get((mCameraValues.size()+mFirstCameraIndex-1)%mCameraValues.size()))
                            task.trySetResult(null);
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        // This SHOULD be a library error so we throw a RuntimeException.
                        String message = LOG.e("onConfigureFailed! Session", session);
                        task.trySetResult(null);
//                    throw new RuntimeException(message);
                    }

                    @Override
                    public void onReady(@NonNull CameraCaptureSession session) {
                        super.onReady(session);
                        LOG.i("CameraCaptureSession.StateCallback reported onReady.");
                    }
                }, null);
            } catch (CameraAccessException e) {
                throw createCameraException(e);
            }
        }
        return task.getTask();
    }

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStartPreview() {
        getCallback().onCameraPreviewStreamSizeChanged();

        Size previewSizeForView = getPreviewStreamSize(Reference.VIEW);
        if (previewSizeForView == null) {
            throw new IllegalStateException("previewStreamSize should not be null at this point.");
        }
        mFacesLastCount = 0;
        mPreview.setStreamSize(previewSizeForView.getWidth(), previewSizeForView.getHeight());
        mAspectRatio = previewSizeForView.getWidth() > previewSizeForView.getHeight() ? (previewSizeForView.getHeight() * 1.0f) / previewSizeForView.getWidth() : (previewSizeForView.getWidth() * 1.0f) / previewSizeForView.getHeight();
        int rotation = getAngles().offset(Reference.BASE, Reference.VIEW, Axis.ABSOLUTE);
        mPreview.setDrawRotation(rotation/*getAngles().offset(Reference.BASE, Reference.VIEW, Axis.ABSOLUTE)*/);
        if (hasFrameProcessors()) {
            getFrameManager().setUp(mFrameProcessingFormat, mFrameProcessingSize, getAngles());
        }

        for (CameraValues cameraValues : mCameraValues) {
//            LOG.e(cameraValues.getCameraId(), " onStartPreview:", cameraValues.mCameraId, " Starting preview.");
            addRepeatingRequestBuilderSurfaces(cameraValues);
            cameraValues.applyRepeatingRequestBuilder(false,
                    CameraException.REASON_FAILED_TO_START_PREVIEW);
//            applyRepeatingRequestBuilder(cameraValues, false,
//                    CameraException.REASON_FAILED_TO_START_PREVIEW);
//            LOG.i("onStartPreview:", "Started preview.");
//            try {
//                Thread.sleep(1000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
        }
        mPreview.startPreview();

        // Start delayed video if needed.
        if (mFullVideoPendingStub != null) {
            // Do not call takeVideo/onTakeVideo. It will reset some stub parameters that
            // the recorder sets. Also we are posting so that doTakeVideo sees a started preview.
            final VideoResult.Stub stub = mFullVideoPendingStub;
            mFullVideoPendingStub = null;
            getOrchestrator().scheduleStateful("do take video", CameraState.PREVIEW,
                    new Runnable() {
                @Override
                public void run() {
                    doTakeVideo(stub);
                }
            });
        }

        float scaleX;
        float scaleY = 1.0f;
        if (!dual()) {
            if (mFacing[0] == FRONT) {
                scaleX = -1.0f;
            } else {
                scaleX = 1.0f;
            }
            if (mVideoRecorder != null) mVideoRecorder.setScaleCrop(scaleX, scaleY);
        }

        // Wait for the first frame.
        final TaskCompletionSource<Void> task = new TaskCompletionSource<>();
        if (mCameraValues.size() > 0)
            new BaseAction() {
                @Override
                public void onCaptureCompleted(@NonNull ActionHolder holder,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(holder, request, result);
                    setState(STATE_COMPLETED);
                    task.trySetResult(null);
                }
            }.start(this);
        else
            task.trySetResult(null);
        return task.getTask();
    }

    //endregion

    //region Stop

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStopPreview() {
        LOG.e("onStopPreview:", "Started.");
//        if (mVideoRecorder != null) {
//            // This should synchronously call onVideoResult that will reset the repeating builder
//            // to the PREVIEW template. This is very important.
//            mVideoRecorder.stop(true);
//            mVideoRecorder = null;
//        }
//        mPictureRecorder = null;
        if (hasFrameProcessors()) {
            getFrameManager().release();
        }
        // Removing the part below for now. It hangs on emulators and can take a lot of time
        // in real devices, for benefits that I'm not 100% sure about.
        for (CameraValues cameraValues : mCameraValues) {
            if (false) {
                try {
                    // Preferring abortCaptures() over stopRepeating(): it makes sure that all
                    // in-flight operations are discarded as fast as possible, which is what we want.
                    // NOTE: this call is asynchronous. Should find a good way to wait for the outcome.
                    LOG.i("onStopPreview:", "calling abortCaptures().");
                    cameraValues.getSession().abortCaptures();
                    LOG.i("onStopPreview:", "called abortCaptures().");
                } catch (CameraAccessException e) {
                    // This tells us that we should stop everything. It's better to throw an
                    // unrecoverable exception rather than just swallow, so everything gets stopped.
                    LOG.w("onStopPreview:", "abortCaptures failed!", e);
                    throw createCameraException(e);
                } catch (IllegalStateException e) {
                    // This tells us that the session was already closed.
                    // Not sure if this can happen, but we can swallow it.
                }
            }
            removeRepeatingRequestBuilderSurfaces(cameraValues);
            cameraValues.setLastRepeatingResult(null);
        }
        LOG.e("onStopPreview:", "Returning.");
        return Tasks.forResult(null);
    }

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStopBind() {
        LOG.e("onStopBind:", "About to clean up.");
        mFrameProcessingSurface = null;
//        mPreviewStreamSurface = null;
        mPreviewStreamSize = null;
        mCaptureSize = null;
        mFrameProcessingSize = null;
        if (mFrameProcessingReader != null) {
            // WARNING: This call synchronously releases all Images and their underlying
            // properties. This can cause issues if the Image is being used.
            mFrameProcessingReader.close();
            mFrameProcessingReader = null;
        }
        if (mPictureReader != null) {
            mPictureReader.close();
            mPictureReader = null;
        }
        for (CameraValues cameraValues : mCameraValues) {
            cameraValues.setPreviewStreamSurface(null);
            cameraValues.getSession().close();
            cameraValues.setSession(null);
        }
        mPreview.resetOutputTextureDrawer();
        LOG.e("onStopBind:", "Returning.");
        return Tasks.forResult(null);
    }

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStopEngine() {
        for (CameraValues cameraValues : mCameraValues) {
            try {
                LOG.i("onStopEngine:", "Clean up.", "Releasing camera.");
                // Just like Camera1Engine, this call can hang (at least on emulators) and if
                // we don't find a way around the lock, it leaves the camera in a bad state.
                //
                // 12:33:28.152  2888  5470 I CameraEngine: onStopEngine: Clean up. Releasing camera.[0m
                // 12:33:29.476  1384  1555 E audio_hw_generic: pcm_write failed cannot write stream data: I/O error[0m
                // 12:33:33.206  1512  3616 E Camera3-Device: Camera 0: waitUntilDrainedLocked: Error waiting for HAL to drain: Connection timed out (-110)[0m
                // 12:33:33.242  1512  3616 E CameraDeviceClient: detachDevice: waitUntilDrained failed with code 0xffffff92[0m
                // 12:33:33.243  1512  3616 E Camera3-Device: Camera 0: disconnect: Shutting down in an error state[0m
                //
                // I believe there is a thread deadlock due to this call internally waiting to
                // dispatch some callback to us (pending captures, ...), but the callback thread
                // is blocked here. We try to workaround this in CameraEngine.destroy().
                cameraValues.getCamera().close();
                LOG.i("onStopEngine:", "Clean up.", "Released camera.");
            } catch (Exception e) {
                LOG.w("onStopEngine:", "Clean up.", "Exception while releasing camera.", e);
            }
            cameraValues.setCamera(null);

            // After engine is stopping, the repeating request builder will be null,
            // so the ActionHolder.getBuilder() contract would be broken. Same for characteristics.
            // This can cause crashes if some ongoing Action queries the holder. So we abort them.
            LOG.i("onStopEngine:", "Aborting actions.");
            for (Action action : mActions) {
                action.abort(this);
            }

            cameraValues.setCameraCharacteristics(null);
            cameraValues.setRepeatingRequestBuilder(null);
        }
        mCameraValues.clear();
        mCameraOptions = null;
        LOG.w("onStopEngine:", "Returning.");
        return Tasks.forResult(null);
    }

    //endregion

    //region Pictures

    @EngineThread
    @Override
    protected void onTakePictureSnapshot(@NonNull final PictureResult.Stub stub,
                                         @NonNull final AspectRatio outputRatio,
                                         boolean doMetering) {
        if (doMetering) {
            Action action = Actions.timeout(METER_TIMEOUT_SHORT, createMeterAction(null));
            action.addCallback(new CompletionCallback() {
                @Override
                protected void onActionCompleted(@NonNull Action action) {
                    // This is called on any thread, so be careful.
                    setPictureSnapshotMetering(false);
                    takePictureSnapshot(stub);
                    setPictureSnapshotMetering(true);
                }
            });
            action.start(this);
        } else {
            if (!(mPreview instanceof RendererCameraPreview)) {
                throw new RuntimeException("takePictureSnapshot with Camera2 is only " +
                        "supported with Preview.GL_SURFACE");
            }
            // stub.size is not the real size: it will be cropped to the given ratio
            // stub.rotation will be set to 0 - we rotate the texture instead.
//            stub.size = getUncroppedSnapshotSize(Reference.OUTPUT);
            stub.size = mPreview.getSurfaceSize();
//            stub.rotation = getAngles().offset(Reference.VIEW, Reference.OUTPUT, Axis.ABSOLUTE);
            mPictureRecorder = new Snapshot2PictureRecorder(stub, this,
                    (RendererCameraPreview) mPreview, outputRatio);
            mPictureRecorder.take();
        }
    }

    @EngineThread
    @Override
    protected void onTakePicture(@NonNull final PictureResult.Stub stub, boolean doMetering) {
        if (doMetering) {
            LOG.i("onTakePicture:", "doMetering is true. Delaying.");
            Action action = Actions.timeout(METER_TIMEOUT_SHORT, createMeterAction(null));
            action.addCallback(new CompletionCallback() {
                @Override
                protected void onActionCompleted(@NonNull Action action) {
                    // This is called on any thread, so be careful.
                    setPictureMetering(false);
                    takePicture(stub);
                    setPictureMetering(true);
                }
            });
            action.start(this);
        } else {
            LOG.i("onTakePicture:", "doMetering is false. Performing.");
            stub.rotation = getAngles().offset(Reference.SENSOR, Reference.OUTPUT,
                    Axis.RELATIVE_TO_SENSOR);
            stub.size = getPictureSize(Reference.OUTPUT);
            try {
                //TODO:打开2个摄像头时拍照功能只能实现一个摄像头的拍照功能
                CameraValues cameraValues = mCameraValues.get(mCurrentCamera);
                if (cameraValues != null) {
                    if (mPictureCaptureStopsPreview) {
                        // These two are present in official samples and are probably meant to
                        // speed things up? But from my tests, they actually make everything slower.
                        // So this is disabled by default with a boolean flag. Maybe in the future
                        // we can make this configurable as some people might want to stop the preview
                        // while picture is being taken even if it increases the latency.
                        cameraValues.getSession().stopRepeating();
                        cameraValues.getSession().abortCaptures();
                    }
                    CaptureRequest.Builder builder
                            = cameraValues.getCamera().createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    applyAllParameters(builder, cameraValues.getRepeatingRequestBuilder());
                    mPictureRecorder = new Full2PictureRecorder(stub, this, builder,
                            mPictureReader);
                    mPictureRecorder.take();
                }
            } catch (CameraAccessException e) {
                throw createCameraException(e);
            }
        }
    }

    @Override
    public void onPictureResult(@Nullable PictureResult.Stub result, @Nullable Exception error) {
        boolean fullPicture = mPictureRecorder instanceof Full2PictureRecorder;
        super.onPictureResult(result, error);
        if (fullPicture && mPictureCaptureStopsPreview) {
            applyRepeatingRequestBuilder();
        }

        // Some picture recorders might lock metering, and we usually run a metering sequence
        // before running the recorders. So, run an unlock/reset sequence if needed.
        boolean unlock = (fullPicture && getPictureMetering())
                || (!fullPicture && getPictureSnapshotMetering());
        if (unlock) {
            getOrchestrator().scheduleStateful("reset metering after picture",
                    CameraState.PREVIEW,
                    new Runnable() {
                @Override
                public void run() {
//                    unlockAndResetMetering();
                }
            });
        }
    }

    //endregion

    //region Videos

    @EngineThread
    @Override
    protected void onTakeVideo(@NonNull VideoResult.Stub stub) {
        LOG.i("onTakeVideo", "called.");
        stub.rotation = getAngles().offset(Reference.SENSOR, Reference.OUTPUT,
                Axis.RELATIVE_TO_SENSOR);
        stub.size = getAngles().flip(Reference.SENSOR, Reference.OUTPUT) ?
                mCaptureSize.flip() : mCaptureSize;
        // We must restart the session at each time.
        // Save the pending data and restart the session.
        LOG.w("onTakeVideo", "calling restartBind.");
        mFullVideoPendingStub = stub;
        restartBind();
    }

    private void doTakeVideo(@NonNull final VideoResult.Stub stub) {
        if (!(mVideoRecorder instanceof Full2VideoRecorder)) {
            throw new IllegalStateException("doTakeVideo called, but video recorder " +
                    "is not a Full2VideoRecorder! " + mVideoRecorder);
        }
        Full2VideoRecorder recorder = (Full2VideoRecorder) mVideoRecorder;
        try {
            for(CameraValues cameraValues:mCameraValues) {
                createRepeatingRequestBuilder(cameraValues, CameraDevice.TEMPLATE_RECORD);
                addRepeatingRequestBuilderSurfaces(cameraValues, recorder.getInputSurface());
                applyRepeatingRequestBuilder(cameraValues, true, CameraException.REASON_DISCONNECTED);
            }
            mVideoRecorder.start(stub);
        } catch (CameraAccessException e) {
            onVideoResult(null, e);
            throw createCameraException(e);
        } catch (CameraException e) {
            onVideoResult(null, e);
            throw e;
        }
    }

    @EngineThread
    @Override
    protected void onTakeVideoSnapshot(@NonNull VideoResult.Stub stub,
                                       @NonNull AspectRatio outputRatio, int rotation) {
        if (!(mPreview instanceof GlCameraPreview)) {
            throw new IllegalStateException("Video snapshots are only supported with GL_SURFACE.");
        }
        GlCameraPreview glPreview = (GlCameraPreview) mPreview;
        Size outputSize = getUncroppedSnapshotSize(Reference.OUTPUT);
        if (outputSize == null) {
            throw new IllegalStateException("outputSize should not be null.");
        }
        Rect outputCrop = CropHelper.computeCrop(outputSize, outputRatio);
        outputSize = new Size(outputCrop.width(), outputCrop.height());
//        stub.size = outputSize; //mate10手机滤镜设置分辨率不对会崩溃，所以严格按照支持的尺寸设置
        stub.rotation = rotation;
//        stub.deviceRotation = getAngles().offset(Reference.VIEW, Reference.OUTPUT, Axis.ABSOLUTE);
        stub.deviceRotation = 0;
        stub.videoFrameRate = Math.round(mPreviewFrameRate);
        if (mOritation == Configuration.ORIENTATION_LANDSCAPE) {
            stub.size = stub.size.flip();
        }
        mVideoRecorder = new SnapshotVideoRecorder(this, glPreview, getOverlay());
        mVideoRecorder.start(stub);
    }

    /**
     * When video ends we must stop the recorder and remove the recorder surface from
     * camera outputs. This is done in onVideoResult. However, on some devices, order matters.
     * If we stop the recorder and AFTER send camera frames to it, the camera will try to fill
     * the recorder "abandoned" Surface and on some devices with a poor internal implementation
     * (HW_LEVEL_LEGACY) this crashes. So if the conditions are met, we restore here. Issue #549.
     */
    @Override
    public void onVideoRecordingEnd() {
        super.onVideoRecordingEnd();
        // SnapshotRecorder will invoke this on its own thread which is risky, but if it was a
        // snapshot, this function does nothing so it's safe.
        boolean needsIssue549Workaround = false;
        for (CameraValues cameraValues:mCameraValues) {
            needsIssue549Workaround = needsIssue549Workaround || ((mVideoRecorder instanceof Full2VideoRecorder) &&
                    (readCharacteristic(cameraValues.getCameraCharacteristics(), CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL, -1)
                            == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY));
        }
        if (needsIssue549Workaround) {
            LOG.w("Applying the Issue549 workaround.", Thread.currentThread());
            maybeRestorePreviewTemplateAfterVideo();
            LOG.w("Applied the Issue549 workaround. Sleeping...");
            try { Thread.sleep(600); } catch (InterruptedException ignore) {}
            LOG.w("Applied the Issue549 workaround. Slept!");
        }
    }

    @Override
    public void onVideoResult(@Nullable VideoResult.Stub result, @Nullable Exception exception) {
        super.onVideoResult(result, exception);
        // SnapshotRecorder will invoke this on its own thread, so let's post in our own thread
        // and check camera state before trying to restore the preview. Engine might have been
        // torn down in the engine thread while this was still being called.
        getOrchestrator().scheduleStateful("restore preview template", CameraState.BIND,
                new Runnable() {
            @Override
            public void run() {
                maybeRestorePreviewTemplateAfterVideo();
            }
        });
    }

    /**
     * Video recorders might change the camera template to {@link CameraDevice#TEMPLATE_RECORD}.
     * After the video is taken, we should restore the template preview, which also means that
     * we'll remove any extra surface target that was added by the video recorder.
     *
     * This method avoids doing this twice by checking the request tag, as set by
     * the {@link #createRepeatingRequestBuilder(CameraValues, int)} method.
     */
    @EngineThread
    private void maybeRestorePreviewTemplateAfterVideo() {
        for(CameraValues cameraValues:mCameraValues) {
            int template = (int) cameraValues.getRepeatingRequestBuilder().build().getTag();
            if (template != CameraDevice.TEMPLATE_PREVIEW) {
                try {
                    createRepeatingRequestBuilder(cameraValues, CameraDevice.TEMPLATE_PREVIEW);
                    addRepeatingRequestBuilderSurfaces(cameraValues);
                    applyRepeatingRequestBuilder(cameraValues, true, CameraException.REASON_DISCONNECTED);
                } catch (CameraAccessException e) {
                    throw createCameraException(e);
                }
            }
        }
    }



    @Override
    public void setAntishake(boolean antishakeOn) {
        super.setAntishake(antishakeOn);
        for (CameraValues cameraValues : mCameraValues) {
            cameraValues.setAntishake(antishakeOn);
        }
    }

    //endregion

    //region Parameters

    private void applyAllParameters(@NonNull CaptureRequest.Builder builder,
                                    @Nullable CaptureRequest.Builder oldBuilder) {
        LOG.i("applyAllParameters:", "called for tag", builder.build().getTag());
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        applyDefaultFocus(builder);
        applyFlash(builder, Flash.OFF);
        applyLocation(builder, null);
        applyWhiteBalance(builder, WhiteBalance.AUTO);
        applyHdr(builder, Hdr.OFF);
        applyZoom(builder, 0F);
        applyExposureCorrection(builder, 0F);
        applyPreviewFrameRate(builder, mPreviewFrameRate);

        if (antishakeOn && supportAntishake()) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        } else if (supportAntishake()) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }
        if (oldBuilder != null) {
            // We might be in a metering operation, or the old builder might have some special
            // metering parameters. Copy these special keys over to the new builder.
            // These are the keys changed by metering.Parameters, or by us in applyFocusForMetering.
            builder.set(CaptureRequest.CONTROL_AF_REGIONS,
                    oldBuilder.get(CaptureRequest.CONTROL_AF_REGIONS));
            builder.set(CaptureRequest.CONTROL_AE_REGIONS,
                    oldBuilder.get(CaptureRequest.CONTROL_AE_REGIONS));
            builder.set(CaptureRequest.CONTROL_AWB_REGIONS,
                    oldBuilder.get(CaptureRequest.CONTROL_AWB_REGIONS));
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    oldBuilder.get(CaptureRequest.CONTROL_AF_MODE));
            // Do NOT copy exposure or focus triggers!
        }
    }

    @SuppressWarnings("WeakerAccess")
    protected void applyDefaultFocus(@NonNull final CaptureRequest.Builder builder) {
        int[] modesArray = readCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                new int[]{});
        List<Integer> modes = new ArrayList<>();
        for (int mode : modesArray) { modes.add(mode); }
        if (getMode() == Mode.VIDEO &&
                modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            if (SensorController.getInstance(getCallback().getContext()).isFocusLocked()) {
//                builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
//                        CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
//                getOrchestrator().scheduleStatefulDelayed("AF_MODE_MACRO",
//                        CameraState.PREVIEW,
//                        200,
//                        new Runnable() {
//                            @Override
//                            public void run() {
//                                builder.set(CaptureRequest.CONTROL_AF_MODE,
//                                        CaptureRequest.CONTROL_AF_MODE_MACRO);
//                            }
//                        });

            } else {
                // 解决华为手机点击屏幕后，无法自动对焦问题
//                if (mLastRepeatingResult!=null && mLastRepeatingResult.get(CaptureResult.CONTROL_AF_STATE) == CONTROL_AF_STATE_ACTIVE_SCAN) {
//                    builder.set(CaptureRequest.CONTROL_AF_TRIGGER,
//                            CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
//                }
                    // 解决华为手机点击屏幕后，无法自动对焦问题
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            }
//            builder.set(CaptureRequest.CONTROL_AF_MODE,
//                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            return;
        }

        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            return;
        }

        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            return;
        }

        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0F);
            //noinspection UnnecessaryReturnStatement
            return;
        }
    }

    /**
     * All focus modes support the AF trigger, except OFF and EDOF.
     * However, unlike the preview, we'd prefer AUTO to any CONTINUOUS value.
     * An AUTO value means that focus is locked unless we run the focus trigger,
     * which is what metering does.
     *
     * @param builder builder
     */
    @SuppressWarnings("WeakerAccess")
    protected void applyFocusForMetering(@NonNull CaptureRequest.Builder builder) {
        int[] modesArray = readCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
                new int[]{});
        List<Integer> modes = new ArrayList<>();
        for (int mode : modesArray) { modes.add(mode); }
        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            return;
        }
        if (getMode() == Mode.VIDEO &&
                modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            return;
        }

        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            //noinspection UnnecessaryReturnStatement
            return;
        }
    }

    @Override
    public void setFlash(@NonNull final Flash flash) {
        final Flash old = mFlash;
        mFlash = flash;
        mFlashTask = getOrchestrator().scheduleStateful("flash (" + flash + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                for (CameraValues cameraValues : mCameraValues) {
                    cameraValues.setFlash(flash);
                }
            }
        });
    }

    /**
     * This sets the CONTROL_AE_MODE to either:
     * - {@link CaptureRequest#CONTROL_AE_MODE_ON}
     * - {@link CaptureRequest#CONTROL_AE_MODE_ON_AUTO_FLASH}
     * - {@link CaptureRequest#CONTROL_AE_MODE_ON_ALWAYS_FLASH}
     *
     * The API offers a high level control through {@link CaptureRequest#CONTROL_AE_MODE},
     * which is what the mapper looks at. It will trigger (if specified) flash only for
     * still captures which is exactly what we want.
     *
     * However, we set CONTROL_AE_MODE to ON/OFF (depending
     * on which is available) with both {@link Flash#OFF} and {@link Flash#TORCH}.
     *
     * When CONTROL_AE_MODE is ON or OFF, the low level control, called
     * {@link CaptureRequest#FLASH_MODE}, becomes effective, and that's where we can actually
     * distinguish between a turned off flash and a torch flash.
     */
    @SuppressWarnings("WeakerAccess")
    protected boolean applyFlash(@NonNull CaptureRequest.Builder builder,
                                 @NonNull Flash oldFlash) {
        if (mCameraOptions.supports(mFlash)) {
            int[] availableAeModesArray = readCharacteristic(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES, new int[]{});
            List<Integer> availableAeModes = new ArrayList<>();
            for (int mode : availableAeModesArray) { availableAeModes.add(mode); }

            List<Pair<Integer, Integer>> pairs = mMapper.mapFlash(mFlash);
            for (Pair<Integer, Integer> pair : pairs) {
                if (availableAeModes.contains(pair.first)) {
                    LOG.i("applyFlash: setting CONTROL_AE_MODE to", pair.first);
                    LOG.i("applyFlash: setting FLASH_MODE to", pair.second);
                    builder.set(CaptureRequest.CONTROL_AE_MODE, pair.first);
                    builder.set(CaptureRequest.FLASH_MODE, pair.second);
                    return true;
                }
            }
        }
        mFlash = oldFlash;
        return false;
    }

    @Override
    public void setLocation(@Nullable final Location location) {
        final Location old = mLocation;
        mLocation = location;
        mLocationTask = getOrchestrator().scheduleStateful("location",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                for (CameraValues cameraValues : mCameraValues) {
                    cameraValues.setLocation(location);
                }
            }
        });
    }

    @SuppressWarnings("WeakerAccess")
    protected boolean applyLocation(@NonNull CaptureRequest.Builder builder,
                                    @SuppressWarnings("unused") @Nullable Location oldLocation) {
        if (mLocation != null) {
            builder.set(CaptureRequest.JPEG_GPS_LOCATION, mLocation);
        }
        return true;
    }

    @Override
    public void setWhiteBalance(@NonNull final WhiteBalance whiteBalance) {
        final WhiteBalance old = mWhiteBalance;
        mWhiteBalance = whiteBalance;
        mWhiteBalanceTask = getOrchestrator().scheduleStateful(
                "white balance (" + whiteBalance + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                for (CameraValues cameraValues : mCameraValues) {
                    cameraValues.setWhiteBalance(whiteBalance);
                }
            }
        });
    }

    @SuppressWarnings("WeakerAccess")
    protected boolean applyWhiteBalance(@NonNull CaptureRequest.Builder builder,
                                        @NonNull WhiteBalance oldWhiteBalance) {
        if (mCameraOptions.supports(mWhiteBalance)) {
            int whiteBalance = mMapper.mapWhiteBalance(mWhiteBalance);
            builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalance);
            return true;
        }
        mWhiteBalance = oldWhiteBalance;
        return false;
    }

    @Override
    public void setHdr(@NonNull final Hdr hdr) {
        final Hdr old = mHdr;
        mHdr = hdr;
        mHdrTask = getOrchestrator().scheduleStateful("hdr (" + hdr + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                for (CameraValues cameraValues : mCameraValues) {
                    cameraValues.setHdr(hdr);
                }
            }
        });
    }

    @SuppressWarnings("WeakerAccess")
    protected boolean applyHdr(@NonNull CaptureRequest.Builder builder, @NonNull Hdr oldHdr) {
        if (mCameraOptions.supports(mHdr)) {
            int hdr = mMapper.mapHdr(mHdr);
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, hdr);
            return true;
        }
        mHdr = oldHdr;
        return false;
    }

    @Override
    public void setZoom(final float zoom, final @Nullable PointF[] points, final boolean notify) {
        final CameraValues cameraValues = getCurrentCamera();
        if (cameraValues != null) {
            mZoomTask = getOrchestrator().scheduleStateful(
                    "zoom (" + zoom + ")",
                    CameraState.ENGINE,
                    new Runnable() {
                        @Override
                        public void run() {
                            cameraValues.setZoom(zoom, points, notify);
                        }
                    });
        }
    }

    @Override
    public float getZoomValue() {
        CameraValues cameraValues = getCurrentCamera();
        if (cameraValues != null) {
            return cameraValues.getZoomValue();
        }
        return 0.0f;
    }

    @SuppressWarnings("WeakerAccess")
    protected boolean applyZoom(@NonNull CaptureRequest.Builder builder, float oldZoom) {
        if (mCameraOptions.isZoomSupported()) {
            float maxZoom = readCharacteristic(
                    CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 1F);
            // converting 0.0f-1.0f zoom scale to the actual camera digital zoom scale
            // (which will be for example, 1.0-10.0)
            float calculatedZoom = (mZoomValue * (maxZoom - 1.0f)) + 1.0f;
            Rect newRect = getZoomRect(calculatedZoom, maxZoom);
            builder.set(CaptureRequest.SCALER_CROP_REGION, newRect);
            return true;
        }
        mZoomValue = oldZoom;
        return false;
    }

    @NonNull
    private Rect getZoomRect(float zoomLevel, float maxDigitalZoom) {
        Rect activeRect = readCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE,
                new Rect());
        int minW = (int) (activeRect.width() / maxDigitalZoom);
        int minH = (int) (activeRect.height() / maxDigitalZoom);
        int difW = activeRect.width() - minW;
        int difH = activeRect.height() - minH;

        // When zoom is 1, we want to return new Rect(0, 0, width, height).
        // When zoom is maxZoom, we want to return a centered rect with minW and minH
        int cropW = (int) (difW * (zoomLevel - 1) / (maxDigitalZoom - 1) / 2F);
        int cropH = (int) (difH * (zoomLevel - 1) / (maxDigitalZoom - 1) / 2F);
        return new Rect(cropW, cropH, activeRect.width() - cropW,
                activeRect.height() - cropH);
    }

    @Override
    public void setExposureCorrection(final float EVvalue,
                                      @NonNull final float[] bounds,
                                      @Nullable final PointF[] points,
                                      final boolean notify) {
        CameraValues cameraValues = getCurrentCamera();
        if (cameraValues != null)
            cameraValues.setExposureCorrection(EVvalue, bounds, points, notify);
    }

    @Override
    public void setExposureCorrection(float EVvalue, @NonNull float[] bounds,
                                            @Nullable PointF[] points, boolean notify, int index) {
        if (index >= mCameraValues.size()) return;
        CameraValues cameraValues = mCameraValues.get(index);
        if (cameraValues != null) cameraValues.setExposureCorrection(EVvalue, bounds, points, notify);
    }

    /**
     * 获取曝光值
     * @param index 0为主镜头(上下模式为上/左半部分画面，画中画为大画面)， 1为副镜头
     * @return 曝光值
     */
    @Override
    public float getExposureCorrectionValue(int index) {
        if (index >= mCameraValues.size()) return 0f;
        CameraValues cameraValues = mCameraValues.get(index);
        return cameraValues.getExposureCorrectionValue();
    }

    @Override
    public final float getExposureCorrectionValue() {
        if (getCurrentCamera() != null) return getCurrentCamera().getExposureCorrectionValue();
        return 0f;
    }

    /**
     * 判断是否自动对焦
     * @param index  0为主镜头(上下模式为上/左半部分画面，画中画为大画面)， 1为副镜头
     * @return 是否自动对焦
     */
    @Override
    public boolean isAutoExposure(int index) {
        if (dual() && !mPreview.getFrontIsFirst()) { // 双摄并且前置不为第一路，需要颠倒一下映射
            index = 1 - index;
        }
        if (index >= mCameraValues.size()) return true;
        CameraValues cameraValues = mCameraValues.get(index);
        return cameraValues.isAutoExposure();
    }

    @Override
    public boolean isAutoExposure() {
        if (getCurrentCamera() != null) return getCurrentCamera().isAutoExposure();
        return false;
    }

    @SuppressWarnings("WeakerAccess")
    protected boolean applyExposureCorrection(@NonNull CaptureRequest.Builder builder,
                                              float oldEVvalue) {
        if (mCameraOptions.isExposureCorrectionSupported()) {
            Rational exposureCorrectionStep = readCharacteristic(
                    CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP,
                    new Rational(1, 1));
            int exposureCorrectionSteps = Math.round(mExposureCorrectionValue
                    * exposureCorrectionStep.floatValue());
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCorrectionSteps);
            return true;
        }
        mExposureCorrectionValue = oldEVvalue;
        return false;
    }

    @Override
    public void setPlaySounds(boolean playSounds) {
        mPlaySounds = playSounds;
        mPlaySoundsTask = Tasks.forResult(null);
    }

    @Nullable
    @Override
    public CameraOptions getCamera2Options() {
        if (mCameraValues.size() > 1) {
            return mCameraValues.get(1-mCurrentCamera).getCameraOptions();
        }
        return null;
    }

    @Override
    public void selectOpenedCamera(PointF legacyPoint) {
        if (mCameraValues.size()>0) {
            mCurrentCamera = -1;

            for (int index = mCameraValues.size()-1; index>=0; index--) {
                RectF layoutRect = mCameraValues.get(index).getLayoutRect();
                if (layoutRect.contains(legacyPoint.x, legacyPoint.y)) {
                    if (mCurrentCamera == -1)
                        mCurrentCamera = index;
                    else {
                        if (mCameraValues.get(mCurrentCamera).getLayoutRect().contains(layoutRect))
                            mCurrentCamera = index;
                    }
                }
            }
            if (mCurrentCamera == -1)
                mCurrentCamera = 0;
            mCameraOptions = mCameraValues.get(mCurrentCamera).getCameraOptions();
        }
    }

    /**
     * 获取当前的位置，1为双摄模式第二路，0为非双摄或双摄模式第一路
     * @return
     */
    @Override
    public int getCurrentCameraIndex() {
        if (mCameraValues.size() > 1) { // 双摄
            if ((mPreview.getFrontIsFirst() && mCurrentCamera == 0) || (!mPreview.getFrontIsFirst() && mCurrentCamera == 1)) {
                return 0;
            } else {
                return 1;
            }
        }
        return 0;
    }

    @Override
    public int getCurrentCameraIndex(PointF legacyPoint) {
        int currentCamera = -1;
        if (mCameraValues.size()>0) {
            for (int index = mCameraValues.size()-1; index>=0; index--) {
                RectF layoutRect = mCameraValues.get(index).getLayoutRect();
                if (layoutRect.contains(legacyPoint.x, legacyPoint.y)) {
                    if (currentCamera == -1)
                        currentCamera = index;
                    else {
                        if (mCameraValues.get(currentCamera).getLayoutRect().contains(layoutRect))
                            currentCamera = index;
                    }
                }
            }
            if (currentCamera == -1)
                currentCamera = 0;
        }
        if (mCameraValues.size() > 1) { // 双摄
            if ((mPreview.getFrontIsFirst() && currentCamera == 0) || (!mPreview.getFrontIsFirst() && currentCamera == 1)) {
                return 0;
            } else {
                return 1;
            }
        }
        return 0;
    }

    @Override
    public boolean changeFaceDetection(boolean usingFaceDetection) {
        if (mCameraValues.size() > 0 && supportFaceDetection() == FACE_DETECT_MODE.support)
            return mCameraValues.get(0).setFaceDetection(usingFaceDetection);
        return false;
    }

    @Override
    public void setFaceDetectionListener(FaceDetectionListener listener) {
        for(CameraValues cameraValues:mCameraValues) {
            cameraValues.addFaceDetectionListener(listener);
        }
    }

    @Override
    public FACE_DETECT_MODE supportFaceDetection() {
        if (mCameraValues.size()>0)
            return mCameraValues.get(0).getSupportsFaceDetection()!=0?FACE_DETECT_MODE.support:FACE_DETECT_MODE.unSupport;
        return FACE_DETECT_MODE.unKnown;
    }

    @Override
    public int getCameraOrientation() {
        if (mCameraValues.size()>0)
            return mCameraValues.get(0).getCharacteristicsSensorOrientation();
        return 0;
    }

    @Override
    public void setPreviewFrameRate(final float previewFrameRate) {
        final float oldPreviewFrameRate = mPreviewFrameRate;
        mPreviewFrameRate = (int) previewFrameRate;
        mPreviewFrameRateTask = getOrchestrator().scheduleStateful(
                "preview fps (" + previewFrameRate + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                for (CameraValues cameraValues : mCameraValues)
                    cameraValues.setPreviewFrameRate(previewFrameRate);
            }
        });
    }

    @SuppressWarnings("WeakerAccess")
    protected boolean applyPreviewFrameRate(@NonNull CaptureRequest.Builder builder,
                                            int oldPreviewFrameRate) {
        //noinspection unchecked
        Range<Integer>[] fallback = new Range[]{};
        Range<Integer>[] fpsRanges = readCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES,
                fallback);
        for (Range<Integer> fpsRange : fpsRanges) {
            LOG.i("applyPreviewFrameRate: fpsRange = " + fpsRange);
        }
        if (mPreviewFrameRate == 0F) {
            // 0F is a special value. Fallback to a reasonable default.
            for (Range<Integer> fpsRange : fpsRanges) {
                if (fpsRange.contains(30)) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                    return true;
                }
            }
        } else {
            Range<Integer> fpsRange = new Range<>(mPreviewFrameRate, mPreviewFrameRate);
            LOG.i("applyPreviewFrameRate: mPreviewFrameRate fpsRange = " + fpsRange);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
            return true;
        }
        mPreviewFrameRate = oldPreviewFrameRate;
        return false;
    }

    @Override
    public void setPictureFormat(final @NonNull PictureFormat pictureFormat) {
        if (pictureFormat != mPictureFormat) {
            mPictureFormat = pictureFormat;
            getOrchestrator().scheduleStateful("picture format (" + pictureFormat + ")",
                    CameraState.ENGINE,
                    new Runnable() {
                @Override
                public void run() {
                    restart();
                }
            });
        }
    }

    //endregion

    //region Frame Processing

    @NonNull
    @Override
    protected FrameManager instantiateFrameManager(int poolSize) {
        return new ImageFrameManager(poolSize);
    }

    @EngineThread
    @Override
    public void onImageAvailable(ImageReader reader) {
        LOG.v("onImageAvailable:", "trying to acquire Image.");
        Image image = null;
        try {
            image = reader.acquireLatestImage();
        } catch (Exception ignore) { }
        if (image == null) {
            LOG.w("onImageAvailable:", "failed to acquire Image!");
        } else if (getState() == CameraState.PREVIEW && !isChangingState()) {
            // After preview, the frame manager is correctly set up
            //noinspection unchecked
            Frame frame = getFrameManager().getFrame(image,
                    System.currentTimeMillis());
            if (frame != null) {
                LOG.v("onImageAvailable:", "Image acquired, dispatching.");
                getCallback().dispatchFrame(frame);
            } else {
                LOG.i("onImageAvailable:", "Image acquired, but no free frames. DROPPING.");
            }
        } else {
            LOG.i("onImageAvailable:", "Image acquired in wrong state. Closing it now.");
            image.close();
        }
    }

    @Override
    public void setHasFrameProcessors(final boolean hasFrameProcessors) {
        // Frame processing is set up partially when binding and partially when starting
        // the preview. If the value is changed between the two, the preview step can crash.
        getOrchestrator().schedule("has frame processors (" + hasFrameProcessors + ")",
                true, new Runnable() {
            @Override
            public void run() {
                if (getState().isAtLeast(CameraState.BIND) && isChangingState()) {
                    // Extremely rare case in which this was called in between startBind and
                    // startPreview. This can cause issues. Try later.
                    setHasFrameProcessors(hasFrameProcessors);
                    return;
                }
                // Apply and restart.
                mHasFrameProcessors = hasFrameProcessors;
                if (getState().isAtLeast(CameraState.BIND)) {
                    restartBind();
                }
            }
        });
    }

    @Override
    public void setFrameProcessingFormat(final int format) {
        // This is called during initialization. Set our default first.
        if (mFrameProcessingFormat == 0) mFrameProcessingFormat = FRAME_PROCESSING_FORMAT;
        // Frame processing format is used both when binding and when starting the preview.
        // If the value is changed between the two, the preview step can crash.
        getOrchestrator().schedule("frame processing format (" + format + ")",
                true, new Runnable() {
            @Override
            public void run() {
                if (getState().isAtLeast(CameraState.BIND) && isChangingState()) {
                    // Extremely rare case in which this was called in between startBind and
                    // startPreview. This can cause issues. Try later.
                    setFrameProcessingFormat(format);
                    return;
                }
                mFrameProcessingFormat = format > 0 ? format : FRAME_PROCESSING_FORMAT;
                if (getState().isAtLeast(CameraState.BIND)) {
                    restartBind();
                }
            }
        });
    }

    @Override
    public boolean dual() {
        return mCameraValues != null && mCameraValues.size() == 2;
    }

    //endregion

    //region 3A Metering

    @Override
    public void startAutoFocus(@Nullable final Gesture gesture,
                               @NonNull final MeteringRegions regions,
                               @NonNull final PointF legacyPoint) {
        // This will only work when we have a preview, since it launches the preview
        // in the end. Even without this it would need the bind state at least,
        // since we need the preview size.
        CameraValues cameraValues = getCurrentCamera();
        if (cameraValues != null)
            cameraValues.startAutoFocus(gesture, regions, legacyPoint);
    }

    @Override
    public void cancelAutoFocus() {

    }

    @Override
    public boolean supportsAutoFocus() {
        return false;
    }

    private static MeteringRectangle convertRectToMeteringRectangle(RectF rect, Rect activeArray, @IntRange(from=0,to=1000) int weight) {
        // rect is of type [0, 0] - [1, 1] and we must convert it to [0, 0] - [sensor width-1, sensor height-1].
        int left = (int)(rect.left * (activeArray.width()-1));
        int right = (int)(rect.right * (activeArray.width()-1));
        int top = (int)(rect.top * (activeArray.height()-1));
        int bottom = (int)(rect.bottom * (activeArray.height()-1));
        left = Math.min(left, activeArray.width()-1);
        right = Math.min(right, activeArray.width()-1);
        top = Math.min(top, activeArray.height()-1);
        bottom = Math.min(bottom, activeArray.height()-1);
        return new MeteringRectangle(new Rect(left, top, right, bottom), weight);
    }

    @NonNull
    private MeterAction createMeterAction(@Nullable MeteringRegions regions) {
        // Before creating any new meter action, abort the old one.
        if (mMeterAction != null) mMeterAction.abort(this);
        // The meter will check the current configuration to see if AF/AE/AWB should run.
        // - AE should be on CONTROL_AE_MODE_ON*    (this depends on setFlash())
        // - AWB should be on CONTROL_AWB_MODE_AUTO (this depends on setWhiteBalance())
        // - AF should be on CONTROL_AF_MODE_AUTO or others
        // The last one is under our control because the library has no focus API.
        // So let's set a good af mode here. This operation is reverted during onMeteringReset().
        CameraValues cameraValues = getCurrentCamera();
        if (cameraValues != null)
            applyFocusForMetering(cameraValues.getRepeatingRequestBuilder());
        mMeterAction = new MeterAction(Camera2Engine.this, regions, regions == null, mMeteringAreas);
        return mMeterAction;
    }

    @EngineThread
    public void unlockAndResetMetering() {
        CameraValues cameraValues = getCurrentCamera();
        if (cameraValues != null)
            cameraValues.cancelAutoFocus();

//        final Integer afState = mLastRepeatingResult.get(CaptureResult.CONTROL_AF_STATE);
//        Integer afMode = mLastRepeatingResult.get(CaptureResult.CONTROL_AF_MODE);
//        Integer mode = mLastRepeatingResult.get(CaptureResult.CONTROL_MODE);
//        Integer aeMode = mLastRepeatingResult.get(CaptureResult.CONTROL_AE_MODE);
//        Log.d("unlockAndResetMetering", "afState:" + afState + ";afMode:" + afMode + ";mode:" + mode + ";aeMode:" + aeMode);
        // Needs the PREVIEW state!
        Actions.sequence(
                new BaseAction() {
                    @Override
                    protected void onStart(@NonNull ActionHolder holder) {
                        super.onStart(holder);
                        if (!SensorController.getInstance(getCallback().getContext()).isFocusLocked()) {
//                            Log.d("onExposureCorrection", "unlockAndResetMetering");
                            applyDefaultFocus(holder.getBuilder(this));
//                            // 解决部分低版本（Android6/7/8）手机点击屏幕后，无法恢复自动对焦问题（华为mate10 Android8仍存在该问题）
//                            // CONTROL_AF_STATE_ACTIVE_SCAN判断一次点击后，CONTROL_AF_STATE_PASSIVE_SCAN判断锁屏后
//                            if (afState == CONTROL_AF_STATE_ACTIVE_SCAN || afState == CONTROL_AF_STATE_PASSIVE_SCAN) {
//                                holder.getBuilder(this).set(CaptureRequest.CONTROL_AF_TRIGGER,
//                                        CONTROL_AF_TRIGGER_CANCEL);
//                            }
                            if (holder.getBuilder(this) != null) {
                                holder.getBuilder(this)
                                        .set(CaptureRequest.CONTROL_AE_LOCK, false);
                                holder.getBuilder(this)
                                        .set(CaptureRequest.CONTROL_AWB_LOCK, false);
                            }
                            holder.applyBuilder(this);
                            setState(STATE_COMPLETED);
                        }
                        // TODO should wait results?
                    }
                },
                new MeterResetAction()
        ).start(Camera2Engine.this);
    }

    /**
     * 锁定曝光
     */
    @EngineThread
    public void lockExposure() {
        // Needs the PREVIEW state!
        Actions.sequence(
                new BaseAction() {
                    @Override
                    protected void onStart(@NonNull ActionHolder holder) {
                        super.onStart(holder);
                        if (holder.getBuilder(this) != null) {
                            CameraValues cameraValues = getCurrentCamera();
                            if (cameraValues != null) cameraValues.setAutoExposure(false); // 当前点击的相机设置为自动曝光关
                            holder.getBuilder(this)
                                    .set(CaptureRequest.CONTROL_AE_LOCK, true);
                        }
                    }
                }
        ).start(Camera2Engine.this);
    }

    /**
     * 解锁曝光
     */
    @EngineThread
    public void unlockExposure() {
        // Needs the PREVIEW state!
        Actions.sequence(
                new BaseAction() {
                    @Override
                    protected void onStart(@NonNull ActionHolder holder) {
                        super.onStart(holder);
                        if (holder.getBuilder(this) != null) {
                            CameraValues cameraValues = getCurrentCamera();
                            if (cameraValues != null) {
                                cameraValues.setAutoExposure(true); // 当前点击的相机设置为自动曝光开
                            }
                            holder.getBuilder(this)
                                    .set(CaptureRequest.CONTROL_AE_LOCK, false);
                        }
                    }
                }
        ).start(Camera2Engine.this);
    }

    /**
     * 双摄模式下所有都解锁
     */
    public void unlockAll() {
        // Needs the PREVIEW state!
        Actions.sequence(
                new BaseAction() {
                    @Override
                    protected void onStart(@NonNull ActionHolder holder) {
                        super.onStart(holder);
                        if (holder.getBuilder(this) != null) {
                            for (CameraValues cameraValue : mCameraValues) {
                                cameraValue.setAutoExposure(true);
                            }
                            holder.getBuilder(this).set(CaptureRequest.CONTROL_AE_LOCK, false); // 解锁曝光
                            holder.getBuilder(this).set(CaptureRequest.CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_CANCEL); // 解锁焦点
                        }
                    }
                }
        ).start(Camera2Engine.this);
    }

    /**
     * 锁定焦点
     */
    @EngineThread
    public void lockFocus() {
        Actions.sequence(
                new BaseAction() {
                    @Override
                    protected void onStart(@NonNull final ActionHolder holder) {
                        super.onStart(holder);
                        if (holder.getLastResult(this) != null) {
                            Integer afState = holder.getLastResult(this).get(CaptureResult.CONTROL_AF_STATE);
                            switch (afState) {
                                // 0,6
                                case CONTROL_AF_STATE_INACTIVE:
                                case CONTROL_AF_STATE_PASSIVE_UNFOCUSED:
                                    holder.getBuilder(this).set(CaptureRequest.CONTROL_AF_TRIGGER,
                                            CaptureRequest.CONTROL_AF_TRIGGER_START);
                                    holder.getBuilder(this).set(CaptureRequest.CONTROL_AF_MODE,
                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                    break;
                                // 2,3,4,5
                                case CONTROL_AF_STATE_PASSIVE_FOCUSED:
                                case CONTROL_AF_STATE_ACTIVE_SCAN:
                                case CONTROL_AF_STATE_FOCUSED_LOCKED:
                                case CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                                    holder.getBuilder(this).set(CaptureRequest.CONTROL_AF_TRIGGER,
                                            CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                                    break;
                            }
                            applyRepeatingRequestBuilder();
                        }
                    }
                }
        ).start(Camera2Engine.this);
    }

    /**
     * 解锁焦点
     */
    @EngineThread
    public void unlockFocus() {
        Actions.sequence(
                new BaseAction() {
                    @Override
                    protected void onStart(@NonNull ActionHolder holder) {
                        super.onStart(holder);
                        if (holder.getBuilder(this) != null) {
                            holder.getBuilder(this).set(CaptureRequest.CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_CANCEL);
                        }
                    }
                }
        ).start(Camera2Engine.this);
    }

    //endregion

    //region Actions

    @Override
    public void addAction(final @NonNull Action action) {
        if (!mActions.contains(action)) {
            mActions.add(action);
        }
    }

    @Override
    public void removeAction(final @NonNull Action action) {
        mActions.remove(action);
    }

    @NonNull
    @Override
    public CameraCharacteristics getCharacteristics(@NonNull Action action) {
        CameraValues cameraValues = getCurrentCamera();
        if (cameraValues != null)
            return cameraValues.getCameraCharacteristics();
        else
            return null;
    }

    @Nullable
    @Override
    public TotalCaptureResult getLastResult(@NonNull Action action) {
        CameraValues cameraValues = getCurrentCamera();
        if (cameraValues != null)
            return cameraValues.getLastRepeatingResult();
        else
            return null;
    }

    @NonNull
    @Override
    public CaptureRequest.Builder getBuilder(@NonNull Action action) {
        CameraValues cameraValues = getCurrentCamera();
        if (cameraValues != null)
            return /*mRepeatingRequestBuilder*/cameraValues.getRepeatingRequestBuilder();
        else
            return null;
    }

    @EngineThread
    @Override
    public void applyBuilder(@NonNull Action source) {
        // NOTE: Should never be called on a non-engine thread!
        // Non-engine threads are not protected by the uncaught exception handler
        // and can make the process crash.
        applyRepeatingRequestBuilder();
    }

    @Override
    public void applyBuilder(@NonNull Action source, @NonNull CaptureRequest.Builder builder)
            throws CameraAccessException {
        // Risky - would be better to ensure that thread is the engine one.
        if (getState() == CameraState.PREVIEW && !isChangingState()) {
            CameraValues cameraValues = getCurrentCamera();
            if (cameraValues != null)
                cameraValues.getSession().capture(builder.build(), cameraValues.getRepeatingRequestCallback(), null);
        }
    }

    private CameraValues getCurrentCamera() {
        if (mCameraValues.size() == 0)
            return null;

        if (mCurrentCamera >= mCameraValues.size())
            mCurrentCamera = 0;
        return mCameraValues.get(mCurrentCamera);
    }


    //endregion
}