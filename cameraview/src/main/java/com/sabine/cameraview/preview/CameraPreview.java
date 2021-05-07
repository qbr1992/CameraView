package com.sabine.cameraview.preview;

import android.content.Context;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.engine.CameraEngine;
import com.sabine.cameraview.engine.offset.Reference;
import com.sabine.cameraview.filter.Filter;
import com.sabine.cameraview.size.Size;

/**
 * A CameraPreview takes in input stream from the {@link CameraEngine}, and streams it
 * into an output surface that belongs to the view hierarchy.
 *
 * @param <T> the type of view which hosts the content surface
 * @param <Output> the type of output, either {@link android.view.SurfaceHolder}
 *                 or {@link android.graphics.SurfaceTexture}
 */
public abstract class CameraPreview<T extends View, Output> {

    protected final static CameraLogger LOG
            = CameraLogger.create(CameraPreview.class.getSimpleName());

    public abstract void setBeauty(float parameterValue1, float parameterValue2);

    /**
     * This is used to notify CameraEngine to recompute its camera Preview size.
     * After that, CameraView will need a new layout pass to adapt to the Preview size.
     */
    public interface SurfaceCallback {

        /**
         * Called when the surface is available.
         */
        void onSurfaceAvailable();

        /**
         * Called when the surface has changed.
         */
        void onSurfaceChanged();

        /**
         * Called when the surface was destroyed.
         */
        void onSurfaceDestroyed();
    }

    protected interface CropCallback {
        void onCrop();
    }

    public final static int MAX_INPUT_SURFACETEXTURE = 2;

    @VisibleForTesting CropCallback mCropCallback;
    private SurfaceCallback mSurfaceCallback;
    private T mView;
    @SuppressWarnings("WeakerAccess")
    protected boolean mCropping;

    // These are the surface dimensions in REF_VIEW.
    @SuppressWarnings("WeakerAccess")
    protected int mOutputSurfaceWidth;
    @SuppressWarnings("WeakerAccess")
    protected int mOutputSurfaceHeight;

    // These are the preview stream dimensions, in REF_VIEW.
    @SuppressWarnings("WeakerAccess")
    protected int mInputStreamWidth = 0;
    @SuppressWarnings("WeakerAccess")
    protected int mInputStreamHeight = 0;

    //渲染器宽高，如果mInputStreamWidth｜mInputStreamHeight大于mOutputSurfaceWidth｜mOutputSurfaceHeight，mRendererWidth(Height)=mInputStreamWidth(Height)，否则mRendererWidth(Height)=mOutputSurfaceWidth(Height)
    protected int mRendererWidth = 0;
    protected int mRendererHeight = 0;

    // The rotation, if any, to be applied when drawing.
    @SuppressWarnings("WeakerAccess")
    protected int mDrawRotation;

    // 判断是否打开多摄开关标志
    protected boolean mDualCamera;
    protected boolean mFrontIsFirst = false;    //第一个SurfaceTexture是否是前置摄像头

    public enum DualInputTextureMode {
        UAD_MODE,   //上下布局
        PIP_MODE,   //画中画布局
    }
    protected DualInputTextureMode mDualInputTextureMode = DualInputTextureMode.UAD_MODE;

    /**
     * Creates a new preview.
     * @param context a context
     * @param parent where to inflate our view
     */
    public CameraPreview(@NonNull Context context, @NonNull ViewGroup parent) {
        mView = onCreateView(context, parent);
    }

    /**
     * Sets a callback to be notified of surface events (creation, change, destruction)
     * @param callback a callback
     */
    public void setSurfaceCallback(@Nullable SurfaceCallback callback) {
        if (hasSurface() && mSurfaceCallback != null) {
            mSurfaceCallback.onSurfaceDestroyed();
        }
        mSurfaceCallback = callback;
        if (hasSurface() && mSurfaceCallback != null) {
            mSurfaceCallback.onSurfaceAvailable();
        }
    }

    /**
     * Called at creation time. Implementors should inflate the hierarchy into the
     * parent ViewGroup, and return the View that actually hosts the surface.
     *
     * @param context a context
     * @param parent where to inflate
     * @return the view hosting the Surface
     */
    @NonNull
    protected abstract T onCreateView(@NonNull Context context, @NonNull ViewGroup parent);

    /**
     * Returns the view hosting the Surface.
     * @return the view
     */
    @NonNull
    public final T getView() {
        return mView;
    }

    /**
     * For testing purposes, should return the root view that was inflated into the
     * parent during {@link #onCreateView(Context, ViewGroup)}.
     * @return the root view
     */
    @SuppressWarnings("unused")
    @NonNull
    public abstract View getRootView();

