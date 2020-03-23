package com.sabine.cameraview.video.encoding;

import android.opengl.EGLContext;

import androidx.annotation.NonNull;

import com.sabine.cameraview.overlay.Overlay;
import com.sabine.cameraview.overlay.OverlayDrawer;

/**
 * Video configuration to be passed as input to the constructor
 * of a {@link TextureMediaEncoder}.
 */
public class TextureConfig extends VideoConfig {

    public int textureId;
    public Overlay.Target overlayTarget;
    public OverlayDrawer overlayDrawer;
    public int overlayRotation;
    public float scaleX;
    public float scaleY;
    public EGLContext eglContext;

    @NonNull
    public TextureConfig copy() {
        TextureConfig copy = new TextureConfig();
        copy(copy);
        copy.textureId = this.textureId;
        copy.overlayDrawer = this.overlayDrawer;
        copy.overlayTarget = this.overlayTarget;
        copy.overlayRotation = this.overlayRotation;
        copy.scaleX = this.scaleX;
        copy.scaleY = this.scaleY;
        copy.eglContext = this.eglContext;
        return copy;
    }

    boolean hasOverlay() {
        return overlayDrawer != null;
    }

    @Override
    public String toString() {
        return "TextureConfig{" +
                "textureId=" + textureId +
                ", overlayTarget=" + overlayTarget +
                ", overlayDrawer=" + overlayDrawer +
                ", overlayRotation=" + overlayRotation +
                ", scaleX=" + scaleX +
                ", scaleY=" + scaleY +
                ", eglContext=" + eglContext +
                ", width=" + width +
                ", height=" + height +
                ", bitRate=" + bitRate +
                ", frameRate=" + frameRate +
                ", rotation=" + rotation +
                ", mimeType='" + mimeType + '\'' +
                ", encoder='" + encoder + '\'' +
                '}';
    }
}
