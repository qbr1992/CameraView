package com.sabine.cameraview.filter;

import android.opengl.GLES20;
import android.util.Log;

import com.otaliastudios.opengl.texture.GlTexture;
import com.sabine.cameraview.utils.OpenGLUtils;

import androidx.annotation.NonNull;

/**
 * A {@link Filter} that draws frames without any modification.
 */
public final class DualInputTextureFilter extends BaseFilter implements TwoParameterFilter {

    private final static String TAG = DualInputTextureFilter.class.getSimpleName();
    private final static String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+";\n"
            + "uniform samplerExternalOES sTexture;\n"
            + "uniform samplerExternalOES sSecondTexture;\n"
            + "uniform float dualInput;\n"
            + "uniform float frontIsFirst;\n"
            + "uniform float aspectRatio;\n"
            + "uniform float drawRotation;\n"
            + "//---------------------------------------------------------\n"
            + "// draw rectangle frame with rounded edges\n"
            + "//---------------------------------------------------------\n"
            + "float roundedRectangle (vec2 uv, vec2 pos, vec2 size, float radius, float thickness)\n"
            + "{\n"
            + "  float d = length(max(abs(uv - pos),size) - size) - radius;\n"
            + "  return smoothstep(0.66, 0.33, d / thickness * 5.0);\n"
            + "}\n"
//            + "float circle(vec2 uv, vec2 pos, float radius)\n"
//            + "{\n"
//            + "    float d = length(uv-pos);\n"
//            + "    return smoothstep(d,d+0.01,radius);\n"
//            + "}\n"
            + "void main() {\n"
            + "  if (dualInput == 1.0) {\n"
            + "     if (frontIsFirst < 1.0) {\n"
            + "         if (drawRotation == 90.0) {\n"
            + "             if (" + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".x > 0.5) {\n"
            + "                 gl_FragColor = texture2D(sTexture, vec2("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x-0.25, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             } else {\n"
            + "                 gl_FragColor = texture2D(sSecondTexture, vec2(1.0-"+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x-0.25, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             }\n"
            + "         } else {\n"
            + "             if (" + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".x < 0.5) {\n"
            + "                 gl_FragColor = texture2D(sTexture, vec2("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x+0.25, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             } else {\n"
            + "                 gl_FragColor = texture2D(sSecondTexture, vec2(1.0-"+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x+0.25, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             }\n"
            + "         }\n"
            + "     } else {\n"
            + "         if (drawRotation == 90.0) {\n"
            + "             if (" + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".x > 0.5) {\n"
            + "                 gl_FragColor = texture2D(sTexture, vec2(1.0-"+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x+0.25, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             } else {\n"
            + "                 gl_FragColor = texture2D(sSecondTexture, vec2("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x+0.25, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             }\n"
            + "         } else {\n"
            + "             if (" + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".x < 0.5) {\n"
            + "                 gl_FragColor = texture2D(sTexture, vec2(1.0-"+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x-0.25, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             } else {\n"
            + "                 gl_FragColor = texture2D(sSecondTexture, vec2("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x-0.25, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             }\n"
            + "         }\n"
            + "     }\n"
            + "  } else if (dualInput == 2.0) {\n"
