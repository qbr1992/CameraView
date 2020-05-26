package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

public class SbBrightnessFilter extends BaseFilter implements OneParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying highp vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            " \n" +
            " uniform samplerExternalOES sTexture;\n" +
            " uniform lowp float brightness;\n" +
            " \n" +
            " void main()\n" +
            " {\n" +
            "     lowp vec4 textureColor = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ");\n" +
            "     \n" +
            "     gl_FragColor = vec4((textureColor.rgb + vec3(brightness)), textureColor.w);\n" +
            " }";

    private float brightness = 0f;
    private int brightnessLocation = -1;

    public float getBrightness() {
        return brightness;
    }

    public void setBrightness(float brightness) {
        this.brightness = brightness;
    }

    @Override
    public void setParameter1(float value) {
        setBrightness(value);
    }

    @Override
    public float getParameter1() {
        return getBrightness();
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        brightnessLocation = GLES20.glGetUniformLocation(programHandle, "brightness");
        GlUtils.checkLocation(brightnessLocation, "brightness");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        brightnessLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        GLES20.glUniform1f(brightnessLocation, brightness);
        GlUtils.checkError("glUniform1f");
    }

}
