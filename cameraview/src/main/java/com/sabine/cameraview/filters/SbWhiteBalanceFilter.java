package com.sabine.cameraview.filters;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.TwoParameterFilter;
import com.sabine.cameraview.internal.GlUtils;

public class SbWhiteBalanceFilter extends BaseFilter implements TwoParameterFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            " uniform samplerExternalOES sTexture;\n" +
            " varying highp vec2 "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ";\n" +
            " \n" +
            "uniform lowp float temperature;\n" +
            "uniform lowp float tint;\n" +
            "\n" +
            "const lowp vec3 warmFilter = vec3(0.93, 0.54, 0.0);\n" +
            "\n" +
            "const mediump mat3 RGBtoYIQ = mat3(0.299, 0.587, 0.114, 0.596, -0.274, -0.322, 0.212, -0.523, 0.311);\n" +
            "const mediump mat3 YIQtoRGB = mat3(1.0, 0.956, 0.621, 1.0, -0.272, -0.647, 1.0, -1.105, 1.702);\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "	lowp vec4 source = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ");\n" +
            "	\n" +
            "	mediump vec3 yiq = RGBtoYIQ * source.rgb; //adjusting tint\n" +
            "	yiq.b = clamp(yiq.b + tint*0.5226*0.1, -0.5226, 0.5226);\n" +
            "	lowp vec3 rgb = YIQtoRGB * yiq;\n" +
            "\n" +
            "	lowp vec3 processed = vec3(\n" +
            "		(rgb.r < 0.5 ? (2.0 * rgb.r * warmFilter.r) : (1.0 - 2.0 * (1.0 - rgb.r) * (1.0 - warmFilter.r))), //adjusting temperature\n" +
            "		(rgb.g < 0.5 ? (2.0 * rgb.g * warmFilter.g) : (1.0 - 2.0 * (1.0 - rgb.g) * (1.0 - warmFilter.g))), \n" +
            "		(rgb.b < 0.5 ? (2.0 * rgb.b * warmFilter.b) : (1.0 - 2.0 * (1.0 - rgb.b) * (1.0 - warmFilter.b))));\n" +
            "\n" +
            "	gl_FragColor = vec4(mix(rgb, processed, temperature), source.a);\n" +
            "}";




//            "uniform float temperature;\n" +
//            "uniform float tint;\n" +
//            "vec3 whiteBalance(vec3 src, float temp, float tint)\n" +
//            "{\n" +
//            "temp = clamp(temp, 1200.0, 12000.0);\n" +
//            "tint = clamp(tint, 0.02, 5.0);\n" +
//            "float xD;\n" +
//            "temp /= 1000.0;\n" +
//            "if(temp < 4.0)\n" +
//                "xD = 0.27475 / (temp * temp * temp) - 0.98598 / (temp * temp) + 1.17444 / temp + 0.145986;\n" +
//            "else if(temp < 7.0)\n" +
//                "xD = -4.6070 / (temp * temp * temp) + 2.9678 / (temp * temp) + 0.09911 / temp + 0.244063;\n" +
//            "else xD = -2.0064 / (temp * temp * temp) + 1.9018 / (temp * temp) + 0.24748 / temp + 0.237040;\n" +
//                "float yD = -3.0 * xD * xD + 2.87 * xD - 0.275;\n" +
//            "float X = xD / yD;\n" +
//            "float Z = (1.0 - xD - yD) / yD;\n" +
//            "vec3 color;\n" +
//            "color.r = X * 3.24074 - 1.53726 - Z * 0.498571;\n" +
//            "color.g = -X * 0.969258 + 1.87599 + Z * 0.0415557;\n" +
//            "color.b = X * 0.0556352 - 0.203996 + Z * 1.05707;\n" +
//            "color.g /= tint;\n" +
//            "color /= max(max(color.r, color.g), color.b);\n" +
//            "color = 1.0 / color;\n" +
//            "color /= color.r * 0.299 + color.g * 0.587 + color.b * 0.114;\n" +
//            "return src * color;\n" +
//            "}\n" +
//            "vec3 map_color(vec3 src, float lum)\n" +
//            "{\n" +
//            "vec3 h = src - lum;\n" +
//            "if(src.r > 1.0)\n" +
//            "{\n" +
//            "float tmp = 1.0 - lum;\n" +
//            "h.g = h.g * tmp / h.r;\n" +
//            "h.b = h.b * tmp / h.r;\n" +
//            "h.r = tmp;\n" +
//            "}\n" +
//            "float t3r = h.b + lum;\n" +
//            "if(t3r < -0.00003)\n" +
//            "{\n" +
//            "src.rg = lum - h.rg * lum / h.b;\n" +
//            "src.b = 0.0;\n" +
//            "}\n" +
//            "else\n" +
//            "{\n" +
//            "src.rg = lum + h.rg;\n" +
//            "src.b = t3r;\n" +
//            "}\n" +
//            "return src;\n" +
//            "}\n" +
//            "vec3 dispatch(vec3 src)\n" +
//            "{\n" +
//            "float lum = dot(src, vec3(0.299, 0.587, 0.114));\n" +
//            "if(src.g > src.b)\n" +
//            "{\n" +
//            "if(src.r > src.g)\n" +
//            "{\n" +
//            "src = map_color(src, lum);\n" +
//            "}\n" +
//            "else if(src.r > src.b)\n" +
//            "{\n" +
//            "src.grb = map_color(src.grb, lum);\n" +
//            "}\n" +
//            "else\n" +
//            "{\n" +
//            "src.gbr = map_color(src.gbr, lum);\n" +
//            "}\n" +
//            "}\n" +
//            "else\n" +
//            "{\n" +
//            "if(src.g > src.r)\n" +
//            "{\n" +
//            "src.bgr = map_color(src.bgr, lum);\n" +
//            "}\n" +
//            "else if(src.b > src.r)\n" +
//            "{\n" +
//            "src.brg = map_color(src.brg, lum);\n" +
//            "}\n" +
//            "else\n" +
//            "{\n" +
//            "src.rbg = map_color(src.rbg, lum);\n" +
//            "}\n" +
//            "}\n" +
//            "return src;\n" +
//            "}\n" +
//            "void main()\n" +
//            "{\n" +
//            "vec4 src = texture2D(sTexture, "+ DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ");\n" +
//            "src.rgb = dispatch(whiteBalance(src.rgb, temperature, tint));\n" +
//            "gl_FragColor = src;\n" +
//            "}\n";