//            + "     float aspectRatio = 16.0 / 9.0;\n"
            + "     vec2 rectangleSize = vec2(1.0 / 3.0, 1.0 / 3.0);\n"
            + "     float marginRight = 1.0 - 0.02 / aspectRatio;\n"
            + "     float marginBottom = 0.02 + rectangleSize.x;\n"
            + "     float marginLeft = marginRight - rectangleSize.y;\n"
            + "     float marginTop = 0.02;\n"
            + "     if (drawRotation == 90.0) {\n"
            + "         marginLeft = 0.02;\n"
            + "         marginRight = marginLeft+rectangleSize.y;\n"
            + "     } else if (drawRotation == 270.0) {\n"
            + "         marginBottom = 1.0 - 0.02;\n"
            + "         marginTop = marginBottom-rectangleSize.x;\n"
            + "     }\n"
            + "     float radius = 0.02;\n"
            + "     if (frontIsFirst < 1.0) {\n"
            + "         if (" + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".x < marginLeft || " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".x > marginRight || " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".y < marginTop || " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".y > marginBottom) {\n"
            + "             gl_FragColor = texture2D(sTexture, vec2("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "         } else {\n"
            + "             vec2 ratio = vec2(aspectRatio, 1.0);\n"
            + "             vec2 uv = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + " * ratio;\n"
            + "             float intensity = roundedRectangle(uv, vec2(marginLeft + rectangleSize.x / 2.0, marginTop + rectangleSize.y / 2.0) * ratio, vec2(rectangleSize.x / 2.0, rectangleSize.y / 2.0) * ratio - vec2(radius, radius), radius, 0.01);\n"
            + "             gl_FragColor = texture2D(sTexture, vec2("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             vec4 secondFragColor = texture2D(sSecondTexture, vec2(1.0-("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x-marginLeft)*3.0, ("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y-marginTop)*3.0));\n"
            + "             gl_FragColor = vec4(mix(gl_FragColor.rgb, secondFragColor.rgb, intensity), 1.0);\n "
            + "         }\n"
            + "     } else {\n"
            + "         if (" + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".x < marginLeft || " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".x > marginRight || " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".y < marginTop || " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + ".y > marginBottom) {\n"
            + "             gl_FragColor = texture2D(sTexture, vec2(1.0-"+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "         } else {\n"
            + "             vec2 ratio = vec2(aspectRatio, 1.0);\n"
            + "             vec2 uv = " + DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME + " * ratio;\n"
            + "             float intensity = roundedRectangle(uv, vec2(marginLeft + rectangleSize.x / 2.0, marginTop + rectangleSize.y / 2.0) * ratio, vec2(rectangleSize.x / 2.0, rectangleSize.y / 2.0) * ratio - vec2(radius, radius), radius, 0.01);\n"
            + "             gl_FragColor = texture2D(sTexture, vec2(1.0-"+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y));\n"
            + "             vec4 secondFragColor = texture2D(sSecondTexture, vec2(("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x-marginLeft)*3.0, ("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y-marginTop)*3.0));\n"
            + "             gl_FragColor = vec4(mix(gl_FragColor.rgb, secondFragColor.rgb, intensity), 1.0);\n "
//            + "             gl_FragColor = texture2D(sSecondTexture, vec2(("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".x-marginLeft)*3.0, ("+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+".y-marginTop)*3.0));\n"
            + "         }\n"
            + "     }\n"
            + "  } else {\n"
            + "     gl_FragColor = texture2D(sTexture, "+DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME+");\n"
            + "  }\n"
            + "}\n";

    private int secondTextureLocation = -1;
    private GlTexture secondTexture = null;
    private int dualInputLocation = -1;
    private float dualInput = 1.0f; //1.0:上下显示模式；2.0:画中画显示模式
    private int frontIsFirstLocation = -1;
    private float frontIsFirst = 0.0f;
    private int aspectRatioLocation = -1;
    private float aspectRatio = 16.0f/9.0f;
    private int drawRotationLocation = -1;
    private float drawRotation = 0.0f;

    @Override
    public void onCreate(int programHandle) {
        super.onCreate(programHandle);

        secondTextureLocation = GLES20.glGetUniformLocation(programHandle, "sSecondTexture");
        dualInputLocation = GLES20.glGetUniformLocation(programHandle, "dualInput");
        frontIsFirstLocation = GLES20.glGetUniformLocation(programHandle, "frontIsFirst");
        aspectRatioLocation = GLES20.glGetUniformLocation(programHandle, "aspectRatio");
        drawRotationLocation = GLES20.glGetUniformLocation(programHandle, "drawRotation");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        secondTextureLocation = -1;
        dualInputLocation = -1;
        frontIsFirstLocation = -1;
        aspectRatioLocation = -1;
        drawRotationLocation = -1;
    }

    @Override
    protected void onPreDraw(long timestampUs, @NonNull float[] transformMatrix) {
        super.onPreDraw(timestampUs, transformMatrix);

        if (secondTexture != null) {
            OpenGLUtils.bindTexture(secondTextureLocation, secondTexture.getId(), secondTexture.getUnit() - GLES20.GL_TEXTURE0, secondTexture.getTarget()/*GLES20.GL_TEXTURE_2D*/);
        }
        GLES20.glUniform1f(dualInputLocation, secondTexture != null ? dualInput : 0.0f);
        GLES20.glUniform1f(frontIsFirstLocation, frontIsFirst);
        GLES20.glUniform1f(aspectRatioLocation, aspectRatio);
        GLES20.glUniform1f(drawRotationLocation, secondTexture != null ? drawRotation : 0.0f);
//        OpenGLUtils.bindTexture(secondTextureLocation, secondTexture, 1, GLES20.GL_TEXTURE_2D);
//        GLES20.glUniform1f(dualInputLocation, secondTexture != -1 ? 1.0f : 0.0f);
    }

    @NonNull
    @Override
    public String getFragmentShader() {
        return FRAGMENT_SHADER;
    }

    @Override
    public void setSecondTexture(GlTexture secondTexture, float frontIsFirst) {
        this.secondTexture = secondTexture;
        this.frontIsFirst = frontIsFirst;
    }

    @Override
    public void setSecondTexture(GlTexture secondTexture, float frontIsFirst, float drawRotation) {
        setSecondTexture(secondTexture, frontIsFirst);
        this.drawRotation = drawRotation;
    }

    @Override
    public void setParameter1(float value) {
        aspectRatio = value;
    }

    @Override
    public float getParameter1() {
        return aspectRatio;
    }

    @Override
    public void setParameter2(float value) {
        dualInput = value;
    }

    @Override
    public float getParameter2() {
        return dualInput;
    }

    @Override
    public void setDualInputTextureMode(float inputTextureMode) {
        super.setDualInputTextureMode(inputTextureMode);
        dualInput = inputTextureMode;
    }
}
