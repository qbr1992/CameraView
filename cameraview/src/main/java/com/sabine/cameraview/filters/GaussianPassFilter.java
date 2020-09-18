package com.sabine.cameraview.filters;

import android.graphics.Color;
import android.opengl.GLES20;

import com.otaliastudios.opengl.core.Egloo;
import com.sabine.cameraview.filter.BaseFilter;
import com.sabine.cameraview.filter.OneParameterFilter;
import com.sabine.cameraview.filter.TwoParameterFilter;

import androidx.annotation.NonNull;

/**
 * Representation of input frames using only two color tones.
 */
public class GaussianPassFilter extends BaseFilter implements OneParameterFilter, TwoParameterFilter {

//    private final static String VERTEX_SHADER = "// 高斯模糊\n" +
//            "uniform mat4 uMVPMatrix;\n" +
//            "uniform mat4 uTexMatrix;\n" +
//            "attribute vec3 aPosition;\n" +
//            "attribute vec2 aTextureCoord;\n" +
//            "uniform highp float texelWidthOffset;\n" +
//            "uniform highp float texelHeightOffset;\n" +
//            "\n" +
//            "varying vec2 "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+";\n" +
//            "varying vec4 textureShift_1;\n" +
//            "varying vec4 textureShift_2;\n" +
//            "varying vec4 textureShift_3;\n" +
//            "varying vec4 textureShift_4;\n" +
//            "\n" +
//            "void main() {\n" +
//            "    gl_Position = uMVPMatrix * vec4(aPosition, 1.0);\n" +
//            "    "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" = (uTexMatrix * vec4(aTextureCoord, 1.0, 1.0)).xy;\n" +
//            "    vec2 singleStepOffset = vec2(texelWidthOffset, texelHeightOffset);\n" +
//            "    textureShift_1 = vec4("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" - singleStepOffset, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" + singleStepOffset);\n" +
//            "    textureShift_2 = vec4("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" - 2.0 * singleStepOffset, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" + 2.0 * singleStepOffset);\n" +
//            "    textureShift_3 = vec4("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" - 3.0 * singleStepOffset, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" + 3.0 * singleStepOffset);\n" +
//            "    textureShift_4 = vec4("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" - 4.0 * singleStepOffset, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" + 4.0 * singleStepOffset);\n" +
//            "}";

    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
            "// 优化后的高斯模糊\n"+
            "precision highp float;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "\n" +
            "varying vec2 "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+";\n"+
//            "varying highp vec4 textureShift_1;\n" +
//            "varying highp vec4 textureShift_2;\n" +
//            "varying highp vec4 textureShift_3;\n" +
//            "varying highp vec4 textureShift_4;\n" +
            "\n" +
            "uniform highp float texelWidthOffset;\n" +
            "uniform highp float texelHeightOffset;\n" +
            "uniform mediump float distanceNormalizationFactor;//7.0\n" +
            "\n" +
            "void main()\n" +
            "{\n" +
            "    highp vec4 textureShift_1;\n" +
            "    highp vec4 textureShift_2;\n" +
            "    highp vec4 textureShift_3;\n" +
            "    highp vec4 textureShift_4;\n" +
            "    lowp vec4 centralColor;\n" +
            "    lowp float gaussianWeightTotal;\n" +
            "    lowp vec4 sum;\n" +
            "    lowp vec4 sampleColor;\n" +
            "    lowp float distanceFromCentralColor;\n" +
            "    lowp float gaussianWeight;\n" +
            "    vec2 singleStepOffset = vec2(texelWidthOffset, texelHeightOffset);\n" +
            "    textureShift_1 = vec4("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" - singleStepOffset, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" + singleStepOffset);\n" +
            "    textureShift_2 = vec4("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" - 2.0 * singleStepOffset, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" + 2.0 * singleStepOffset);\n" +
            "    textureShift_3 = vec4("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" - 3.0 * singleStepOffset, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" + 3.0 * singleStepOffset);\n" +
            "    textureShift_4 = vec4("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" - 4.0 * singleStepOffset, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+" + 4.0 * singleStepOffset);\n" +
            "\n" +
            "    centralColor = texture2D(sTexture, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+");\n" +
            "    gaussianWeightTotal = 0.18;\n" +
            "    sum = centralColor * 0.18;\n" +
            "\n" +
            "    sampleColor = texture2D(sTexture, textureShift_4.xy);\n" +
            "    distanceFromCentralColor = min(distance(centralColor, sampleColor) * distanceNormalizationFactor, 1.0);\n" +
            "    gaussianWeight = 0.05 * (1.0 - distanceFromCentralColor);\n" +
            "    gaussianWeightTotal += gaussianWeight;\n" +
            "    sum += sampleColor * gaussianWeight;\n" +
            "\n" +
            "    sampleColor = texture2D(sTexture, textureShift_3.xy);\n" +
            "    distanceFromCentralColor = min(distance(centralColor, sampleColor) * distanceNormalizationFactor, 1.0);\n" +
            "    gaussianWeight = 0.09 * (1.0 - distanceFromCentralColor);\n" +
            "    gaussianWeightTotal += gaussianWeight;\n" +
            "    sum += sampleColor * gaussianWeight;\n" +
            "\n" +
            "    sampleColor = texture2D(sTexture, textureShift_2.xy);\n" +
            "    distanceFromCentralColor = min(distance(centralColor, sampleColor) * distanceNormalizationFactor, 1.0);\n" +
            "    gaussianWeight = 0.12 * (1.0 - distanceFromCentralColor);\n" +
            "    gaussianWeightTotal += gaussianWeight;\n" +
            "    sum += sampleColor * gaussianWeight;\n" +
            "\n" +
            "    sampleColor = texture2D(sTexture, textureShift_1.xy);\n" +
            "    distanceFromCentralColor = min(distance(centralColor, sampleColor) * distanceNormalizationFactor, 1.0);\n" +
            "    gaussianWeight = 0.15 * (1.0 - distanceFromCentralColor);\n" +
            "    gaussianWeightTotal += gaussianWeight;\n" +
            "    sum += sampleColor * gaussianWeight;\n" +
            "\n" +
            "    sampleColor = texture2D(sTexture, textureShift_1.zw);\n" +
            "    distanceFromCentralColor = min(distance(centralColor, sampleColor) * distanceNormalizationFactor, 1.0);\n" +
            "    gaussianWeight = 0.15 * (1.0 - distanceFromCentralColor);\n" +
            "    gaussianWeightTotal += gaussianWeight;\n" +
            "    sum += sampleColor * gaussianWeight;\n" +
            "\n" +
            "    sampleColor = texture2D(sTexture, textureShift_2.zw);\n" +
            "    distanceFromCentralColor = min(distance(centralColor, sampleColor) * distanceNormalizationFactor, 1.0);\n" +
            "    gaussianWeight = 0.12 * (1.0 - distanceFromCentralColor);\n" +
            "    gaussianWeightTotal += gaussianWeight;\n" +
            "    sum += sampleColor * gaussianWeight;\n" +
            "\n" +
            "    sampleColor = texture2D(sTexture, textureShift_3.zw);\n" +
            "    distanceFromCentralColor = min(distance(centralColor, sampleColor) * distanceNormalizationFactor, 1.0);\n" +
            "    gaussianWeight = 0.09 * (1.0 - distanceFromCentralColor);\n" +
            "    gaussianWeightTotal += gaussianWeight;\n" +
            "    sum += sampleColor * gaussianWeight;\n" +
            "\n" +
            "    sampleColor = texture2D(sTexture, textureShift_4.zw);\n" +
            "    distanceFromCentralColor = min(distance(centralColor, sampleColor) * distanceNormalizationFactor, 1.0);\n" +
            "    gaussianWeight = 0.05 * (1.0 - distanceFromCentralColor);\n" +
            "    gaussianWeightTotal += gaussianWeight;\n" +
            "    sum += sampleColor * gaussianWeight;\n" +
            "\n" +
            "    if (gaussianWeightTotal < 0.4)\n" +
            "    {\n" +
            "        gl_FragColor = centralColor;\n" +
            "    }\n" +
            "    else if (gaussianWeightTotal < 0.5)\n" +
            "    {\n" +
            "        gl_FragColor = mix(sum / gaussianWeightTotal, centralColor, (gaussianWeightTotal - 0.4) / 0.1);\n" +
            "    }\n" +
            "    else\n" +
            "    {\n" +
            "        gl_FragColor = sum / gaussianWeightTotal;\n" +
            "    }\n" +
            "}";

    // Default values
    protected float blurSize = 3.0f;
    private float texelWidthOffset = 0.0f;
    private float texelHeightOffset = 0.0f;
    private float distanceNormalizationFactor = 7.0f;
    private int texelWidthOffsetLocation = -1;
    private int texelHeightOffsetLocation = -1;
    private int distanceNormalizationFactorLocation = -1;

    private boolean isVerticalFilter = true;

    public GaussianPassFilter() {
        blurSize = 3.0f;
        distanceNormalizationFactor = 7.0f;
    }

    /**
     * Sets the two texelOffset.
     * @param texelWidthOffset texelWidthOffset
     * @param texelHeightOffset texelHeightOffset
     */
    @SuppressWarnings({"unused"})
    public void setTexelOffset(float texelWidthOffset, float texelHeightOffset) {
        setTexelWidthOffset(texelWidthOffset);
        setTexelHeightOffset(texelHeightOffset);
    }

    /**
     * Sets the TexelWidthOffset.
     *
     * @param texelWidthOffset texelWidthOffset
     */
    @SuppressWarnings("WeakerAccess")
    public void setTexelWidthOffset(float texelWidthOffset) {
        // Remove any alpha.
        if (isVerticalFilter)
            texelWidthOffset = 0.0f;
        this.texelWidthOffset = texelWidthOffset;
    }

    /**
     * Sets the setTexelHeightOffset.
     * Defaults to {@link Color#YELLOW}.
     *
     * @param texelHeightOffset texelHeightOffset
     */
    @SuppressWarnings("WeakerAccess")
    public void setTexelHeightOffset(float texelHeightOffset) {
        // Remove any alpha.
        if (!isVerticalFilter)
            texelHeightOffset = 0.0f;
        this.texelHeightOffset = texelHeightOffset;
    }

    /**
     * Returns the TexelWidthOffset.
     *
     * @see #setTexelWidthOffset(float)
     * @return texelWidthOffset
     */
    @SuppressWarnings({"unused", "WeakerAccess"})
    public float getTexelWidthOffset() {
        return texelWidthOffset;
    }

    /**
     * Returns the TexelHeightOffset.
     *
     * @see #setTexelHeightOffset(float)
     * @return texelHeightOffset
     */
    @SuppressWarnings({"unused", "WeakerAccess"})
    public float getTexelHeightOffset() {
        return texelHeightOffset;
    }

    /**
     * 设置模糊半径大小，默认为3.0f
     * @param blurSize
     */
    public void setBlurSize(float blurSize) {
        this.blurSize = blurSize;
    }

    public void setDistanceNormalizationFactor(float distanceNormalizationFactor) {
        this.distanceNormalizationFactor = distanceNormalizationFactor;
    }

    public float getDistanceNormalizationFactor() { return distanceNormalizationFactor; }

    public void setFilterOrientation(boolean isVertical) { isVerticalFilter = isVertical; }

    public boolean getFilterOrientation() { return isVerticalFilter; }

    @Override
    public void setParameter1(float value) {
        // no easy way to transform 0...1 into a color.
        setDistanceNormalizationFactor(value);
    }

    @Override
    public float getParameter1() {
        return getDistanceNormalizationFactor();
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

//    @NonNull
//    @Override
//    public String getVertexShader() {
//        return VERTEX_SHADER;
//    }

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);
        texelWidthOffsetLocation = GLES20.glGetUniformLocation(programHandle, "texelWidthOffset");
        Egloo.checkGlProgramLocation(texelWidthOffsetLocation, "texelWidthOffset");
        texelHeightOffsetLocation = GLES20.glGetUniformLocation(programHandle, "texelHeightOffset");
        Egloo.checkGlProgramLocation(texelHeightOffsetLocation, "texelHeightOffset");
        distanceNormalizationFactorLocation = GLES20.glGetUniformLocation(programHandle, "distanceNormalizationFactor");
        Egloo.checkGlProgramLocation(distanceNormalizationFactorLocation, "distanceNormalizationFactor");
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);

        GLES20.glUniform1f(texelWidthOffsetLocation, texelWidthOffset==0.0f?0.0f:blurSize/texelWidthOffset);
        Egloo.checkGlError("glUniform1f");
        GLES20.glUniform1f(texelHeightOffsetLocation, texelHeightOffset==0.0f?0.0f:blurSize/texelHeightOffset);
        Egloo.checkGlError("glUniform1f");
        GLES20.glUniform1f(distanceNormalizationFactorLocation, distanceNormalizationFactor);
        Egloo.checkGlError("glUniform1f");
    }

    @Override
    public void setSize(int width, int height) {
        super.setSize(width, height);
        setTexelOffset(width, height);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        texelWidthOffsetLocation = -1;
        texelHeightOffsetLocation = -1;
        distanceNormalizationFactorLocation = -1;
    }

    @Override
    public void setParameter2(float value) {

    }

    @Override
    public float getParameter2() {
        return 0;
    }
}
