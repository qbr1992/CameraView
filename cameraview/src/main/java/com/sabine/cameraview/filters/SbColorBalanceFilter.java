package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.MultiParameterFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

public class SbColorBalanceFilter extends BaseFilter implements MultiParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying highp vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            "uniform samplerExternalOES sTexture;\n" +
            " \n" +
            "uniform float redShift;\n" +
            "uniform float greenShift;\n" +
            "uniform float blueShift;\n" +
            "uniform float saturation;\n" +
            "uniform float hue;\n" +
            "uniform float luminance;\n" +
            "float RGBToL(vec3 color)\n" +
            "{\n" +
                "float fmin = min(min(color.r, color.g), color.b);    //Min. value of RGB\n" +
                "float fmax = max(max(color.r, color.g), color.b);    //Max. value of RGB\n" +
                "return (fmax + fmin) / 2.0; // Luminance\n" +
            "}\n" +
            "vec3 RGBToHSL(vec3 color)\n" +
            "{\n" +
                "vec3 hsl; // init to 0 to avoid warnings ? (and reverse if + remove first part)\n" +
                "float fmin = min(min(color.r, color.g), color.b);    //Min. value of RGB\n" +
                "float fmax = max(max(color.r, color.g), color.b);    //Max. value of RGB\n" +
                "float delta = fmax - fmin;             //Delta RGB value\n" +
                "hsl.z = (fmax + fmin) / 2.0; // Luminance\n" +
                "if (delta == 0.0)		//This is a gray, no chroma...\n" +
            "{\n" +
                "hsl.x = 0.0;	// Hue\n" +
                "hsl.y = 0.0;	// Saturation\n" +
            "}\n" +
            "else                                    //Chromatic data...\n" +
            "{\n" +
                "if (hsl.z < 0.5)\n" +
                    "hsl.y = delta / (fmax + fmin); // Saturation\n" +
                "else\n" +
                    "hsl.y = delta / (2.0 - fmax - fmin); // Saturation\n" +
                "float deltaR = (((fmax - color.r) / 6.0) + (delta / 2.0)) / delta;\n" +
                "float deltaG = (((fmax - color.g) / 6.0) + (delta / 2.0)) / delta;\n" +
                "float deltaB = (((fmax - color.b) / 6.0) + (delta / 2.0)) / delta;\n" +
                "if (color.r == fmax )\n" +
                    "hsl.x = deltaB - deltaG; // Hue\n" +
                "else if (color.g == fmax)\n" +
                    "hsl.x = (1.0 / 3.0) + deltaR - deltaB; // Hue\n" +
                "else if (color.b == fmax)\n" +
                    "hsl.x = (2.0 / 3.0) + deltaG - deltaR; // Hue\n" +
                "if (hsl.x < 0.0)\n" +
                    "hsl.x += 1.0; // Hue\n" +
                "else if (hsl.x > 1.0)\n" +
                    "hsl.x -= 1.0; // Hue\n" +
                "}\n" +
                "return hsl;\n" +
            "}\n" +
            "float HueToRGB(float f1, float f2, float hue)\n" +
            "{\n" +
                "if (hue < 0.0)\n" +
                    "hue += 1.0;\n" +
                "else if (hue > 1.0)\n" +
                    "hue -= 1.0;\n" +
                    "float res;\n" +
                "if ((6.0 * hue) < 1.0)\n" +
                    "res = f1 + (f2 - f1) * 6.0 * hue;\n" +
                "else if ((2.0 * hue) < 1.0)\n" +
                    "res = f2;\n" +
                "else if ((3.0 * hue) < 2.0)\n" +
                    "res = f1 + (f2 - f1) * ((2.0 / 3.0) - hue) * 6.0;\n" +
                "else\n" +
                    "res = f1;\n" +
                "return res;\n" +
            "}\n" +
            "vec3 HSLToRGB(vec3 hsl)\n" +
            "{\n" +
                "vec3 rgb;\n" +
                "if (hsl.y == 0.0)\n" +
                    "rgb = vec3(hsl.z); // Luminance\n" +
                "else\n" +
                "{\n" +
                    "float f2;\n" +
                    "if (hsl.z < 0.5)\n" +
                        "f2 = hsl.z * (1.0 + hsl.y);\n" +
                    "else\n" +
                        "f2 = (hsl.z + hsl.y) - (hsl.y * hsl.z);\n" +
                    "float f1 = 2.0 * hsl.z - f2;\n" +
                    "rgb.r = HueToRGB(f1, f2, hsl.x + (1.0/3.0));\n" +
                    "rgb.g = HueToRGB(f1, f2, hsl.x);\n" +
                    "rgb.b= HueToRGB(f1, f2, hsl.x - (1.0/3.0));\n" +
                "}\n" +
                "return rgb;\n" +
            "}\n" +
            "void main()\n" +
            "{\n" +
                "vec4 textureColor = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ");\n" +
                "// New way:\n" +
                "float lightness = RGBToL(textureColor.rgb);\n" +
                "vec3 shift = vec3(redShift, greenShift, blueShift);\n" +
                "const float a = 0.25;\n" +
                "const float b = 0.333;\n" +
                "const float scale = 0.7;\n" +
                "vec3 midtones = (clamp((lightness - b) /  a + 0.5, 0.0, 1.0) * clamp ((lightness + b - 1.0) / -a + 0.5, 0.0, 1.0) * scale) * shift;\n" +
                "vec3 newColor = textureColor.rgb + midtones;\n" +
                "newColor = clamp(newColor, 0.0, 1.0);\n" +
                "// preserve luminosity\n" +
                "vec3 newHSL = RGBToHSL(newColor);\n" +
