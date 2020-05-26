package com.sabine.cameraview.filters;

import androidx.annotation.NonNull;

import com.sabine.cameraview.filter.BaseFilter;

/**
 * Applies a posterization effect to the input frames.
 */
public class SbHSVSeattleFilter extends BaseFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "varying vec2 "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+";\n"+
            "vec3 hsvAdjust(vec3 src) //color1:red green blue, color2: magenta yellow cyan\n" +
            "{\n" +
            "float fmax = max(src.r,max(src.g,src.b));\n" +
            "float fmin = min(src.r,min(src.g,src.b));\n" +
            "float fdelta = fmax - fmin;\n" +
            "float fs_off;\n" +
            "vec3 hsv;\n" +
            "hsv.z = fmax;\n" +
            "if(0.0 == fdelta)\n" +
            "{\n" +
            "return src;\n" +
            "}\n" +
            "//hue calculate\n" +
            "hsv.y = fdelta/fmax;\n" +
            "if(fmax == src.r)\n" +
            "{\n" +
            "if(src.g >= src.b)\n" +
            "{\n" +
            "hsv.x = (src.g - src.b)/fdelta;\n" +
            "fs_off = (-0.5 - -0.5)*hsv.x + -0.5;\n" +
            "//saturation adjust\n" +
            "hsv.y = hsv.y*(1.0 + fs_off);\n" +
            "clamp(hsv.y, 0.0, 1.0);\n" +
            "//rgb2hsv end\n" +
            "//hsv2rgb\n" +
            "src.r = hsv.z;\n" +
            "src.b = hsv.z*(1.0 - hsv.y);\n" +
            "src.g = hsv.z*(1.0 - hsv.y + hsv.y*hsv.x);\n" +
            "}\n" +
            "else\n" +
            "{\n" +
            "hsv.x = (src.r - src.b)/fdelta;\n" +
            "fs_off = (-0.5 - -0.5)*hsv.x + -0.5;\n" +
            "//saturation adjust\n" +
            "hsv.y = hsv.y*(1.0 + fs_off);\n" +
            "clamp(hsv.y, 0.0, 1.0);\n" +
            "//rgb2hsv end\n" +
            "//hsv2rgb\n" +
            "src.r = hsv.z;\n" +
            "src.g = hsv.z*(1.0 - hsv.y);\n" +
            "src.b = hsv.z*(1.0 - hsv.y*hsv.x);\n" +
            "}\n" +
            "}\n" +
            "else if(fmax == src.g)\n" +
            "{\n" +
            "if(src.r > src.b)\n" +
            "{\n" +
            "hsv.x = (src.g - src.r)/fdelta;\n" +
            "fs_off = (-0.5 - -0.5)*hsv.x + -0.5;\n" +
            "//saturation adjust\n" +
            "hsv.y = hsv.y*(1.0 + fs_off);\n" +
            "clamp(hsv.y, 0.0, 1.0);\n" +
            "//rgb2hsv end\n" +
            "//hsv2rgb\n" +
            "src.g = hsv.z;\n" +
            "src.r = hsv.z*(1.0 - hsv.y*hsv.x);\n" +
            "src.b = hsv.z*(1.0 - hsv.y);\n" +
            "}\n" +
            "else\n" +
            "{\n" +
            "//2\n" +
            "hsv.x = (src.b - src.r)/fdelta;\n" +
            "fs_off = (-0.5 - -0.5)*hsv.x + -0.5;\n" +
            "//saturation adjust\n" +
            "hsv.y = hsv.y*(1.0 + fs_off);\n" +
            "clamp(hsv.y, 0.0, 1.0);\n" +
            "//rgb2hsv end\n" +
            "//hsv2rgb\n" +
            "src.g = hsv.z;\n" +
            "src.r = hsv.z*(1.0 - hsv.y);\n" +
            "src.b = hsv.z*(1.0 - hsv.y + hsv.y*hsv.x);\n" +
            "}\n" +
            "}\n" +
            "else\n" +
            "{\n" +
            "if(src.g > src.r)\n" +
            "{\n" +
            "hsv.x = (src.b - src.g)/fdelta;\n" +
            "fs_off = (-0.5 - -0.5)*hsv.x + -0.5;\n" +
            "//saturation adjust\n" +
            "hsv.y = hsv.y*(1.0 + fs_off);\n" +
            "clamp(hsv.y, 0.0, 1.0);\n" +
            "//rgb2hsv end\n" +
            "//hsv2rgb\n" +
            "src.b = hsv.z;\n" +
            "src.r = hsv.z*(1.0 - hsv.y);\n" +
            "src.g = hsv.z*(1.0 - hsv.y*hsv.x);\n" +
            "}\n" +
            "else\n" +
            "{\n" +
            "//4\n" +
            "hsv.x = (src.r - src.g)/fdelta;\n" +
            "fs_off = (-0.5 - -0.5)*hsv.x + -0.5;\n" +
            "//saturation adjust\n" +
            "hsv.y = hsv.y*(1.0 + fs_off);\n" +
            "clamp(hsv.y, 0.0, 1.0);\n" +
            "//rgb2hsv end\n" +
            "//hsv2rgb\n" +
            "src.b = hsv.z;\n" +
            "src.r = hsv.z*(1.0 - hsv.y + hsv.y*hsv.x);\n" +
            "src.g = hsv.z*(1.0 - hsv.y);\n" +
            "}\n" +
            "}\n" +
            "return src;\n" +
            "}\n" +
            "void main()\n" +
            "{\n" +
            "vec4 src = texture2D(sTexture, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+");\n" +
            "src.rgb = hsvAdjust(src.rgb);\n" +
            "gl_FragColor = src;\n" +
            "}";

    public SbHSVSeattleFilter() { }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }
}
