package com.sabine.cameraview.filters;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Environment;

import com.otaliastudios.opengl.core.Egloo;
import com.otaliastudios.opengl.program.GlProgram;
import com.otaliastudios.opengl.program.GlTextureProgram;
import com.otaliastudios.opengl.texture.GlFramebuffer;
import com.otaliastudios.opengl.texture.GlTexture;
import com.sabine.cameraview.filter.Filter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.filter.TwoParameterFilter;
import com.sabine.cameraview.size.Size;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import static com.otaliastudios.opengl.core.Egloo.checkGlError;

/**
 * A {@link BeautyV1Filter} is a special {@link Filter} that can group one or more filters together.
 * When this happens, filters are applied in sequence:
 * - the first filter reads from input frames
 * - the second filters reads the output of the first
 * And so on, until the last filter which will read from the previous and write to the real output.
 *
 * New filters can be added at any time through {@link #addFilter(Filter)}, but currently they
 * can not be removed because we can not easily ensure that they would be correctly released.
 *
 * The {@link BeautyV1Filter} does also implement {@link OneParameterFilter} and
 * {@link TwoParameterFilter}, dispatching all the parameter calls to child filters,
 * assuming they support it.
 *
 * There are some important technical caveats when using {@link BeautyV1Filter}:
 * - each child filter requires the allocation of a GL framebuffer. Using a large number of filters
 *   will likely cause memory issues (e.g. https://stackoverflow.com/q/6354208/4288782).
 * - some of the children need to write into {@link GLES20#GL_TEXTURE_2D} instead of
 *   {@link GLES11Ext#GL_TEXTURE_EXTERNAL_OES}! To achieve this, we replace samplerExternalOES
 *   with sampler2D in your fragment shader code. This might cause issues for some shaders.
 */
@SuppressWarnings("unused")
public class BeautyV1Filter implements Filter, OneParameterFilter, TwoParameterFilter {

    @VisibleForTesting
    static class State {
        @VisibleForTesting boolean isProgramCreated = false;
        @VisibleForTesting boolean isFramebufferCreated = false;
        @VisibleForTesting
        Size size = null;
        private int programHandle = -1;
        private GlFramebuffer outputFramebuffer = null;
        private GlTexture outputTexture = null;
        private int inputImageTexture1 = -1;
        private int inputImageTexture2 = -1;
    }

    @VisibleForTesting final List<Filter> filters = new ArrayList<>();
    @VisibleForTesting final Map<Filter, State> states = new HashMap<>();
    private final Object lock = new Object();
    private Size size = null;
    private float blurOpacity = 0.48f;
    private float skinOpacity = 0.52f;
    private float whiteOpacity = 1.0f;

    private GlTexture inputImageTexture0;
    private static String TAG = BeautyV1Filter.class.getSimpleName();
    private Context context;
    private int frameCount = 0;

    /**
     * Creates a new group with the given filters.
     */
    public BeautyV1Filter(Context context) {
        this.context = context;
        GaussianPassFilter gaussVBlurFilter = new GaussianPassFilter();
        gaussVBlurFilter.setFilterOrientation(true);
        gaussVBlurFilter.setDistanceNormalizationFactor(2.746f);
        addFilter(gaussVBlurFilter);
        GaussianPassFilter gaussHBlurFilter = new GaussianPassFilter();
        gaussHBlurFilter.setFilterOrientation(false);
        gaussHBlurFilter.setDistanceNormalizationFactor(2.746f);
        addFilter(gaussHBlurFilter);
        BeautyAdjustV1Filter beautyAdjustV1Filter = new BeautyAdjustV1Filter(context);
        addFilter(beautyAdjustV1Filter);
    }

    /**
     * Adds a new filter. It will be used in the next frame.
     * If the filter is a {@link BeautyV1Filter}, we'll use its children instead.
     *
     * @param filter a new filter
     */
    @SuppressWarnings("WeakerAccess")
    public void addFilter(@NonNull Filter filter) {
        synchronized (lock) {
            if (!filters.contains(filter)) {
                filters.add(filter);
                states.put(filter, new State());
            }
        }
    }

    // We don't offer a removeFilter method since that would cause issues
    // with cleanup. Cleanup must happen on the GL thread so we'd have to wait
    // for new rendering call (which might not even happen).

