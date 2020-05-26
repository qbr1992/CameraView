package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.TwoParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

public class SbShadowHighlightFilter extends BaseFilter implements TwoParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            " uniform samplerExternalOES sTexture;\n" +
            " varying highp vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            "  \n" +
            " uniform lowp float shadows;\n" +
            " uniform lowp float highlights;\n" +
            " \n" +
            " const mediump vec3 luminanceWeighting = vec3(0.3, 0.3, 0.3);\n" +
            " \n" +
            " void main()\n" +
            " {\n" +
            " 	lowp vec4 source = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ");\n" +
            " 	mediump float luminance = dot(source.rgb, luminanceWeighting);\n" +
            " \n" +
            " 	mediump float shadow = clamp((pow(luminance, 1.0/(shadows+1.0)) + (-0.76)*pow(luminance, 2.0/(shadows+1.0))) - luminance, 0.0, 1.0);\n" +
            " 	mediump float highlight = clamp((1.0 - (pow(1.0-luminance, 1.0/(2.0-highlights)) + (-0.8)*pow(1.0-luminance, 2.0/(2.0-highlights)))) - luminance, -1.0, 0.0);\n" +
            " 	lowp vec3 result = vec3(0.0, 0.0, 0.0) + ((luminance + shadow + highlight) - 0.0) * ((source.rgb - vec3(0.0, 0.0, 0.0))/(luminance - 0.0));\n" +
            " \n" +
            " 	gl_FragColor = vec4(result.rgb, source.a);\n" +
            " }";

    private float shadows;              // v2项目中shadows值除以100
    private int shadowsLocation;
    private float highlights;           // v2项目中highlights值相同
    private int highlightsLocation;

    public float getShadows() {
        return shadows;
    }

    public void setShadows(float shadows) {
        this.shadows = shadows;
    }

    public float getHighlights() {
        return highlights;
    }

    public void setHighlights(float highlights) {
        this.highlights = highlights;
    }

    @Override
    public void setParameter1(float value) {
        setShadows(value);
    }

    @Override
    public float getParameter1() {
        return getShadows();
    }

    @Override
    public void setParameter2(float value) {
        setHighlights(value);
    }

    @Override
    public float getParameter2() {
        return getHighlights();
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        shadowsLocation = GLES20.glGetUniformLocation(programHandle, "shadows");
        GlUtils.checkLocation(shadowsLocation, "shadows");
        highlightsLocation = GLES20.glGetUniformLocation(programHandle, "highlights");
        GlUtils.checkLocation(highlightsLocation, "highlights");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shadowsLocation = -1;
        highlightsLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        GLES20.glUniform1f(shadowsLocation, shadows);
        GlUtils.checkError("glUniform1f");
        GLES20.glUniform1f(highlightsLocation, highlights);
        GlUtils.checkError("glUniform1f");
    }

}
