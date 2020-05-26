package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

public class SbCurveFilter extends BaseFilter implements OneParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying highp vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            " \n" +
            " uniform samplerExternalOES sTexture;\n" +
            "uniform samplerExternalOES curveTexture; //We do not use sampler1D because GLES dosenot support that.\n" +
//            "uniform float strength;\n" +
//            "void main()\n" +
//            "{\n" +
//            "vec3 src = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ").rgb;\n" +
//            "vec3 dst = vec3((0, 0),(130, 157),(255, 255));\n" +
//            "gl_FragColor = vec4(mix(src, dst, strength), 1.0);\n" +
//            "}";



//            "uniform float strength;\n" +
//            "vec3 rgb2hsv(vec3 c)\n" +
//            "{\n" +
//            "vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n" +
//            "vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));\n" +
//            "vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));\n" +
//            "float d = q.x - min(q.w, q.y);\n" +
//            "float e = 1.0e-10;\n" +
//            "return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);\n" +
//            "}\n" +
//            "vec3 hsv2rgb(vec3 c)\n" +
//            "{\n" +
//            "vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);\n" +
//            "vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);\n" +
//            "return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);\n" +
//            "}\n" +
//            "void main()\n" +
//            "{\n" +
//            "lowp vec4 sourceColor = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy);\n" +
//            "lowp vec4 textureColor = sourceColor;\n" +
//            "// step1 20% opacity  ExclusionBlending\n" +
//            "mediump vec4 textureColor2 = textureColor;\n" +
//            "textureColor2 = textureColor + textureColor2 - (2.0 * textureColor2 * textureColor);\n" +
//            "textureColor = (textureColor2 - textureColor) * 0.2 + textureColor;\n" +
//            "// step2 curve\n" +
//            "highp float redCurveValue = texture2D(curveTexture, vec2(textureColor.r, 0.0)).r;\n" +
//            "highp float greenCurveValue = texture2D(curveTexture, vec2(textureColor.g, 0.0)).g;\n" +
//            "highp float blueCurveValue = texture2D(curveTexture, vec2(textureColor.b, 0.0)).b;\n" +
//            "redCurveValue = texture2D(curveTexture, vec2(redCurveValue, 1.0)).r;\n" +
//            "greenCurveValue = texture2D(curveTexture, vec2(greenCurveValue, 1.0)).r;\n" +
//            "blueCurveValue = texture2D(curveTexture, vec2(blueCurveValue, 1.0)).r;\n" +
//            "redCurveValue = texture2D(curveTexture, vec2(redCurveValue, 1.0)).g;\n" +
//            "greenCurveValue = texture2D(curveTexture, vec2(greenCurveValue, 1.0)).g;\n" +
//            "blueCurveValue = texture2D(curveTexture, vec2(blueCurveValue, 1.0)).g;\n" +
//            "vec3 tColor = vec3(redCurveValue, greenCurveValue, blueCurveValue);\n" +
//            "tColor = rgb2hsv(tColor);\n" +
//            "tColor.g = tColor.g * 0.65;\n" +
//            "tColor = hsv2rgb(tColor);\n" +
//            "tColor = clamp(tColor, 0.0, 1.0);\n" +
//            "mediump vec4 base = vec4(tColor, 1.0);\n" +
//            "mediump vec4 overlay = vec4(0.62, 0.6, 0.498, 1.0);\n" +
//            "// step6 overlay blending\n" +
//            "mediump float ra;\n" +
//            "if (base.r < 0.5)\n" +
//            "{\n" +
//            "ra = overlay.r * base.r * 2.0;\n" +
//            "} else\n" +
//            "{\n" +
//            "ra = 1.0 - ((1.0 - base.r) * (1.0 - overlay.r) * 2.0);\n" +
//            "}\n" +
//            "mediump float ga;\n" +
//            "if (base.g < 0.5)\n" +
//            "{\n" +
//            "ga = overlay.g * base.g * 2.0;\n" +
//            "} else\n" +
//            "{\n" +
//            "ga = 1.0 - ((1.0 - base.g) * (1.0 - overlay.g) * 2.0);\n" +
//            "}\n" +
//            "mediump float ba;\n" +
//            "if (base.b < 0.5)\n" +
//            "{\n" +
//            "ba = overlay.b * base.b * 2.0;\n" +
//            "} else\n" +
//            "{\n" +
//            "ba = 1.0 - ((1.0 - base.b) * (1.0 - overlay.b) * 2.0);\n" +
//            "}\n" +
//            "textureColor = vec4(ra, ga, ba, 1.0);\n" +
//            "textureColor = (textureColor - base) * 0.1 + base;\n" +
//            "gl_FragColor = mix(sourceColor, textureColor, strength);\n" +
//            "}";


            "uniform float strength;\n" +
            "vec3 rgb2hsv(vec3 c)\n" +
            "{\n" +
            "vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n" +
            "vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));\n" +
            "vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));\n" +
            "float d = q.x - min(q.w, q.y);\n" +
            "float e = 1.0e-10;\n" +
            "return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);\n" +
            "}\n" +
            "vec3 hsv2rgb(vec3 c)\n" +
            "{\n" +
            "vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);\n" +
            "vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);\n" +
            "return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);\n" +
            "}\n" +
            "void main()\n" +
            "{\n" +
            "lowp vec4 sourceColor = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".xy);\n" +
            "lowp vec4 textureColor = sourceColor;\n" +
            "// step1 20% opacity  ExclusionBlending\n" +
            "mediump vec4 textureColor2 = textureColor;\n" +
            "textureColor2 = textureColor + textureColor2 - (2.0 * textureColor2 * textureColor);\n" +
            "textureColor = (textureColor2 - textureColor) * 0.2 + textureColor;\n" +
            "// step2 curve\n" +
            "highp float redCurveValue = texture2D(curveTexture, vec2(textureColor.r, 0.0)).r;\n" +
            "highp float greenCurveValue = texture2D(curveTexture, vec2(textureColor.g, 0.0)).g;\n" +
            "highp float blueCurveValue = texture2D(curveTexture, vec2(textureColor.b, 0.0)).b;\n" +
            "redCurveValue = texture2D(curveTexture, vec2(redCurveValue, 1.0)).r;\n" +
            "greenCurveValue = texture2D(curveTexture, vec2(greenCurveValue, 1.0)).r;\n" +
            "blueCurveValue = texture2D(curveTexture, vec2(blueCurveValue, 1.0)).r;\n" +
            "redCurveValue = texture2D(curveTexture, vec2(redCurveValue, 1.0)).g;\n" +
            "greenCurveValue = texture2D(curveTexture, vec2(greenCurveValue, 1.0)).g;\n" +
            "blueCurveValue = texture2D(curveTexture, vec2(blueCurveValue, 1.0)).g;\n" +
            "vec3 tColor = vec3(redCurveValue, greenCurveValue, blueCurveValue);\n" +
            "tColor = rgb2hsv(tColor);\n" +
            "tColor.g = tColor.g * 0.65;\n" +
            "tColor = hsv2rgb(tColor);\n" +
            "tColor = clamp(tColor, 0.0, 1.0);\n" +
            "mediump vec4 base = vec4(tColor, 1.0);\n" +
            "mediump vec4 overlay = vec4(0.62, 0.6, 0.498, 1.0);\n" +
            "// step6 overlay blending\n" +
            "mediump float ra;\n" +
            "if (base.r < 0.5)\n" +
            "{\n" +
            "ra = overlay.r * base.r * 2.0;\n" +
            "} else\n" +
            "{\n" +
            "ra = 1.0 - ((1.0 - base.r) * (1.0 - overlay.r) * 2.0);\n" +
            "}\n" +
            "mediump float ga;\n" +
            "if (base.g < 0.5)\n" +
            "{\n" +
            "ga = overlay.g * base.g * 2.0;\n" +
            "} else\n" +
            "{\n" +
            "ga = 1.0 - ((1.0 - base.g) * (1.0 - overlay.g) * 2.0);\n" +
            "}\n" +
            "mediump float ba;\n" +
            "if (base.b < 0.5)\n" +
            "{\n" +
            "ba = overlay.b * base.b * 2.0;\n" +
            "} else\n" +
            "{\n" +
            "ba = 1.0 - ((1.0 - base.b) * (1.0 - overlay.b) * 2.0);\n" +
            "}\n" +
            "textureColor = vec4(ra, ga, ba, 1.0);\n" +
            "textureColor = (textureColor - base) * 0.1 + base;\n" +
            "gl_FragColor = mix(sourceColor, textureColor, strength);\n" +
            "}";





    private float strength = 0f;
    private int strengthLocation = -1;



    public float getStrength() {
        return strength;
    }

    public void setStrength(float strength) {
        this.strength = strength;
    }

    @Override
    public void setParameter1(float value) {
        setStrength(value);
    }

    @Override
    public float getParameter1() {
        return getStrength();
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        strengthLocation = GLES20.glGetUniformLocation(programHandle, "strength");
        GlUtils.checkLocation(strengthLocation, "strength");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        strengthLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        GLES20.glUniform1f(strengthLocation, strength);
        GlUtils.checkError("glUniform1f");
    }

}