    private void maybeCreateProgram(@NonNull Filter filter, boolean isFirst, boolean isLast) {
        State state = states.get(filter);
        //noinspection ConstantConditions
        if (state.isProgramCreated) return;
        state.isProgramCreated = true;

        // The first shader actually reads from a OES texture, but the others
        // will read from the 2d framebuffer texture. This is a dirty hack.
        String fragmentShader = isFirst
                ? filter.getFragmentShader()
                : filter.getFragmentShader().replace("samplerExternalOES ", "sampler2D ");
        String vertexShader = filter.getVertexShader();
        state.programHandle = GlProgram.create(vertexShader, fragmentShader);
        filter.onCreate(state.programHandle);
    }

    private void maybeDestroyProgram(@NonNull Filter filter) {
        State state = states.get(filter);
        //noinspection ConstantConditions
        if (!state.isProgramCreated) return;
        state.isProgramCreated = false;
        filter.onDestroy();
        GLES20.glDeleteProgram(state.programHandle);
        state.programHandle = -1;
    }

    private void maybeCreateFramebuffer(@NonNull Filter filter, boolean isFirst, boolean isLast) {
        State state = states.get(filter);
        //noinspection ConstantConditions
        if (state.isFramebufferCreated) return;

        if (filter instanceof BeautyAdjustV1Filter && state.inputImageTexture1 == -1) {
            if (filters.size() > 1 && filters.get(1) instanceof GaussianPassFilter) {
                State state1 = states.get(filters.get(1));
                state.inputImageTexture1 = state1.outputTexture.getId();
                ((BeautyAdjustV1Filter) filter).setBlurTexture(state.inputImageTexture1);
            }
        }

        if (isLast) return;
        state.isFramebufferCreated = true;

        state.outputTexture = new GlTexture(GLES20.GL_TEXTURE0,
                GLES20.GL_TEXTURE_2D,
                state.size.getWidth(),
                state.size.getHeight());
        state.outputFramebuffer = new GlFramebuffer();
        state.outputFramebuffer.attach(state.outputTexture);
    }

    private void maybeDestroyFramebuffer(@NonNull Filter filter) {
        State state = states.get(filter);
        //noinspection ConstantConditions
        if (!state.isFramebufferCreated) return;
        state.isFramebufferCreated = false;
        state.outputFramebuffer.release();
        state.outputFramebuffer = null;
        state.outputTexture.release();
        state.outputTexture = null;
    }

    private void maybeSetSize(@NonNull Filter filter) {
        State state = states.get(filter);
        //noinspection ConstantConditions
        if (size != null && filter != null) {
            if (filter instanceof GaussianPassFilter) {
                Size downsamplingSize = new Size(size.getWidth() / 3, size.getHeight() / 3);
                if (!downsamplingSize.equals(state.size)) {
                    state.size = downsamplingSize;
                    filter.setSize(state.size.getWidth(), state.size.getHeight());
                }
            } else {
                if (!size.equals(state.size)) {
                    state.size = size;
                    filter.setSize(size.getWidth(), size.getHeight());
                }
            }
        }
    }

    @Override
    public void onCreate(int programHandle) {
        // We'll create children during the draw() op, since some of them
        // might have been added after this onCreate() is called.
    }

    @NonNull
    @Override
    public String getVertexShader() {
        // Whatever, we won't be using this.
        return GlTextureProgram.SIMPLE_VERTEX_SHADER;
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        // Whatever, we won't be using this.
        return GlTextureProgram.SIMPLE_FRAGMENT_SHADER;
    }

    @Override
    public void onDestroy() {
        synchronized (lock) {
            for (Filter filter : filters) {
                maybeDestroyFramebuffer(filter);
                maybeDestroyProgram(filter);
            }
        }
    }

    @Override
    public void setSize(int width, int height) {
        size = new Size(width, height);
        synchronized (lock) {
            for (Filter filter : filters) {
                maybeSetSize(filter);
            }
        }
    }

