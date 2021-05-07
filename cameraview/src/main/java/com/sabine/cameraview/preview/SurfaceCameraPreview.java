package com.sabine.cameraview.preview;

import android.content.Context;
import android.graphics.RectF;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.R;

/**
 * This is the fallback preview when hardware acceleration is off, and is the last resort.
 * Currently does not support cropping, which means that
 * {@link com.sabine.cameraview.CameraView} is forced to be wrap_content.
 *
 * Do not use.
 */
public class SurfaceCameraPreview extends CameraPreview<SurfaceView, SurfaceHolder> {

    private final static CameraLogger LOG
            = CameraLogger.create(SurfaceCameraPreview.class.getSimpleName());

    private boolean mDispatched;
    private View mRootView;

    @Override
    public void setBeauty(float parameterValue1, float parameterValue2) {

    }

    public SurfaceCameraPreview(@NonNull Context context, @NonNull ViewGroup parent) {
        super(context, parent);
    }

    @NonNull
    @Override
    protected SurfaceView onCreateView(@NonNull Context context, @NonNull ViewGroup parent) {
        View root = LayoutInflater.from(context).inflate(R.layout.cameraview_surface_view, parent,
                false);
        parent.addView(root, 0);
        SurfaceView surfaceView = root.findViewById(R.id.surface_view);
        final SurfaceHolder holder = surfaceView.getHolder();
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        holder.addCallback(new SurfaceHolder.Callback() {

            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                // This is too early to call anything.
                // surfaceChanged is guaranteed to be called after, with exact dimensions.
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                LOG.i("callback:", "surfaceChanged",
                        "w:", width,
                        "h:", height,
                        "dispatched:", mDispatched);
                if (!mDispatched) {
                    dispatchOnSurfaceAvailable(width, height);
                    mDispatched = true;
                } else {
                    dispatchOnSurfaceSizeChanged(width, height);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                LOG.i("callback:", "surfaceDestroyed");
                dispatchOnSurfaceDestroyed();
                mDispatched = false;
            }
        });
        mRootView = root;
        return surfaceView;
    }

    @NonNull
    @Override
    public View getRootView() {
        return mRootView;
    }

    @NonNull
    @Override
    public SurfaceHolder getOutput(int index) {
        return getView().getHolder();
    }

    @Override
    public void removeInputSurfaceTexture(int index) {

    }

    @Override
    public int getInputSurfaceTextureCount() {
        return 1;
    }

    @NonNull
    @Override
    public Class<SurfaceHolder> getOutputClass() {
        return SurfaceHolder.class;
    }

    @Override
    public boolean getFrontIsFirst() {
        return false;
    }

    @Override
    public void switchInputTexture() {

    }

    @Override
    public RectF getSurfaceLayoutRect(int index) {
        return new RectF(0, 0, getView().getWidth(), getView().getHeight());
    }

    @Override
    public void resetOutputTextureDrawer() {

    }

    @Override
    public void startPreview() {

    }

    @Override
    public void setSensorTimestampOffset(long offset) {

    }

    @Override
    public void addRendererFpsCallback(@NonNull RendererFpsCallback callback) {

    }

}