    /**
     * Returns the output surface object (for example a SurfaceHolder
     * or a SurfaceTexture).
     * @param index a SurfaceTexture index, start from 1
     * @return the surface object
     */
    @NonNull
    public abstract Output getOutput(int index);

    /**
     * remove the input surface object (for example a SurfaceHolder
     * or a SurfaceTexture).
     * @param index a SurfaceTexture index, start from 0
     */
    public abstract void removeInputSurfaceTexture(int index);

    /**
     * return the input surface(for example a SurfaceHolder
     * or a SurfaceTexture) count .
     */
    public abstract int getInputSurfaceTextureCount();

    /**
     * Returns the type of the output returned by {@link #getOutput(int)}.
     * @return the output type
     */
    @NonNull
    public abstract Class<Output> getOutputClass();

    /**
     * Called to notify the preview of the input stream size. The width and height must be
     * rotated before calling this, if needed, to be consistent with the VIEW reference.
     * @param width width of the preview stream, in view coordinates
     * @param height height of the preview stream, in view coordinates
     */
    public void setStreamSize(int width, int height) {
        LOG.i("setStreamSize:", "desiredW=", width, "desiredH=", height);
        mInputStreamWidth = width;
        mInputStreamHeight = height;
        computeRendererSize();

        if (mInputStreamWidth > 0 && mInputStreamHeight > 0) {
            crop(mCropCallback);
        }
    }

    /**
     * Returns the current input stream size, in view coordinates.
     * @return the current input stream size
     */
    @VisibleForTesting
    @NonNull
    final Size getStreamSize() {
        return new Size(mInputStreamWidth, mInputStreamHeight);
    }

    /**
     * Returns the current output surface size, in view coordinates.
     * @return the current output surface size.
     */
    @NonNull
    public final Size getSurfaceSize() {
        return new Size(mOutputSurfaceWidth, mOutputSurfaceHeight);
    }

    /**
     * Whether we have a valid surface already.
     * @return whether we have a surface
     */
    public final boolean hasSurface() {
        return mOutputSurfaceWidth > 0 && mOutputSurfaceHeight > 0;
    }

    /**
     * 设置Two Camera显示模式
     * 分为上下和画中画两种显示模式，默认上下显示.
     * @param dualInputTextureMode 设置显示模式
     */
    public void setDualInputTextureMode(DualInputTextureMode dualInputTextureMode) {
        mDualInputTextureMode = dualInputTextureMode;
    }

    /**
     * 获取设置的Two Camera显示模式.
     * @return 返回显示模式
     */
    public DualInputTextureMode getDualInputTextureMode() {
        return mDualInputTextureMode;
    }

    public void setPreviewAspectRatio(float aspectRatio) {

    }

    public void setFrontIsFirst(boolean frontIsFirst) {
        mFrontIsFirst = frontIsFirst;
    }

    public boolean frontIsFirst() {
        return mFrontIsFirst;
    }

    public abstract boolean getFrontIsFirst();

    /**
     * Subclasses can call this to notify that the surface is available.
     * @param width surface width
     * @param height surface height
     */
    @SuppressWarnings("WeakerAccess")
    protected final void dispatchOnSurfaceAvailable(int width, int height) {
        LOG.i("dispatchOnSurfaceAvailable:", "w=", width, "h=", height);
        mOutputSurfaceWidth = width;
        mOutputSurfaceHeight = height;
        if (mOutputSurfaceWidth > 0 && mOutputSurfaceHeight > 0) {
            crop(mCropCallback);
        }
        if (mSurfaceCallback != null) {
            mSurfaceCallback.onSurfaceAvailable();
        }
    }

    /**
     * Subclasses can call this to notify that the surface has changed.
     * @param width surface width
     * @param height surface height
     */
    @SuppressWarnings("WeakerAccess")
    protected final void dispatchOnSurfaceSizeChanged(int width, int height) {
        LOG.e("dispatchOnSurfaceSizeChanged:", "w=", width, "h=", height);
        if (width != mOutputSurfaceWidth || height != mOutputSurfaceHeight) {
            mOutputSurfaceWidth = width;
            mOutputSurfaceHeight = height;
            computeRendererSize();
            if (width > 0 && height > 0) {
                crop(mCropCallback);
            }
            if (mSurfaceCallback != null) {
                mSurfaceCallback.onSurfaceChanged();
            }
        }
    }

