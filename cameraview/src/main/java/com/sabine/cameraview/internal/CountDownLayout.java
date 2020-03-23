package com.sabine.cameraview.internal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;

import com.sabine.cameraview.R;

public class CountDownLayout extends View {

    public static boolean isDrawProgress = false;
    // 进度条
    private Paint textPaint;
    // 倒计时数
    private int index = 3;

    private CountDownListener mCountDownListener;

    private int mRotation = 0;

    public CountDownLayout(Context context) {
        this(context, null);
    }

    public CountDownLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CountDownLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initData();
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
        isDrawProgress = false;
    }

    // 初始数据
    private void initData() {
        textPaint = new Paint();
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setAntiAlias(true);
    }

    public void startDraw(CountDownListener countDownListener, int rotation) {
        this.mCountDownListener = countDownListener;
        isDrawProgress = true;
        this.mRotation = rotation;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isDrawProgress) {
            drawProgress(canvas);
        }
    }

    private int[] timerRes = new int[]{
            R.drawable.icon_timer_index_1,
            R.drawable.icon_timer_index_2,
            R.drawable.icon_timer_index_3,
    };

    private int centerX, centerY;
    // 绘制计时器
    private void drawProgress(Canvas canvas) {
        if (index >= 3) {
            centerX = 0;
            centerY = 0;
        }
        if (index > 0) {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), timerRes[index - 1]);
            bitmap = compressBitmap(bitmap,getWidth() / 5);
            if (mRotation == 90) {
                canvas.rotate(-90);
                canvas.translate(-getHeight(), getWidth() / 10);
                if (centerX == 0) centerX = getHeight() * 6 / 10;
                if (centerY == 0) centerY = getWidth() * 4 / 10;
            } else if (mRotation == 270) {
                canvas.rotate(90);
                canvas.translate(getHeight() / 10, -getWidth());
                if (centerX == 0) centerX = getHeight() * 4 / 10;
                if (centerY == 0) centerY = getWidth() * 4 / 10;
            } else if (mRotation == 180) {
                canvas.rotate(180);
                canvas.translate(-getWidth(), -getHeight());
                if (centerX == 0) centerX = getWidth() / 2;
                if (centerY == 0) centerY = getHeight() * 4 / 10;
            } else {
                if (centerX == 0) centerX = getWidth() / 2;
                if (centerY == 0) centerY = getHeight() * 4 / 10;
            }
            if (centerX == 0) centerX = getWidth() / 2;
            if (centerY == 0) centerY = getHeight() * 4 / 10;
            canvas.drawBitmap(bitmap, centerX - bitmap.getWidth() / 2, centerY - bitmap.getHeight() / 2, textPaint);
        }
        index--;
        postDelayed(new Runnable() {
            @Override
            public void run() {
                invalidate();
            }
        }, 800);
        if (index < 0) {
            // 清空 canvas
            centerX = 0;
            centerY = 0;
//            canvas.drawLine(0, 0, 0, 0, textPaint);
            // 开始录制
            if (mCountDownListener != null) mCountDownListener.countdownOver();
            isDrawProgress = false;
        }
    }

    public interface CountDownListener {
        void countdownOver();
    }

    /**
     * 压缩bitmap
     * @param bitmap     bitmap对象
     * @param maxMeasure 最大尺寸
     * @return
     */
    public Bitmap compressBitmap(Bitmap bitmap, int maxMeasure) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int max = Math.max(width, height);
        float scale = (float) max / maxMeasure;
        int w = Math.round(width / scale);
        int h = Math.round(height / scale);
        bitmap = Bitmap.createScaledBitmap(bitmap,w,h,true);
        return bitmap;
    }

}