//                "float oldLum = RGBToL(textureColor.rgb);\n" +
//                "textureColor.rgb = HSLToRGB(vec3(newHSL.x, newHSL.y, oldLum));\n" +
                "newHSL.x += hue;\n" +
                "newHSL.y *= 1.0 + saturation;\n" +
                "newHSL.z = lightness * (1.0 + luminance);\n" +
//                "textureColor.rgb = HSLToRGB(vec3(newHSL.x, newHSL.y, lightness));\n" +
                "textureColor.rgb = HSLToRGB(newHSL);\n" +
                "gl_FragColor = textureColor;\n" +
            "}";

    private float redShift = 0f;
    private float greenShift = 0f;
    private float blueShift = 0f;
    private int redShiftLocation = -1;
    private int greenShiftLocation = -1;
    private int blueShiftLocation = -1;

    //ADD:增加调整色调/对比度/亮度的参数
    private float saturation = 0f;
    private float hue = 0f;
    private float luminance = 0f;
    private int saturationLocation = -1;
    private int hueLocation = -1;
    private int luminanceLocation = -1;
    private float[] redGreenBlueArr = {0, 0, 0, 0, 0, 0};

    public float getRedShift() {
        return redShift;
    }

    public void setRedShift(float redShift) {
        this.redShift = redShift;
        redGreenBlueArr[0] = redShift;
    }

    public float getGreenShift() {
        return greenShift;
    }

    public void setGreenShift(float greenShift) {
        this.greenShift = greenShift;
        redGreenBlueArr[1] = greenShift;
    }

    public float getBlueShift() {
        return blueShift;
    }

    public void setBlueShift(float blueShift) {
        this.blueShift = blueShift;
        redGreenBlueArr[2] = blueShift;
    }

    public float getSaturation() {
        return saturation;
    }

    public void setSaturation(float saturation) {
        this.saturation = saturation;
        redGreenBlueArr[3] = saturation;
    }

    public float getHue() {
        return hue;
    }

    public void setHue(float hue) {
        this.hue = hue;
        redGreenBlueArr[4] = hue;
    }

    public float getLuminance() {
        return luminance;
    }

    public void setLuminance(float luminance) {
        this.luminance = luminance;
        redGreenBlueArr[5] = luminance;
    }

    @Override
    public void setParameterMulti(float[] value) {
        redGreenBlueArr = value;
        redShift = value[0];
        greenShift = value[1];
        blueShift = value[2];
        saturation = value[3];
        hue = value[4];
        luminance = value[5];
    }

    @Override
    public float[] getParameterMulti() {
//        if (redGreenBlueArr[0]==0 && redGreenBlueArr[1]==0 && redGreenBlueArr[2]==0) {
//            return null;
//        } else {
            return redGreenBlueArr;
//        }
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
        redShiftLocation = GLES20.glGetUniformLocation(programHandle, "redShift");
        GlUtils.checkLocation(redShiftLocation, "redShift");
        greenShiftLocation = GLES20.glGetUniformLocation(programHandle, "greenShift");
        GlUtils.checkLocation(greenShiftLocation, "greenShift");
        blueShiftLocation = GLES20.glGetUniformLocation(programHandle, "blueShift");
        GlUtils.checkLocation(blueShiftLocation, "blueShift");
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
        redShiftLocation = -1;
        greenShiftLocation = -1;
        blueShiftLocation = -1;
        saturationLocation = -1;
        hueLocation = -1;
        luminanceLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
        GLES20.glUniform1f(redShiftLocation, redShift);
        GlUtils.checkError("glUniform1f");
        GLES20.glUniform1f(greenShiftLocation, greenShift);
        GlUtils.checkError("glUniform1f");
        GLES20.glUniform1f(blueShiftLocation, blueShift);
        GlUtils.checkError("glUniform1f");
        GLES20.glUniform1f(saturationLocation, saturation);
        GlUtils.checkError("glUniform1f");
        GLES20.glUniform1f(hueLocation, hue);
        GlUtils.checkError("glUniform1f");
        GLES20.glUniform1f(luminanceLocation, luminance);
        GlUtils.checkError("glUniform1f");
    }

}
