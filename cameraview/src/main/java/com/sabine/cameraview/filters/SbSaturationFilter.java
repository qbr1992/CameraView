package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

public class SbSaturationFilter extends BaseFilter implements OneParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            " varying highp vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            " \n" +
            " uniform samplerExternalOES sTexture;\n" +
            " uniform lowp float saturation;\n" +
            " \n" +
            " // Values from \"Graphics Shaders: Theory and Practice\" by Bailey and Cunningham\n" +
            " const mediump vec3 luminanceWeighting = vec3(0.2125, 0.7154, 0.0721);\n" +
            " \n" +
            " void main()\n" +
            " {\n" +
            "    lowp vec4 textureColor = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ");\n" +
            "    lowp float luminance = dot(textureColor.rgb, luminanceWeighting);\n" +
            "    lowp vec3 greyScaleColor = vec3(luminance);\n" +
            "    \n" +
            "    gl_FragColor = vec4(mix(greyScaleColor, textureColor.rgb, saturation), textureColor.w);\n" +
            "     \n" +
            " }";

    private float saturation = 0f;
    private int saturationLocation = -1;

    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        this.saturation = saturation;
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void setParameter1(float value) {
        setSaturation(value);
    }

    @Override
    public float getParameter1() {
        return getSaturation();
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        saturationLocation = GLES20.glGetUniformLocation(programHandle, "saturation");
        GlUtils.checkLocation(saturationLocation, "saturation");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        saturationLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        GLES20.glUniform1f(saturationLocation, saturation);
        GlUtils.checkError("glUniform1f");
    }

}