//const static char* const s_fshWhiteBalanceOptimized = CGE_SHADER_STRING_PRECISION_H
//            (
//                    varying vec2 textureCoordinate;
//                    uniform sampler2D inputImageTexture;
//                    uniform vec3 balance;
//
//                    vec3 map_color(vec3 src, float lum)
//    {
//        vec3 h = src - lum;
//        if(src.r > 1.0)
//        {
//            float tmp = 1.0 - lum;
//            h.g = h.g * tmp / h.r;
//            h.b = h.b * tmp / h.r;
//            h.r = tmp;
//        }
//        float t3r = h.b + lum;
//        if(t3r < -0.00003)
//        {
//            src.rg = lum - h.rg * lum / h.b;
//            src.b = 0.0;
//        }
//        else
//        {
//            src.rg = lum + h.rg;
//            src.b = t3r;
//        }
//        return src;
//    }
//
//    vec3 dispatch(vec3 src)
//    {
//        float lum = dot(src, vec3(0.299, 0.587, 0.114));
//        if(src.g > src.b)
//        {
//            if(src.r > src.g)
//            {
//                src = map_color(src, lum);
//            }
//            else if(src.r > src.b)
//            {
//                src.grb = map_color(src.grb, lum);
//            }
//            else
//            {
//                src.gbr = map_color(src.gbr, lum);
//            }
//        }
//        else
//        {
//            if(src.g > src.r)
//            {
//                src.bgr = map_color(src.bgr, lum);
//            }
//            else if(src.b > src.r)
//            {
//                src.brg = map_color(src.brg, lum);
//            }
//            else
//            {
//                src.rbg = map_color(src.rbg, lum);
//            }
//        }
//        return src;
//    }
//
//    void main()
//    {
//        vec4 src = texture2D(inputImageTexture, textureCoordinate);
//        src.rgb = dispatch(src.rgb * balance);
//        gl_FragColor = src;
//    }





    private float temperature = 5000;
    private int temperatureLocation;
    private float tint;
    private int tintLocation;

    public float getTemperature() {
        return temperature;
    }

    public void setTemperature(float temperature) {
        this.temperature = temperature;
    }

    public float getTint() {
        return tint;
    }

    /**
     * 数字越大越红
     * @param tint
     */
    public void setTint(float tint) {
        this.tint = tint;
    }

    @Override
    public void setParameter1(float value) {
        setTemperature(value);
    }

    @Override
    public float getParameter1() {
        return getTemperature();
    }

    @Override
    public void setParameter2(float value) {
        setTint(value);
    }

    @Override
    public float getParameter2() {
        return getTint();
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        temperatureLocation = GLES20.glGetUniformLocation(programHandle, "temperature");
        GlUtils.checkLocation(temperatureLocation, "temperature");
        tintLocation = GLES20.glGetUniformLocation(programHandle, "tint");
        GlUtils.checkLocation(tintLocation, "tint");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        temperatureLocation = -1;
        tintLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);
//        GLES20.glUniform1f(temperatureLocation, this.temperature < 5000 ? (float) (0.0004 * (this.temperature - 5000.0)) : (float) (0.00006 * (this.temperature - 5000.0)));
        GLES20.glUniform1f(temperatureLocation, temperature);
        GlUtils.checkError("glUniform1f");
//        GLES20.glUniform1f(tintLocation, (float) (this.tint / 100.0));
        GLES20.glUniform1f(tintLocation, tint);
        GlUtils.checkError("glUniform1f");
    }

}
