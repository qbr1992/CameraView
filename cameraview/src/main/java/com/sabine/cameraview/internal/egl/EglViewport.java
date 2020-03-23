package com.sabine.cameraview.internal.egl;


import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.NonNull;

import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.filter.Filter;
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
    private float filterLevel;

    public EglViewport() {
        this(new NoFilter(), 0);
    }

    public EglViewport(@NonNull Filter filter, float filterLevel) {
        mTextureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        mTextureUnit = GLES20.GL_TEXTURE0;
        mFilter = filter;
        this.filterLevel = filterLevel;
        if (mFilter instanceof OneParameterFilter) {
            ((OneParameterFilter) mFilter).setParameter1(filterLevel);
        }
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
            ((OneParameterFilter) mPendingFilter).setParameter1(filterLevel);
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