    @Override
    public void draw(long timestampUs, @NonNull float[] transformMatrix) {
        synchronized (lock) {
            for (int i = 0; i < filters.size(); i++) {
                boolean isFirst = i == 0;
                boolean isLast = i == filters.size() - 1;
                Filter filter = filters.get(i);
                State state = states.get(filter);

                maybeSetSize(filter);
                maybeCreateProgram(filter, isFirst, isLast);
                maybeCreateFramebuffer(filter, isFirst, isLast);

                //noinspection ConstantConditions
                GLES20.glUseProgram(state.programHandle);

                // Define the output framebuffer.
                // Each filter outputs into its own framebuffer object, except the
                // last filter, which outputs into the default framebuffer.
                if (!isLast) {
                    state.outputFramebuffer.bind();
                    GLES20.glClearColor(0, 0, 0, 0);
                } else {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                }

                // Perform the actual drawing.
                // The first filter should apply all the transformations. Then,
                // since they are applied, we should use a no-op matrix.
                if (isFirst) {
                    filter.draw(timestampUs, transformMatrix);
                } else {
                    filter.draw(timestampUs, Egloo.IDENTITY_MATRIX);
                }

                // Set the input for the next cycle:
                // It is the framebuffer texture from this cycle. If this is the last
                // filter, reset this value just to cleanup.
                if (!isLast) {
                    state.outputTexture.bind();
                } else {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                }

                GLES20.glUseProgram(0);
            }
        }
    }

    public void draw(long timestampUs, @NonNull float[] transformMatrix, boolean isFirstFilter, boolean isLastFilter) {
        synchronized (lock) {
            for (int i = 0; i < filters.size(); i++) {
                boolean isFirst = ((i == 0) && isFirstFilter);
                boolean isLast = ((i == filters.size() - 1) && isLastFilter);
                Filter filter = filters.get(i);
                State state = states.get(filter);
//                Log.e(TAG, "i="+i+" isFirst="+isFirst+" isLast="+isLast);

                maybeSetSize(filter);
                maybeCreateProgram(filter, isFirst, isLast);
                maybeCreateFramebuffer(filter, isFirst, isLast);

                //noinspection ConstantConditions
                GLES20.glUseProgram(state.programHandle);

                GLES20.glViewport(0, 0, state.size.getWidth(), state.size.getHeight());
                // Define the output framebuffer.
                // Each filter outputs into its own framebuffer object, except the
                // last filter, which outputs into the default framebuffer.
                if (!isLast) {
                    state.outputFramebuffer.bind();
                    GLES20.glClearColor(0, 0, 0, 0);
                } else {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                }

                // Perform the actual drawing.
                // The first filter should apply all the transformations. Then,
                // since they are applied, we should use a no-op matrix.
                if (isFirst) {
                    filter.draw(timestampUs, transformMatrix);
                } else {
                    filter.draw(timestampUs, Egloo.IDENTITY_MATRIX);
                }

                // Set the input for the next cycle:
                // It is the framebuffer texture from this cycle. If this is the last
                // filter, reset this value just to cleanup.
                if (!isLast) {
                    if (i == 0 || i == 2 || inputImageTexture0 == null) {
                        state.outputTexture.bind();
                    }
                    else {
                        inputImageTexture0.bind();
                    }
                } else {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                }

//                if (i == 2 && frameCount++>300) {
//                    Log.e(TAG, "--------------------------frameCount="+frameCount);
//                    frameCount = 0;
//                    saveTexture(inputImageTexture0.getId(), size.getWidth(), size.getHeight());
//
//                    Filter filter1 = filters.get(1);
//                    State state1 = states.get(filter1);
//                    saveTexture(state1.outputTexture.getId(), state1.size.getWidth(), state1.size.getHeight());
//
////                    Filter filter2 = filters.get(2);
////                    State state2 = states.get(filter2);
////                    saveTexture(GLES20.GL_TEXTURE0, state2.size.getWidth(), state2.size.getHeight());
//                }

                GLES20.glUseProgram(0);
            }
        }
    }

    @NonNull
    @Override
    public Filter copy() {
        synchronized (lock) {
            BeautyV1Filter copy = new BeautyV1Filter(context);
            if (size != null) {
                copy.setSize(size.getWidth(), size.getHeight());
            }
            for (Filter filter : filters) {
                copy.addFilter(filter.copy());
            }
            return copy;
        }
    }

