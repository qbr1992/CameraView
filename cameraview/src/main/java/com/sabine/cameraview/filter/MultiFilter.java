package com.sabine.cameraview.filter;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.sabine.cameraview.filters.BeautyAdjustV1Filter;
import com.sabine.cameraview.filters.GaussianPassFilter;
import com.sabine.cameraview.filters.SbBrightnessFilter;
import com.sabine.cameraview.size.Size;
import com.otaliastudios.opengl.core.Egloo;
import com.otaliastudios.opengl.program.GlProgram;
import com.otaliastudios.opengl.program.GlTextureProgram;
import com.otaliastudios.opengl.texture.GlFramebuffer;
import com.otaliastudios.opengl.texture.GlTexture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link MultiFilter} is a special {@link Filter} that can group one or more filters together.
 * When this happens, filters are applied in sequence:
 * - the first filter reads from input frames
 * - the second filters reads the output of the first
 * And so on, until the last filter which will read from the previous and write to the real output.
 *
 * New filters can be added at any time through {@link #addFilter(Filter)}, but currently they
 * can not be removed because we can not easily ensure that they would be correctly released.
 *
 * The {@link MultiFilter} does also implement {@link OneParameterFilter} and
 * {@link TwoParameterFilter}, dispatching all the parameter calls to child filters,
 * assuming they support it.
 *
 * There are some important technical caveats when using {@link MultiFilter}:
 * - each child filter requires the allocation of a GL framebuffer. Using a large number of filters
 *   will likely cause memory issues (e.g. https://stackoverflow.com/q/6354208/4288782).
 * - some of the children need to write into {@link GLES20#GL_TEXTURE_2D} instead of
 *   {@link GLES11Ext#GL_TEXTURE_EXTERNAL_OES}! To achieve this, we replace samplerExternalOES
 *   with sampler2D in your fragment shader code. This might cause issues for some shaders.
 */
@SuppressWarnings("unused")
public class MultiFilter implements Filter, OneParameterFilter, TwoParameterFilter {

    @VisibleForTesting
    static class State {
        @VisibleForTesting boolean isProgramCreated = false;
        @VisibleForTesting boolean isFramebufferCreated = false;
        @VisibleForTesting Size size = null;
        private int programHandle = -1;
        private GlFramebuffer outputFramebuffer = null;
        private GlTexture outputTexture = null;
    }

    @VisibleForTesting final List<Filter> filters = new ArrayList<>();
    @VisibleForTesting final Map<Filter, State> states = new HashMap<>();
    private final Object lock = new Object();
    private Size size = null;
    private Size mOutputSize = null;
    private float parameter1 = 0F;
    private float parameter2 = 0F;

    private GlTexture inputImageTexture0;
    private static String TAG = MultiFilter.class.getSimpleName();
    private GlTexture lastFramebufferTexture = null;

    private static int MAX_FRAMEBUFFER = 30;
//    private GlFramebuffer[] outputFramebuffer = new GlFramebuffer[MAX_FRAMEBUFFER];
//    private GlTexture[] outputFramebufferTexture = new GlTexture[MAX_FRAMEBUFFER];
    private offscreenTexture[] offscreenTextures = new offscreenTexture[MAX_FRAMEBUFFER];
    private int outputFrameBufferIndex = -1;

    /**
     * Creates a new group with the given filters.
     * @param filters children
     */
    public MultiFilter(@NonNull Filter... filters) {
        this(Arrays.asList(filters));
    }

    /**
     * Creates a new group with the given filters.
     * @param filters children
     */
    @SuppressWarnings("WeakerAccess")
    public MultiFilter(@NonNull Collection<Filter> filters) {
        for (Filter filter : filters) {
            addFilter(filter);
        }
    }

    /**
     * Adds a new filter. It will be used in the next frame.
     * If the filter is a {@link MultiFilter}, we'll use its children instead.
     *
     * @param filter a new filter
     */
    @SuppressWarnings("WeakerAccess")
    public void addFilter(@NonNull Filter filter) {
        if (filter instanceof MultiFilter) {
            MultiFilter multiFilter = (MultiFilter) filter;
            for (Filter multiChild : multiFilter.filters) {
                addFilter(multiChild);
            }
            return;
        }
        synchronized (lock) {
            if (!filters.contains(filter)) {
                filters.add(filter);
                states.put(filter, new State());
            }
        }
    }

    /**
     * Adds a new filter. It will be used in the next frame.
     * If the filter is a {@link MultiFilter}, we'll use its children instead.
     *
     * @param index add new filter from index
     * @param filter a new filter
     */
    @SuppressWarnings("WeakerAccess")
    public void addFilter(int index, @NonNull Filter filter) {
        if (!filters.contains(filter)) {
            filters.add(index, filter);
            states.put(filter, new State());
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
        if (state.isFramebufferCreated || isLast) return;
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
        if (size != null && !size.equals(state.size)) {
            state.size = size;
            if (filter != null) {
                filter.setSize(size.getWidth(), size.getHeight());
            }
        }
    }

    private void maybeSetSize(@NonNull Filter filter, int width, int height) {
        State state = states.get(filter);
        //noinspection ConstantConditions
        Size newSize = new Size(width, height);
        if (!newSize.equals(state.size)) {
            state.size = newSize;
            filter.setSize(width, height);
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

            for (int i = 0; i < MAX_FRAMEBUFFER; i++) {
//                if (outputFramebufferTexture[i] != null) {
//                    outputFramebufferTexture[i].release();
//                    outputFramebufferTexture[i] = null;
//                    outputFramebuffer[i].release();
//                    outputFramebuffer[i] = null;
//                }
                if (offscreenTextures[i] != null) {
                    offscreenTextures[i].outputFramebufferTexture.release();
                    offscreenTextures[i].outputFramebufferTexture = null;
                    offscreenTextures[i].outputFramebuffer.release();
                    offscreenTextures[i].outputFramebuffer = null;
                    offscreenTextures[i] = null;
                }
            }
        }
    }

    @Override
    public void setSize(int width, int height) {
        if (size == null || width != size.getWidth() || height != size.getHeight()) {
            size = new Size(width, height);
            synchronized (lock) {
                for (Filter filter : filters) {
                    maybeDestroyFramebuffer(filter);
                }

                for (int i = 0; i < MAX_FRAMEBUFFER; i++) {
//                    if (outputFramebufferTexture[i] != null) {
//                        outputFramebufferTexture[i].release();
//                        outputFramebufferTexture[i] = null;
//                        outputFramebuffer[i].release();
//                        outputFramebuffer[i] = null;
//                    }
                    if (offscreenTextures[i] != null) {
                        offscreenTextures[i].outputFramebufferTexture.release();
                        offscreenTextures[i].outputFramebufferTexture = null;
                        offscreenTextures[i].outputFramebuffer.release();
                        offscreenTextures[i].outputFramebuffer = null;
                        offscreenTextures[i] = null;
                    }
                }

                for (Filter filter : filters) {
                    maybeSetSize(filter);
                }
            }
        }
    }

    @Override
    public void setSize(int width, int height, int inputStreamWidth, int inputStreamHeight) {
        if (filters.size() > 0) {
            synchronized (lock) {
                if (size == null || inputStreamWidth != size.getWidth() || inputStreamHeight != size.getHeight()) {
                    size = new Size(inputStreamWidth, inputStreamHeight);

                    for (Filter filter : filters) {
                        maybeDestroyFramebuffer(filter);
                    }
                    for (int i = 0; i < MAX_FRAMEBUFFER; i++) {
//                        if (outputFramebufferTexture[i] != null) {
//                            outputFramebufferTexture[i].release();
//                            outputFramebufferTexture[i] = null;
//                            outputFramebuffer[i].release();
//                            outputFramebuffer[i] = null;
//                        }
                        if (offscreenTextures[i] != null) {
                            offscreenTextures[i].outputFramebufferTexture.release();
                            offscreenTextures[i].outputFramebufferTexture = null;
                            offscreenTextures[i].outputFramebuffer.release();
                            offscreenTextures[i].outputFramebuffer = null;
                            offscreenTextures[i] = null;
                        }
                    }
                    for (int i = 0; i < filters.size() - 1; i++) {
                        Filter filter = filters.get(i);
                        maybeSetSize(filter);
                    }
                }

                if (mOutputSize == null || mOutputSize.getWidth() != width || mOutputSize.getHeight() != height) {
                    mOutputSize = new Size(width, height);
                }

                Filter filter = filters.get(filters.size() - 1);
                maybeSetSize(filter, width, height);
            }
        }
    }

    @Override
    public Size getSize() {
        return size;
    }

    @Override
    public void draw(long timestampNs, @NonNull float[] transformMatrix) {
        synchronized (lock) {
            int filterSize = filters.size();
            for (int i = 0; i < filterSize; i++) {
                boolean isFirst = i == 0;
                boolean isLast = i == filterSize - 1;
                boolean isLastFrameBuffer = i == filterSize - 2;
                Filter filter = filters.get(i);
                State state = states.get(filter);

//                if (filter instanceof BeautyV1Filter) {
//                    ((BeautyV1Filter) filter).draw(timestampUs, transformMatrix, isFirst, isLast);
//                    continue;
//                }

                if (state.size == null || state.size.getWidth() == 0 || state.size.getHeight() == 0) {
                    if (isLast) maybeSetSize(filter, mOutputSize.getWidth(), mOutputSize.getHeight());
                    else maybeSetSize(filter);
                }

                maybeCreateProgram(filter, isFirst, isLast);
                maybeCreateFramebuffer(filter, isFirst, isLast);
                if (isLastFrameBuffer) {
//                    if (lastFramebufferTexture!=null)
//                        LogUtil.e(TAG, "size:"+filters.size()+", i:"+i+", lastFramebufferTexture.id:"+lastFramebufferTexture.getId()+", state.outputTexture.id:"+state.outputTexture.getId());
//                    if (outputFrameBufferIndex>=0)
//                        lastFramebufferTexture = outputFramebufferTexture[outputFrameBufferIndex];
                    outputFrameBufferIndex = (outputFrameBufferIndex+1)%MAX_FRAMEBUFFER;
//                    if (outputFramebufferTexture[outputFrameBufferIndex] == null) {
//                        outputFramebufferTexture[outputFrameBufferIndex] = new GlTexture(GLES20.GL_TEXTURE0,
//                                GLES20.GL_TEXTURE_2D,
//                                state.size.getWidth(),
//                                state.size.getHeight());
//                        outputFramebuffer[outputFrameBufferIndex] = new GlFramebuffer();
//                        outputFramebuffer[outputFrameBufferIndex].attach(outputFramebufferTexture[outputFrameBufferIndex]);
//                    }
                    if (offscreenTextures[outputFrameBufferIndex] == null) {
                        offscreenTextures[outputFrameBufferIndex] = new offscreenTexture();
                        offscreenTextures[outputFrameBufferIndex].outputFramebufferTexture = new GlTexture(GLES20.GL_TEXTURE0,
                                GLES20.GL_TEXTURE_2D,
                                state.size.getWidth(),
                                state.size.getHeight());
                        offscreenTextures[outputFrameBufferIndex].outputFramebuffer = new GlFramebuffer();
                        offscreenTextures[outputFrameBufferIndex].outputFramebuffer.attach(offscreenTextures[outputFrameBufferIndex].outputFramebufferTexture);
                    }
//                    lastFramebufferTexture = outputFramebufferTexture[outputFrameBufferIndex];
//                    lastFramebufferTexture = state.outputTexture;
                }

                if (isLast) {
                    GLES20.glViewport(0, 0, state.size.getWidth(), state.size.getHeight());
                } else {
                    if (isLastFrameBuffer) {
//                        outputFramebuffer[outputFrameBufferIndex].bind();
                        offscreenTextures[outputFrameBufferIndex].outputFramebuffer.bind();
                        offscreenTextures[outputFrameBufferIndex].timestampNs = timestampNs;
                    }
                    else
                        state.outputFramebuffer.bind();
                }
                //noinspection ConstantConditions
                GLES20.glUseProgram(state.programHandle);

                // Define the output framebuffer.
                // Each filter outputs into its own framebuffer object, except the
                // last filter, which outputs into the default framebuffer.
//                if (!isLast) {
//                    state.outputFramebuffer.bind();
////                    GLES20.glClearColor(0, 0, 0, 0);
//                }/* else {
//                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
//                }*/

                // Perform the actual drawing.
                // The first filter should apply all the transformations. Then,
                // since they are applied, we should use a no-op matrix.
                if (isFirst) {
                    filter.draw(timestampNs, transformMatrix);
                } else {
                    filter.draw(timestampNs, Egloo.IDENTITY_MATRIX);
                }

                // Set the input for the next cycle:
                // It is the framebuffer texture from this cycle. If this is the last
                // filter, reset this value just to cleanup.
                if (!isLast) {
//                    state.outputTexture.bind();
//                    if (filters.get(i+1) instanceof BeautyV1Filter) {
//                        filters.get(i+1).setInputImageTexture0(state.outputTexture);
//                    }

//                    if (i+1 == filters.size() - 1) {
//                        GLES20.glViewport(0, 0, state.size.getWidth(), state.size.getHeight());
//                    }

                    if (filters.get(i+1) instanceof BeautyAdjustV1Filter && inputImageTexture0 != null) {
                        ((BeautyAdjustV1Filter)filters.get(i+1)).setBlurTexture(state.outputTexture.getId());
                        inputImageTexture0.bind();
                    } else {
                        if (filter instanceof NoFilter || filter instanceof DualInputTextureFilter) {
                            inputImageTexture0 = state.outputTexture;
                        }
                        if (isLastFrameBuffer)
//                            outputFramebufferTexture[outputFrameBufferIndex].bind();
                            offscreenTextures[outputFrameBufferIndex].outputFramebufferTexture.bind();
                        else
                            state.outputTexture.bind();
                    }
                    if (isLastFrameBuffer)
//                        outputFramebuffer[outputFrameBufferIndex].unbind();
                        offscreenTextures[outputFrameBufferIndex].outputFramebuffer.unbind();
                    else
                        state.outputFramebuffer.unbind();
                } /*else {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                }
*/

                GLES20.glUseProgram(0);
            }
        }
    }

    @NonNull
    @Override
    public Filter copy() {
        synchronized (lock) {
            MultiFilter copy = new MultiFilter();
//            if (size != null) {
//                copy.setSize(size.getWidth(), size.getHeight());
//            }
            for (Filter filter : filters) {
                if (filter instanceof GaussianPassFilter) {
                    GaussianPassFilter oldFilter = (GaussianPassFilter) filter;
                    GaussianPassFilter gaussianPassFilter = (GaussianPassFilter) filter.copy();
                    gaussianPassFilter.setFilterOrientation(oldFilter.getFilterOrientation());
                    copy.addFilter(gaussianPassFilter);
                } else if (filter instanceof BeautyAdjustV1Filter) {
                    BeautyAdjustV1Filter beautyAdjustV1Filter = (BeautyAdjustV1Filter) filter.copy();
                    beautyAdjustV1Filter.setLutTexture(((BeautyAdjustV1Filter)filter).getLutTexture());
                    copy.addFilter(beautyAdjustV1Filter);
                } else {
                    copy.addFilter(filter.copy());
                }
            }
            if (size != null) {
                copy.setSize(size.getWidth(), size.getHeight());
            }
            return copy;
        }
    }

    public Filter copyBeautyFilter() {
        synchronized (lock) {
            MultiFilter copy = new MultiFilter();
//            if (size != null) {
//                copy.setSize(size.getWidth(), size.getHeight());
//            }

            for (Filter filter : filters) {
                if (filter instanceof GaussianPassFilter) {
                    GaussianPassFilter gaussianPassFilter = (GaussianPassFilter) filter.copy();
                    gaussianPassFilter.setFilterOrientation(((GaussianPassFilter) filter).getFilterOrientation());
                    copy.addFilter(gaussianPassFilter);
                } else if (filter instanceof BeautyAdjustV1Filter) {
                    BeautyAdjustV1Filter beautyAdjustV1Filter = (BeautyAdjustV1Filter) filter.copy();
                    beautyAdjustV1Filter.setLutTexture(((BeautyAdjustV1Filter)filter).getLutTexture());
                    copy.addFilter(beautyAdjustV1Filter);
                } else if (filter instanceof NoFilter || filter instanceof DualInputTextureFilter) {
                    copy.addFilter(filter.copy());
                }
            }
            if (size != null) {
                copy.setSize(size.getWidth(), size.getHeight());
            }
            return copy;
        }
    }

    public Filter copyBaseFilter() {
        synchronized (lock) {
            MultiFilter copy = new MultiFilter();

            if (filters.size()>0)
                copy.addFilter(filters.get(0).copy());

            //TODO:如果有美颜组合滤镜，copy美颜组合滤镜的2个GaussianPassFilter、1个BeautyAdjustV1Filter滤镜和1个SbBrightnessFilter，如果addBeautyFilter中美颜组合滤镜有改动，需要同步修改下边copy滤镜的代码
            if (getBeautyFilter() != null && filters.size()>4) {
                for (int i = 1; i < 4; i++) {
                    Filter filter = filters.get(i);
                    if (filter instanceof GaussianPassFilter) {
                        GaussianPassFilter gaussianPassFilter = (GaussianPassFilter) filter.copy();
                        gaussianPassFilter.setFilterOrientation(((GaussianPassFilter) filter).getFilterOrientation());
                        copy.addFilter(gaussianPassFilter);
                    } else if (filter instanceof BeautyAdjustV1Filter) {
                        BeautyAdjustV1Filter beautyAdjustV1Filter = (BeautyAdjustV1Filter) filter.copy();
                        beautyAdjustV1Filter.setLutTexture(((BeautyAdjustV1Filter)filter).getLutTexture());
                        copy.addFilter(beautyAdjustV1Filter);
                    } else {
                        copy.addFilter(filter.copy());
                    }
                }
            }

            if (size != null) {
                copy.setSize(size.getWidth(), size.getHeight());
            }
            return copy;
        }
    }

    public Filter getBaseFilter() {
        synchronized (lock) {
            int nBaseSize = 1;
            if (getBeautyFilter() != null && filters.size()>4) {
                nBaseSize = 4;
            }
            int index = 0;
            while (filters.size() > nBaseSize) {
                index = filters.size()-1;
                Filter filter = filters.get(index);
                maybeDestroyFramebuffer(filter);
                maybeDestroyProgram(filter);
                filters.remove(index);
            }

            return this;
        }
    }

    @Override
    public void setInputImageTexture0(GlTexture glTexture) {
//        inputImageTexture0 = glTexture;
//        synchronized (lock) {
//            for (Filter filter : filters) {
//                if (filter instanceof BeautyV1Filter) {
//                    filter.setInputImageTexture0(glTexture);
//                    break;
//                }
//            }
//        }
    }

    @Override
    public void setSecondTexture(GlTexture secondTexture, float frontIsFirst) {
        for (Filter filter : filters) {
            if (filter instanceof DualInputTextureFilter) {
                filter.setSecondTexture(secondTexture, frontIsFirst);
                break;
            }
        }
    }

    @Override
    public void setSecondTexture(GlTexture secondTexture, float frontIsFirst, float drawRotation) {
        for (Filter filter : filters) {
            if (filter instanceof DualInputTextureFilter) {
                filter.setSecondTexture(secondTexture, frontIsFirst, drawRotation);
                break;
            }
        }
    }

    @Override
    public void setDualInputTextureMode(float inputTextureMode) {
        for (Filter filter : filters) {
            if (filter instanceof DualInputTextureFilter) {
                ((DualInputTextureFilter) filter).setParameter2(inputTextureMode);
                break;
            }
        }
    }

    @Override
    public void setAspectRatio(float aspectRatio) {
        for (Filter filter : filters) {
            if (filter instanceof DualInputTextureFilter) {
                ((DualInputTextureFilter) filter).setParameter1(aspectRatio);
                break;
            }
        }
    }

    @Override
    public offscreenTexture getLastOutputTextureId() {
//        if (lastFramebufferTexture != null)
//            return lastFramebufferTexture;
        int last = (outputFrameBufferIndex+MAX_FRAMEBUFFER-5)%MAX_FRAMEBUFFER;
//        if (last>=0 && outputFramebufferTexture[last] != null)
//            return outputFramebufferTexture[last];
        if (last>=0 && offscreenTextures[last] != null)
            return offscreenTextures[last];
        return null;
//        else {
//            if (filters.size() > 1) {
//                Filter filter = filters.get(filters.size() - 2);
//                State state = states.get(filter);
//                return state.outputTexture;
//            }
//            return null;
//        }
    }

    @Override
    public void setParameter1(float parameter1) {
        this.parameter1 = parameter1;
        synchronized (lock) {
            for (Filter filter : filters) {
                if (filter instanceof OneParameterFilter) {
                    ((OneParameterFilter) filter).setParameter1(parameter1);
                }
            }
        }
    }

    @Override
    public void setParameter2(float parameter2) {
        this.parameter2 = parameter2;
        synchronized (lock) {
            for (Filter filter : filters) {
                if (filter instanceof TwoParameterFilter) {
                    ((TwoParameterFilter) filter).setParameter2(parameter2);
                }
            }
        }
    }

    @Override
    public float getParameter1() {
        return parameter1;
    }

    @Override
    public float getParameter2() {
        return parameter2;
    }

    /**
     * 获取美颜滤镜
     * @return
     */
//    public BeautyV1Filter getBeautyFilter() {
//        for (Filter filter : filters) {
//            if (filter instanceof BeautyV1Filter) {
//                return (BeautyV1Filter) filter;
//            }
//        }
//        return null;
//    }
    public BeautyAdjustV1Filter getBeautyFilter() {
        synchronized (lock) {
            for (Filter filter : filters) {
                if (filter instanceof BeautyAdjustV1Filter) {
                    return (BeautyAdjustV1Filter) filter;
                }
            }
        }
        return null;
    }

    public void addBeautyFilter(Context context) {
        if (getBeautyFilter() != null)
            return;

        synchronized (lock) {
            //TODO:添加美颜组合滤镜，包括2个GaussianPassFilter、1个BeautyAdjustV1Filter滤镜和1个SbBrightnessFilter，如果组合滤镜有改动，需要同步修改removeBeautyFilter和copyBeautyFilter、copyBaseFilter接口的代码
            int addIndex = 1;
            GaussianPassFilter gaussVBlurFilter = new GaussianPassFilter();
            gaussVBlurFilter.setFilterOrientation(true);
            gaussVBlurFilter.setDistanceNormalizationFactor(2.746f);
            addFilter(addIndex++, gaussVBlurFilter);

            GaussianPassFilter gaussHBlurFilter = new GaussianPassFilter();
            gaussHBlurFilter.setFilterOrientation(false);
            gaussHBlurFilter.setDistanceNormalizationFactor(2.746f);
            addFilter(addIndex++, gaussHBlurFilter);

            BeautyAdjustV1Filter beautyAdjustV1Filter = new BeautyAdjustV1Filter(context);
            addFilter(addIndex++, beautyAdjustV1Filter);

//            SbBrightnessFilter brightnessFilter = new SbBrightnessFilter();
//            brightnessFilter.setBrightness(0.05f);
//            addFilter(addIndex++, brightnessFilter);
        }
    }

    public void removeBeautyFilter() {
        synchronized (lock) {
            if (getBeautyFilter() == null || filters.size() < 4)
                return;

            //TODO:如果有美颜组合滤镜，remove美颜组合滤镜的2个GaussianPassFilter、1个BeautyAdjustV1Filter滤镜和1个SbBrightnessFilter，如果addBeautyFilter中美颜组合滤镜有改动，需要同步修改下边remove滤镜的代码
            for (int i = 0; i < 3; i++) {
                Filter filter = filters.get(1);
                maybeDestroyFramebuffer(filter);
                states.remove(filter);
                filters.remove(1);
            }
        }
    }

    /**
     * 获取Filter列表
     * @return
     */
    public List<Filter> getFilters() {
        return filters;
    }

}
