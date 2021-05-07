package com.sabine.cameraview.preview;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;

import com.otaliastudios.opengl.core.Egloo;
import com.otaliastudios.opengl.texture.GlTexture;
import com.sabine.cameraview.R;
import com.sabine.cameraview.filter.Filter;
import com.sabine.cameraview.filter.MultiFilter;
import com.sabine.cameraview.filter.NoFilter;
import com.sabine.cameraview.filters.BeautyAdjustV1Filter;
import com.sabine.cameraview.internal.GlTextureDrawer;
import com.sabine.cameraview.size.AspectRatio;
import com.sabine.cameraview.utils.LogUtil;
import com.sabine.cameraview.utils.OpenGLUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * - The android camera will stream image to the given {@link SurfaceTexture}.
 *
 * - in the SurfaceTexture constructor we pass the GL texture handle that we have created.
 *
 * - The SurfaceTexture is linked to the Camera1Engine object. The camera will pass down
 *   buffers of data with a specified size (that is, the Camera1Engine preview size).
 *   For this reason we don't have to specify surfaceTexture.setDefaultBufferSize()
 *   (like we do, for example, in Snapshot1PictureRecorder).
 *
 * - When SurfaceTexture.updateTexImage() is called, it will fetch the latest texture image from the
 *   camera stream and assign it to the GL texture that was passed.
 *   Now the GL texture must be drawn using draw* APIs. The SurfaceTexture will also give us
 *   the transformation matrix to be applied.
 *
 * - The easy way to render an OpenGL texture is using the {@link GLSurfaceView} class.
 *   It manages the GL context, hosts a surface and runs a separated rendering thread that will
 *   perform the rendering.
 *
 * - As per docs, we ask the GLSurfaceView to delegate rendering to us, using
 *   {@link GLSurfaceView#setRenderer(GLSurfaceView.Renderer)}. We request a render on the
 *   SurfaceView anytime the SurfaceTexture notifies that it has new data available
 *   (see OnFrameAvailableListener below).
 *
 * - So in short:
 *   - The SurfaceTexture has buffers of data of mInputStreamSize
 *   - The SurfaceView hosts a view (and a surface) of size mOutputSurfaceSize.
 *     These are determined by the CameraView.onMeasure method.
 *   - We have a GL rich texture to be drawn (in the given method and thread).
 *
 * This class will provide rendering callbacks to anyone who registers a
 * {@link RendererFrameCallback}. Callbacks are guaranteed to be called on the renderer thread,
 * which means that we can fetch the GL context that was created and is managed
 * by the {@link GLSurfaceView}.
 */
public class GlCameraPreview extends CameraPreview<GLSurfaceView, SurfaceTexture>
        implements FilterCameraPreview, RendererCameraPreview {

    private boolean mDispatched;
    private final List<SurfaceTexture> mInputSurfaceTexture = new ArrayList<>();
    private final List<RectF> mInputSurfaceRect = new ArrayList<>();
    private GlTextureDrawer mOutputTextureDrawer;
    // A synchronized set was not enough to avoid crashes, probably due to external classes
    // removing the callback while this set is being iterated. CopyOnWriteArraySet solves this.
    private final Set<RendererFrameCallback> mRendererFrameCallbacks = new CopyOnWriteArraySet<>();
    private RendererFpsCallback mRendererFpsCallback;
    @VisibleForTesting float mCropScaleX = 1F;
    @VisibleForTesting float mCropScaleY = 1F;
    private View mRootView;
    private Filter mCurrentFilter;
    private float mCurrentFilterLevel;
    private int mLutTexture = 0;
    private final Context mContext;
    private boolean mFilterChanged = false;

    private long mDrawNumbers = 0;
    private long mFirstDrawTimes = 0;

    private boolean isFirstDraw = true;

    //是否开始画面刷新
    private boolean mStartPreview;
    //是否已经设置正确的TextureTransform
    private boolean mTextureTransformFlag;

    private long mSensorTimestampOffset = 0L;

    public GlCameraPreview(@NonNull Context context, @NonNull ViewGroup parent) {
        super(context, parent);
        mContext = context;
        mDualCamera = true;
        mStartPreview = false;
        mTextureTransformFlag = false;
        mSensorTimestampOffset = 0L;
        isFirstDraw = true;
    }

    @NonNull
    @Override
    protected GLSurfaceView onCreateView(@NonNull Context context, @NonNull ViewGroup parent) {
        ViewGroup root = (ViewGroup) LayoutInflater.from(context)
                .inflate(R.layout.cameraview_gl_view, parent, false);
        final GLSurfaceView glView = root.findViewById(R.id.gl_surface_view);
        final Renderer renderer = instantiateRenderer();
        glView.setEGLContextClientVersion(2);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        glView.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder) {}
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                dispatchOnSurfaceDestroyed();
                glView.queueEvent(new Runnable() {
                    @Override
                    public void run() {
                        renderer.onSurfaceDestroyed();
                    }
                });
                mDispatched = false;
            }
        });
        parent.addView(root, 0);
        mRootView = root;
        return glView;
    }

    @NonNull
    @Override
    public View getRootView() {
        return mRootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        getView().onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        getView().onPause();
        // 防止MultiFilter时息屏后再打开崩溃
        //TODO:需要测试手机收到第三方APP/系统消息弹窗时mOutputTextureDrawer.draw崩溃的问题
        if (mOutputTextureDrawer != null) {
            mOutputTextureDrawer.release();
            mOutputTextureDrawer = null;
        }
        mFirstDrawTimes = 0;
        mDrawNumbers = 0;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // View is gone, so EGL context is gone: callbacks make no sense anymore.
        mRendererFrameCallbacks.clear();
    }

    /**
     * The core renderer that performs the actual drawing operations.
     */
    public class Renderer implements GLSurfaceView.Renderer {

        @RendererThread
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
//            LOG.e("---------------->GlCameraPreview", "onSurfaceCreated", mOutputTextureDrawer != null);
            if (mOutputTextureDrawer != null)
                return;

            mOutputTextureDrawer = new GlTextureDrawer(MAX_INPUT_SURFACETEXTURE, false);
//            //if (mLutTexture == 0) {
                mLutTexture = OpenGLUtils.createTextureFromAssets(mContext, "texture/beautyLut_16_16.png");
//            //}
            if (mCurrentFilter == null) {
                mCurrentFilter = new NoFilter();
            } else {
                if (mCurrentFilter instanceof MultiFilter) {
                    for (Filter filter: ((MultiFilter) mCurrentFilter).getFilters()) {
                        if (filter instanceof BeautyAdjustV1Filter) {
                            ((BeautyAdjustV1Filter) filter).setLutTexture(mLutTexture);
                            break;
                        }
                    }
                }
            }

            mInputSurfaceTexture.clear();
            mInputSurfaceRect.clear();

            mOutputTextureDrawer.setFilter(mCurrentFilter);
            mOutputTextureDrawer.setDualInputTextureMode(mDualInputTextureMode==DualInputTextureMode.PIP_MODE?2.0f:1.0f);
            final int textureId = mOutputTextureDrawer.getTexture(0).getId();
            mInputSurfaceTexture.add(new SurfaceTexture(textureId));
//            mOutputTextureDrawer.setOtherTexture(1, mLutTexture);
//            mInputSurfaceTexture[1] = new SurfaceTexture(textureId);
            getView().queueEvent(new Runnable() {
                @Override
                public void run() {
                    if (mCurrentFilter.getLastOutputTextureId() == null)
                        return;
                    for (RendererFrameCallback callback : mRendererFrameCallbacks) {
                        callback.onRendererTextureCreated(mCurrentFilter.getLastOutputTextureId().outputFramebufferTexture.getId(), mOutputTextureDrawer.getFrontIsFirst());
                    }
                }
            });

            // Since we are using GLSurfaceView.RENDERMODE_WHEN_DIRTY, we must notify
            // the SurfaceView of dirtyness, so that it draws again. This is how it's done.
            mInputSurfaceTexture.get(0).setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    getView().requestRender(); // requestRender is thread-safe.
                }
            });

            mDrawNumbers = 0;
            mFirstDrawTimes = 0;
        }

        @SuppressWarnings("WeakerAccess")
        @RendererThread
        public void onSurfaceDestroyed() {
            for(int i = 0; i < mInputSurfaceTexture.size(); i++) {
                mInputSurfaceTexture.get(i).setOnFrameAvailableListener(null);
                mInputSurfaceTexture.get(i).release();
            }
            mInputSurfaceTexture.clear();
            mInputSurfaceRect.clear();
            if (mOutputTextureDrawer != null) {
                mOutputTextureDrawer.release();
                mOutputTextureDrawer = null;
            }
        }

        @RendererThread
        @Override
        public void onSurfaceChanged(GL10 gl, final int width, final int height) {
//            gl.glViewport(0, 0, width, height);

//            if (mOutputTextureDrawer!=null)
//                mOutputTextureDrawer.setFilterSize(width, height);
            if (!mDispatched) {
                dispatchOnSurfaceAvailable(width, height);
                mDispatched = true;
            } else if (width != mOutputSurfaceWidth || height != mOutputSurfaceHeight) {
                dispatchOnSurfaceSizeChanged(width, height);
            }
            mCurrentFilter.setSize(mOutputSurfaceWidth, mOutputSurfaceHeight, mRendererWidth, mRendererHeight);
        }

        @RendererThread
        @Override
        public void onDrawFrame(GL10 gl) {
            if (!mStartPreview || mInputSurfaceTexture.size() == 0 || mInputStreamWidth <= 0 || mInputStreamHeight <= 0) {
                // Skip drawing. Camera was not opened.
                mDrawNumbers = 0;
                mFirstDrawTimes = 0;
                return;
            }

            if (mFirstDrawTimes == 0)
                mFirstDrawTimes = SystemClock.elapsedRealtime();
            // Latch the latest frame. If there isn't anything new,
            // we'll just re-use whatever was there before.
            try {
//                long beginTime = SystemClock.elapsedRealtime();
                GLES20.glViewport(0, 0, mRendererWidth, mRendererHeight);

                final float[] transform = mOutputTextureDrawer.getTextureTransform();
                for (int i = 0; i < mInputSurfaceTexture.size(); i++) {
                    mInputSurfaceTexture.get(i).updateTexImage();
                    if (i > 0 || mTextureTransformFlag)
                        continue;
                    //TODO:transform控制OPENGL渲染的方向，mInputSurfaceTexture[0]关联的是前置摄像头时，和DualInputTextureFilter渲染时处理的垂直方向相反，所以用mInputSurfaceTexture[1]获取transform来控制渲染方向
                    float[] oldTransform = transform.clone();
                    if (mInputSurfaceTexture.size() > 1 && mFrontIsFirst)
                        mInputSurfaceTexture.get(1).getTransformMatrix(transform);
                    else
                        mInputSurfaceTexture.get(i).getTransformMatrix(transform);
                    float[] defaultTransform = Egloo.IDENTITY_MATRIX.clone();
                    for (int index = 0; index < transform.length; index++) {
                        if (defaultTransform[index] != transform[index]) {
                            mTextureTransformFlag = true;
                            break;
                        }
                    }
                    if (mTextureTransformFlag) {
                        /**
                         * For Camera2, apply the draw rotation.
                         * See {@link #setDrawRotation(int)} for info.
                         */
                        if (mDrawRotation != 0) {
                            Matrix.translateM(transform, 0, 0.5F, 0.5F, 0);
                            Matrix.rotateM(transform, 0, mDrawRotation, 0, 0, 1);
                            Matrix.translateM(transform, 0, -0.5F, -0.5F, 0);
                        }

                        if (isCropping()) {
                            // Scaling is easy, but we must also translate before:
                            // If the view is 10x1000 (very tall), it will show only the left strip
                            // of the preview (not the center one).
                            // If the view is 1000x10 (very large), it will show only the bottom strip
                            // of the preview (not the center one).
                            float translX = (1F - mCropScaleX) / 2F;
                            float translY = (1F - mCropScaleY) / 2F;
                            Matrix.translateM(transform, 0, translX, translY, 0);
                            Matrix.scaleM(transform, 0, mCropScaleX, mCropScaleY, 1);
                        }
                    } else {
                        for (int xy = 0; xy < transform.length; xy++) {
                            transform[xy] = oldTransform[xy];
                        }
                    }
                }
                if (isFirstDraw) {
                    isFirstDraw = false;
//                    if (Math.abs(mInputSurfaceTexture.get(0).getTimestamp() - SystemClock.elapsedRealtimeNanos()) <= 200 * 1000 * 1000) {
//                        mSensorTimestampOffset = 0;
//                    }
                }

                mOutputTextureDrawer.draw(mSensorTimestampOffset == 0 ? mInputSurfaceTexture.get(0).getTimestamp() : (mInputSurfaceTexture.get(0).getTimestamp() - mSensorTimestampOffset), mDrawRotation);
//                LOG.e("GlCameraPreview onDrawFrame time:", SystemClock.elapsedRealtime()-beginTime);
//                LOG.e(mInputSurfaceTexture.get(0).getTimestamp(), System.nanoTime(), SystemClock.elapsedRealtimeNanos(), mSensorTimestampOffset);
                Filter.offscreenTexture glTexture = mCurrentFilter.getLastOutputTextureId();
                if (glTexture != null) {
                    for (RendererFrameCallback callback : mRendererFrameCallbacks) {
                        //TODO:和预览时一样，录制时也要通过mInputSurfaceTexture来获取transform控制OPENGL渲染的方向，mInputSurfaceTexture[0]关联的是前置摄像头时，和DualInputTextureFilter渲染时处理的垂直方向相反，所以用mInputSurfaceTexture[1]获取transform来控制渲染方向
//                        GlTexture glTexture = mCurrentFilter.getLastOutputTextureId();
//                    int inputTextureId = -1;
//                    if (glTexture != null)
//                        inputTextureId = glTexture.getId();
//                        if (mInputSurfaceTexture.size() > 1 && mFrontIsFirst)
//                            callback.onRendererFrame(mInputSurfaceTexture.get(1), mSensorTimestampOffset == 0 ? mInputSurfaceTexture.get(1).getTimestamp() : (mInputSurfaceTexture.get(1).getTimestamp() - mSensorTimestampOffset), mDrawRotation, mCropScaleX, mCropScaleY, glTexture);
//                        else
//                            callback.onRendererFrame(mInputSurfaceTexture.get(0), mSensorTimestampOffset == 0 ? mInputSurfaceTexture.get(0).getTimestamp() : (mInputSurfaceTexture.get(0).getTimestamp() - mSensorTimestampOffset), mDrawRotation, mCropScaleX, mCropScaleY, glTexture);
                        if (mInputSurfaceTexture.size() > 1 && mFrontIsFirst)
                            callback.onRendererFrame(mInputSurfaceTexture.get(1), glTexture.timestampNs, mDrawRotation, mCropScaleX, mCropScaleY, glTexture.outputFramebufferTexture);
                        else
                            callback.onRendererFrame(mInputSurfaceTexture.get(0), glTexture.timestampNs, mDrawRotation, mCropScaleX, mCropScaleY, glTexture.outputFramebufferTexture);
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                LogUtil.e("GlCameraPreview", "onDrawFrame " + e.getLocalizedMessage());
            }

            mDrawNumbers++;
            long drawTimes = SystemClock.elapsedRealtime() - mFirstDrawTimes;
            if (drawTimes >= 1000 && mInputSurfaceTexture.size() != 0) {
                long dynamicFps;
                dynamicFps = (long)(mDrawNumbers*1000.0/drawTimes);
                if (mRendererFpsCallback != null) {
                    mRendererFpsCallback.onRendererFps((int) dynamicFps / mInputSurfaceTexture.size());
                }

                mDrawNumbers = 0;
                mFirstDrawTimes = 0;
            }

        }
    }

    @NonNull
    @Override
    public Class<SurfaceTexture> getOutputClass() {
        return SurfaceTexture.class;
    }

    @Override
    public void setDrawRotation(int drawRotation) {
        super.setDrawRotation(drawRotation);

        mInputSurfaceRect.clear();
        if (mInputSurfaceTexture.size() == 0)
            return;

        if (mDualInputTextureMode == DualInputTextureMode.PIP_MODE) {
            int nHeight = getView().getHeight();
            int nWidth = getView().getWidth();
            for (int i = 0; i < mInputSurfaceTexture.size(); i++) {
                if (i == 0) {
                    mInputSurfaceRect.add(new RectF(0, 0, nWidth, nHeight));
                } else {
//                    switch (mDrawRotation) {
//                        case 90:
//                            mInputSurfaceRect.add(new RectF(nWidth-nWidth/3.0f-nWidth*0.02f, nHeight-nHeight/3.0f-nWidth*0.02f, nWidth-nWidth*0.02f, nHeight-nWidth*0.02f));
//                            break;
//                        case 270:
//                            mInputSurfaceRect.add(new RectF(nWidth-nWidth/3.0f-nWidth*0.02f, nHeight-nHeight/3.0f-nWidth*0.02f, nWidth-nWidth*0.02f, nHeight-nWidth*0.02f));
//                            break;
//                        default:
                            mInputSurfaceRect.add(new RectF(nWidth-nWidth/3.0f-nWidth*0.02f, nHeight-nHeight/3.0f-nWidth*0.02f, nWidth-nWidth*0.02f, nHeight-nWidth*0.02f));
//                            break;
//                    }
                }
            }
        } else {
            int nHeight = getView().getHeight()/mInputSurfaceTexture.size();
            int nWidth = getView().getWidth();
            int nTop = 0;
            int nLeft;
            switch (mDrawRotation) {
//                case 90: {
//                    nHeight = getView().getHeight();
//                    nWidth = getView().getWidth()/mInputSurfaceTexture.size();
//                    nLeft = getView().getWidth() - nWidth;
//                    for (int i = 0; i < mInputSurfaceTexture.size(); i++) {
//                        mInputSurfaceRect.add(new RectF(nLeft, nTop, nLeft + nWidth, nHeight));
//                        nLeft -= nWidth;
//                    }
//                }
//                break;
                case 90:
                case 270: {
                    nHeight = getView().getHeight();
                    nWidth = getView().getWidth()/mInputSurfaceTexture.size();
                    nLeft = 0;
                    for (int i = 0; i < mInputSurfaceTexture.size(); i++) {
                        mInputSurfaceRect.add(new RectF(nLeft, nTop, nLeft + nWidth, nHeight));
                        nLeft += nWidth;
                    }
                }
                break;
                default: {
                    for (int i = 0; i < mInputSurfaceTexture.size(); i++) {
                        mInputSurfaceRect.add(new RectF(0, nTop, nWidth, nTop + nHeight));
                        nTop += nHeight;
                    }

                    break;
                }
            }

        }
    }

    /**
     * 获取索引为index的SurfaceTexture
     * index从0开始
     *
     * @param index mInputSurfaceTexture的索引
     */
    @NonNull
    @Override
    public SurfaceTexture getOutput(int index) {
        if (index >= MAX_INPUT_SURFACETEXTURE || index < 0 || mOutputTextureDrawer == null)
            return null;
        GlTexture newTexture = mOutputTextureDrawer.getTexture(index);
        if (index >= mInputSurfaceTexture.size()) {
            mInputSurfaceTexture.add(new SurfaceTexture(newTexture.getId()));
            mInputSurfaceTexture.get(index).setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    getView().requestRender(); // requestRender is thread-safe.
                }
            });
        }
        return mInputSurfaceTexture.get(index);
    }

    @Override
    public void removeInputSurfaceTexture(int index) {
        if (index >= MAX_INPUT_SURFACETEXTURE || index >= mInputSurfaceTexture.size() || index < 0)
            return;
//        mOutputTextureDrawer.removeTexture(index);
        mInputSurfaceTexture.get(index).release();
        mInputSurfaceTexture.remove(index);
    }

    @Override
    public int getInputSurfaceTextureCount() {
        return mInputSurfaceTexture.size();
    }

    @Override
    public boolean supportsCropping() {
        return true;
    }

    @Override
    public void switchInputTexture() {
        if (mOutputTextureDrawer != null) {
            mOutputTextureDrawer.switchInputTexture();

            try {
                if (mInputSurfaceRect.size()>0) {
                    RectF firstRect = mInputSurfaceRect.get(0);
                    int i;
                    for (i = 0; i < mInputSurfaceRect.size() - 1; i++) {
                        mInputSurfaceRect.set(i, mInputSurfaceRect.get(i + 1));
                    }
                    mInputSurfaceRect.set(i, firstRect);
                }
                for (RendererFrameCallback callback : mRendererFrameCallbacks) {
                    callback.onRendererSwitchInputTexture();
                }
            } catch (IndexOutOfBoundsException e) {
                LOG.e("inputSurface rect size is 0.");
            }
        }
    }

    @Override
    public RectF getSurfaceLayoutRect(int index) {
        if (index < mInputSurfaceRect.size() && index >= 0) {
            return mInputSurfaceRect.get(index);
        } else {
            return new RectF(0, 0, getView().getWidth(), getView().getHeight());
        }
    }

    @Override
    public void resetOutputTextureDrawer() {
        isFirstDraw = true;
        mStartPreview = false;
        mTextureTransformFlag = false;

        if (mOutputTextureDrawer != null)
            mOutputTextureDrawer.reset();

        int length = mInputSurfaceTexture.size();
        for(int i = 0; i < length; i++) {
            SurfaceTexture surfaceTexture = mInputSurfaceTexture.get(0);
            mInputSurfaceTexture.remove(0);
            surfaceTexture.setOnFrameAvailableListener(null);
            surfaceTexture.release();
        }

        mInputSurfaceRect.clear();
    }

    @Override
    public void startPreview() {
        mStartPreview = true;
    }

    @Override
    public void setSensorTimestampOffset(long offset) {
        mSensorTimestampOffset = offset;
    }

    /**
     * To crop in GL, we could actually use view.setScaleX and setScaleY, but only from Android N
     * onward. See documentation: https://developer.android.com/reference/android/view/SurfaceView
     *
     *   Note: Starting in platform version Build.VERSION_CODES.N, SurfaceView's window position
     *   is updated synchronously with other View rendering. This means that translating and scaling
     *   a SurfaceView on screen will not cause rendering artifacts. Such artifacts may occur on
     *   previous versions of the platform when its window is positioned asynchronously.
     *
     * But to support older platforms, this seem to work - computing scale values and requesting
     * a new frame, then drawing it with a scaled transformation matrix.
     * See {@link Renderer#onDrawFrame(GL10)}.
     */
    @Override
    protected void crop(@Nullable final CropCallback callback) {
        if (mInputStreamWidth > 0 && mInputStreamHeight > 0 && mOutputSurfaceWidth > 0
                && mOutputSurfaceHeight > 0) {
            float scaleX = 1f, scaleY = 1f;
//            Log.e("aaa", "crop: mOutputStreamSize === " + mOutputSurfaceWidth + "x" + mOutputSurfaceHeight + ", mInputStreamSize === " + mInputStreamWidth + "x" + mInputStreamHeight);
            AspectRatio current = AspectRatio.of(mOutputSurfaceWidth, mOutputSurfaceHeight);
            AspectRatio target = AspectRatio.of(mInputStreamWidth, mInputStreamHeight);
            if (current.toFloat() >= target.toFloat()) {
                // We are too short. Must increase height.
                scaleY = current.toFloat() / target.toFloat();
            } else {
                // We must increase width.
                scaleX = target.toFloat() / current.toFloat();
            }
            mCropping = scaleX > 1.02f || scaleY > 1.02f;
            mCropScaleX = 1F / scaleX;
            mCropScaleY = 1F / scaleY;
            getView().requestRender();
        }
        if (callback != null) callback.onCrop();
    }

    @Override
    public void addRendererFrameCallback(@NonNull final RendererFrameCallback callback) {
        getView().queueEvent(new Runnable() {
            @Override
            public void run() {
                mRendererFrameCallbacks.add(callback);
                if (mOutputTextureDrawer != null) {
//                    int[] textureIds = new int[mInputSurfaceTexture.size()];
//                    for (int i = 0; i < mInputSurfaceTexture.size(); i++) {
//                        textureIds[i] = mOutputTextureDrawer.getTexture(i).getId();
//                    }
//                    LOG.e("addRendererFrameCallback textureId", ((MultiFilter)mCurrentFilter).getLastOutputTextureId().getId());
                    //TODO:上层保证 getLastOutputTextureId 不为空，否则需要判断 getLastOutputTextureId 是否为空，确保不会出现 NullPointerException
                    if (mCurrentFilter.getLastOutputTextureId() != null) {
                        callback.onRendererTextureCreated(mCurrentFilter.getLastOutputTextureId().outputFramebufferTexture.getId()/*textureIds*/, mOutputTextureDrawer.getFrontIsFirst());
                    }
                }
//                callback.onRendererFilterChanged(mCurrentFilter, mCurrentFilterLevel);
            }
        });
    }

    @Override
    public void removeRendererFrameCallback(@NonNull final RendererFrameCallback callback) {
        mRendererFrameCallbacks.remove(callback);
    }

    @Override
    public void addRendererFpsCallback(@NonNull RendererFpsCallback callback) {
        mRendererFpsCallback = callback;
    }

    /**
     * Returns the output GL texture id.
     * @return the output GL texture id
     */
    @SuppressWarnings("unused")
    protected int getTextureId() {
        return mOutputTextureDrawer != null ? mOutputTextureDrawer.getTexture(0).getId() : -1;
    }

    /**
     * Creates the renderer for this GL surface.
     * @return the renderer for this GL surface
     */
    @SuppressWarnings("WeakerAccess")
    @NonNull
    protected Renderer instantiateRenderer() {
        return new Renderer();
    }

    //region Filters


    @NonNull
    @Override
    public Filter getCurrentFilter() {
        if (mOutputTextureDrawer != null)
            return mOutputTextureDrawer.getFilter();
        else
            return mCurrentFilter;
    }

    @Override
    public void setFilter(final @NonNull Filter filter) {
        mCurrentFilter = filter;
        if (hasSurface()) {
            mCurrentFilter.setSize(mOutputSurfaceWidth, mOutputSurfaceHeight, mRendererWidth, mRendererHeight);
        }
        if (mCurrentFilter instanceof MultiFilter && mLutTexture != 0)
            for (Filter filter1: ((MultiFilter) mCurrentFilter).getFilters()) {
                if (filter1 instanceof BeautyAdjustV1Filter) {
                    ((BeautyAdjustV1Filter) filter1).setLutTexture(mLutTexture);
                    break;
                }
            }

        getView().queueEvent(new Runnable() {
            @Override
            public void run() {
                if (mOutputTextureDrawer != null) {
                    mOutputTextureDrawer.setFilter(mCurrentFilter);
                }
            }
        });
    }

    @Override
    public void setFilterLevel(float filterLevel) {
        mCurrentFilterLevel = filterLevel;
    }

    @Override
    public void setBeauty(final float parameterValue1, final float parameterValue2) {
        final MultiFilter multiFilter;
        if (mOutputTextureDrawer != null) {
            Filter filter = mOutputTextureDrawer.getFilter();
            multiFilter = filter instanceof MultiFilter ? (MultiFilter) filter : null;
        } else {
            multiFilter = mCurrentFilter instanceof MultiFilter ? (MultiFilter) mCurrentFilter : null;
        }
        if (multiFilter == null)
            return;

        getView().queueEvent(new Runnable() {
            @Override
            public void run() {
                if (parameterValue1 == 0.0f) {
                    multiFilter.removeBeautyFilter();
                } else {
                    multiFilter.addBeautyFilter(mContext);
                    multiFilter.getBeautyFilter().setParameter1(parameterValue1);
                    multiFilter.getBeautyFilter().setParameter2(parameterValue2);
                }
            }
        });
    }

    @Override
    public float getFilterLevel() {
        return mCurrentFilterLevel;
    }

    @Override
    public void setDualInputTextureMode(DualInputTextureMode dualInputTextureMode) {
        super.setDualInputTextureMode(dualInputTextureMode);

        if (mInputSurfaceTexture.size() == 0) return;

        mInputSurfaceRect.clear();
        if (mDualInputTextureMode == DualInputTextureMode.PIP_MODE) {
            if (mOutputTextureDrawer != null) mOutputTextureDrawer.setDualInputTextureMode(2.0f);
            int nHeight = getView().getHeight();
            int nWidth = getView().getWidth();
            for (int i = 0; i < mInputSurfaceTexture.size(); i++) {
                if (i == 0) {
                    mInputSurfaceRect.add(new RectF(0, 0, nWidth, nHeight));
                } else {
                    mInputSurfaceRect.add(new RectF(nWidth-nWidth/3.0f-nWidth*0.02f, nHeight-nHeight/3.0f-nWidth*0.02f, nWidth-nWidth*0.02f, nHeight-nWidth*0.02f));
                }
            }
        } else {
            if (mOutputTextureDrawer != null) mOutputTextureDrawer.setDualInputTextureMode(1.0f);
            if (mInputSurfaceTexture.size() != 0) {
                int nHeight = getView().getHeight()/mInputSurfaceTexture.size();
                int nWidth = getView().getWidth();
                int nTop = 0;
                int nLeft;
                switch (mDrawRotation) {
                    case 90:
                    case 270: {
                        nHeight = getView().getHeight();
                        nWidth = getView().getWidth()/mInputSurfaceTexture.size();
                        nLeft = 0;
                        for (int i = 0; i < mInputSurfaceTexture.size(); i++) {
                            mInputSurfaceRect.add(new RectF(nLeft, nTop, nLeft + nWidth, nHeight));
                            nLeft += nWidth;
                        }
                    }
                    break;
                    default: {
                        for (int i = 0; i < mInputSurfaceTexture.size(); i++) {
                            mInputSurfaceRect.add(new RectF(0, nTop, nWidth, nTop + nHeight));
                            nTop += nHeight;
                        }

                        break;
                    }
                }
            }
        }
    }

    @Override
    public void setPreviewAspectRatio(float aspectRatio) {
        super.setPreviewAspectRatio(aspectRatio);
        if (mOutputTextureDrawer != null) {
            mOutputTextureDrawer.setPreviewAspectRatio(aspectRatio);
        }
    }

    @Override
    public void setFrontIsFirst(boolean frontIsFirst) {
        super.setFrontIsFirst(frontIsFirst);
        if (mOutputTextureDrawer != null) {
            mOutputTextureDrawer.setFrontIsFirst(frontIsFirst);
        }
    }

    @Override
    public boolean getFrontIsFirst() {
        if (mOutputTextureDrawer != null) return mOutputTextureDrawer.getFrontIsFirst();
        return false;
    }

    @Override
    public void setStreamSize(int width, int height) {
        super.setStreamSize(width, height);
        if (mCurrentFilter != null)
            mCurrentFilter.setSize(mOutputSurfaceWidth, mOutputSurfaceHeight, mRendererWidth, mRendererHeight);
    }
}
