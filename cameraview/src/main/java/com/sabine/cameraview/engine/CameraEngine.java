package com.sabine.cameraview.engine;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.tasks.OnCanceledListener;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.SuccessContinuation;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.sabine.cameraview.CameraException;
import com.sabine.cameraview.CameraLogger;
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
import com.sabine.cameraview.engine.offset.Reference;
import com.sabine.cameraview.engine.orchestrator.CameraOrchestrator;
import com.sabine.cameraview.engine.orchestrator.CameraState;
import com.sabine.cameraview.engine.orchestrator.CameraStateOrchestrator;
import com.sabine.cameraview.frame.Frame;
import com.sabine.cameraview.frame.FrameManager;
import com.sabine.cameraview.gesture.Gesture;
import com.sabine.cameraview.internal.WorkerHandler;
import com.sabine.cameraview.metering.MeteringRegions;
import com.sabine.cameraview.overlay.Overlay;
import com.sabine.cameraview.picture.PictureRecorder;
import com.sabine.cameraview.preview.CameraPreview;
import com.sabine.cameraview.size.Size;
import com.sabine.cameraview.video.VideoRecorder;

import java.io.File;
import java.io.FileDescriptor;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/**
 * PROCESS
 * Setting up the Camera is usually a 4 steps process:
 * 1. Setting up the Surface. Done by {@link CameraPreview}.
 * 2. Starting the camera. Done by us. See {@link #startEngine()}, {@link #onStartEngine()}.
 * 3. Binding the camera to the surface. Done by us. See {@link #startBind()},
 *    {@link #onStartBind()}
 * 4. Streaming the camera preview. Done by us. See {@link #startPreview()},
 *    {@link #onStartPreview()}
 *
 * The first two steps can actually happen at the same time, anyway
 * the order is not guaranteed, we just get a callback from the Preview when 1 happens.
 * So at the end of both step 1 and 2, the engine should check if both have
 * been performed and trigger the steps 3 and 4.
 *
 * STATE
 * We only expose generic {@link #start(int)} and {@link #stop(boolean)} calls to the outside.
 * The external users of this class are most likely interested in whether we have completed step 2
 * or not, since that tells us if we can act on the camera or not, rather than knowing about
 * steps 3 and 4.
 *
 * So in the {@link CameraEngine} notation,
 * - {@link #start(int)}: ASYNC - starts the engine (S2). When possible, at a later time,
 *                     S3 and S4 are also performed.
 * - {@link #stop(boolean)}: ASYNC - stops everything: undoes S4, then S3, then S2.
 * - {@link #restart()}: ASYNC - completes a stop then a start.
 * - {@link #destroy(boolean)}: SYNC - performs a {@link #stop(boolean)} that will go on no matter
 *                              what, without throwing. Makes the engine unusable and clears
 *                              resources.
 *
 * THREADING
 * Subclasses should always execute code on the thread given by {@link #mHandler}.
 * For convenience, all the setup and tear down methods are called on this engine thread:
 * {@link #onStartEngine()}, {@link #onStartBind()}, {@link #onStartPreview()} to setup and
 * {@link #onStopEngine()}, {@link #onStopBind()}, {@link #onStopPreview()} to tear down.
 * However, these methods are not forced to be synchronous and then can simply return a Google's
 * {@link Task}.
 *
 * Other setters are executed on the callers thread so subclasses should make sure they post
 * to the engine handler before acting on themselves.
 *
 *
 * ERROR HANDLING
 * THe {@link #mHandler} thread has a special {@link Thread.UncaughtExceptionHandler} that handles
 * exceptions and dispatches error to the callback (instead of crashing the app).
 * This lets subclasses run code safely and directly throw {@link CameraException}s when needed.
 *
 * For convenience, the two main method {@link #onStartEngine()} and {@link #onStopEngine()}
 * are already called on the engine thread, but they can still be asynchronous by returning a
 * Google's {@link com.google.android.gms.tasks.Task}.
 */
