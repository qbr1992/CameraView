package com.sabine.cameraview.engine;

import android.location.Location;
import android.util.Log;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.sabine.cameraview.CameraException;
import com.sabine.cameraview.CameraOptions;
import com.sabine.cameraview.PictureResult;
import com.sabine.cameraview.VideoResult;
import com.sabine.cameraview.controls.Audio;
import com.sabine.cameraview.controls.Facing;
import com.sabine.cameraview.controls.Flash;
import com.sabine.cameraview.controls.Hdr;
import com.sabine.cameraview.controls.Mode;
import com.sabine.cameraview.controls.PictureFormat;
import com.sabine.cameraview.controls.VideoCodec;
import com.sabine.cameraview.controls.WhiteBalance;
import com.sabine.cameraview.engine.offset.Angles;
import com.sabine.cameraview.engine.offset.Axis;
import com.sabine.cameraview.engine.offset.Reference;
import com.sabine.cameraview.engine.orchestrator.CameraState;
import com.sabine.cameraview.frame.FrameManager;
import com.sabine.cameraview.overlay.Overlay;
import com.sabine.cameraview.picture.PictureRecorder;
import com.sabine.cameraview.preview.CameraPreview;
import com.sabine.cameraview.size.AspectRatio;
import com.sabine.cameraview.size.Size;
import com.sabine.cameraview.size.SizeSelector;
import com.sabine.cameraview.size.SizeSelectors;
import com.sabine.cameraview.utils.LogUtil;
import com.sabine.cameraview.video.VideoRecorder;

import java.io.File;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


/**
 * Abstract implementation of {@link CameraEngine} that helps in common tasks.
 */
public abstract class CameraBaseEngine extends CameraEngine {

    @SuppressWarnings("WeakerAccess") protected CameraPreview mPreview;
    @SuppressWarnings("WeakerAccess") protected CameraOptions mCameraOptions;
    @SuppressWarnings("WeakerAccess") protected PictureRecorder mPictureRecorder;
    @SuppressWarnings("WeakerAccess") protected VideoRecorder mVideoRecorder;
    @SuppressWarnings("WeakerAccess") protected Size mCaptureSize;
    @SuppressWarnings("WeakerAccess") protected Size mPreviewStreamSize;
    @SuppressWarnings("WeakerAccess") protected Size mFrameProcessingSize;
    @SuppressWarnings("WeakerAccess") protected int mFrameProcessingFormat;
    @SuppressWarnings("WeakerAccess") protected boolean mHasFrameProcessors;
    @SuppressWarnings("WeakerAccess") protected Flash mFlash;
    @SuppressWarnings("WeakerAccess") protected WhiteBalance mWhiteBalance;
    @SuppressWarnings("WeakerAccess") protected VideoCodec mVideoCodec;
    @SuppressWarnings("WeakerAccess") protected Hdr mHdr;
    @SuppressWarnings("WeakerAccess") protected PictureFormat mPictureFormat;
    @SuppressWarnings("WeakerAccess") protected Location mLocation;
    @SuppressWarnings("WeakerAccess") protected float mZoomValue;
    @SuppressWarnings("WeakerAccess") protected float mExposureCorrectionValue;
    @SuppressWarnings("WeakerAccess") protected boolean mPlaySounds;
    @SuppressWarnings("WeakerAccess") protected boolean mPictureMetering;
    @SuppressWarnings("WeakerAccess") protected boolean mPictureSnapshotMetering;
    @SuppressWarnings("WeakerAccess") protected int mPreviewFrameRate;

    private FrameManager mFrameManager;
    private final Angles mAngles = new Angles();
//    @Nullable private Size mPreviewStreamSize;
    private Size mPictureSize;
    private Size mVideoSize;
    protected Facing mFacing = Facing.FRONT;
    private Mode mMode;
    private Audio mAudio;
    private int mVideoBitRate;
    private int mAudioBitRate;
    private long mAutoFocusResetDelayMillis;
    private int mSnapshotMaxWidth; // in REF_VIEW like SizeSelectors
    private int mSnapshotMaxHeight; // in REF_VIEW like SizeSelectors
    private int mFrameProcessingMaxWidth; // in REF_VIEW like SizeSelectors
    private int mFrameProcessingMaxHeight; // in REF_VIEW like SizeSelectors
    private int mFrameProcessingPoolSize;
    private Overlay mOverlay;