    /**
     * Subclasses can call this to notify that the surface has been destroyed.
     */
    @SuppressWarnings("WeakerAccess")
    protected final void dispatchOnSurfaceDestroyed() {
        mOutputSurfaceWidth = 0;
        mOutputSurfaceHeight = 0;
        if (mSurfaceCallback != null) {
            mSurfaceCallback.onSurfaceDestroyed();
        }
    }

    /**
     * Called by the hosting {@link com.sabine.cameraview.CameraView},
     * this is a lifecycle event.
     */
    public void onResume() {}

    /**
     * Called by the hosting {@link com.sabine.cameraview.CameraView},
     * this is a lifecycle event.
     */
    public void onPause() {}

    /**
     * Called by the hosting {@link com.sabine.cameraview.CameraView},
     * this is a lifecycle event.
     */
    @CallSuper
    public void onDestroy() {
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            onDestroyView();
        } else {
            // Do this on the UI thread and wait.
            Handler ui = new Handler(Looper.getMainLooper());
            final TaskCompletionSource<Void> task = new TaskCompletionSource<>();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    onDestroyView();
                    task.setResult(null);
                }
            });
            try { Tasks.await(task.getTask()); } catch (Exception ignore) {}
        }
    }

    /**
     * At this point we undo the work that was done during
     * {@link #onCreateView(Context, ViewGroup)}, which basically means removing the root view
     * from the hierarchy.
     */
    @SuppressWarnings("WeakerAccess")
    @UiThread
    protected void onDestroyView() {
        View root = getRootView();
        ViewParent parent = root.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(root);
        }
    }

    /**
     * Here we must crop the visible part by applying a scale greater than 1 to one of our
     * dimensions. This way our internal aspect ratio (mOutputSurfaceWidth / mOutputSurfaceHeight)
     * will match the preview size aspect ratio (mInputStreamWidth / mInputStreamHeight).
     *
     * There might still be some absolute difference (e.g. same ratio but bigger / smaller).
     * However that should be already managed by the framework.
     *
     * @param callback the callback
     */
    protected void crop(@Nullable CropCallback callback) {
        // The base implementation does not support cropping.
        if (callback != null) callback.onCrop();
    }

    /**
     * Whether this preview implementation supports cropping.
     * The base implementation does not, but it is strongly recommended to do so.
     * @return true if cropping is supported
     */
    public boolean supportsCropping() {
        return false;
    }

    /**
     * Whether we are currently cropping the output.
     * If false, this means that the output image will match the visible bounds.
     * @return true if cropping
     */
    public boolean isCropping() {
        return mCropping;
    }


    /**
     * Should be called after {@link #setStreamSize(int, int)}!
     *
     * Sets the rotation, if any, to be applied when drawing.
     * Sometimes we don't need this:
     * - In Camera1, the buffer producer sets our Surface size and rotates it based on the value
     *   that we pass to {@link android.hardware.Camera.Parameters#setDisplayOrientation(int)},
     *   so the stream that comes in is already rotated (if we apply SurfaceTexture transform).
     * - In Camera2, for {@link android.view.SurfaceView} based previews, apparently it just works
     *   out of the box. The producer might be doing something similar.
     *
     * But in all the other Camera2 cases, we need to apply this rotation when drawing the surface.
     * Seems that Camera1 can correctly rotate the stream/transform to {@link Reference#VIEW},
     * while Camera2, that does not have any rotation API, will only rotate to {@link Reference#BASE}.
     * That's why in Camera2 this angle is set as the offset between BASE and VIEW.
     *
     * @param drawRotation the rotation in degrees
     */
    public void setDrawRotation(int drawRotation) {
        mDrawRotation = drawRotation;
    }

    public void setDualCamera(boolean isDualCamera) {
        mDualCamera = isDualCamera;
    }

    private void computeRendererSize() {
        if (mInputStreamWidth>mOutputSurfaceWidth || mInputStreamHeight>mOutputSurfaceHeight) {
            mRendererWidth = mInputStreamWidth;
            mRendererHeight = mInputStreamHeight;
        } else {
            mRendererWidth = mOutputSurfaceWidth;
            mRendererHeight = mOutputSurfaceHeight;
        }
    }
    public abstract void switchInputTexture();
    public abstract RectF getSurfaceLayoutRect(int index);
    public abstract void resetOutputTextureDrawer();
    public abstract void startPreview();
    public abstract void setSensorTimestampOffset(long offset);

    /**
     * Adds a {@link RendererFpsCallback} to receive renderer fps events.
     * @param callback a callback
     */
    public abstract void addRendererFpsCallback(@NonNull final RendererFpsCallback callback);
}
