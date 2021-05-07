package com.sabine.cameraview.filter;

import androidx.annotation.NonNull;

/**
 * A {@link Filter} 离屏渲染的2D纹理输出到屏幕/编码器的滤镜
 */
public class EndOutputFilter extends BaseFilter {

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+";\n"
            + "uniform sampler2D sTexture;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(sTexture, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+");\n"
            + "}\n";

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }
}
