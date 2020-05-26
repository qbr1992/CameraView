package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.MultiParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

public class SbHslFilter extends BaseFilter implements MultiParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying highp vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            "uniform samplerExternalOES sTexture;\n" +
            " \n" +
            "uniform float saturation;\n" +
            "uniform float hue;\n" +
            "uniform float luminance;\n" +
            "vec3 RGB2HSL(vec3 src)\n" +
            "{\n" +
            "float maxc = max(max(src.r, src.g), src.b);\n" +
            "float minc = min(min(src.r, src.g), src.b);\n" +
            "float L = (maxc + minc) / 2.0;\n" +
            "if(maxc == minc)\n" +
            "return vec3(0.0, 0.0, L);\n" +
            "float H, S;\n" +
            "//注意， 某些低精度情况下 N - (A+B) != N - A - B\n" +
            "float temp1 = maxc - minc;\n" +
            "S = mix(temp1 / (2.0 - maxc - minc), temp1 / (maxc + minc), step(L, 0.5));\n" +
            "vec3 comp;\n" +
            "comp.xy = vec2(equal(src.xy, vec2(maxc)));\n" +
            "float comp_neg = 1.0 - comp.x;\n" +
            "comp.y *= comp_neg;\n" +
            "comp.z = (1.0 - comp.y) * comp_neg;\n" +
            "float dif = maxc - minc;\n" +
            "vec3 result = comp * vec3((src.g - src.b) / dif,\n" +
            "2.0 + (src.b - src.r) / dif,\n" +
            "4.0 + (src.r - src.g) / dif);\n" +
            "H = result.x + result.y + result.z;\n" +
            "H *= 60.0;\n" +
            "//if(H < 0.0) H += 360.0;\n" +
            "H += step(H, 0.0) * 360.0;\n" +
            "return vec3(H / 360.0, S, L); // H(0~1), S(0~1), L(0~1)\n" +
            "}\n" +
            "vec3 HSL2RGB(vec3 src) // H, S, L\n" +
            "{\n" +
            "float q = (src.z < 0.5) ? src.z * (1.0 + src.y) : (src.z + src.y - (src.y * src.z));\n" +
            "float p = 2.0 * src.z - q;\n" +
            "vec3 dst = vec3(src.x + 0.333, src.x, src.x - 0.333);\n" +
            "dst = fract(dst);\n" +
            "vec3 weight = step(dst, vec3(1.0 / 6.0));\n" +
            "vec3 weight_neg = 1.0 - weight;\n" +
            "vec3 weight2 = weight_neg * step(dst, vec3(0.5));\n" +
            "vec3 weight2_neg = weight_neg * (1.0 - weight2);\n" +
            "vec3 weight3 = weight2_neg * step(dst, vec3(2.0 / 3.0));\n" +
            "vec3 weight4 = (1.0 - weight3) * weight2_neg;\n" +
            "float q_p = q - p;\n" +
            "dst = mix(dst, p + q_p * 6.0 * dst, weight);\n" +
            "dst = mix(dst, vec3(q), weight2);\n" +
            "dst = mix(dst, p + q_p * ((2.0 / 3.0) - dst) * 6.0, weight3);\n" +
            "dst = mix(dst, vec3(p), weight4);\n" +
            "return dst;\n" +
            "}\n" +
            "vec3 adjustColor(vec3 src, float h, float s, float l) //hue should be positive\n" +
            "{\n" +
            "src = RGB2HSL(src);\n" +
            "src.x += h;\n" +
            "src.y *= 1.0 + s;\n" +
            "src.z *= 1.0 + l;\n" +
            "return HSL2RGB(src);\n" +
            "}\n" +
            "void main()\n" +
            "{\n" +
            "vec4 src = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ");\n" +
            "src.rgb = adjustColor(src.rgb, hue, saturation, luminance);\n" +
            "gl_FragColor = src;" +
            "}";

    private float saturation = 0f;
    private float hue = 0f;
    private float luminance = 0f;
    private int saturationLocation = -1;
    private int hueLocation = -1;
    private int luminanceLocation = -1;
    private float[] hslArr = {0, 0, 0};

    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        this.saturation = saturation;
        hslArr[0] = saturation;
    }

    public float getHue() {
        return hue;
    }

    public void setHue(float hue) {
        this.hue = hue;
        hslArr[1] = hue;
    }

    public float getLuminance() {
        return luminance;
    }

    public void setLuminance(float luminance) {
        this.luminance = luminance;
        hslArr[2] = luminance;
    }

    @Override
    public void setParameterMulti(float[] value) {
        hslArr = value;
        saturation = value[0];
        hue = value[1];
        luminance = value[2];
    }

    @Override
    public float[] getParameterMulti() {
        if (hslArr[0]==0 && hslArr[1]==0 && hslArr[2]==0) {
            return null;
        } else {
            return hslArr;
        }
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }


    @Override
    public void setParameter2(float value) {

    }

    @Override
    public float getParameter2() {
        return 0;
    }

    @Override
    public void setParameter1(float value) {

    }

    @Override
    public float getParameter1() {
        return 0;
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        saturationLocation = GLES20.glGetUniformLocation(programHandle, "saturation");
        GlUtils.checkLocation(saturationLocation, "saturation");
        hueLocation = GLES20.glGetUniformLocation(programHandle, "hue");
        GlUtils.checkLocation(hueLocation, "hue");
        luminanceLocation = GLES20.glGetUniformLocation(programHandle, "luminance");
        GlUtils.checkLocation(luminanceLocation, "luminance");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        saturationLocation = -1;
        hueLocation = -1;
        luminanceLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        GLES20.glUniform1f(saturationLocation, saturation);
        GlUtils.checkError("glUniform1f");
        GLES20.glUniform1f(hueLocation, hue);
        GlUtils.checkError("glUniform1f");
        GLES20.glUniform1f(luminanceLocation, luminance);
        GlUtils.checkError("glUniform1f");
    }

}