    @Override
    public void setInputImageTexture0(GlTexture glTexture) {
        inputImageTexture0 = glTexture;
    }

    @Override
    public void setParameter1(float parameter1) {
        this.blurOpacity = parameter1;
        synchronized (lock) {
            for (Filter filter : filters) {
                if (filter instanceof BeautyAdjustV1Filter) {
                    ((BeautyAdjustV1Filter) filter).setParameter1(this.blurOpacity);
                }
            }
        }
    }

    @Override
    public void setParameter2(float parameter2) {
        this.skinOpacity = parameter2;
        synchronized (lock) {
            for (Filter filter : filters) {
                if (filter instanceof BeautyAdjustV1Filter) {
                    ((BeautyAdjustV1Filter) filter).setParameter2(this.skinOpacity);
                }
            }
        }
    }

    @Override
    public float getParameter1() {
        return blurOpacity;
    }

    @Override
    public float getParameter2() {
        return skinOpacity;
    }

    public void saveTexture(int texture, int width, int height) {
        int[] frame = new int[1];
        GLES20.glGenFramebuffers(1, frame, 0);
        checkGlError("glGenFramebuffers");
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frame[0]);
        checkGlError("glBindFramebuffer");
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture,
                0);
        checkGlError("glFramebufferTexture2D");

        ByteBuffer buffer = ByteBuffer.allocate(width * height * 4);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, buffer);
        checkGlError("glReadPixels");
        String fileInPath = getDCIMImagePath(context);
        saveBitmap(fileInPath, buffer, width, height);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        checkGlError("glBindFramebuffer");
        GLES20.glDeleteFramebuffers(1, frame, 0);
        checkGlError("glDeleteFramebuffer");
    }

    protected static String getDCIMImagePath(Context context) {
        String directoryPath;
        // 判断外部存储是否可用，如果不可用则使用内部存储路径
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            directoryPath =Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath();
        } else { // 使用内部存储缓存目录
            directoryPath = context.getCacheDir().getAbsolutePath();
        }
        String path = directoryPath + File.separator + Environment.DIRECTORY_PICTURES + File.separator + "SmartMike_" + System.currentTimeMillis() + ".jpeg";
        File file = new File(path);
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return path;
    }

    public static void saveBitmap(String filePath, ByteBuffer buffer, int width, int height) {
        BufferedOutputStream bos = null;
        try {
            bos = new BufferedOutputStream(new FileOutputStream(filePath));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            bitmap = rotateBitmap(bitmap, 180, true);
            bitmap = flipBitmap(bitmap, true);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
            bitmap.recycle();
            bitmap = null;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } finally {
            if (bos != null) try {
                bos.close();
            } catch (IOException e) {
                // do nothing
            }
        }
    }

    /**
     * 将Bitmap图片旋转一定角度
     * @param bitmap
     * @param rotate
     * @param isRecycled
     * @return
     */
    public static Bitmap rotateBitmap(Bitmap bitmap, int rotate, boolean isRecycled) {
        if (bitmap == null) {
            return null;
        }
        Matrix matrix = new Matrix();
        matrix.reset();
        matrix.postRotate(rotate);
        Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                bitmap.getHeight(), matrix, true);
        if (!bitmap.isRecycled() && isRecycled) {
            bitmap.recycle();
            bitmap = null;
        }
        return rotatedBitmap;
    }

    /**
     * 镜像翻转图片
     * @param bitmap
     * @param isRecycled
     * @return
     */
    public static Bitmap flipBitmap(Bitmap bitmap, boolean isRecycled) {
        return flipBitmap(bitmap, true, false, isRecycled);
    }

    /**
     * 翻转图片
     * @param bitmap
     * @param flipX
     * @param flipY
     * @param isRecycled
     * @return
     */
    public static Bitmap flipBitmap(Bitmap bitmap, boolean flipX, boolean flipY, boolean isRecycled) {
        if (bitmap == null) {
            return null;
        }
        Matrix matrix = new Matrix();
        matrix.setScale(flipX ? -1 : 1, flipY ? -1 : 1);
        matrix.postTranslate(bitmap.getWidth(), 0);
        Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                bitmap.getHeight(), matrix, false);
        if (isRecycled && bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
            bitmap = null;
        }
        return result;
    }
}
