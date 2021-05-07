package com.sabine.cameraview.preview;

import android.graphics.SurfaceTexture;

import androidx.annotation.NonNull;

import com.otaliastudios.opengl.texture.GlTexture;
import com.sabine.cameraview.filter.Filter;

/**
 * Callback for renderer frames.
 */
public interface RendererFrameCallback {

    /**
     * Called on the renderer thread, hopefully only once, to notify that
     * the texture was created (or to inform a new callback of the old texture).
     *
     * @param textureId the GL texture linked to the image streams
     * @param frontIsFirst (新增)GlCameraPreview传递过来的前置摄像头是否显示在第一路的值
     */
    @RendererThread
    void onRendererTextureCreated(int textureId, boolean frontIsFirst);

    /**
     * Called on the renderer thread after each frame was drawn.
     * You are not supposed to hold for too long onto this thread, because
     * well, it is the rendering thread.
     *
     * @param surfaceTexture the texture to get transformation
     * @param timestampNanos the timestamp for surfaceTexture
     * @param rotation the rotation (to reach REF_VIEW)
     * @param scaleX the scaleX (in REF_VIEW) value
     * @param scaleY the scaleY (in REF_VIEW) value
     * @param inputTextureId the last framebuffer texture for filters
     */
    @RendererThread
    void onRendererFrame(@NonNull SurfaceTexture surfaceTexture, long timestampNanos, int rotation, float scaleX, float scaleY, GlTexture inputTextureId);

    /**
     * Called when the renderer filter changes. This is guaranteed to be called at least once
     * before the first {@link #onRendererFrame(SurfaceTexture, long, int, float, float, int)}.
     *
     * @param filter the new filter
     */
    @RendererThread
    void onRendererFilterChanged(@NonNull Filter filter, float filterLevel);

    /**
     * Called when the renderer filter changes. update new textureId
     * before the first {@link #onRendererFrame(SurfaceTexture, long, int, float, float, int)}.
     *
     * @param textureId the new textureId
     */
    @RendererThread
    void onRendererInputTextureIdChanged(@NonNull int textureId);

    /**
     * Called on the renderer thread after switch two input texture.
     * You are not supposed to hold for too long onto this thread, because
     * well, it is the rendering thread.
     *
     */
    @RendererThread
    void onRendererSwitchInputTexture();
}
