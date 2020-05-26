package com.sabine.cameraview.internal.egl;


import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.NonNull;

import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.filter.Filter;
import com.sabine.cameraview.filter.MultiFilter;
import com.sabine.cameraview.filter.MultiParameterFilter;
import com.sabine.cameraview.filter.NoFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.filter.TwoParameterFilter;
import com.sabine.cameraview.internal.GlUtils;


public class EglViewport {

    private final static CameraLogger LOG = CameraLogger.create(EglViewport.class.getSimpleName());

    private int mProgramHandle = -1;
    private int mTextureTarget;
    private int mTextureUnit;

    private Filter mFilter;
    private Filter mPendingFilter;

    public EglViewport() {
        this(new NoFilter());
    }

    public EglViewport(@NonNull Filter filter) {
        mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        mTextureUnit = GLES20.GL_TEXTURE0;
        mFilter = filter;
        createProgram();
    }

    private void createProgram() {
        mProgramHandle = GlUtils.createProgram(mFilter.getVertexShader(),
                mFilter.getFragmentShader());
        mFilter.onCreate(mProgramHandle);
    }

    public void release() {
        if (mProgramHandle != -1) {
            mFilter.onDestroy();
            GLES20.glDeleteProgram(mProgramHandle);
            mProgramHandle = -1;
        }
    }

    public int createTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GlUtils.checkError("glGenTextures");

        int texId = textures[0];
        GLES20.glActiveTexture(mTextureUnit);
        GLES20.glBindTexture(mTextureTarget, texId);
        GlUtils.checkError("glBindTexture " + texId);

        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GlUtils.checkError("glTexParameter");

        return texId;
    }

    public void setFilter(@NonNull Filter filter, float filterLevel) {
        // TODO see if this is needed. If setFilter is always called from the correct GL thread,
        // we don't need to wait for a new draw call (which might not even happen).
        mPendingFilter = filter;
        if (mPendingFilter instanceof OneParameterFilter) {
//            ((OneParameterFilter) mPendingFilter).setParameter1(filterLevel);
            // 录制的时候会调用，会重新create，需重新设置参数
            if (mPendingFilter instanceof MultiFilter) {
                for (Filter eachFilter: ((MultiFilter) mPendingFilter).getFilters()) {
                    if (eachFilter instanceof TwoParameterFilter) {
                        float parameter1 = ((TwoParameterFilter) eachFilter).getParameter1();
                        float parameter2 = ((TwoParameterFilter) eachFilter).getParameter2();
                        if (parameter1!=0 || parameter2!=0) {
                            ((TwoParameterFilter) eachFilter).setParameter1(parameter1);
                            ((TwoParameterFilter) eachFilter).setParameter2(parameter2);
                        } else {
                            ((TwoParameterFilter) eachFilter).setParameter1(filterLevel);
                            ((TwoParameterFilter) eachFilter).setParameter2(filterLevel);
                        }
                    } else if (eachFilter instanceof OneParameterFilter) {
                        float parameter1 = ((OneParameterFilter) eachFilter).getParameter1();
                        if (parameter1 != 0) {
                            ((OneParameterFilter) eachFilter).setParameter1(parameter1);
                        } else {
                            ((OneParameterFilter) eachFilter).setParameter1(filterLevel);
                        }
                    } else if (eachFilter instanceof MultiParameterFilter) {
                        float[] parameterMulti = ((MultiParameterFilter) eachFilter).getParameterMulti();
                        if (parameterMulti != null) {
                            ((MultiParameterFilter) eachFilter).setParameterMulti(parameterMulti);
                        }
                    }
                }
            } else {
                ((OneParameterFilter) mPendingFilter).setParameter1(filterLevel);
            }
        }
    }

    public void drawFrame(long timestampUs, int textureId, float[] textureMatrix) {
        if (mPendingFilter != null) {
            release();
            mFilter = mPendingFilter;
            mPendingFilter = null;
            createProgram();
        }

        GlUtils.checkError("draw start");

        // Select the program and the active texture.
        GLES20.glUseProgram(mProgramHandle);
        GlUtils.checkError("glUseProgram");
        GLES20.glActiveTexture(mTextureUnit);
        GLES20.glBindTexture(mTextureTarget, textureId);

        // Draw.
        mFilter.draw(timestampUs, textureMatrix);

        // Release.
        GLES20.glBindTexture(mTextureTarget, 0);
        GLES20.glUseProgram(0);
    }
}
