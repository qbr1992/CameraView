package com.sabine.cameraview.picture;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.os.Build;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.otaliastudios.opengl.surface.EglSurface;
import com.otaliastudios.opengl.texture.GlTexture;
import com.sabine.cameraview.PictureResult;
import com.sabine.cameraview.engine.CameraEngine;
import com.sabine.cameraview.engine.offset.Reference;
import com.sabine.cameraview.filter.Filter;
import com.sabine.cameraview.internal.GlUtils;
import com.sabine.cameraview.internal.WorkerHandler;
import com.sabine.cameraview.overlay.Overlay;
import com.sabine.cameraview.overlay.OverlayDrawer;
import com.sabine.cameraview.preview.RendererCameraPreview;
import com.sabine.cameraview.preview.RendererFrameCallback;
import com.sabine.cameraview.preview.RendererThread;
import com.sabine.cameraview.size.AspectRatio;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * API 19.
 * Records a picture snapshots from the {@link RendererCameraPreview}. It works as follows:
 *
 * - We register a one time {@link RendererFrameCallback} on the preview
 * - We get the textureId and the frame callback on the {@link RendererThread}
 * - [Optional: we construct another textureId for overlays]
 * - We take a handle of the EGL context from the {@link RendererThread}
 * - We move to another thread, and create a new EGL surface for that EGL context.
 * - We make this new surface current, and re-draw the textureId on it
 * - [Optional: fill the overlayTextureId and draw it on the same surface]
 * - We use glReadPixels (through {@link EglSurface#toByteArray(Bitmap.CompressFormat)})
 *   and save to file.
 *
 * We create a new EGL surface and redraw the frame because:
 * 1. We want to go off the renderer thread as soon as possible
 * 2. We have overlays to be drawn - we don't want to draw them on the preview surface,
 *    not even for a frame.
 */
public class SnapshotGlPictureRecorder extends SnapshotPictureRecorder {

    private CameraEngine mEngine;
    private RendererCameraPreview mPreview;
    private AspectRatio mOutputRatio;

    private Overlay mOverlay;
    private boolean mHasOverlay;
    private OverlayDrawer mOverlayDrawer;

    private ByteArrayOutputStream bos;

    public SnapshotGlPictureRecorder(
            @NonNull PictureResult.Stub stub,
            @NonNull CameraEngine engine,
            @Nullable PictureResultListener listener,
            @NonNull RendererCameraPreview preview,
            @NonNull AspectRatio outputRatio,
            @Nullable Overlay overlay) {
        super(stub, listener);
        mEngine = engine;
        mPreview = preview;
        mOutputRatio = outputRatio;
        mOverlay = overlay;
        mHasOverlay = mOverlay != null && mOverlay.drawsOn(Overlay.Target.PICTURE_SNAPSHOT);
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void take() {
        mPreview.addRendererFrameCallback(new RendererFrameCallback() {

            @RendererThread
            public void onRendererTextureCreated(int textureId, boolean frontIsFirst) {
                SnapshotGlPictureRecorder.this.onRendererTextureCreated(textureId, frontIsFirst);
            }

            @RendererThread
            @Override
            public void onRendererFilterChanged(@NonNull Filter filter, float filterLevel) {
                SnapshotGlPictureRecorder.this.onRendererFilterChanged(filter, filterLevel);
            }

            @Override
            public void onRendererInputTextureIdChanged(@NonNull int textureId) {

            }

            @Override
            public void onRendererSwitchInputTexture() {

            }

            @RendererThread
            @Override
            public void onRendererFrame(@NonNull SurfaceTexture surfaceTexture, long timestampNanos,
                                        int rotation, float scaleX, float scaleY, GlTexture inputTextureId) {
                mPreview.removeRendererFrameCallback(this);
                SnapshotGlPictureRecorder.this.onRendererFrame(surfaceTexture,
                        rotation, scaleX, scaleY);
            }
        });
    }

    @SuppressWarnings("WeakerAccess")
    @RendererThread
    @TargetApi(Build.VERSION_CODES.KITKAT)
    protected void onRendererTextureCreated(int textureId, boolean frontIsFirst) {
        if (mHasOverlay) {
            mOverlayDrawer = new OverlayDrawer(mOverlay, mResult.size);
        }
    }

    @SuppressWarnings("WeakerAccess")
    @RendererThread
    @TargetApi(Build.VERSION_CODES.KITKAT)
    protected void onRendererFilterChanged(@NonNull Filter filter, float filterLevel) {
    }

    @SuppressWarnings("WeakerAccess")
    @RendererThread
    @TargetApi(Build.VERSION_CODES.KITKAT)
    protected void onRendererFrame(@SuppressWarnings("unused") @NonNull final SurfaceTexture surfaceTexture,
                                 final int rotation,
                                 final float scaleX,
                                 final float scaleY) {
        // Get egl context from the RendererThread, which is the one in which we have created
        // the textureId and the overlayTextureId, managed by the GlSurfaceView.
        // Next operations can then be performed on different threads using this handle.
        final EGLContext eglContext = EGL14.eglGetCurrentContext();

        int width = mResult.size.getWidth();
        int height = mResult.size.getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                buf);
        GlUtils.checkError("glReadPixels");
        buf.rewind();

        bos = new ByteArrayOutputStream(buf.array().length);
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buf);

        float flipScale = -1.0f;
        android.graphics.Matrix mx = new android.graphics.Matrix();
        mx.setScale(1.0f, flipScale);
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), mx, true);

        bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos);
        bmp.recycle();

        WorkerHandler.execute(new Runnable() {
            @Override
            public void run() {
                takeFrame(surfaceTexture, rotation, scaleX, scaleY, eglContext);

            }
        });
    }

    /**
     * The tricky part here is the EGL surface creation.
     *
     * We don't have a real output window for the EGL surface - we will use glReadPixels()
     * and never call swapBuffers(), so what we draw is never published.
     *
     * 1. One option is to use a pbuffer EGL surface. This works, we just have to pass
     *    the correct width and height. However, it is significantly slower than the current
     *    solution.
     *
     * 2. Another option is to create the EGL surface out of a ImageReader.getSurface()
     *    and use the reader to create a JPEG. In this case, we would have to publish
     *    the frame with swapBuffers(). However, currently ImageReader does not support
     *    all formats, it's risky. This is an example error that we get:
     *    "RGBA override BLOB format buffer should have height == width"
     *
     * The third option, which we are using, is to create the EGL surface using whatever
     * {@link Surface} or {@link SurfaceTexture} we have at hand. Since we never call
     * swapBuffers(), the frame will not actually be rendered. This is the fastest.
     *
     * @param scaleX frame scale x in {@link Reference#VIEW}
     * @param scaleY frame scale y in {@link Reference#VIEW}
     */
    @SuppressWarnings("WeakerAccess")
    @WorkerThread
    @TargetApi(Build.VERSION_CODES.KITKAT)
    protected void takeFrame(@NonNull SurfaceTexture surfaceTexture,
                             int rotation,
                             float scaleX,
                             float scaleY,
                             @NonNull EGLContext eglContext) {
        if (bos!=null) {
            mResult.rotation = 0;
            mResult.data = bos.toByteArray();
            dispatchResult();
        }
    }

    @Override
    protected void dispatchResult() {
        mOutputRatio = null;
        bos = null;
        super.dispatchResult();
    }
}
