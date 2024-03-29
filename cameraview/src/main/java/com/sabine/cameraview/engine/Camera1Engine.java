package com.sabine.cameraview.engine;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Location;
import android.os.Build;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.sabine.cameraview.CameraException;
import com.sabine.cameraview.CameraOptions;
import com.sabine.cameraview.PictureResult;
import com.sabine.cameraview.VideoResult;
import com.sabine.cameraview.controls.Facing;
import com.sabine.cameraview.controls.Flash;
import com.sabine.cameraview.controls.Hdr;
import com.sabine.cameraview.controls.Mode;
import com.sabine.cameraview.controls.PictureFormat;
import com.sabine.cameraview.controls.WhiteBalance;
import com.sabine.cameraview.engine.mappers.Camera1Mapper;
import com.sabine.cameraview.engine.metering.Camera1MeteringTransform;
import com.sabine.cameraview.engine.offset.Axis;
import com.sabine.cameraview.engine.offset.Reference;
import com.sabine.cameraview.engine.options.Camera1Options;
import com.sabine.cameraview.engine.orchestrator.CameraState;
import com.sabine.cameraview.frame.ByteBufferFrameManager;
import com.sabine.cameraview.frame.Frame;
import com.sabine.cameraview.frame.FrameManager;
import com.sabine.cameraview.gesture.Gesture;
import com.sabine.cameraview.internal.CropHelper;
import com.sabine.cameraview.metering.MeteringRegions;
import com.sabine.cameraview.metering.MeteringTransform;
import com.sabine.cameraview.picture.Full1PictureRecorder;
import com.sabine.cameraview.picture.Snapshot1PictureRecorder;
import com.sabine.cameraview.picture.SnapshotGlPictureRecorder;
import com.sabine.cameraview.preview.GlCameraPreview;
import com.sabine.cameraview.preview.RendererCameraPreview;
import com.sabine.cameraview.size.AspectRatio;
import com.sabine.cameraview.size.Size;
import com.sabine.cameraview.video.Full1VideoRecorder;
import com.sabine.cameraview.video.SnapshotVideoRecorder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;


