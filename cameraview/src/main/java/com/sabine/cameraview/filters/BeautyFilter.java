package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

import java.nio.FloatBuffer;

public class BeautyFilter extends BaseFilter implements OneParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying mediump vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform vec2 singleStepOffset;\n" +
            "uniform mediump float beauty;\n" +
            "const highp vec3 W = vec3(0.299,0.587,0.114);\n" +
            "vec2 blurCoordinates[20];\n" +
            "float hardLight(float color)\n" +
            "{\n" +
            "\tif(color <= 0.5)\n" +
            "\t\tcolor = color * color * 2.0;\n" +
            "\telse\n" +
            "\t\tcolor = 1.0 - ((1.0 - color)*(1.0 - color) * 2.0);\n" +
            "\treturn color;\n" +
            "}\n" +
            "void main(){\n" +
            "    vec3 centralColor = texture2D(sTexture, " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ").rgb;\n" +
            "    if(beauty != 0.0){\n" +
            "        blurCoordinates[0] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(0.0, -10.0);\n" +
            "        blurCoordinates[1] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(0.0, 10.0);\n" +
            "        blurCoordinates[2] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(-10.0, 0.0);\n" +
            "        blurCoordinates[3] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(10.0, 0.0);\n" +
            "        blurCoordinates[4] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(5.0, -8.0);\n" +
            "        blurCoordinates[5] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(5.0, 8.0);\n" +
            "        blurCoordinates[6] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(-5.0, 8.0);\n" +
            "        blurCoordinates[7] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(-5.0, -8.0);\n" +
            "        blurCoordinates[8] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(8.0, -5.0);\n" +
            "        blurCoordinates[9] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(8.0, 5.0);\n" +
            "        blurCoordinates[10] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(-8.0, 5.0);\n" +
            "        blurCoordinates[11] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(-8.0, -5.0);\n" +
            "        blurCoordinates[12] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(0.0, -6.0);\n" +
            "        blurCoordinates[13] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(0.0, 6.0);\n" +
            "        blurCoordinates[14] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(6.0, 0.0);\n" +
            "        blurCoordinates[15] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(-6.0, 0.0);\n" +
            "        blurCoordinates[16] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(-4.0, -4.0);\n" +
            "        blurCoordinates[17] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(-4.0, 4.0);\n" +
            "        blurCoordinates[18] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(4.0, -4.0);\n" +
            "        blurCoordinates[19] = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy + singleStepOffset * vec2(4.0, 4.0);\n" +
            "        float sampleColor = centralColor.g * 20.0;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[0]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[1]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[2]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[3]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[4]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[5]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[6]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[7]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[8]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[9]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[10]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[11]).g;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[12]).g * 2.0;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[13]).g * 2.0;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[14]).g * 2.0;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[15]).g * 2.0;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[16]).g * 2.0;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[17]).g * 2.0;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[18]).g * 2.0;\n" +
            "        sampleColor += texture2D(sTexture, blurCoordinates[19]).g * 2.0;\n" +
            "        sampleColor = sampleColor / 48.0;\n" +
            "        float highPass = centralColor.g - sampleColor + 0.5;\n" +
            "        for(int i = 0; i < 5;i++)\n" +
            "        {\n" +
            "            highPass = hardLight(highPass);\n" +
            "        }\n" +
            "        float luminance = dot(centralColor, W);\n" +
            "        float alpha = pow(luminance, beauty);\n" +
            "        vec3 smoothColor = centralColor + (centralColor-vec3(highPass))*alpha*0.1;\n" +
            "        gl_FragColor = vec4(mix(smoothColor.rgb, max(smoothColor, centralColor), alpha), 1.0);\n" +
            "    }else{\n" +
            "        gl_FragColor = vec4(centralColor.rgb,1.0);;\n" +
            "    }\n" +
            "}";

    private float beauty = 0.33f;
    private int beautyLocation = -1;
    private int singleStepOffsetLocation = -1;


    public float getBeautyLevel() {
        return beauty;
    }

    public void setBeautyLevel(float beautyLevel) {
        this.beauty = beautyLevel;
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void setParameter1(float value) {
        setBeautyLevel(value);
    }

    @Override
    public float getParameter1() {
        return getBeautyLevel();
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        beautyLocation = GLES20.glGetUniformLocation(programHandle, "beauty");
        singleStepOffsetLocation = GLES20.glGetUniformLocation(programHandle, "singleStepOffset");
        GlUtils.checkLocation(beautyLocation, "beauty");
        GlUtils.checkLocation(singleStepOffsetLocation, "singleStepOffset");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        beautyLocation = -1;
        singleStepOffsetLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        GLES20.glUniform1f(beautyLocation, beauty);
        float[] arrays = new float[] {2.0f / 1080, 2.0f / 1920};
        GLES20.glUniform2fv(singleStepOffsetLocation, 1, FloatBuffer.wrap(arrays));
        GlUtils.checkError("glUniform1f");
        GlUtils.checkError("glUniform2fv");
    }
}
