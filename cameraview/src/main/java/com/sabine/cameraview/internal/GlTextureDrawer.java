package com.sabine.cameraview.internal;


import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.CameraLogger;
import com.sabine.cameraview.filter.Filter;
import com.sabine.cameraview.filter.NoFilter;
import com.otaliastudios.opengl.core.Egloo;
import com.otaliastudios.opengl.program.GlProgram;
import com.otaliastudios.opengl.texture.GlTexture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;


public class GlTextureDrawer {

    private final static String TAG = GlTextureDrawer.class.getSimpleName();
    private final static CameraLogger LOG = CameraLogger.create(TAG);

    private final static int TEXTURE_TARGET = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
    private final static int TEXTURE_UNIT = GLES20.GL_TEXTURE0;

//    private final GlTexture mTexture;
    private List<GlTexture> mTexture = new ArrayList<>();
    private float[] mTextureTransform = Egloo.IDENTITY_MATRIX.clone();

    @NonNull
    private Filter mFilter = new NoFilter();
    private Filter mPendingFilter = null;
    private int mProgramHandle = -1;

    //前置摄像头是否显示在第一路输入
    private boolean mFrontIsFirst = false;

    //当前显示的Texture最大数量
    private  int mMaxTexture = 1;

    /**
     * 初始化接口，生成新的渲染纹理textureId
     * 该接口必须在GLThread线程中调用
     */
    @SuppressWarnings("unused")
    public GlTextureDrawer() {
        this(new GlTexture(TEXTURE_UNIT, TEXTURE_TARGET));
    }

    /**
     * 初始化接口，生成多个新的渲染纹理textureId
     * 该接口必须在GLThread线程中调用
     * @param textureSize 生成的渲染纹理数量
     * @param frontIsFirst 初始前置摄像头是否显示在第一路
     */
    @SuppressWarnings("unused")
    public GlTextureDrawer(int textureSize, boolean frontIsFirst) {
        for (int i = 0; i < textureSize; i++)
            mTexture.add(new GlTexture(TEXTURE_UNIT+i, TEXTURE_TARGET));
        mFrontIsFirst = frontIsFirst;
    }

    @SuppressWarnings("unused")
    public GlTextureDrawer(int textureId) {
        this(new GlTexture(TEXTURE_UNIT, TEXTURE_TARGET, textureId));
    }

    @SuppressWarnings("unused")
    public GlTextureDrawer(int[] textureIds, boolean frontIsFirst) {
        int i = 0;
        for (int textureId:textureIds)
            mTexture.add(new GlTexture(TEXTURE_UNIT + i++, TEXTURE_TARGET, textureId));
        mMaxTexture = textureIds.length;
        mFrontIsFirst = frontIsFirst;
    }

    @SuppressWarnings("unused")
    public GlTextureDrawer(int[] textureIds, int textureTarget, boolean frontIsFirst) {
        int i = 0;
        for (int textureId:textureIds)
            mTexture.add(new GlTexture(TEXTURE_UNIT + i++, textureTarget, textureId));
        mMaxTexture = textureIds.length;
        mFrontIsFirst = frontIsFirst;
    }

    @SuppressWarnings("WeakerAccess")
    public GlTextureDrawer(@NonNull GlTexture texture) {
        mTexture.add(texture);
    }

    public void changeTextureId(int index, int textureId) {
        if (index>=mTexture.size())
            return;
        if (mTexture.get(index).getId() != textureId) {
            GlTexture glTexture = new GlTexture(mTexture.get(index).getUnit(), mTexture.get(index).getTarget(), textureId);
            mTexture.set(index, glTexture);
        }
    }

    public void setFilter(@NonNull Filter filter) {
        mPendingFilter = filter;
    }

    public Filter getFilter() {
        if (mPendingFilter != null)
            return mPendingFilter;
        else
            return mFilter;
    }

    public void setFilterSize(int width, int hegiht) {
        if (mPendingFilter != null) {
            mPendingFilter.setSize(width, hegiht);
        } else {
            mFilter.setSize(width, hegiht);
        }
    }

    /**
     * 获取索引为index-1的Texture
     * index从0开始
     *
     * @param index mTexture的索引
     */
    @NonNull
    public GlTexture getTexture(int index) {
        if (index < 0)
            index = 0;
        if (index >= mTexture.size()) {
            return null;
        }

        if (index+1 > mMaxTexture) {
            mMaxTexture = index+1;
            if (mPendingFilter != null) {
                if (mTexture.size() > 1 && mMaxTexture > 1) {
                    mPendingFilter.setSecondTexture(mTexture.get(1), mFrontIsFirst ? 1.0f : 0.0f);
                } else {
                    mFrontIsFirst = false;
                    mPendingFilter.setSecondTexture(null, mFrontIsFirst ? 1.0f : 0.0f);
                }
            } else {
                if (mTexture.size() > 1 && mMaxTexture > 1) {
                    mFilter.setSecondTexture(mTexture.get(1), mFrontIsFirst ? 1.0f : 0.0f);
                } else {
                    mFrontIsFirst = false;
                    mFilter.setSecondTexture(null, mFrontIsFirst ? 1.0f : 0.0f);
                }
            }
        }

        return mTexture.get(index);
    }