public class Camera1Engine extends CameraBaseEngine implements
        Camera.PreviewCallback,
        Camera.ErrorCallback,
        ByteBufferFrameManager.BufferCallback {
    private static final String JOB_FOCUS_RESET = "focus reset";
    private static final String JOB_FOCUS_END = "focus end";

    private static final int PREVIEW_FORMAT = ImageFormat.NV21;
    @VisibleForTesting static final int AUTOFOCUS_END_DELAY_MILLIS = 2500;

    private final Camera1Mapper mMapper = Camera1Mapper.get();
    private Camera mCamera;
    @VisibleForTesting int mCameraId;

    public Camera1Engine(@NonNull Callback callback) {
        super(callback);
    }

    //region Utilities

    @Override
    public void onError(int error, Camera camera) {
        String message = LOG.e("Internal Camera1 error.", error);
        Exception runtime = new RuntimeException(message);
        int reason;
        switch (error) {
            case Camera.CAMERA_ERROR_SERVER_DIED:
            case Camera.CAMERA_ERROR_EVICTED:
                reason = CameraException.REASON_DISCONNECTED; break;
            case Camera.CAMERA_ERROR_UNKNOWN: // Pass DISCONNECTED which is considered unrecoverable
                reason = CameraException.REASON_DISCONNECTED; break;
            default: reason = CameraException.REASON_UNKNOWN;
        }
        throw new CameraException(runtime, reason);
    }

    //endregion

    //region Protected APIs

    @EngineThread
    @NonNull
    @Override
    protected List<Size> getPreviewStreamAvailableSizes() {
        List<Camera.Size> sizes = mCamera.getParameters().getSupportedPreviewSizes();
        List<Size> result = new ArrayList<>(sizes.size());
        for (Camera.Size size : sizes) {
            Size add = new Size(size.width, size.height);
            if (!result.contains(add)) result.add(add);
        }
        LOG.i("getPreviewStreamAvailableSizes:", result);
        return result;
    }

    @EngineThread
    @NonNull
    @Override
    protected List<Size> getFrameProcessingAvailableSizes() {
        // We don't choose the frame processing size.
        // It comes from the preview stream.
        return Collections.singletonList(mPreviewStreamSize);
    }

    @EngineThread
    @Override
    protected void onPreviewStreamSizeChanged() {
        restartPreview();
    }

    @EngineThread
    @Override
    protected boolean collectCameraInfo(@NonNull Facing... facings) {
        int internalFacing = mMapper.mapFacing(facings[0]);
        LOG.i("collectCameraInfo",
                "Facing:", facings[0],
                "Internal:", internalFacing,
                "Cameras:", Camera.getNumberOfCameras());
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == internalFacing) {
                getAngles().setSensorOffset(facings[0], cameraInfo.orientation);
                mCameraId = i;
                return true;
            }
        }
        return false;
    }

    //endregion

    //region Start

    @NonNull
    @EngineThread
    @Override
    protected Task<CameraOptions> onStartEngine() {
        try {
            mCamera = Camera.open(mCameraId);
        } catch (Exception e) {
            LOG.e("onStartEngine:", "Failed to connect. Maybe in use by another app?");
            throw new CameraException(e, CameraException.REASON_FAILED_TO_CONNECT);
        }
        mCamera.setErrorCallback(this);

        // Set parameters that might have been set before the camera was opened.
        LOG.i("onStartEngine:", "Applying default parameters.");
        Camera.Parameters params = mCamera.getParameters();
        mCameraOptions = new Camera1Options(params, mCameraId,
                getAngles().flip(Reference.SENSOR, Reference.VIEW));
        applyAllParameters(params);
        mCamera.setParameters(params);
        mCamera.setDisplayOrientation(getAngles().offset(Reference.SENSOR, Reference.VIEW,
                Axis.ABSOLUTE)); // <- not allowed during preview
        LOG.i("onStartEngine:", "Ended");
        return Tasks.forResult(mCameraOptions);
    }

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStartBind() {
        LOG.i("onStartBind:", "Started");
        try {
            if (mPreview.getOutputClass() == SurfaceHolder.class) {
                mCamera.setPreviewDisplay((SurfaceHolder) mPreview.getOutput(0));
            } else if (mPreview.getOutputClass() == SurfaceTexture.class) {
                mCamera.setPreviewTexture((SurfaceTexture) mPreview.getOutput(0));
            } else {
                throw new RuntimeException("Unknown CameraPreview output class.");
            }
        } catch (IOException e) {
            LOG.e("onStartBind:", "Failed to bind.", e);
            throw new CameraException(e, CameraException.REASON_FAILED_TO_START_PREVIEW);
        }

        mCaptureSize = computeCaptureSize();
        mPreviewStreamSize = computePreviewStreamSize();
        return Tasks.forResult(null);
    }

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStartPreview() {
        LOG.i("onStartPreview", "Dispatching onCameraPreviewStreamSizeChanged.");
        getCallback().onCameraPreviewStreamSizeChanged();

        Size previewSize = getPreviewStreamSize(Reference.VIEW);
        if (previewSize == null) {
            throw new IllegalStateException("previewStreamSize should not be null at this point.");
        }
        mPreview.setStreamSize(previewSize.getWidth(), previewSize.getHeight());
        mPreview.setDrawRotation(0);

        Camera.Parameters params = mCamera.getParameters();
        // NV21 should be the default, but let's make sure, since YuvImage will only support this
        // and a few others
        params.setPreviewFormat(ImageFormat.NV21);
        // setPreviewSize is not allowed during preview
        params.setPreviewSize(mPreviewStreamSize.getWidth(), mPreviewStreamSize.getHeight());
        if (getMode() == Mode.PICTURE) {
            // setPictureSize is allowed during preview
            params.setPictureSize(mCaptureSize.getWidth(), mCaptureSize.getHeight());
        } else {
            // mCaptureSize in this case is a video size. The available video sizes are not
            // necessarily a subset of the picture sizes, so we can't use the mCaptureSize value:
            // it might crash. However, the setPictureSize() passed here is useless : we don't allow
            // HQ pictures in video mode.
            // While this might be lifted in the future, for now, just use a picture capture size.
            Size pictureSize = computeCaptureSize(Mode.PICTURE);
            params.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
        }
        mCamera.setParameters(params);

        mCamera.setPreviewCallbackWithBuffer(null); // Release anything left
        mCamera.setPreviewCallbackWithBuffer(this); // Add ourselves
        getFrameManager().setUp(PREVIEW_FORMAT, mPreviewStreamSize, getAngles());

        LOG.i("onStartPreview", "Starting preview with startPreview().");
        try {
            mCamera.startPreview();
        } catch (Exception e) {
            LOG.e("onStartPreview", "Failed to start preview.", e);
            throw new CameraException(e, CameraException.REASON_FAILED_TO_START_PREVIEW);
        }
        LOG.i("onStartPreview", "Started preview.");
        return Tasks.forResult(null);
    }

    //endregion

    //region Stop

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStopPreview() {
        LOG.i("onStopPreview:", "Started.");
        if (mVideoRecorder != null) {
            mVideoRecorder.stop(true);
            mVideoRecorder = null;
        }
        mPictureRecorder = null;
        getFrameManager().release();
        LOG.i("onStopPreview:", "Releasing preview buffers.");
        mCamera.setPreviewCallbackWithBuffer(null); // Release anything left
        try {
            LOG.i("onStopPreview:", "Stopping preview.");
            mCamera.stopPreview();
            LOG.i("onStopPreview:", "Stopped preview.");
        } catch (Exception e) {
            LOG.e("stopPreview", "Could not stop preview", e);
        }
        return Tasks.forResult(null);
    }

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStopBind() {
        mPreviewStreamSize = null;
        mCaptureSize = null;
        try {
            if (mPreview.getOutputClass() == SurfaceHolder.class) {
                mCamera.setPreviewDisplay(null);
            } else if (mPreview.getOutputClass() == SurfaceTexture.class) {
                mCamera.setPreviewTexture(null);
            } else {
                throw new RuntimeException("Unknown CameraPreview output class.");
            }
        } catch (IOException e) {
            // NOTE: when this happens, the next onStopEngine() call hangs on camera.release(),
            // Not sure for how long. This causes the destroy() flow to fail the timeout.
            LOG.e("onStopBind", "Could not release surface", e);
        }
        return Tasks.forResult(null);
    }

    @EngineThread
    @NonNull
    @Override
    protected Task<Void> onStopEngine() {
        LOG.i("onStopEngine:", "About to clean up.");
        getOrchestrator().remove(JOB_FOCUS_RESET);
        getOrchestrator().remove(JOB_FOCUS_END);
        if (mCamera != null) {
            try {
                LOG.i("onStopEngine:", "Clean up.", "Releasing camera.");
                // Just like Camera2Engine, this call can hang (at least on emulators) and if
                // we don't find a way around the lock, it leaves the camera in a bad state.
                // This is anticipated by the exception in onStopBind() (see above).
                //
                // 12:29:32.163 E Camera3-Device: Camera 0: clearStreamingRequest: Device has encountered a serious error[0m
                // 12:29:32.163 E Camera2-StreamingProcessor: stopStream: Camera 0: Can't clear stream request: Function not implemented (-38)[0m
                // 12:29:32.163 E Camera2Client: stopPreviewL: Camera 0: Can't stop streaming: Function not implemented (-38)[0m
                // 12:29:32.273 E Camera2-StreamingProcessor: deletePreviewStream: Unable to delete old preview stream: Device or resource busy (-16)[0m
                // 12:29:32.274 E Camera2-CallbackProcessor: deleteStream: Unable to delete callback stream: Device or resource busy (-16)[0m
                // 12:29:32.274 E Camera3-Device: Camera 0: disconnect: Shutting down in an error state[0m
                //
                // I believe there is a thread deadlock due to this call internally waiting to
                // dispatch some callback to us (pending captures, ...), but the callback thread
                // is blocked here. We try to workaround this in CameraEngine.destroy().
                mCamera.release();
                LOG.i("onStopEngine:", "Clean up.", "Released camera.");
            } catch (Exception e) {
                LOG.w("onStopEngine:", "Clean up.", "Exception while releasing camera.", e);
            }
            mCamera = null;
            mCameraOptions = null;
        }
        mVideoRecorder = null;
        mCameraOptions = null;
        mCamera = null;
        LOG.w("onStopEngine:", "Clean up.", "Returning.");
        return Tasks.forResult(null);
    }

    //endregion

    //region Pictures

    @EngineThread
    @Override
    protected void onTakePicture(@NonNull PictureResult.Stub stub, boolean doMetering) {
        LOG.i("onTakePicture:", "executing.");
        stub.rotation = getAngles().offset(Reference.SENSOR, Reference.OUTPUT,
                Axis.RELATIVE_TO_SENSOR);
        stub.size = getPictureSize(Reference.OUTPUT);
        mPictureRecorder = new Full1PictureRecorder(stub, Camera1Engine.this, mCamera);
        mPictureRecorder.take();
        LOG.i("onTakePicture:", "executed.");
    }

    @EngineThread
    @Override
    protected void onTakePictureSnapshot(@NonNull PictureResult.Stub stub,
                                         @NonNull AspectRatio outputRatio,
                                         boolean doMetering) {
        LOG.i("onTakePictureSnapshot:", "executing.");
        // Not the real size: it will be cropped to match the view ratio
        stub.size = getUncroppedSnapshotSize(Reference.OUTPUT);
        if (mPreview instanceof RendererCameraPreview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            stub.rotation = getAngles().offset(Reference.VIEW, Reference.OUTPUT, Axis.ABSOLUTE);
            mPictureRecorder = new SnapshotGlPictureRecorder(stub, this, this,
                    (RendererCameraPreview) mPreview, outputRatio, getOverlay());
        } else {
            stub.rotation = getAngles().offset(Reference.SENSOR, Reference.OUTPUT, Axis.RELATIVE_TO_SENSOR);
            mPictureRecorder = new Snapshot1PictureRecorder(stub, this, mCamera, outputRatio);
        }
        mPictureRecorder.take();
        LOG.i("onTakePictureSnapshot:", "executed.");
    }

    //endregion

    //region Videos

    @EngineThread
    @Override
    protected void onTakeVideo(@NonNull VideoResult.Stub stub) {
        stub.rotation = getAngles().offset(Reference.SENSOR, Reference.OUTPUT,
                Axis.RELATIVE_TO_SENSOR);
        stub.size = getAngles().flip(Reference.SENSOR, Reference.OUTPUT) ? mCaptureSize.flip()
                : mCaptureSize;
        // Unlock the camera and start recording.
        try {
            mCamera.unlock();
        } catch (Exception e) {
            // If this failed, we are unlikely able to record the video.
            // Dispatch an error.
            onVideoResult(null, e);
            return;
        }
        mVideoRecorder = new Full1VideoRecorder(Camera1Engine.this, mCamera, mCameraId);
        mVideoRecorder.start(stub);
    }

    @SuppressLint("NewApi")
    @EngineThread
    @Override
    protected void onTakeVideoSnapshot(@NonNull VideoResult.Stub stub,
                                       @NonNull AspectRatio outputRatio, int rotation) {
        if (!(mPreview instanceof RendererCameraPreview)) {
            throw new IllegalStateException("Video snapshots are only supported with GL_SURFACE.");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            throw new IllegalStateException("Video snapshots are only supported on API 18+.");
        }
        GlCameraPreview glPreview = (GlCameraPreview) mPreview;
        Size outputSize = getUncroppedSnapshotSize(Reference.OUTPUT);
        if (outputSize == null) {
            throw new IllegalStateException("outputSize should not be null.");
        }
        Rect outputCrop = CropHelper.computeCrop(outputSize, outputRatio);
        outputSize = new Size(outputCrop.width(), outputCrop.height());
        stub.size = outputSize;
        // Vertical:               0   (270-0-0)
        // Left (unlocked):        0   (270-90-270)
        // Right (unlocked):       0   (270-270-90)
        // Upside down (unlocked): 0   (270-180-180)
        // Left (locked):          270 (270-0-270)
        // Right (locked):         90  (270-0-90)
        // Upside down (locked):   180 (270-0-180)
        // The correct formula seems to be deviceOrientation+displayOffset,
        // which means offset(Reference.VIEW, Reference.OUTPUT, Axis.ABSOLUTE).
        stub.rotation = getAngles().offset(Reference.VIEW, Reference.OUTPUT, Axis.ABSOLUTE);
        stub.videoFrameRate = Math.round(mPreviewFrameRate);
        LOG.i("onTakeVideoSnapshot", "rotation:", stub.rotation, "size:", stub.size);

        // Start.
        mVideoRecorder = new SnapshotVideoRecorder(Camera1Engine.this, glPreview, getOverlay());
        mVideoRecorder.start(stub);
    }

    @Override
    public void onVideoResult(@Nullable VideoResult.Stub result, @Nullable Exception exception) {
        super.onVideoResult(result, exception);
        if (result == null) {
            // Something went wrong, lock the camera again.
            mCamera.lock();
        }
    }

    //endregion

    //region Parameters

    private void applyAllParameters(@NonNull Camera.Parameters params) {
        params.setRecordingHint(getMode() == Mode.VIDEO);
        applyDefaultFocus(params);
        applyFlash(params, Flash.OFF);
        applyLocation(params, null);
        applyWhiteBalance(params, WhiteBalance.AUTO);
        applyHdr(params, Hdr.OFF);
        applyZoom(params, 0F);
        applyExposureCorrection(params, 0F);
        applyPlaySounds(mPlaySounds);
        applyPreviewFrameRate(params, 0F);
    }

    private void applyDefaultFocus(@NonNull Camera.Parameters params) {
        List<String> modes = params.getSupportedFocusModes();

        if (getMode() == Mode.VIDEO &&
                modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            return;
        }

        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            return;
        }

        if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            return;
        }

        if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
            //noinspection UnnecessaryReturnStatement
            return;
        }
    }

    @Override
    public void setFlash(@NonNull Flash flash) {
        final Flash old = mFlash;
        mFlash = flash;
        mFlashTask = getOrchestrator().scheduleStateful("flash (" + flash + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (applyFlash(params, old)) mCamera.setParameters(params);
            }
        });
    }

    private boolean applyFlash(@NonNull Camera.Parameters params, @NonNull Flash oldFlash) {
        if (mCameraOptions.supports(mFlash)) {
            params.setFlashMode(mMapper.mapFlash(mFlash));
            return true;
        }
        mFlash = oldFlash;
        return false;
    }

    @Override
    public void setLocation(@Nullable Location location) {
        final Location oldLocation = mLocation;
        mLocation = location;
        mLocationTask = getOrchestrator().scheduleStateful("location",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (applyLocation(params, oldLocation)) mCamera.setParameters(params);
            }
        });
    }

    private boolean applyLocation(@NonNull Camera.Parameters params,
                                  @SuppressWarnings("unused") @Nullable Location oldLocation) {
        if (mLocation != null) {
            params.setGpsLatitude(mLocation.getLatitude());
            params.setGpsLongitude(mLocation.getLongitude());
            params.setGpsAltitude(mLocation.getAltitude());
            params.setGpsTimestamp(mLocation.getTime());
            params.setGpsProcessingMethod(mLocation.getProvider());
        }
        return true;
    }

    @Override
    public void setWhiteBalance(@NonNull WhiteBalance whiteBalance) {
        final WhiteBalance old = mWhiteBalance;
        mWhiteBalance = whiteBalance;
        mWhiteBalanceTask = getOrchestrator().scheduleStateful(
                "white balance (" + whiteBalance + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (applyWhiteBalance(params, old)) mCamera.setParameters(params);
            }
        });
    }

    private boolean applyWhiteBalance(@NonNull Camera.Parameters params,
                                      @NonNull WhiteBalance oldWhiteBalance) {
        if (mCameraOptions.supports(mWhiteBalance)) {
            // If this lock key is present, the engine can throw when applying the
            // parameters, not sure why. Since we never lock it, this should be
            // harmless for the rest of the engine.
            params.setWhiteBalance(mMapper.mapWhiteBalance(mWhiteBalance));
            params.remove("auto-whitebalance-lock");
            return true;
        }
        mWhiteBalance = oldWhiteBalance;
        return false;
    }

    @Override
    public void setHdr(@NonNull Hdr hdr) {
        final Hdr old = mHdr;
        mHdr = hdr;
        mHdrTask = getOrchestrator().scheduleStateful("hdr (" + hdr + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (applyHdr(params, old)) mCamera.setParameters(params);
            }
        });
    }

    private boolean applyHdr(@NonNull Camera.Parameters params, @NonNull Hdr oldHdr) {
        if (mCameraOptions.supports(mHdr)) {
            params.setSceneMode(mMapper.mapHdr(mHdr));
            return true;
        }
        mHdr = oldHdr;
        return false;
    }

    @Override
    public void setZoom(final float zoom, @Nullable final PointF[] points, final boolean notify) {
        final float old = mZoomValue;
        mZoomValue = zoom;
        mZoomTask = getOrchestrator().scheduleStateful("zoom (" + zoom + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (applyZoom(params, old)) {
                    mCamera.setParameters(params);
                    if (notify) {
                        getCallback().dispatchOnZoomChanged(mZoomValue, points);
                    }
                }
            }
        });
    }

    @Override
    public float getZoomValue() {
        return mZoomValue;
    }

    private boolean applyZoom(@NonNull Camera.Parameters params, float oldZoom) {
        if (mCameraOptions.isZoomSupported()) {
            float max = params.getMaxZoom();
            params.setZoom((int) (mZoomValue * max));
            mCamera.setParameters(params);
            return true;
        }
        mZoomValue = oldZoom;
        return false;
    }

    @Override
    public void setExposureCorrection(final float EVvalue, @NonNull final float[] bounds,
                                      @Nullable final PointF[] points, final boolean notify) {
        final float old = mExposureCorrectionValue;
        mExposureCorrectionValue = EVvalue;
        mExposureCorrectionTask = getOrchestrator().scheduleStateful(
                "exposure correction (" + EVvalue + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (applyExposureCorrection(params, old)) {
                    mCamera.setParameters(params);
                    if (notify) {
                        getCallback().dispatchOnExposureCorrectionChanged(mExposureCorrectionValue,
                                bounds, points);
                    }
                }
            }
        });
    }

    @Override
    public void setExposureCorrection(float EVvalue, @NonNull float[] bounds, @Nullable PointF[] points, boolean notify, int index) {

    }

    @Override
    public float getExposureCorrectionValue(int index) {
        return 0;
    }

    @Override
    public boolean isAutoExposure(int index) {
        return false;
    }

    @Override
    public boolean isAutoExposure() {
        return false;
    }

    private boolean applyExposureCorrection(@NonNull Camera.Parameters params,
                                            float oldExposureCorrection) {
        if (mCameraOptions.isExposureCorrectionSupported()) {
            // Just make sure we're inside boundaries.
            float max = mCameraOptions.getExposureCorrectionMaxValue();
            float min = mCameraOptions.getExposureCorrectionMinValue();
            float val = mExposureCorrectionValue;
            val = val < min ? min : val > max ? max : val; // cap
            mExposureCorrectionValue = val;
            // Apply.
            int indexValue = (int) (mExposureCorrectionValue
                    / params.getExposureCompensationStep());
            params.setExposureCompensation(indexValue);
            return true;
        }
        mExposureCorrectionValue = oldExposureCorrection;
        return false;
    }

    @Override
    public void setPlaySounds(boolean playSounds) {
        final boolean old = mPlaySounds;
        mPlaySounds = playSounds;
        mPlaySoundsTask = getOrchestrator().scheduleStateful(
                "play sounds (" + playSounds + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                applyPlaySounds(old);
            }
        });
    }

    @Override
    public void selectOpenedCamera(PointF legacyPoint) {

    }

    @Override
    public int getCurrentCameraIndex() {
        return 0;
    }

    @Override
    public int getCurrentCameraIndex(PointF legacyPoint) {
        return 0;
    }

    private boolean setFaceDetection(boolean useFaceDetection) {
        return false;
    }

    @Override
    public boolean changeFaceDetection(boolean useFaceDetection) {
        return false;
    }

    @Override
    public void setFaceDetectionListener(FaceDetectionListener listener) {

    }

    @Override
    public FACE_DETECT_MODE supportFaceDetection() {
        return FACE_DETECT_MODE.unKnown;
    }

    @Override
    public int getCameraOrientation() {
        return 0;
    }

    @SuppressWarnings("UnusedReturnValue")
    @TargetApi(17)
    private boolean applyPlaySounds(boolean oldPlaySound) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(mCameraId, info);
            if (info.canDisableShutterSound) {
                try {
                    // this method is documented to throw on some occasions. #377
                    return mCamera.enableShutterSound(mPlaySounds);
                } catch (RuntimeException exception) {
                    return false;
                }
            }
        }
        if (mPlaySounds) {
            return true;
        }
        mPlaySounds = oldPlaySound;
        return false;
    }

    @Override
    public void setPreviewFrameRate(float previewFrameRate) {
        final float old = previewFrameRate;
        mPreviewFrameRate = (int) previewFrameRate;
        mPreviewFrameRateTask = getOrchestrator().scheduleStateful(
                "preview fps (" + previewFrameRate + ")",
                CameraState.ENGINE,
                new Runnable() {
            @Override
            public void run() {
                Camera.Parameters params = mCamera.getParameters();
                if (applyPreviewFrameRate(params, old)) mCamera.setParameters(params);
            }
        });
    }

    private boolean applyPreviewFrameRate(@NonNull Camera.Parameters params,
                                          float oldPreviewFrameRate) {
        List<int[]> fpsRanges = params.getSupportedPreviewFpsRange();
        if (mPreviewFrameRate == 0F) {
            // 0F is a special value. Fallback to a reasonable default.
            for (int[] fpsRange : fpsRanges) {
                float lower = (float) fpsRange[0] / 1000F;
                float upper = (float) fpsRange[1] / 1000F;
                if ((lower <= 30F && 30F <= upper) || (lower <= 24F && 24F <= upper)) {
                    params.setPreviewFpsRange(fpsRange[0], fpsRange[1]);
                    return true;
                }
            }
        } else {
            // If out of boundaries, adjust it.
//            mPreviewFrameRate = Math.min(mPreviewFrameRate,
//                    mCameraOptions.getPreviewFrameRateMaxValue());
//            mPreviewFrameRate = Math.max(mPreviewFrameRate,
//                    mCameraOptions.getPreviewFrameRateMinValue());
//            for (int[] fpsRange : fpsRanges) {
//                float lower = (float) fpsRange[0] / 1000F;
//                float upper = (float) fpsRange[1] / 1000F;
//                float rate = Math.round(mPreviewFrameRate);
//                if (lower <= rate && rate <= upper) {
//                    params.setPreviewFpsRange(fpsRange[0], fpsRange[1]);
//                    return true;
//                }
//            }
        }
        mPreviewFrameRate = (int) oldPreviewFrameRate;
        return false;
    }

    @Override
    public void setPictureFormat(@NonNull PictureFormat pictureFormat) {
        if (pictureFormat != PictureFormat.JPEG) {
            throw new UnsupportedOperationException("Unsupported picture format: " + pictureFormat);
        }
        mPictureFormat = pictureFormat;
    }

    //endregion

    //region Frame Processing

    @NonNull
    @Override
    protected FrameManager instantiateFrameManager(int poolSize) {
        return new ByteBufferFrameManager(poolSize, this);
    }

    @NonNull
    @Override
    public ByteBufferFrameManager getFrameManager() {
        return (ByteBufferFrameManager) super.getFrameManager();
    }

    @Nullable
    @Override
    public CameraOptions getCamera2Options() {
        return null;
    }

    @Override
    public void setHasFrameProcessors(boolean hasFrameProcessors) {
        // we don't care, FP is always on
        mHasFrameProcessors = hasFrameProcessors;
    }

    @Override
    public void setFrameProcessingFormat(int format) {
        // Ignore input: we only support NV21.
        mFrameProcessingFormat = ImageFormat.NV21;
    }

    @Override
    public boolean dual() {
        return false;
    }

    @Override
    public void onBufferAvailable(@NonNull byte[] buffer) {
        if (getState().isAtLeast(CameraState.ENGINE)
                && getTargetState().isAtLeast(CameraState.ENGINE)) {
            mCamera.addCallbackBuffer(buffer);
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (data == null) {
            // Seen this happen in logs.
            return;
        }
        Frame frame = getFrameManager().getFrame(data, System.currentTimeMillis());
        if (frame != null) {
            getCallback().dispatchFrame(frame);
        }
    }

    //endregion

    //region Auto Focus

    @Override
    public void startAutoFocus(@Nullable final Gesture gesture,
                               @NonNull final MeteringRegions regions,
                               @NonNull final PointF legacyPoint) {
        getOrchestrator().scheduleStateful("auto focus", CameraState.BIND, new Runnable() {
            @Override
            public void run() {
                if (!mCameraOptions.isAutoFocusSupported()) return;
                MeteringTransform<Camera.Area> transform = new Camera1MeteringTransform(
                        getAngles(),
                        getPreview().getSurfaceSize());
                MeteringRegions transformed = regions.transform(transform);

                Camera.Parameters params = mCamera.getParameters();
                int maxAF = params.getMaxNumFocusAreas();
                int maxAE = params.getMaxNumMeteringAreas();
                if (maxAF > 0) params.setFocusAreas(transformed.get(maxAF, transform));
                if (maxAE > 0) params.setMeteringAreas(transformed.get(maxAE, transform));
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                mCamera.setParameters(params);
                getCallback().dispatchOnFocusStart(gesture, legacyPoint);

                // The auto focus callback is not guaranteed to be called, but we really want it
                // to be. So we remove the old runnable if still present and post a new one.
                getOrchestrator().remove(JOB_FOCUS_END);
                getOrchestrator().scheduleDelayed(JOB_FOCUS_END, AUTOFOCUS_END_DELAY_MILLIS,
                        new Runnable() {
                    @Override
                    public void run() {
                        getCallback().dispatchOnFocusEnd(gesture, false, legacyPoint);
                    }
                });

                // Wrapping autoFocus in a try catch to handle some device specific exceptions,
                // see See https://github.com/natario1/CameraView/issues/181.
                try {
                    mCamera.autoFocus(new Camera.AutoFocusCallback() {
                        @Override
                        public void onAutoFocus(boolean success, Camera camera) {
                            getOrchestrator().remove(JOB_FOCUS_END);
                            getOrchestrator().remove(JOB_FOCUS_RESET);
                            getCallback().dispatchOnFocusEnd(gesture, success, legacyPoint);
                            if (shouldResetAutoFocus()) {
                                getOrchestrator().scheduleStatefulDelayed(
                                        JOB_FOCUS_RESET,
                                        CameraState.ENGINE,
                                        getAutoFocusResetDelay(),
                                        new Runnable() {
                                    @Override
                                    public void run() {
                                        mCamera.cancelAutoFocus();
                                        Camera.Parameters params = mCamera.getParameters();
                                        int maxAF = params.getMaxNumFocusAreas();
                                        int maxAE = params.getMaxNumMeteringAreas();
                                        if (maxAF > 0) params.setFocusAreas(null);
                                        if (maxAE > 0) params.setMeteringAreas(null);
                                        applyDefaultFocus(params); // Revert to internal focus.
                                        mCamera.setParameters(params);
                                    }
                                });
                            }
                        }
                    });
                } catch (RuntimeException e) {
                    LOG.e("startAutoFocus:", "Error calling autoFocus", e);
                    // Let the mFocusEndRunnable do its job. (could remove it and quickly dispatch
                    // onFocusEnd here, but let's make it simpler).
                }
            }
        });
    }

    @Override
    public void cancelAutoFocus() {
        try {
            mCamera.cancelAutoFocus();
        }
        catch(RuntimeException e) {
            // had a report of crash on some devices, see comment at https://sourceforge.net/p/opencamera/tickets/4/ made on 20140520
            LOG.e("cancelAutoFocus() failed");
            e.printStackTrace();
        }
    }

    @Override
    public boolean supportsAutoFocus() {
        try {
            Camera.Parameters parameters = mCamera.getParameters();
            String focus_mode = parameters.getFocusMode();
            // getFocusMode() is documented as never returning null, however I've had null pointer exceptions reported in Google Play from the below line (v1.7),
            // on Galaxy Tab 10.1 (GT-P7500), Android 4.0.3 - 4.0.4; HTC EVO 3D X515m (shooteru), Android 4.0.3 - 4.0.4
            if( focus_mode != null && ( focus_mode.equals(Camera.Parameters.FOCUS_MODE_AUTO) || focus_mode.equals(Camera.Parameters.FOCUS_MODE_MACRO) ) ) {
                return true;
            }
        }
        catch(RuntimeException e) {
            e.printStackTrace();
        }
        return false;
    }

    //endregion
}

