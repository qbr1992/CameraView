package com.sabine.cameraview.internal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.sabine.cameraview.controls.Grid;
import com.sabine.cameraview.engine.CameraEngine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * A layout overlay that draws grid lines based on the {@link Grid} parameter.
 */
public class FaceRangeLayout extends View {

    public final static int DEFAULT_COLOR = Color.argb(255, 20, 231, 21);

    private CameraEngine.Face [] mFaces;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int faceColor = DEFAULT_COLOR;
    private WorkerHandler mHideFaceHandler;
    private boolean mHideFaceRange = false;

    interface DrawCallback {
        void onDraw(int lines);
    }

    @VisibleForTesting DrawCallback callback;

    public FaceRangeLayout(@NonNull Context context) {
        this(context, null);
    }

    public FaceRangeLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        mPaint.setColor(faceColor);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, context.getResources().getDisplayMetrics()));
        mPaint.setAntiAlias(true);

        mHideFaceHandler = WorkerHandler.get("HideFaceRange");
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        if( mFaces != null && !mHideFaceRange) {
            for(CameraEngine.Face face : mFaces) {
                // Android doc recommends filtering out faces with score less than 50 (same for both Camera and Camera2 APIs)
                if( face != null/* && face.score >= 50*/ ) {
//                    canvas.drawRect(face.rect, mPaint);
                    Rect faceRect = face.rect;
                    int pos_x = faceRect.centerX();
                    int pos_y = faceRect.centerY();
                    int length = faceRect.width()>faceRect.height()?faceRect.height()/10:faceRect.width()/10;
                    canvas.drawLine(faceRect.left, faceRect.top, faceRect.left, faceRect.top+length, mPaint);
                    canvas.drawLine(faceRect.left, faceRect.bottom-length, faceRect.left, faceRect.bottom, mPaint);
                    canvas.drawLine(faceRect.right, faceRect.top, faceRect.right, faceRect.top+length, mPaint);
                    canvas.drawLine(faceRect.right, faceRect.bottom-length, faceRect.right, faceRect.bottom, mPaint);
                    // vertical strokes
                    canvas.drawLine(faceRect.left, faceRect.top, faceRect.left+length, faceRect.top, mPaint);
                    canvas.drawLine(faceRect.left, faceRect.bottom, faceRect.left+length, faceRect.bottom, mPaint);
                    canvas.drawLine(faceRect.right-length, faceRect.top, faceRect.right, faceRect.top, mPaint);
                    canvas.drawLine(faceRect.right-length, faceRect.bottom, faceRect.right, faceRect.bottom, mPaint);
                }
            }
        }
        if (callback != null) {
            callback.onDraw(mFaces == null ? 0 : mFaces.length);
        }
    }

    public void setFaces(CameraEngine.Face [] faces, boolean isChanged) {
        if (isChanged) {
            mHideFaceRange = false;
            mHideFaceHandler.remove(mHideFaceRangeRunnable);
            mHideFaceHandler.post(3000, mHideFaceRangeRunnable);
        }
        mFaces = faces;
        invalidate();
    }

    private Runnable mHideFaceRangeRunnable = new Runnable() {
        @Override
        public void run() {
            mHideFaceRange = true;
        }
    };
}
