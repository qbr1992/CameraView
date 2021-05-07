package com.sabine.cameraview.filters;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import com.otaliastudios.opengl.core.Egloo;
import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.MultiFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.filter.TwoParameterFilter;
import com.sabine.cameraview.utils.OpenGLUtils;

import androidx.annotation.NonNull;

/**
 * Applies gamma correction to the frames.
 */
public class BeautyAdjustV1Filter extends BaseFilter implements OneParameterFilter, TwoParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "varying vec2 "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+";\n" +
            "\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform sampler2D inputImageTexture1;\n" +
            "uniform sampler2D inputImageTexture2;\n" +
            "uniform float blurOpacity;\n" +
            "uniform float skinOpacity;\n" +
            "uniform float whiteOpacity;\n" +
            "\n" +
            "lowp float factor1 = 2.782;\n" +
            "lowp float factor2 = 1.131;\n" +
            "lowp float factor3 = 1.158;\n" +
            "lowp float factor4 = 2.901;\n" +
            "lowp float factor5 = 1.0;\n" +
            "lowp float factor6 = 0.639;\n" +
            "lowp float factor7 = 0.963;\n" +
            "\n" +
            "lowp vec3 rgb2hsv(lowp vec3 c) {\n" +
            "    lowp vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n" +
            "    highp vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));\n" +
            "    highp vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));\n" +
            "    highp float d = q.x - min(q.w, q.y);\n" +
            "    highp float e = 1.0e-10;\n" +
            "    lowp vec3 hsv = vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);\n" +
            "    return hsv;\n" +
            "}\n" +
            "\n" +
            "lowp vec3 ContrastSaturationBrightness(lowp vec3 color, lowp float brt, lowp float sat, lowp float con)\n" +
            "{\n" +
            "    const lowp float AvgLumR = 0.5;\n" +
            "    const lowp float AvgLumG = 0.5;\n" +
            "    const lowp float AvgLumB = 0.5;\n" +
            "\n" +
            "    const lowp vec3 LumCoeff = vec3(0.2125, 0.7154, 0.0721);\n" +
            "\n" +
            "    lowp vec3 AvgLumin = vec3(AvgLumR, AvgLumG, AvgLumB);\n" +
            "    lowp vec3 brtColor = color * brt;\n" +
            "    lowp vec3 intensity = vec3(dot(brtColor, LumCoeff));\n" +
            "    lowp vec3 satColor = mix(intensity, brtColor, sat);\n" +
            "    lowp vec3 conColor = mix(AvgLumin, satColor, con);\n" +
            "\n" +
            "    return conColor;\n" +
            "}\n" +
            "\n" +
            "lowp vec4 lut3d(highp vec4 textureColor)\n" +
            "{\n" +
            "    mediump float blueColor = textureColor.b * 15.0;\n" +
            "    mediump vec2 quad1;\n" +
            "    quad1.y = max(min(4.0,floor(floor(blueColor) / 4.0)),0.0);\n" +
            "    quad1.x = max(min(4.0,floor(blueColor) - (quad1.y * 4.0)),0.0);\n" +
            "\n" +
            "    mediump vec2 quad2;\n" +
            "    quad2.y = max(min(floor(ceil(blueColor) / 4.0),4.0),0.0);\n" +
            "    quad2.x = max(min(ceil(blueColor) - (quad2.y * 4.0),4.0),0.0);\n" +
            "\n" +
            "    highp vec2 texPos1;\n" +
            "    texPos1.x = (quad1.x * 0.25) + 0.5/64.0 + ((0.25 - 1.0/64.0) * textureColor.r);\n" +
            "    texPos1.y = (quad1.y * 0.25) + 0.5/64.0 + ((0.25 - 1.0/64.0) * textureColor.g);\n" +
            "\n" +
            "    highp vec2 texPos2;\n" +
            "    texPos2.x = (quad2.x * 0.25) + 0.5/64.0 + ((0.25 - 1.0/64.0) * textureColor.r);\n" +
            "    texPos2.y = (quad2.y * 0.25) + 0.5/64.0 + ((0.25 - 1.0/64.0) * textureColor.g);\n" +
            "\n" +
            "    lowp vec4 newColor1 = texture2D(inputImageTexture2, texPos1);\n" +
            "    lowp vec4 newColor2 = texture2D(inputImageTexture2, texPos2);\n" +
            "\n" +
            "    mediump vec4 newColor = mix(newColor1, newColor2, fract(blueColor));\n" +
            "    return newColor;\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    lowp vec4 inputColor = texture2D(sTexture, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+");\n" +
            "    if (blurOpacity == 0.0 && skinOpacity == 0.0) {\n" +
            "        gl_FragColor = inputColor;\n" +
            "        return;\n" +
            "    }\n" +
            "\n" +
            "    lowp vec3 hsv = rgb2hsv(inputColor.rgb);\n" +
            "\n" +
            "    lowp float opacityLimit = 1.0;\n" +
            "\n" +
            "    if ((0.18 <= hsv.x && hsv.x <= 0.89) || hsv.z <= 0.2 || hsv.z >=0.99) {\n" +
            "        opacityLimit = 0.0;\n" +
            "    }\n" +
            "    if (0.16 < hsv.x && hsv.x < 0.18) {\n" +
            "        opacityLimit = min(opacityLimit, (0.18 - hsv.x) / 0.02);\n" +
            "    }\n" +
            "    if (0.89 < hsv.x && hsv.x < 0.91) {\n" +
            "        opacityLimit = min(opacityLimit, 1.0 - (0.91 - hsv.x) / 0.02);\n" +
            "    }\n" +
            "    if (0.2 < hsv.z && hsv.x < 0.3) {\n" +
            "        opacityLimit = min(opacityLimit, 1.0 - (0.3 - hsv.z) / 0.1);\n" +
            "    }\n" +
            "\n" +
            "    vec4 resultColor = inputColor;\n" +
            "    if (opacityLimit != 0.0) {\n" +
            "        lowp vec4 blurColor = texture2D(inputImageTexture1, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+");\n" +
            "\n" +
            "        opacityLimit = blurOpacity * opacityLimit;\n" +
            "\n" +
            "        lowp float cDistance = distance(vec3(0.0, 0.0, 0.0), max(blurColor.rgb - inputColor.rgb, 0.0)) * factor1;\n" +
            "        lowp vec3 brightColor = ContrastSaturationBrightness(inputColor.rgb, factor2, 1.0, factor3);\n" +
            "        lowp vec3 mix11Color = mix(inputColor.rgb, brightColor.rgb, cDistance);\n" +
            "\n" +
            "        lowp float dDistance = distance(vec3(0.0, 0.0, 0.0), max(inputColor.rgb-blurColor.rgb, 0.0)) * factor4;\n" +
            "        lowp vec3 darkColor = ContrastSaturationBrightness(inputColor.rgb, factor5, 1.0, factor6);\n" +
            "        lowp vec3 mix115Color = mix(mix11Color.rgb, darkColor.rgb, dDistance);\n" +
            "\n" +
            "        lowp vec3 mix12Color;\n" +
            "\n" +
            "        if (factor7 < 0.999)\n" +
            "        {\n" +
            "            lowp vec3 mix116Color = mix(inputColor.rgb, mix115Color.rgb, factor7);\n" +
            "            mix12Color = mix(mix116Color.rgb, blurColor.rgb, opacityLimit);\n" +
            "        }\n" +
            "        else\n" +
            "        {\n" +
            "            mix12Color = mix(mix115Color.rgb, blurColor.rgb, opacityLimit);\n" +
            "        }\n" +
            "\n" +
            "        vec4 skinColor;\n" +
            "        if (skinOpacity < 0.999)\n" +
            "        {\n" +
            "            skinColor = vec4(mix(inputColor.rgb, mix12Color.rgb, skinOpacity), 1.0);\n" +
            "        }\n" +
            "        else\n" +
            "        {\n" +
            "            skinColor = vec4(mix12Color.rgb, 1.0);\n" +
            "        }\n" +
            "        inputColor = skinColor;\n" +
            "        resultColor = skinColor;\n" +
            "    }\n" +
            "\n" +
            "    resultColor = lut3d(resultColor);\n" +
            "    gl_FragColor = mix(inputColor, resultColor, whiteOpacity);\n" +
            "    gl_FragColor = vec4((gl_FragColor.rgb + vec3(0.05)), gl_FragColor.w);\n" +
            "}";

    private int blurTextureLocation = -1;
    private int lutTextureLocation = -1;

    private int blurTexture = 0;
    private int lutTexture = 0;

    private int blurOpacityLocation;
    private float blurOpacity = 0.48f;
    private int skinOpacityLocation;
    private float skinOpacity = 0.52f;
    private int whiteOpacityLocation;
    private float whiteOpacity = 1.0f;

    private Context context = null;

    public BeautyAdjustV1Filter() {
        blurOpacity = 0.48f;
        skinOpacity = 0.52f;
        whiteOpacity = 1.0f;
    }
    public BeautyAdjustV1Filter(Context context) { this.context = context; }

    public void setBlurTexture(int blurTexture) { this.blurTexture = blurTexture; }
    public int getBlurTexture() { return blurTexture; }

    public void setLutTexture(int lutTexture) { this.lutTexture = lutTexture; }
    public int getLutTexture() { return lutTexture; }

    /**
     * Sets the new blurOpacity value.
     *
     * @param blurOpacity alpha value
     */
    @SuppressWarnings("WeakerAccess")
    public void setBlurOpacity(float blurOpacity) {
        this.blurOpacity = blurOpacity;
    }

    /**
     * Returns the current blurOpacity.
     *
     * @see #setBlurOpacity(float)
     * @return intensity
     */
    @SuppressWarnings("WeakerAccess")
    public float getBlurOpacity() {
        return blurOpacity;
    }

    public void setSkinOpacity(float skinOpacity) {
        this.skinOpacity = skinOpacity;
    }

    public float getSkinOpacity() { return skinOpacity; }

    public void setWhiteOpacity(float whiteOpacity) {
        this.whiteOpacity = whiteOpacity;
    }

    public float getWhiteOpacity() { return whiteOpacity; }

    @Override
    public void setParameter1(float value) {
        setBlurOpacity(value);
    }

    @Override
    public float getParameter1() {
        return getBlurOpacity();
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);

        blurTextureLocation = GLES20.glGetUniformLocation(programHandle, "inputImageTexture1");
        Egloo.checkGlProgramLocation(blurTextureLocation, "inputImageTexture1");
        lutTextureLocation = GLES20.glGetUniformLocation(programHandle, "inputImageTexture2");
        Egloo.checkGlProgramLocation(lutTextureLocation, "inputImageTexture2");
        blurOpacityLocation = GLES20.glGetUniformLocation(programHandle, "blurOpacity");
        Egloo.checkGlProgramLocation(blurOpacityLocation, "blurOpacity");
        skinOpacityLocation = GLES20.glGetUniformLocation(programHandle, "skinOpacity");
        Egloo.checkGlProgramLocation(skinOpacityLocation, "skinOpacity");
        whiteOpacityLocation = GLES20.glGetUniformLocation(programHandle, "whiteOpacity");
        Egloo.checkGlProgramLocation(whiteOpacityLocation, "whiteOpacity");

        if (context != null)
            lutTexture = OpenGLUtils.createTextureFromAssets(context, "texture/beautyLut_16_16.png");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        blurTextureLocation = -1;
        lutTextureLocation = -1;
        blurOpacityLocation = -1;
        skinOpacityLocation = -1;
        whiteOpacityLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        OpenGLUtils.bindTexture(blurTextureLocation, blurTexture, 1, GLES20.GL_TEXTURE_2D);
//        if (lutTexture == -1 && context != null) {
//            lutTexture = OpenGLUtils.createTextureFromAssets(context, "texture/beautyLut_16_16.png");
//        }
            OpenGLUtils.bindTexture(lutTextureLocation, lutTexture, 2, GLES20.GL_TEXTURE_2D);
        GLES20.glUniform1f(blurOpacityLocation, blurOpacity);
        GLES20.glUniform1f(skinOpacityLocation, skinOpacity);
        GLES20.glUniform1f(whiteOpacityLocation, whiteOpacity);
    }

    @Override
    public void setParameter2(float value) {
        setSkinOpacity(value);
    }

    @Override
    public float getParameter2() {
        return getSkinOpacity();
    }
}