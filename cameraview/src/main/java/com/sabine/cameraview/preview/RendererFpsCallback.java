package com.sabine.cameraview.preview;

public interface RendererFpsCallback {

    /**
     * Called once a second, return the fps value.
     * @param fps The fps of video.
     */
    @RendererThread
    void onRendererFps(int fps);

}