    // Ops used for testing.
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) Task<Void> mZoomTask
            = Tasks.forResult(null);
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) Task<Void> mExposureCorrectionTask
            = Tasks.forResult(null);
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) Task<Void> mFlashTask
            = Tasks.forResult(null);
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) Task<Void> mWhiteBalanceTask
            = Tasks.forResult(null);
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) Task<Void> mHdrTask
            = Tasks.forResult(null);
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) Task<Void> mLocationTask
            = Tasks.forResult(null);
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) Task<Void> mPlaySoundsTask
            = Tasks.forResult(null);
    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED) Task<Void> mPreviewFrameRateTask
            = Tasks.forResult(null);

    @SuppressWarnings("WeakerAccess")
    protected CameraBaseEngine(@NonNull Callback callback) {
        super(callback);
    }

    /**
     * Called at construction time to get a frame manager that can later be
     * accessed through {@link #getFrameManager()}.
     * @param poolSize pool size
     * @return a frame manager
     */
    @NonNull
    protected abstract FrameManager instantiateFrameManager(int poolSize);

    @NonNull
    @Override
    public final Angles getAngles() {
        return mAngles;
    }

    @NonNull
    @Override
    public FrameManager getFrameManager() {
        if (mFrameManager == null) {
            mFrameManager = instantiateFrameManager(mFrameProcessingPoolSize);
        }
        return mFrameManager;
    }

    @Nullable
    @Override
    public final CameraOptions getCameraOptions() {
        return mCameraOptions;
    }

    @Override
    public final void setPreview(@NonNull CameraPreview cameraPreview) {
        if (mPreview != null) mPreview.setSurfaceCallback(null);
        mPreview = cameraPreview;
        mPreview.setSurfaceCallback(this);
    }

    @NonNull
    @Override
    public final CameraPreview getPreview() {
        return mPreview;
    }

    @Override
    public final void setOverlay(@Nullable Overlay overlay) {
        mOverlay = overlay;
    }

    @Nullable
    @Override
    public final Overlay getOverlay() {
        return mOverlay;
    }

    @Override
    public final void setPreviewStreamSize(@Nullable Size previewStreamSize) {
        mPreviewStreamSize = previewStreamSize;
    }

    @Nullable
    @Override
    public final Size getPreviewStreamSize() {
        return mPreviewStreamSize;
    }

    @Override
    public final void setPictureSize(@NonNull Size pictureSize) {
        mPictureSize = pictureSize;
    }

    @NonNull
    @Override
    public final Size getPictureSize() {
        return mPictureSize;
    }

    @Override
    public final void setVideoSize(@NonNull Size videoSize) {
        mVideoSize = videoSize;
    }

    @NonNull
    @Override
    public final Size getVideoSize() {
        return mVideoSize;
    }

    @Override
    public final void setVideoCodec(@NonNull VideoCodec codec) {
        mVideoCodec = codec;
    }

    @NonNull
    @Override
    public final VideoCodec getVideoCodec() {
        return mVideoCodec;
    }

    @Override
    public final void setVideoBitRate(int videoBitRate) {
        mVideoBitRate = videoBitRate;
    }

    @Override
    public final int getVideoBitRate() {
        return mVideoBitRate;
    }

    @Override
    public final void setAudioBitRate(int audioBitRate) {
        mAudioBitRate = audioBitRate;
    }

    @Override
    public final int getAudioBitRate() {
        return mAudioBitRate;
    }

    @Override
    public final void setSnapshotMaxWidth(int maxWidth) {
        mSnapshotMaxWidth = maxWidth;
    }

    @Override
    public final int getSnapshotMaxWidth() {
        return mSnapshotMaxWidth;
    }

    @Override
    public final void setSnapshotMaxHeight(int maxHeight) {
        mSnapshotMaxHeight = maxHeight;
    }

    @Override
    public final int getSnapshotMaxHeight() {
        return mSnapshotMaxHeight;
    }

    @Override
    public final void setFrameProcessingMaxWidth(int maxWidth) {
        mFrameProcessingMaxWidth = maxWidth;
    }

    @Override
    public final int getFrameProcessingMaxWidth() {
        return mFrameProcessingMaxWidth;
    }

    @Override
    public final void setFrameProcessingMaxHeight(int maxHeight) {
        mFrameProcessingMaxHeight = maxHeight;
    }

    @Override
    public final int getFrameProcessingMaxHeight() {
        return mFrameProcessingMaxHeight;
    }

    @Override
    public final int getFrameProcessingFormat() {
        return mFrameProcessingFormat;
    }

    @Override
    public final void setFrameProcessingPoolSize(int poolSize) {
        mFrameProcessingPoolSize = poolSize;
    }

    @Override
    public final int getFrameProcessingPoolSize() {
        return mFrameProcessingPoolSize;
    }

    @Override
    public final void setAutoFocusResetDelay(long delayMillis) {
        mAutoFocusResetDelayMillis = delayMillis;
    }

    @Override
    public final long getAutoFocusResetDelay() {
        return mAutoFocusResetDelayMillis;
    }

    /**
     * Helper function for subclasses.
     * @return true if AF should be reset
     */
    @SuppressWarnings("WeakerAccess")
    protected final boolean shouldResetAutoFocus() {
        return mAutoFocusResetDelayMillis > 0 && mAutoFocusResetDelayMillis != Long.MAX_VALUE;
    }

    /**
     * Sets a new facing value. This will restart the engine session (if there's any)
     * so that we can open the new facing camera.
     * @param facing facing
     */
    @Override
    public final void setFacing(final @NonNull Facing facing) {
        final Facing old = mFacing;
        if (facing != old) {
            mFacing = facing;
            getOrchestrator().scheduleStateful("facing", CameraState.ENGINE,
                    new Runnable() {
                @Override
                public void run() {
                    if (collectCameraInfo(facing)) {
                        restart();
                    } else {
                        mFacing = old;
                    }
                }
            });
        }
    }

    @NonNull
    @Override
    public final Facing getFacing() {
        return mFacing;
    }

    /**
     * Sets a new audio value that will be used for video recordings.
     * @param audio desired audio
     */
    @Override
    public final void setAudio(@NonNull Audio audio) {
        if (mAudio != audio) {
            if (isTakingVideo()) {
                LOG.w("Audio setting was changed while recording. " +
                        "Changes will take place starting from next video");
            }
            mAudio = audio;
        }
    }

    @NonNull
    @Override
    public final Audio getAudio() {
        return mAudio;
    }

    /**
     * Sets the desired mode (either picture or video).
     * @param mode desired mode.
     */
    @Override
    public final void setMode(@NonNull Mode mode) {
        if (mode != mMode) {
            mMode = mode;
            getOrchestrator().scheduleStateful("mode", CameraState.ENGINE,
                    new Runnable() {
                @Override
                public void run() {
                    restart();
                }
            });
        }
    }

    @NonNull
    @Override
    public final Mode getMode() {
        return mMode;
    }

    @Override
    public final float getZoomValue() {
        return mZoomValue;
    }

    @Override
    public final float getExposureCorrectionValue() {
        return mExposureCorrectionValue;
    }

    @NonNull
    @Override
    public final Flash getFlash() {
        return mFlash;
    }

    @NonNull
    @Override
    public final WhiteBalance getWhiteBalance() {
        return mWhiteBalance;
    }

    @NonNull
    @Override
    public final Hdr getHdr() {
        return mHdr;
    }

    @Nullable
    @Override
    public final Location getLocation() {
        return mLocation;
    }

    @NonNull
    @Override
    public final PictureFormat getPictureFormat() {
        return mPictureFormat;
    }

    @Override
    public final float getPreviewFrameRate() {
        return mPreviewFrameRate;
    }

    public final float getMaxPreviewFrameRate() {
        if (mCameraOptions == null) return 0;
        return mCameraOptions.getPreviewFrameRateMaxValue();
    }

    public final float getMinPreviewFrameRate() {
        if (mCameraOptions == null) return 0;
        return mCameraOptions.getPreviewFrameRateMinValue();
    }

    @Override
    public final boolean hasFrameProcessors() {
        return mHasFrameProcessors;
    }

    @Override
    public final void setPictureMetering(boolean enable) {
        mPictureMetering = enable;
    }

    @Override
    public final boolean getPictureMetering() {
        return mPictureMetering;
    }

    @Override
    public final void setPictureSnapshotMetering(boolean enable) {
        mPictureSnapshotMetering = enable;
    }

    @Override
    public final boolean getPictureSnapshotMetering() {
        return mPictureSnapshotMetering;
    }

    //region Picture and video control

    @Override
    public final boolean isTakingPicture() {
        return mPictureRecorder != null;
    }

    @Override
    public /* final */ void takePicture(final @NonNull PictureResult.Stub stub) {
        // Save boolean before scheduling! See how Camera2Engine calls this with a temp value.
        final boolean metering = mPictureMetering;
        getOrchestrator().scheduleStateful("take picture", CameraState.BIND,
                new Runnable() {
            @Override
            public void run() {
                LOG.i("takePicture:", "running. isTakingPicture:", isTakingPicture());
                if (isTakingPicture()) return;
//                if (mMode == Mode.VIDEO) {
//                    throw new IllegalStateException("Can't take hq pictures while in VIDEO mode");
//                }
                stub.isSnapshot = false;
                stub.location = mLocation;
                stub.facing = mFacing;
                stub.format = mPictureFormat;
                onTakePicture(stub, metering);
            }
        });
    }

    /**
     * The snapshot size is the {@link #getPreviewStreamSize(Reference)}, but cropped based on the
     * view/surface aspect ratio.
     * @param stub a picture stub
     */
    @Override
    public /* final */ void takePictureSnapshot(final @NonNull PictureResult.Stub stub) {
        // Save boolean before scheduling! See how Camera2Engine calls this with a temp value.
        final boolean metering = mPictureSnapshotMetering;
        getOrchestrator().scheduleStateful("take picture snapshot", CameraState.BIND,
                new Runnable() {
            @Override
            public void run() {
                LOG.i("takePictureSnapshot:", "running. isTakingPicture:", isTakingPicture());
                if (isTakingPicture()) return;
                stub.location = mLocation;
                stub.isSnapshot = true;
                stub.facing = mFacing;
                stub.format = PictureFormat.JPEG;
                // Leave the other parameters to subclasses.
                //noinspection ConstantConditions
                AspectRatio ratio = AspectRatio.of(getPreviewSurfaceSize(Reference.OUTPUT));
                onTakePictureSnapshot(stub, ratio, metering);
            }
        });
    }

    @Override
    public void onPictureShutter(boolean didPlaySound) {
        getCallback().onShutter(!didPlaySound);
    }

    @Override
    public void onPictureResult(@Nullable PictureResult.Stub result, @Nullable Exception error) {
        mPictureRecorder = null;
        if (result != null) {
            getCallback().dispatchOnPictureTaken(result);
        } else {
            LOG.e("onPictureResult", "result is null: something went wrong.", error);
            getCallback().dispatchError(new CameraException(error,
                    CameraException.REASON_PICTURE_FAILED));
        }
    }

    @Override
    public final boolean isTakingVideo() {
        return mVideoRecorder != null && mVideoRecorder.isRecording();
    }

    @Override
    public final void takeVideo(final @NonNull VideoResult.Stub stub,
                                final @Nullable File file,
                                final @Nullable FileDescriptor fileDescriptor) {
        getOrchestrator().scheduleStateful("take video", CameraState.BIND, new Runnable() {
            @Override
            public void run() {
                LOG.i("takeVideo:", "running. isTakingVideo:", isTakingVideo());
                if (isTakingVideo()) return;
                if (mMode == Mode.PICTURE) {
                    throw new IllegalStateException("Can't record video while in PICTURE mode");
                }
                if (file != null) {
                    LOG.e("takeVideo:", "file:", file.getAbsolutePath());

                    stub.file = file;
                } else if (fileDescriptor != null) {
                    stub.fileDescriptor = fileDescriptor;
                } else {
                    throw new IllegalStateException("file and fileDescriptor are both null.");
                }
                stub.isSnapshot = false;
                stub.videoCodec = mVideoCodec;
                stub.location = mLocation;
                stub.facing = mFacing;
                stub.audio = mAudio;
                stub.videoBitRate = mVideoBitRate;
                stub.audioBitRate = mAudioBitRate;
                onTakeVideo(stub);
            }
        });
    }

    /**
     * @param stub a video stub
     * @param file the output file
     */
    @Override
    public final void takeVideoSnapshot(@NonNull final VideoResult.Stub stub,
                                        @NonNull final File file, @NonNull final Size size, final boolean isFlip) {
        getOrchestrator().scheduleStateful("take video snapshot", CameraState.BIND,
                new Runnable() {
            @Override
            public void run() {
                LOG.i("takeVideoSnapshot:", "running. isTakingVideo:", isTakingVideo());
                stub.file = file;
                stub.isSnapshot = true;
                stub.videoCodec = mVideoCodec;
                stub.location = mLocation;
                stub.facing = mFacing;
                stub.videoBitRate = mVideoBitRate;
                stub.audioBitRate = mAudioBitRate;
                stub.size = size;
                if (isFlip) {
                    stub.scaleX = getAngles().flip(Reference.SENSOR, Reference.OUTPUT) ? -1.0f : 1.0f;
                    stub.scaleY = getAngles().flip(Reference.SENSOR, Reference.OUTPUT) ? 1.0f : -1.0f;
                } else {
                    stub.scaleX = 1.0f;
                    stub.scaleY = 1.0f;
                }
                //noinspection ConstantConditions
                AspectRatio ratio = AspectRatio.of(getPreviewSurfaceSize(Reference.OUTPUT));
                onTakeVideoSnapshot(stub, ratio);
            }
        });
    }

    @Override
    public final void stopVideo() {
        LogUtil.w(TAG, "stopVideo");
        getOrchestrator().schedule("stop video", true, new Runnable() {
            @Override
            public void run() {
                LogUtil.w(TAG, "stopVideo running");
                LOG.i("stopVideo", "running. isTakingVideo?", isTakingVideo());
                onStopVideo();
            }
        });
    }

    @EngineThread
    @SuppressWarnings("WeakerAccess")
    protected void onStopVideo() {
        LogUtil.w(TAG, "onStopVideo");
        if (mVideoRecorder != null) {
            mVideoRecorder.stop(false);
            // Do not null this, so we respond correctly to isTakingVideo(),
            // which checks for recorder presence and recorder.isRecording().
            // It will be nulled in onVideoResult.
        }
    }

    @CallSuper
    @Override
    public void onVideoResult(@Nullable VideoResult.Stub result, @Nullable Exception exception) {
        mVideoRecorder = null;
        if (result != null) {
            getCallback().dispatchOnVideoTaken(result);
        } else {
            LOG.e("onVideoResult", "result is null: something went wrong.", exception);
            getCallback().dispatchError(new CameraException(exception,
                    CameraException.REASON_VIDEO_FAILED));
        }
    }

    @Override
    public void onVideoRecordingStart() {
        getCallback().dispatchOnVideoRecordingStart();
    }

    @Override
    public void onVideoEncodeStart(int videoBitrate) {
        getCallback().dispatchOnVideoEncodeStart(videoBitrate);
    }

    @Override
    public void onVideoRecordingEnd() {
        getCallback().dispatchOnVideoRecordingEnd();
    }

    @Override
    public void onVideoMuxerChange() {
        getCallback().dispatchOnMuxerChange();
    }

    @EngineThread
    protected abstract void onTakePicture(@NonNull PictureResult.Stub stub, boolean doMetering);

    @EngineThread
    protected abstract void onTakePictureSnapshot(@NonNull PictureResult.Stub stub,
                                                  @NonNull AspectRatio outputRatio,
                                                  boolean doMetering);

    @EngineThread
    protected abstract void onTakeVideoSnapshot(@NonNull VideoResult.Stub stub,
                                                @NonNull AspectRatio outputRatio);

    @EngineThread
    protected abstract void onTakeVideo(@NonNull VideoResult.Stub stub);

    //endregion

    //region Size / Surface

    @Override
    public final void onSurfaceChanged() {
        LOG.i("onSurfaceChanged:", "Size is", getPreviewSurfaceSize(Reference.VIEW));
        getOrchestrator().scheduleStateful("surface changed", CameraState.BIND,
                new Runnable() {
            @Override
            public void run() {
                // Compute a new camera preview size and apply.
                Size newSize = computePreviewStreamSize();
                if (newSize.equals(mPreviewStreamSize)) {
                    LOG.i("onSurfaceChanged:",
                            "The computed preview size is identical. No op.");
                } else {
                    LOG.i("onSurfaceChanged:",
                            "Computed a new preview size. Calling onPreviewStreamSizeChanged().");
                    mPreviewStreamSize = newSize;
                    onPreviewStreamSizeChanged();
                }
            }
        });
    }

    /**
     * The preview stream size has changed. At this point, some engine might want to
     * simply call {@link #restartPreview()}, others to {@link #restartBind()}.
     *
     * It basically depends on the step at which the preview stream size is actually used.
     */
    @EngineThread
    protected abstract void onPreviewStreamSizeChanged();

    @Nullable
    @Override
    public final Size getPictureSize(@SuppressWarnings("SameParameterValue") @NonNull Reference reference) {
        Size size = mCaptureSize;
        if (size == null || mMode == Mode.VIDEO) return null;
        return getAngles().flip(Reference.SENSOR, reference) ? size.flip() : size;
    }

    @Nullable
    @Override
    public final Size getVideoSize(@SuppressWarnings("SameParameterValue") @NonNull Reference reference) {
        Size size = mCaptureSize;
        if (size == null || mMode == Mode.PICTURE) return null;
        return getAngles().flip(Reference.SENSOR, reference) ? size.flip() : size;
    }

    @Nullable
    @Override
    public final Size getPreviewStreamSize(@NonNull Reference reference) {
        Size size = mPreviewStreamSize;
        if (size == null) return null;
        return getAngles().flip(Reference.SENSOR, reference) ? size.flip() : size;
    }

    @SuppressWarnings("SameParameterValue")
    @Nullable
    private Size getPreviewSurfaceSize(@NonNull Reference reference) {
        CameraPreview preview = mPreview;
        if (preview == null) return null;
        return getAngles().flip(Reference.VIEW, reference) ? preview.getSurfaceSize().flip()
                : preview.getSurfaceSize();
    }

    /**
     * Returns the snapshot size, but not cropped with the view dimensions, which
     * is what we will do before creating the snapshot. However, cropping is done at various
     * levels so we don't want to perform the op here.
     *
     * The base snapshot size is based on PreviewStreamSize (later cropped with view ratio). Why?
     * One might be tempted to say that it's the SurfaceSize (which already matches the view ratio).
     *
     * The camera sensor will capture preview frames with PreviewStreamSize and that's it. Then they
     * are hardware-scaled by the preview surface, but this does not affect the snapshot, as the
     * snapshot recorder simply creates another surface.
     *
     * Done tests to ensure that this is true, by using
     * 1. small SurfaceSize and biggest() PreviewStreamSize: output is not low quality
     * 2. big SurfaceSize and smallest() PreviewStreamSize: output is low quality
     * In both cases the result.size here was set to the biggest of the two.
     *
     * I could not find the same evidence for videos, but I would say that the same things should
     * apply, despite the capturing mechanism being different.
     *
     * @param reference the reference system
     * @return the uncropped snapshot size
     */
    @Nullable
    @Override
    public final Size getUncroppedSnapshotSize(@NonNull Reference reference) {
        Size baseSize = getPreviewStreamSize(reference);
        if (baseSize == null) return null;
        boolean flip = getAngles().flip(reference, Reference.VIEW);
        int maxWidth = flip ? mSnapshotMaxHeight : mSnapshotMaxWidth;
        int maxHeight = flip ? mSnapshotMaxWidth : mSnapshotMaxHeight;
        if (maxWidth <= 0) maxWidth = Integer.MAX_VALUE;
        if (maxHeight <= 0) maxHeight = Integer.MAX_VALUE;
        float baseRatio = AspectRatio.of(baseSize).toFloat();
        float maxValuesRatio = AspectRatio.of(maxWidth, maxHeight).toFloat();
        if (maxValuesRatio >= baseRatio) {
            // Height is the real constraint.
            int outHeight = Math.min(baseSize.getHeight(), maxHeight);
            int outWidth = (int) Math.floor((float) outHeight * baseRatio);
            return new Size(outWidth, outHeight);
        } else {
            // Width is the real constraint.
            int outWidth = Math.min(baseSize.getWidth(), maxWidth);
            int outHeight = (int) Math.floor((float) outWidth / baseRatio);
            return new Size(outWidth, outHeight);
        }
    }

    /**
     * This is called either on cameraView.start(), or when the underlying surface changes.
     * It is possible that in the first call the preview surface has not already computed its
     * dimensions.
     * But when it does, the {@link CameraPreview.SurfaceCallback} should be called,
     * and this should be refreshed.
     *
     * @return the capture size
     */
    @NonNull
    @SuppressWarnings("WeakerAccess")
    protected final Size computeCaptureSize() {
        return computeCaptureSize(mMode);
    }

    @NonNull
    @SuppressWarnings("WeakerAccess")
    protected final Size computeCaptureSize(@NonNull Mode mode) {
        // We want to pass stuff into the REF_VIEW reference, not the sensor one.
        // This is already managed by CameraOptions, so we just flip again at the end.
        boolean flip = getAngles().flip(Reference.SENSOR, Reference.VIEW);
        Size result;
        Collection<Size> sizes;
        if (mode == Mode.PICTURE) {
            result = mPictureSize;
//            sizes = mCameraOptions.getSupportedPictureSizes();
        } else {
            result = mVideoSize;
//            sizes = mCameraOptions.getSupportedVideoSizes();
        }
//        selector = SizeSelectors.or(selector, SizeSelectors.biggest());
//        List<Size> list = new ArrayList<>(sizes);
//        Size result = selector.select(list).get(0);
//        if (!list.contains(result)) {
//            throw new RuntimeException("SizeSelectors must not return Sizes other than " +
//                    "those in the input list.");
//        }
        if (flip) result = result.flip(); // Go back to REF_SENSOR
        return result;
    }

    /**
     * This is called anytime {@link #computePreviewStreamSize()} is called.
     * This means that it should be called during the binding process, when
     * we can be sure that the camera is available (engineState == STARTED).
     * @return a list of available sizes for preview
     */
    @EngineThread
    @NonNull
    protected abstract List<Size> getPreviewStreamAvailableSizes();

    @EngineThread
    @NonNull
    @SuppressWarnings("WeakerAccess")
    protected final Size computePreviewStreamSize() {
        @NonNull List<Size> previewSizes = getPreviewStreamAvailableSizes();
        // These sizes come in REF_SENSOR. Since there is an external selector involved,
        // we must convert all of them to REF_VIEW, then flip back when returning.
        boolean flip = getAngles().flip(Reference.SENSOR, Reference.VIEW);
        List<Size> sizes = new ArrayList<>(previewSizes.size());
        for (Size size : previewSizes) {
            sizes.add(flip ? size.flip() : size);
        }

        // Create our own default selector, which will be used if the external
        // mPreviewStreamSizeSelector is null, or if it fails in finding a size.
        Size targetMinSize = getPreviewSurfaceSize(Reference.VIEW);
        if (targetMinSize == null) {
            throw new IllegalStateException("targetMinSize should not be null here.");
        }
        AspectRatio targetRatio = AspectRatio.of(mCaptureSize.getWidth(), mCaptureSize.getHeight());
        if (flip) targetRatio = targetRatio.flip();
        LOG.e("computePreviewStreamSize:",
                "targetRatio:", targetRatio,
                "targetMinSize:", targetMinSize);
        SizeSelector matchRatio = SizeSelectors.and( // Match this aspect ratio and sort by biggest
                SizeSelectors.aspectRatio(targetRatio, 0),
                SizeSelectors.smallest());
        SizeSelector matchSize = SizeSelectors.and( // Bigger than this size, and sort by smallest
                SizeSelectors.minHeight(flip ? mCaptureSize.getWidth() : mCaptureSize.getHeight()),
                SizeSelectors.minWidth(flip ? mCaptureSize.getHeight() : mCaptureSize.getWidth()),
                SizeSelectors.smallest());
        SizeSelector matchAll = SizeSelectors.or(
                SizeSelectors.and(matchRatio, matchSize), // Try to respect both constraints.
                matchSize, // If couldn't match aspect ratio, at least respect the size
                matchRatio, // If couldn't respect size, at least match aspect ratio
                SizeSelectors.smallest() // If couldn't match any, take the biggest.
        );

        // Apply the external selector with this as a fallback,
        // and return a size in REF_SENSOR reference.
        SizeSelector selector;
        selector = matchAll;
        Size result;
        if (selector.select(sizes).isEmpty()) {
            result = mCaptureSize;
        } else {
            result = selector.select(sizes).get(0);
        }
//        if (!sizes.contains(result)) {
//            throw new RuntimeException("SizeSelectors must not return Sizes other than " +
//                    "those in the input list.");
//        }
        if (flip) result = result.flip();
        LOG.e("computePreviewStreamSize:", "result:", result, "flip:", flip);
        return result;
    }

    /**
     * This is called anytime {@link #computeFrameProcessingSize()} is called.
     * Implementors can return null if frame processor size is not selectable
     * @return a list of available sizes for frame processing
     */
    @EngineThread
    @NonNull
    protected abstract List<Size> getFrameProcessingAvailableSizes();

    @EngineThread
    @NonNull
    @SuppressWarnings("WeakerAccess")
    protected final Size computeFrameProcessingSize() {
        @NonNull List<Size> frameSizes = getFrameProcessingAvailableSizes();
        // These sizes come in REF_SENSOR. Since there is an external selector involved,
        // we must convert all of them to REF_VIEW, then flip back when returning.
        boolean flip = getAngles().flip(Reference.SENSOR, Reference.VIEW);
        List<Size> sizes = new ArrayList<>(frameSizes.size());
        for (Size size : frameSizes) {
            sizes.add(flip ? size.flip() : size);
        }
        AspectRatio targetRatio = AspectRatio.of(
                mPreviewStreamSize.getWidth(),
                mPreviewStreamSize.getHeight());
        if (flip) targetRatio = targetRatio.flip();
        int maxWidth = mFrameProcessingMaxWidth;
        int maxHeight = mFrameProcessingMaxHeight;
        if (maxWidth <= 0 || maxWidth == Integer.MAX_VALUE) maxWidth = 640;
        if (maxHeight <= 0 || maxHeight == Integer.MAX_VALUE) maxHeight = 640;
        Size targetMaxSize = new Size(maxWidth, maxHeight);
        LOG.i("computeFrameProcessingSize:",
                "targetRatio:", targetRatio,
                "targetMaxSize:", targetMaxSize);
        SizeSelector matchRatio = SizeSelectors.aspectRatio(targetRatio, 0);
        SizeSelector matchSize = SizeSelectors.and(
                SizeSelectors.maxHeight(targetMaxSize.getHeight()),
                SizeSelectors.maxWidth(targetMaxSize.getWidth()),
                SizeSelectors.biggest());
        SizeSelector matchAll = SizeSelectors.or(
                SizeSelectors.and(matchRatio, matchSize), // Try to respect both constraints.
                matchSize, // If couldn't match aspect ratio, at least respect the size
                SizeSelectors.smallest() // If couldn't match any, take the smallest.
        );
        Size result = matchAll.select(sizes).get(0);
        if (!sizes.contains(result)) {
            throw new RuntimeException("SizeSelectors must not return Sizes other than " +
                    "those in the input list.");
        }
        if (flip) result = result.flip();
        LOG.e("computeFrameProcessingSize:", "result:", result, "flip:", flip);
        return result;
    }

    @Override
    public long getTimeStamp() {
        if (mVideoRecorder != null) return mVideoRecorder.getTimeStamp();
        return 0;
    }

    @Override
    public void putAudioPcm(byte[] pcm, int length, boolean isEndOfStream) {
        if (mVideoRecorder != null) mVideoRecorder.putAudioPcm(pcm, length, isEndOfStream);
    }

    //endregion
}
