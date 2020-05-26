package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

public class SbWhiteFilter extends BaseFilter implements OneParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying highp vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            " \n" +
            " uniform samplerExternalOES sTexture;\n" +
            "uniform float level;\n" +
            "\n " +
            "void modifyColor(vec4 color){\n" +
            "color.r=max(min(color.r,1.0),0.0);\n" +
            "color.g=max(min(color.g,1.0),0.0);\n" +
            "color.b=max(min(color.b,1.0),0.0);\n" +
            "color.a=max(min(color.a,1.0),0.0);\n" +
            " }\n" +
            "\n " +
            "void main() {\n" +
            "vec4 nColor = texture2D(sTexture,"+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ");\n" +
            "vec4 deltaColor = nColor+vec4(vec3(level * 0.25),0.0);\n" +
            "modifyColor(deltaColor);\n" +
            "gl_FragColor = deltaColor;\n" +
            "}";





    private float level = 0f;
    private int levelLocation = -1;



    public float getLevel() {
        return level;
    }

    public void setLevel(float level) {
        this.level = level;
    }

    @Override
    public void setParameter1(float value) {
        setLevel(value);
    }

    @Override
    public float getParameter1() {
        return getLevel();
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        levelLocation = GLES20.glGetUniformLocation(programHandle, "level");
        GlUtils.checkLocation(levelLocation, "level");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        levelLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        GLES20.glUniform1f(levelLocation, level);
        GlUtils.checkError("glUniform1f");
    }

}