    /**
     * 删除索引为index的Texture
     * index从0开始
     *
     * @param index mTexture的索引
     */
    public void removeTexture(int index) {
        if (index < mTexture.size()) {
            mTexture.remove(index);
        }
    }

    /**
     * 删除全部Texture
     *
     */
    public void removeAllTexture() {
        mTexture.clear();
    }

    public int getMaxTexture() {
        return mMaxTexture;
    }

    @NonNull
    public float[] getTextureTransform() {
        return mTextureTransform;
    }

    public void setTextureTransform(@NonNull float[] textureTransform) {
        mTextureTransform = textureTransform;
    }

    public boolean getFrontIsFirst() {
        return mFrontIsFirst;
    }

    public void setFrontIsFirst(boolean frontIsFirst) {
        mFrontIsFirst = frontIsFirst;
    }

    public void draw(final long timestampNs, int drawRotation) {
        if (mPendingFilter != null) {

            if (mProgramHandle != -1) {
                mFilter.onDestroy();
                GLES20.glDeleteProgram(mProgramHandle);
                mProgramHandle = -1;
            }

            mFilter = mPendingFilter;
            mPendingFilter = null;
        }

        if (mProgramHandle == -1) {
            mProgramHandle = GlProgram.create(
                    mFilter.getVertexShader(),
                    mFilter.getFragmentShader());
            mFilter.onCreate(mProgramHandle);
            Egloo.checkGlError("program creation");
            if (mTexture.size() > 1 && mMaxTexture > 1) {
                mFilter.setSecondTexture(mTexture.get(1), mFrontIsFirst?1.0f:0.0f, drawRotation*1.0f);
            }
            else {
                mFrontIsFirst = false;
                mFilter.setSecondTexture(null, mFrontIsFirst?1.0f:0.0f, drawRotation*1.0f);
            }
        }

        GLES20.glUseProgram(mProgramHandle);
        Egloo.checkGlError("glUseProgram(handle)");

        mTexture.get(0).bind();
        mFilter.draw(timestampNs, mTextureTransform);
        mTexture.get(0).unbind();

        GLES20.glUseProgram(0);
        Egloo.checkGlError("glUseProgram(0)");
    }

    public void draw(GlTexture glTexture, final long timestampNs, int drawRotation) {
        if (mPendingFilter != null) {

            if (mProgramHandle != -1) {
                mFilter.onDestroy();
                GLES20.glDeleteProgram(mProgramHandle);
                mProgramHandle = -1;
            }

            mFilter = mPendingFilter;
            mPendingFilter = null;
        }

        if (mProgramHandle == -1) {
            mProgramHandle = GlProgram.create(
                    mFilter.getVertexShader(),
                    mFilter.getFragmentShader());
            mFilter.onCreate(mProgramHandle);
            Egloo.checkGlError("program creation");

            mFrontIsFirst = false;
            mFilter.setSecondTexture(null, mFrontIsFirst?1.0f:0.0f, drawRotation*1.0f);
        }

//        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
//        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(mProgramHandle);
        Egloo.checkGlError("glUseProgram(handle)");

        glTexture.bind();
        mFilter.draw(timestampNs, mTextureTransform);
        glTexture.unbind();

        GLES20.glUseProgram(0);
        Egloo.checkGlError("glUseProgram(0)");
    }

    public void release() {
        if (mProgramHandle == -1) return;
        mFilter.onDestroy();
        GLES20.glDeleteProgram(mProgramHandle);
        mProgramHandle = -1;

        for (GlTexture glTexture : mTexture)
            glTexture.release();
        mTexture.clear();
    }

    public void switchInputTexture() {
        int nSize = mTexture.size();
        if (nSize>1) {
            List<GlTexture> newTextures = new ArrayList<>();
            int i = 1;
            for (i = 1; i < nSize; i++) {
                newTextures.add(new GlTexture(TEXTURE_UNIT + i - 1, TEXTURE_TARGET, mTexture.get(i).getId()));
            }
            newTextures.add(new GlTexture(TEXTURE_UNIT + i - 1, TEXTURE_TARGET, mTexture.get(0).getId()));
            mTexture = newTextures;
            mFrontIsFirst = !mFrontIsFirst;
            mFilter.setSecondTexture(mTexture.get(1), mFrontIsFirst?1.0f:0.0f);
        }
    }

    public void setDualInputTextureMode(float dualInputTextureMode) {
        if (mPendingFilter != null) {
            mPendingFilter.setDualInputTextureMode(dualInputTextureMode);
        } else {
            mFilter.setDualInputTextureMode(dualInputTextureMode);
        }
    }

    public void setPreviewAspectRatio(float aspectRatio) {
        if (mPendingFilter != null) {
            mPendingFilter.setAspectRatio(aspectRatio);
        } else {
            mFilter.setAspectRatio(aspectRatio);
        }
    }

    public void reset() {
        mMaxTexture = 1;
        mFrontIsFirst = false;
        if (mPendingFilter != null) {
            mPendingFilter.setSecondTexture(null, mFrontIsFirst ? 1.0f : 0.0f);
        } else {
            mFilter.setSecondTexture(null, mFrontIsFirst ? 1.0f : 0.0f);
        }
    }
}