public abstract class CameraEngine implements
        CameraPreview.SurfaceCallback,
        PictureRecorder.PictureResultListener,
        VideoRecorder.VideoResultListener {

    public interface Callback {
        @NonNull
        Context getContext();
        void dispatchOnCameraOpened(@NonNull CameraOptions options);
        void dispatchOnCameraClosed();
        void onCameraPreviewStreamSizeChanged();
        void onShutter(boolean shouldPlaySound);
        void dispatchOnVideoTaken(@NonNull VideoResult.Stub stub);
        void dispatchOnVideoFps(final int fps);
        void dispatchOnPictureTaken(@NonNull PictureResult.Stub stub);
        void dispatchOnFocusStart(@Nullable Gesture trigger, @NonNull PointF where);
        void dispatchOnFocusEnd(@Nullable Gesture trigger, boolean success, @NonNull PointF where);
        void dispatchOnZoomChanged(final float newValue, @Nullable final PointF[] fingers);
        void dispatchOnExposureCorrectionChanged(float newValue, @NonNull float[] bounds,
                                                 @Nullable PointF[] fingers);
        void dispatchFrame(@NonNull Frame frame);
        void dispatchError(CameraException exception);
        void dispatchOnVideoRecordingStart(long timestamp);
        void dispatchOnVideoEncodeStart(int bitrate);
        void dispatchOnVideoRecordingEnd();
    }

    protected static final String TAG = CameraEngine.class.getSimpleName();
    protected static final CameraLogger LOG = CameraLogger.create(TAG);
    // If this is 2, this means we'll try to run destroy() twice.
    private static final int DESTROY_RETRIES = 2;

    private WorkerHandler mHandler;
    @VisibleForTesting
    Handler mCrashHandler;
    private boolean openCamera = true;

    enum CameraOpenState {
        CAMERAOPENSTATE_CLOSED, // have yet to attempt to open the camera (either at all, or since the camera was closed)
        CAMERAOPENSTATE_OPENING, // the camera is currently being opened
        CAMERAOPENSTATE_OPENED, // either the camera is open
        CAMERAOPENSTATE_CLOSING // the camera is currently being closed
    }
    protected CameraOpenState mCameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED;
    protected boolean mReOpenCamera = false;

    protected int mFirstCameraIndex = 0;    //多摄时默认第一路视频从mFirstCameraIndex开始
    private final Callback mCallback;
    private final CameraStateOrchestrator mOrchestrator
            = new CameraStateOrchestrator(new CameraOrchestrator.Callback() {
        @Override
        @NonNull
        public WorkerHandler getJobWorker(@NonNull String job) {
            return mHandler;
        }

        @Override
        public void handleJobException(@NonNull String job, @NonNull Exception exception) {
            handleException(exception, false);
        }
    });

    protected CameraEngine(@NonNull Callback callback) {
        mCallback = callback;
        mCrashHandler = new Handler(Looper.getMainLooper());
        recreateHandler(false);
    }

    public boolean isOpenCamera() {
        return openCamera;
    }

    public void setOpenCamera(boolean openCamera) {
        this.openCamera = openCamera;
    }

    @NonNull
    protected final Callback getCallback() {
        return mCallback;
    }

    @NonNull
    protected final CameraStateOrchestrator getOrchestrator() {
        return mOrchestrator;
    }

    //region Error handling

    /**
     * The base exception handler, which inspects the exception and
     * decides what to do.
     */
    private class CrashExceptionHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
            handleException(throwable, true);
        }
    }

    /**
     * A static exception handler used during destruction to avoid leaks,
     * since the default handler is not static and the thread might survive the engine.
     */
    private static class NoOpExceptionHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
            LOG.w("EXCEPTION:", "In the NoOpExceptionHandler, probably while destroying.",
                    "Thread:", thread, "Error:", throwable);
        }
    }

    /**
     * Handles exceptions coming from either runtime errors on the {@link #mHandler} code that is
     * not caught (using the {@link CrashExceptionHandler}), as might happen during standard
     * mHandler.post() operations that subclasses might do, OR for errors caught by tasks and
     * continuations that we launch here.
     *
     * In the first case, the thread is about to be terminated. In the second case,
     * we can actually keep using it.
     *
     * @param throwable the throwable
     * @param isUncaught true if coming from exception handler
     */
    private void handleException(@NonNull final Throwable throwable,
                                 final boolean isUncaught) {
        // 1. If this comes from the exception handler, the thread has crashed. Replace it.
        // Most actions are wrapped into Tasks so don't go here, but some callbacks do
        // (at least in Camera1, e.g. onError).
        if (isUncaught) {
            LOG.e("EXCEPTION:", "Handler thread is gone. Replacing.");
            recreateHandler(false);
        }

        // 2. Depending on the exception, we must destroy(false|true) to release resources, and
        // notify the outside, either with the callback or by crashing the app.
        LOG.e("EXCEPTION:", "Scheduling on the crash handler...");
        mCrashHandler.post(new Runnable() {
            @Override
            public void run() {
                if (throwable instanceof CameraException) {
                    CameraException exception = (CameraException) throwable;
                    if (exception.isUnrecoverable()) {
                        LOG.e("EXCEPTION:", "Got CameraException. " +
                                "Since it is unrecoverable, executing destroy(false).");
                        destroy(false);
                    }
                    LOG.e("EXCEPTION:", "Got CameraException. Dispatching to callback.");
                    mCallback.dispatchError(exception);
                } else {
                    LOG.e("EXCEPTION:", "Unexpected error! Executing destroy(true).");
                    destroy(true);
                    LOG.e("EXCEPTION:", "Unexpected error! Throwing.");
                    if (throwable instanceof RuntimeException) {
                        throw (RuntimeException) throwable;
                    } else {
                        throw new RuntimeException(throwable);
                    }
                }
            }
        });
    }

    /**
     * Recreates the handler, to ensure we use a fresh one from now on.
     * If we suspect that handler is currently stuck, the orchestrator should be reset
     * because it hosts a chain of tasks and the last one will never complete.
     * @param resetOrchestrator true to reset
     */
    private void recreateHandler(boolean resetOrchestrator) {
        if (mHandler != null) mHandler.destroy();
        mHandler = WorkerHandler.get("CameraViewEngine");
        mHandler.getThread().setUncaughtExceptionHandler(new CrashExceptionHandler());
        if (resetOrchestrator) mOrchestrator.reset();
    }

    //endregion

    //region State management

    @NonNull
    public final CameraState getState() {
        return mOrchestrator.getCurrentState();
    }

    @NonNull
    public final CameraState getTargetState() {
        return mOrchestrator.getTargetState();
    }

    public final boolean isChangingState() {
        return mOrchestrator.hasPendingStateChange();
    }

    /**
     * Calls {@link #stop(boolean)} and waits for it.
     * Not final due to mockito requirements.
     *
     * If unrecoverably is true, this also releases resources and the engine will not be in a
     * functional state after. If forever is false, this really is just a synchronous stop.
     *
     * NOTE: Should not be called on the orchestrator thread! This would cause deadlocks due to us
     * awaiting for {@link #stop(boolean)} to return.
     */
    public void destroy(boolean unrecoverably) {
        destroy(unrecoverably, 0);
    }

    private void destroy(boolean unrecoverably, int depth) {
        LOG.i("DESTROY:", "state:", getState(),
                "thread:", Thread.currentThread(),
                "depth:", depth,
                "unrecoverably:", unrecoverably);
        if (unrecoverably) {
            // Prevent CameraEngine leaks. Don't set to null, or exceptions
            // inside the standard stop() method might crash the main thread.
            mHandler.getThread().setUncaughtExceptionHandler(new NoOpExceptionHandler());
        }
        // Cannot use Tasks.await() because we might be on the UI thread.
        final CountDownLatch latch = new CountDownLatch(1);
        stop(true).addOnCompleteListener(
                mHandler.getExecutor(),
                new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        latch.countDown();
                    }
                });
        try {
            boolean success = latch.await(6, TimeUnit.SECONDS);
            if (!success) {
                // This thread is likely stuck. The reason might be deadlock issues in the internal
                // camera implementation, at least in emulators: see Camera1Engine and Camera2Engine
                // onStopEngine() implementation and comments.
                LOG.e("DESTROY: Could not destroy synchronously after 6 seconds.",
                        "Current thread:", Thread.currentThread(),
                        "Handler thread:", mHandler.getThread());
                depth++;
                if (depth < DESTROY_RETRIES) {
                    recreateHandler(true);
                    LOG.e("DESTROY: Trying again on thread:", mHandler.getThread());
                    destroy(unrecoverably, depth);
                } else {
                    LOG.w("DESTROY: Giving up because DESTROY_RETRIES was reached.");
                }
            }
        } catch (InterruptedException ignore) {}
    }

    @SuppressWarnings("WeakerAccess")
    public void restart() {
        LOG.i("RESTART:", "scheduled. State:", getState());
        stop(false);
        start(mFirstCameraIndex);
    }

    @NonNull
    public Task<Void> start(int firstCameraIndex) {
        LOG.i("START:", "scheduled. State:", getState());
        if (mCameraOpenState == CameraOpenState.CAMERAOPENSTATE_CLOSING) {
            mReOpenCamera = true;
            return Tasks.forCanceled();
        }

        mFirstCameraIndex = firstCameraIndex;
        Task<Void> engine = startEngine();
        startBind();
        startPreview();
        return engine;
    }

    @NonNull
    public Task<Void> stop(final boolean swallowExceptions) {
        LOG.i("STOP:", "scheduled. State:", getState());
        mReOpenCamera = false;
        if (mOrchestrator.getCurrentState() == CameraState.OFF)
            return Tasks.forCanceled();

        mCameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSING;

        stopPreview(swallowExceptions);
        stopBind(swallowExceptions);
        return stopEngine(swallowExceptions);
    }

    @SuppressWarnings({"WeakerAccess", "UnusedReturnValue"})
    @NonNull
    protected Task<Void> restartBind() {
        LOG.i("RESTART BIND:", "scheduled. State:", getState());
        stopPreview(false);
        stopBind(false);
        startBind();
        return startPreview();
    }

    @SuppressWarnings({"WeakerAccess", "UnusedReturnValue"})
    @NonNull
    protected Task<Void> restartPreview() {
        LOG.i("RESTART PREVIEW:", "scheduled. State:", getState());
        stopPreview(false);
        return startPreview();
    }

    //endregion

    //region Start & Stop the engine

    @NonNull
    @EngineThread
    private Task<Void> startEngine() {
        return mOrchestrator.scheduleStateChange(CameraState.OFF, CameraState.ENGINE,
                true,
                new Callable<Task<CameraOptions>>() {
            @Override
            public Task<CameraOptions> call() {
                if (mCameraOpenState == CameraOpenState.CAMERAOPENSTATE_CLOSING)
                    return Tasks.forCanceled();

                if (!collectCameraInfo(getFacing())) {
                    LOG.e("onStartEngine:", "No camera available for facing", getFacing());
                    throw new CameraException(CameraException.REASON_NO_CAMERA);
                }
                return onStartEngine();
            }
        }).onSuccessTask(new SuccessContinuation<CameraOptions, Void>() {
            @NonNull
            @Override
            public Task<Void> then(@Nullable CameraOptions cameraOptions) {
                // Put this on the outer task so we're sure it's called after getState() is changed.
                // This was breaking some tests on rare occasions.
                if (cameraOptions == null) throw new RuntimeException("Null options!");
                mCallback.dispatchOnCameraOpened(cameraOptions);
                return Tasks.forResult(null);
            }
        });
    }

    @NonNull
    @EngineThread
    private Task<Void> stopEngine(boolean swallowExceptions) {
        return mOrchestrator.scheduleStateChange(CameraState.ENGINE, CameraState.OFF,
                !swallowExceptions,
                new Callable<Task<Void>>() {
            @Override
            public Task<Void> call() {
                return onStopEngine();
            }
        }).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                // Put this on the outer task so we're sure it's called after getState() is OFF.
                // This was breaking some tests on rare occasions.
                mCallback.dispatchOnCameraClosed();
                mCameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED;
                if (mReOpenCamera) {
                    mReOpenCamera = false;
                    start(mFirstCameraIndex);
                }
            }
        }).addOnCanceledListener(new OnCanceledListener() {
            @Override
            public void onCanceled() {
                mCameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED;
                if (mReOpenCamera) {
                    mReOpenCamera = false;
                    start(mFirstCameraIndex);
                }
            }
        }).addOnCompleteListener(new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull Task<Void> task) {
                mCameraOpenState = CameraOpenState.CAMERAOPENSTATE_CLOSED;
                if (mReOpenCamera) {
                    mReOpenCamera = false;
                    start(mFirstCameraIndex);
                }
            }
        });
    }

    /**
     * Camera is about to be opened. Implementors should look into available cameras
     * and see if anyone matches the given {@link Facing value}.
     *
     * If so, implementors should set {@link Angles#setSensorOffset(Facing, int)}
     * and any other information (like camera ID) needed to start the engine.
     *
     * @param facings the facing value
     * @return true if we have one
     */
    @EngineThread
    protected abstract boolean collectCameraInfo(@NonNull Facing... facings);

    /**
     * Starts the engine.
     * @return a task
     */
    @NonNull
    @EngineThread
    protected abstract Task<CameraOptions> onStartEngine();

    /**
     * Stops the engine.
     * Stop events should generally not throw exceptions. We
     * want to release resources either way.
     * @return a task
     */
    @NonNull
    @EngineThread
    protected abstract Task<Void> onStopEngine();

    //endregion

    //region Start & Stop binding

    @NonNull
    @EngineThread
    private Task<Void> startBind() {
//        if (!openCamera) {
//            return Tasks.forCanceled();
//        }
        return mOrchestrator.scheduleStateChange(CameraState.ENGINE, CameraState.BIND,
                true,
                new Callable<Task<Void>>() {
            @Override
            public Task<Void> call() {
                if (mCameraOpenState == CameraOpenState.CAMERAOPENSTATE_CLOSING)
                    return Tasks.forCanceled();

                if (getPreview() != null && getPreview().hasSurface()) {
                    return onStartBind();
                } else {
                    return Tasks.forCanceled();
                }
            }
        });
    }

    @SuppressWarnings("UnusedReturnValue")
    @NonNull
    @EngineThread
    private Task<Void> stopBind(boolean swallowExceptions) {
        return mOrchestrator.scheduleStateChange(CameraState.BIND, CameraState.ENGINE,
                !swallowExceptions,
                new Callable<Task<Void>>() {
            @Override
            public Task<Void> call() {
                return onStopBind();
            }
        });
    }

    /**
     * Starts the binding process.
     * @return a task
     */
    @NonNull
    @EngineThread
    protected abstract Task<Void> onStartBind();

    /**
     * Stops the binding process.
     * Stop events should generally not throw exceptions. We
     * want to release resources either way.
     * @return a task
     */
    @NonNull
    @EngineThread
    protected abstract Task<Void> onStopBind();

    //endregion

    //region Start & Stop preview

    @NonNull
    @EngineThread
    private Task<Void> startPreview() {
//        if (!openCamera) {
//            return Tasks.forCanceled();
//        }
        return mOrchestrator.scheduleStateChange(CameraState.BIND, CameraState.PREVIEW,
                true,
                new Callable<Task<Void>>() {
            @Override
            public Task<Void> call() {
                if (mCameraOpenState == CameraOpenState.CAMERAOPENSTATE_CLOSING)
                    return Tasks.forCanceled();

                return onStartPreview();
            }
        }).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                // Put this on the outer task so we're sure it's called after getState() is OFF.
                // This was breaking some tests on rare occasions.
//                mCameraOpenState = CameraOpenState.CAMERAOPENSTATE_OPENED;
                mReOpenCamera = false;
//                LOG.e("------------camera start preview");
            }
        });
    }

    @SuppressWarnings("UnusedReturnValue")
    @NonNull
    @EngineThread
    private Task<Void> stopPreview(boolean swallowExceptions) {
        return mOrchestrator.scheduleStateChange(CameraState.PREVIEW, CameraState.BIND,
                !swallowExceptions,
                new Callable<Task<Void>>() {
            @Override
            public Task<Void> call() {
                return onStopPreview();
            }
        });
    }

    /**
     * Starts the preview streaming.
     * @return a task
     */
    @NonNull
    @EngineThread
    protected abstract Task<Void> onStartPreview();

    /**
     * Stops the preview streaming.
     * Stop events should generally not throw exceptions. We
     * want to release resources either way.
     * @return a task
     */
    @NonNull
    @EngineThread
    protected abstract Task<Void> onStopPreview();

    //endregion

    //region Surface callbacks

    /**
     * The surface is now available, which means that step 1 has completed.
     * If we have also completed step 2, go on with binding and streaming.
     */
    @SuppressWarnings("ConstantConditions")
    @Override
    public final void onSurfaceAvailable() {
        LOG.i("onSurfaceAvailable:", "Size is", getPreview().getSurfaceSize());
        startBind();
        startPreview();
    }

    @Override
    public final void onSurfaceDestroyed() {
        LOG.i("onSurfaceDestroyed");
        stopPreview(false);
        stopBind(false);
    }

    public enum FACE_DETECT_MODE {
        unKnown,
        support,
        unSupport
    }

    public static class Face {
        public final int score;
        /* The has values from [-1000,-1000] (for top-left) to [1000,1000] (for bottom-right) for whatever is
         * the current field of view (i.e., taking zoom into account).
         */
        public final Rect rect;

        Face(int score, Rect rect) {
            this.score = score;
            this.rect = rect;
        }
    }

    public interface FaceDetectionListener {
        void onFaceDetection(Face[] faces, boolean isChanged);
    }


    public void setFirstCameraIndex(int firstCameraIndex) {
        mFirstCameraIndex = firstCameraIndex;
    }

    public int getFirstCameraIndex() {
        return mFirstCameraIndex;
    }

    //endregion

    //region Abstract getters

    @NonNull
    public abstract Angles getAngles();

    @NonNull
    public abstract FrameManager getFrameManager();

    @Nullable
    public abstract CameraOptions getCameraOptions();

    @Nullable
    public abstract CameraOptions getCamera2Options();

    @Nullable
    public abstract Size getPictureSize(@NonNull Reference reference);

    @Nullable
    public abstract Size getVideoSize(@NonNull Reference reference);

    @Nullable
    public abstract Size getPreviewStreamSize(@NonNull Reference reference);

    @Nullable
    public abstract Size getUncroppedSnapshotSize(@NonNull Reference reference);

    //endregion

    //region Abstract APIs

    public abstract void setPreview(@NonNull CameraPreview cameraPreview);
    @Nullable public abstract CameraPreview getPreview();

    public abstract void setOverlay(@Nullable Overlay overlay);
    @Nullable public abstract Overlay getOverlay();

    public abstract void setPreviewStreamSize(@Nullable Size previewSize);
    @Nullable public abstract Size getPreviewStreamSize();

    public abstract void setPictureSize(@NonNull Size pictureSize);
    @NonNull public abstract Size getPictureSize();

    public abstract void setVideoSize(@NonNull Size videoSize, boolean isRestart);
    @NonNull public abstract Size getVideoSize();

    public abstract void setVideoCodec(@NonNull VideoCodec codec);
    @NonNull public abstract VideoCodec getVideoCodec();

    public abstract void setVideoBitRate(int videoBitRate);
    public abstract int getVideoBitRate();

    public abstract void setAudioBitRate(int audioBitRate);
    public abstract int getAudioBitRate();

    public abstract void setSnapshotMaxWidth(int maxWidth);
    public abstract int getSnapshotMaxWidth();

    public abstract void setSnapshotMaxHeight(int maxHeight);
    public abstract int getSnapshotMaxHeight();

    public abstract void setFrameProcessingMaxWidth(int maxWidth);
    public abstract int getFrameProcessingMaxWidth();

    public abstract void setFrameProcessingMaxHeight(int maxHeight);
    public abstract int getFrameProcessingMaxHeight();

    public abstract void setFrameProcessingFormat(int format);
    public abstract int getFrameProcessingFormat();

    public abstract void setFrameProcessingPoolSize(int poolSize);
    public abstract int getFrameProcessingPoolSize();

    public abstract void setAutoFocusResetDelay(long delayMillis);
    public abstract long getAutoFocusResetDelay();

    public abstract void setFacing(final @NonNull Facing[] facing, int firstCameraIndex);
    @NonNull public abstract Facing[] getFacing();

    public abstract boolean dual();

    public abstract void setAudio(@NonNull Audio audio);
    @NonNull public abstract Audio getAudio();

    public abstract void setMode(@NonNull Mode mode);
    @NonNull public abstract Mode getMode();

    public abstract void setZoom(float zoom, @Nullable PointF[] points, boolean notify);
    public abstract float getZoomValue();

    public abstract void setExposureCorrection(float EVvalue, @NonNull float[] bounds,
                                               @Nullable PointF[] points, boolean notify);
    public abstract float getExposureCorrectionValue();

    public abstract void setExposureCorrection(float EVvalue, @NonNull float[] bounds,
                                                     @Nullable PointF[] points, boolean notify, int index);
    public abstract float getExposureCorrectionValue(int index);

    // 是否锁定曝光
    public abstract boolean isAutoExposure(int index);

    // 是否锁定曝光
    public abstract boolean isAutoExposure();

    public abstract void setFlash(@NonNull Flash flash);
    @NonNull public abstract Flash getFlash();

    public abstract void setWhiteBalance(@NonNull WhiteBalance whiteBalance);
    @NonNull public abstract WhiteBalance getWhiteBalance();

    public abstract void setHdr(@NonNull Hdr hdr);
    @NonNull public abstract Hdr getHdr();

    public abstract void setLocation(@Nullable Location location);
    @Nullable public abstract Location getLocation();

    public abstract void setPictureFormat(@NonNull PictureFormat pictureFormat);
    @NonNull public abstract PictureFormat getPictureFormat();

    public abstract void setPreviewFrameRateExact(boolean previewFrameRateExact);
    public abstract boolean getPreviewFrameRateExact();
    public abstract void setPreviewFrameRate(float previewFrameRate);
    public abstract boolean supportHighSpeed();
    public abstract List<Integer> getSupportPreviewFramerate();
    public abstract boolean supportDuoCamera();
    public abstract boolean supportAntishake();
    public abstract void setAntishake(boolean antishakeOn);
    public abstract float getPreviewFrameRate();

    public abstract void setHasFrameProcessors(boolean hasFrameProcessors);
    public abstract boolean hasFrameProcessors();

    public abstract void setPictureMetering(boolean enable);
    public abstract boolean getPictureMetering();

    public abstract void setPictureSnapshotMetering(boolean enable);
    public abstract boolean getPictureSnapshotMetering();

    public abstract void startAutoFocus(@Nullable Gesture gesture,
                                        @NonNull MeteringRegions regions,
                                        @NonNull PointF legacyPoint);
    public abstract void cancelAutoFocus();
    public abstract boolean supportsAutoFocus();

    public abstract void setPlaySounds(boolean playSounds);

    public abstract boolean isTakingPicture();
    public abstract void takePicture(@NonNull PictureResult.Stub stub);
    public abstract void takePictureSnapshot(final @NonNull PictureResult.Stub stub);

    public abstract boolean isTakingVideo();
    public abstract void takeVideo(@NonNull VideoResult.Stub stub,
                                   @Nullable File file,
                                   @Nullable FileDescriptor fileDescriptor);
    public abstract void takeVideoSnapshot(@NonNull VideoResult.Stub stub, @NonNull File file, Size size, boolean isFlip, int rotation);
    public abstract void stopVideo(boolean isCameraShutdown);

    public abstract long getTimeStamp();

    // 向编码器内传入音频数据进行合成
    public abstract void putAudioPcm(byte[] pcm, int length, boolean isEndOfStream);

    // 设置横竖屏
    public abstract void setOrientation(int orientation);

    // 设置当前选择摄像头
    public abstract void selectOpenedCamera(PointF legacyPoint);

    // 获取当前是哪个摄像头
    public abstract int getCurrentCameraIndex();

    // 根据坐标获取是哪个摄像头
    public abstract int getCurrentCameraIndex(PointF legacyPoint);

    // 设置当前旋转角度
    public abstract void setDrawRotation(int rotation);

    public abstract boolean changeFaceDetection(boolean useFaceDetection);
    public abstract void setFaceDetectionListener(final CameraEngine.FaceDetectionListener listener);
    public abstract FACE_DETECT_MODE supportFaceDetection();
    public abstract int getCameraOrientation();
    //endregion
}
