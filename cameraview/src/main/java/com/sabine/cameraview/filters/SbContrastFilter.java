package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

public class SbContrastFilter extends BaseFilter implements OneParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying highp vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            " \n" +
            " uniform samplerExternalOES sTexture;\n" +
            " uniform lowp float contrast;\n" +
            " \n" +
            " void main()\n" +
            " {\n" +
            "     lowp vec4 textureColor = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ");\n" +
            "     \n" +
            "     gl_FragColor = vec4(((textureColor.rgb - vec3(0.5)) * contrast + vec3(0.5)), textureColor.w);\n" +
            " }";

    private float contrast = 0f;
    private int contrastLocation = -1;

    public float getContrast() {
        return contrast;
    }

    public void setContrast(float contrast) {
        this.contrast = contrast;
    }

    @Override
    public void setParameter1(float value) {
        setContrast(value);
    }

    @Override
    public float getParameter1() {
        return getContrast();
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        contrastLocation = GLES20.glGetUniformLocation(programHandle, "contrast");
        GlUtils.checkLocation(contrastLocation, "contrast");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        contrastLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        GLES20.glUniform1f(contrastLocation, contrast);
        GlUtils.checkError("glUniform1f");
    }

}
