package com.sabine.cameraview.internal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.sabine.cameraview.CameraOptions;
import com.sabine.cameraview.CameraView;
import com.sabine.cameraview.R;
import com.sabine.cameraview.SensorController;
import com.sabine.cameraview.engine.Camera2Engine;
import com.sabine.cameraview.engine.CameraBaseEngine;
import com.sabine.cameraview.engine.CameraEngine;
import com.sabine.cameraview.preview.CameraPreview;
import com.sabine.cameraview.preview.GlCameraPreview;

import java.lang.reflect.Method;

public class FocusLayout extends View {

    public static final String TAG = FocusLayout.class.getSimpleName();

    // 放大缩小
    private float zoomTrackLength;// track 的长度
    private float zoomProgress = 0;// 当前的进度
    private float gainValue = 0; //放大倍数
    private float zoomMin = 0; // 首值（起始值）
    private float zoomMax = 0; // 尾值（结束值）
    private float zoomDelta = 0; // track 上的总值
    private float zoomThumbCenterX = 0;// thumb的中心X坐标
    private boolean isZoomThumbOnDragging; // thumb是否在被拖动
    private float zoomLeft;// 左边点
    private float zoomRight;// 右边点
    private float zoomBottom;// 下边点
    private float zoomBmpLeftPoint, zoomBmpRightPoint, zoomBmpTopPoint, zoomBmpBottomPoint;
    private int zoomOffset;// 左右间隔

    // 曝光 测光 对焦
    private float exposureTrackLength;// track 的长度
    private float exposureTrackRadius;// track 的半径
    private float exposureProgress = 0;// 当前的进度
    private float exposureMin = 0; // 首值（起始值）
    private float exposureMax = 0; // 尾值（结束值）
    private float exposureDelta = 0; // track 上的总值
    private float exposureThumbCenterX = 0;// thumb的中心Y坐标
    private float exposureThumbCenterY = 0;// thumb的中心Y坐标
    private boolean isExposureThumbOnDragging; // thumb是否在被拖动
    private float exposureTop, exposureBottom;// 进度条 最小最大点
    private float exposureBmpLeftPoint, exposureBmpRightPoint, exposureBmpTopPoint, exposureBmpBottomPoint;
    private float currX, currY;// 当前X Y 点
    private float currX2, currY2;// 双摄第二路当前X Y 点
    private Paint focusProgressPaint, exposureLinePaint, exposureBpmPaint;
    private float focusBmpLeftPoint, focusBmpRightPoint, focusBmpTopPoint, focusBmpBottomPoint;     // 焦点框坐标
    private float focus2BmpLeftPoint, focus2BmpRightPoint, focus2BmpTopPoint, focus2BmpBottomPoint;     // 双摄第二路焦点框坐标
    private Bitmap exposureBmp;// thumb
    private Bitmap focusBmp;
    private boolean isClear = true;// 是否清空view
    private boolean camera2Clear = true;// 是否清空view
    private int exposureOffset;// 左右间隔
    private boolean isShowExposure;// 是否显示曝光调节
    private boolean isMoveOccurs = false;

    // 计算放大缩小事件
    private int mode = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    // 另一个手指压下屏幕
    private boolean pointerDown = false; //判断是否滑动
    private float startDis;// 开始距离
    private PointF startPoint = new PointF();
    private float eventX, eventY;
    private float dx, dy;
    private static final int CLEAR_VIEW = 1;// 清空view
    private float initZoomProgress = 0, initExposureProgress = 0;

    private CameraView mCameraView;
    private OnFocusListener mFocusListener;

    private Camera2Engine mCameraEngine;

    public FocusLayout(Context context) {
        this(context, null);
    }

    public FocusLayout(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FocusLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initDrawData();
    }

    public void setCameraView(CameraView cameraView) {
        this.mCameraView = cameraView;
        mCameraEngine = (Camera2Engine) mCameraView.getCameraEngine();
    }

    public void setOnFocusListener(OnFocusListener focusListener) {
        this.mFocusListener = focusListener;
    }

    // 初始化 绘制的参数
    private void initDrawData() {

        // 初始化 曝光 测光 对焦 数据
        // 焦距 测光区域 圆
        focusProgressPaint = new Paint();
        initPaint(focusProgressPaint);
        focusProgressPaint.setStyle(Paint.Style.STROKE);
        focusProgressPaint.setStrokeCap(Paint.Cap.ROUND);
        // 线条画笔
        exposureLinePaint = new Paint();
        initPaint(exposureLinePaint);
        // 图片画笔
        exposureBpmPaint = new Paint();
        initPaint(exposureBpmPaint);
    }

    // 初始化默认的画笔
    private void initPaint(Paint paint) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#FFFFFF"));
        paint.setAntiAlias(true);// 抗锯齿效果
        paint.setStrokeWidth(4.0f);
    }

    // 初始化Camera 设置参数
    public void initCameraViewData() {
        if (mCameraView == null) return;
        CameraOptions options = mCameraView.getCameraOptions();
        if (options == null) return;
        zoomMin = 0;
        zoomMax = 1;
        zoomProgress = mCameraView.getZoom();
        initZoomProgress = zoomProgress;
        // 曝光
        // 曝光的取值范围
        exposureTrackRadius = DensityUtil.dp2px(48);
        exposureOffset = DensityUtil.dp2px(3);
        exposureMin = options.getExposureCorrectionMinValue();
        exposureMax = options.getExposureCorrectionMaxValue();
        initExposureProgress = exposureProgress = mCameraView.getExposureCorrection();
        exposureDelta = exposureMax - exposureMin;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isShowExposure) {
            // 是否重新绘制位置
            if (!isExposureThumbOnDragging && !flip && mCameraEngine.isAutoExposure(mCameraEngine.getCurrentCameraIndex())) {
                if (mCameraView.dual() && mCameraEngine.getCurrentCameraIndex() == 1) {
                    currX2 = eventX;
                    currY2 = eventY;
                } else {
                    currX = eventX;
                    currY = eventY;
                }
            }
            if (mCameraView.dual()) {
                // 双摄
                if ((currX != 0 || currY != 0) && ((!isClear && mCameraEngine.getCurrentCameraIndex() == 0) || !mCameraEngine.isAutoExposure(mCameraEngine.getCurrentCameraIndex(new PointF(currX, currY))))) {
                    drawFocusMeteringExposure(canvas, currX, currY, mCameraEngine.isAutoExposure(mCameraEngine.getCurrentCameraIndex(new PointF(currX, currY))));
                } else { // camera1隐藏后，需要将坐标清零，避免点击相同位置直接变为锁定状态
                    focusBmpTopPoint = focusBmpBottomPoint = focusBmpLeftPoint = focusBmpRightPoint = 0;
                }
                if ((currX2 != 0 || currY2 != 0) && ((!camera2Clear && mCameraEngine.getCurrentCameraIndex() == 1) || !mCameraEngine.isAutoExposure(mCameraEngine.getCurrentCameraIndex(new PointF(currX2, currY2))))) {
                    drawFocusMeteringExposure(canvas, currX2, currY2, mCameraEngine.isAutoExposure(mCameraEngine.getCurrentCameraIndex(new PointF(currX2, currY2))));
                } else { // camera2隐藏后，需要将坐标清零，避免点击相同位置直接变为锁定状态
                    focus2BmpTopPoint = focus2BmpBottomPoint = focus2BmpLeftPoint = focus2BmpRightPoint = 0;
                }
            } else {
                // 非双摄
                if (!isClear()) {
                    drawFocusMeteringExposure(canvas, currX, currY, mCameraEngine.isAutoExposure());
                }
            }
            if (flip) flip = false;
        }
    }

    // 绘制聚焦测光区域 -曝光进度条
    private void drawFocusMeteringExposure(Canvas canvas, float currX, float currY, boolean isAutoExploreFocus) {
        // 圆半径
        float radius = DensityUtil.dp2px(32);

        // 绘制圆 对焦测光区域
        float left = currX - radius;
        float right = currX + radius;
        float top = currY - radius;
        float bottom = currY + radius;

        // 判断焦距区域圆的点
        // 左点
        if (left < 0) {
            left = 0;
            right = left + radius * 2;
        }
        // 右点
        if (right > getWidth()) {
            right = getWidth();
            left = right - radius * 2;
        }
        // 上点
        if (top < 0) {
            top = 0;
            bottom = top + radius * 2;
        }
        // 下点
        if (bottom > getHeight()) {
            bottom = getHeight();
            top = bottom - radius * 2;
        }
        //绘图
        if (focusBmp == null) focusBmp = BitmapFactory.decodeResource(getResources(), R.drawable.focus_square);
        //居中计算
        float w = focusBmp.getWidth();
        float h = focusBmp.getHeight();
        float bitmap_l = (left+right) / 2 - radius;
        float bitmap_r = (left+right) / 2 + radius;
        float bitmap_t = (top+bottom) / 2 - radius;
        float bitmap_b = (top+bottom) / 2 + radius;

        if (mCameraView.dual() && mCameraEngine.getCurrentCameraIndex(new PointF(currX, currY)) == 1) {
            focus2BmpLeftPoint = bitmap_l;focus2BmpRightPoint = bitmap_r;focus2BmpTopPoint = bitmap_t;focus2BmpBottomPoint = bitmap_b;
        } else {
            focusBmpLeftPoint = bitmap_l;focusBmpRightPoint = bitmap_r;focusBmpTopPoint = bitmap_t;focusBmpBottomPoint = bitmap_b;
        }
        // 锁定时，设置焦点框红色
        if (!isAutoExploreFocus) {
            focusProgressPaint.setColorFilter(new PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN));
        // 取消锁定时，恢复正常
        } else {
            focusProgressPaint.setColorFilter(null);
        }
        canvas.drawBitmap(focusBmp, null, new Rect((int) bitmap_l, (int) bitmap_t, (int) bitmap_r, (int) bitmap_b), focusProgressPaint);
        // 若不自动曝光对焦，则取消绘制滑块
        if (!isAutoExploreFocus) {
            return;
        }
        // thumb 图片
        if (exposureBmp == null) exposureBmp = BitmapFactory.decodeResource(getResources(), R.drawable.video_btn_exposure);
        // Track 的长度
        exposureTrackLength = 2 * exposureTrackRadius;

        // Thumb 中心X点
        exposureThumbCenterX = right + DensityUtil.dp2px(5);
        // 判断拖动条的点大于屏幕的宽度是 拖动条放左
        if (exposureThumbCenterX + exposureBmp.getWidth() > getWidth()) {
            exposureThumbCenterX = left - DensityUtil.dp2px(5) - exposureBmp.getWidth();
        }
        // Thumb 中心Y点
        exposureThumbCenterY = currY - exposureProgress / (exposureMax - exposureMin) * 2 * exposureTrackRadius;
        // 绘制 Thumb 左边点
        float bmpLeft = exposureThumbCenterX;
        // 绘制 Thumb 上边点
        float bmpTop = exposureThumbCenterY - exposureBmp.getHeight() / 2;

        // Thumb 左边 点
        exposureBmpLeftPoint = bmpLeft - DensityUtil.dp2px(6);
        exposureBmpTopPoint = bmpTop - DensityUtil.dp2px(6);
        exposureBmpRightPoint = bmpLeft + exposureBmp.getWidth() + DensityUtil.dp2px(6);
        exposureBmpBottomPoint = bmpTop + exposureBmp.getHeight() + DensityUtil.dp2px(6);

        // 上半部分线
        float topStartX = exposureThumbCenterX + exposureBmp.getWidth() / 2.0f - DensityUtil.dp2px(1);
        float topStartY = bmpTop - exposureOffset;
        float topStopX = topStartX;
        float topStopY = currY - exposureTrackRadius;
        exposureTop = topStopY;

        // 下半部分线
        float bottomStartX = topStartX;
        float bottomStartY = bmpTop + exposureBmp.getHeight() + DensityUtil.dp2px(2);
        float bottomStopX = topStartX;
        float bottomStopY = currY + exposureTrackRadius;
        exposureBottom = bottomStopY;

        // 判断滑到最上点
        if (bmpTop <= topStopY + exposureOffset) {
            topStartY = topStopY;
        }

        // 判断滑到最下点
        if (bmpTop + (float) exposureBmp.getHeight() + exposureOffset >= bottomStopY) {
            bottomStartY = bottomStopY;
        }

        // draw left track background
        canvas.drawLine(topStartX, topStartY, topStopX, topStopY, exposureLinePaint);

        // draw right track background
        canvas.drawLine(bottomStartX, bottomStartY, bottomStopX, bottomStopY, exposureLinePaint);

        // draw thumb
        canvas.drawBitmap(exposureBmp, bmpLeft, bmpTop, exposureBpmPaint);
    }

    int downX = 0;
    // 触摸屏方法
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        switch (action) {
            // 按下
            case MotionEvent.ACTION_DOWN: //0
                mCameraView.selectOpenedCamera(new PointF(event.getX(), event.getY()));
                // 显示时再次down，则取消自动曝光和自动聚焦
                exposureProgress = mCameraView.getExposureCorrection();
                if (!isClear() && mCameraEngine.isAutoExposure() && isFocusTouched(event)) {
                    if (mCameraEngine.getCurrentCameraIndex() == 1) {
                        SensorController.getInstance(mCameraView.getContext()).lockCamera2Focus();
                    }
                    if (mCameraEngine.getCurrentCameraIndex() == 0) {
                        SensorController.getInstance(mCameraView.getContext()).lockFocus();
                    }
                    mCameraEngine.lockExposure();
                    mCameraEngine.lockFocus();
                    if (mFocusListener != null) mFocusListener.onExposure(exposureProgress, new float[4], new PointF[1]);
                    postInvalidate();       // 取消曝光条
                    try {
                        Class<?> aClass = Class.forName("com.sabine.library.utils.StatisticsUtils");
                        Method method = aClass.getMethod("numStatistics", Context.class, String.class);
                        method.invoke(aClass.newInstance(), mCameraView.getContext(), "event_record_lock_focus");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    eventX = event.getX();
                    eventY = event.getY();
                    return false;
                // 锁定时再次down，则取消锁定
                } else if (!isClear() && !mCameraEngine.isAutoExposure() && isFocusTouched(event)) {
                    if (mCameraEngine.getCurrentCameraIndex() == 1) {
                        SensorController.getInstance(mCameraView.getContext()).unlockCamera2Focus();
                    }
                    if (mCameraEngine.getCurrentCameraIndex() == 0) {
                        SensorController.getInstance(mCameraView.getContext()).unlockFocus();
                    }
                    setClear(true, false);
                    mCameraEngine.unlockExposure();
                    mCameraEngine.unlockFocus();
                // 锁定但点击focus区域外，则不处理事件
                } else if (!isClear() && !mCameraEngine.isAutoExposure()) {       // bug trigger 1
                    eventX = event.getX();
                    eventY = event.getY();
                    return false;
                }
                downX = (int) event.getX();
                // 每次点击初始化曝光值
//                exposureProgress = initExposureProgress;
                isMoveOccurs = false;
                actionDownExposure(event);
                break;
            // 移动 事件不断地触发
            case MotionEvent.ACTION_MOVE: //2
                if (Math.abs(event.getX() - downX) >= 10) {
                    isMoveOccurs = true;
                }
                /* 手势缩放 */
                actionMoveZoom(event);
                /* 曝光调节 */
                actionMoveProgressExposure(event);
                if (isExposureThumbOnDragging) {
                    handler.removeMessages(CLEAR_VIEW);
                    handler.sendEmptyMessageDelayed(CLEAR_VIEW, 3000);
                }
                break;
            // 离开屏幕
            // 有手指离开屏幕,但屏幕还有触点
            case MotionEvent.ACTION_UP: // 1
                // 若显示焦点框 && 锁定，则取消事件
                if (!isClear() && !mCameraEngine.isAutoExposure()) {
                    return false;
                }
                if (isMoveOccurs) {
                    break;
                } else {
                    /* 对焦 测光 曝光 */
                    // 点击设置焦距和测光
//                    focusAtPoints(event);
                    isShowExposure = true;
//                    if (mFocusListener != null) mFocusListener.onExposure(exposureProgress, new float[4], new PointF[1]);
                }
                /* 清空 */
                pointerDown = false;
                setClear(false, false);
                handler.removeMessages(CLEAR_VIEW);
                postInvalidate();
                handler.sendEmptyMessageDelayed(CLEAR_VIEW, 3000);
                break;
            case MotionEvent.ACTION_CANCEL: //3
            case 262:
            case MotionEvent.ACTION_POINTER_UP: //6
                zoomProgress = gainValue;
                // 是否显示曝光图
                if (isZoomThumbOnDragging) {
                    isShowExposure = false;
                }
                // 缩放
                isZoomThumbOnDragging = false;
                mode = 0;
                handler.removeMessages(CLEAR_VIEW);
                // 手指离开后 3秒钟后清空View
                handler.sendEmptyMessageDelayed(CLEAR_VIEW, 3000);
                break;
            // 当屏幕上还有触点（手指），再有一个手指压下屏幕
            case 261:
            case MotionEvent.ACTION_POINTER_DOWN: //5
                mCameraView.selectOpenedCamera(new PointF(event.getX(), event.getY()));
                setClear(true, false);        // 解决双指接触屏幕后，焦点框可能一直无法显示问题 --> bug trigger 1
                isShowExposure = false;
                // 缩放
                mode = ZOOM;
                pointerDown = true;
                zoomProgress = mCameraView.getZoom();
                startDis = distance(event);
                handler.removeMessages(CLEAR_VIEW);
                break;
        }
        return true;
    }

    protected void clearView(int index) {
        // 重置view时 重置参数值
        if (index == 1) {
            camera2Clear = true;
        } else {
            isClear = true;
        }
    }

    // 接收消息机制
    @SuppressLint("HandlerLeak")
    private final Handler handler = new Handler() {
        public void handleMessage(android.os.Message msg) {
            int tag = msg.what;
            if (tag == CLEAR_VIEW) {
                if (mCameraView.dual()) {
                    if (mCameraEngine.isAutoExposure(mCameraEngine.getCurrentCameraIndex(new PointF(currX, currY)))) {
                        clearView(0);
                    }
                    if (mCameraEngine.isAutoExposure(mCameraEngine.getCurrentCameraIndex(new PointF(currX2, currY2)))) {
                        clearView(1);
                    }
                } else {
                    if (mCameraEngine.isAutoExposure()) {
                        clearView(0);
                    }
                }
                zoomProgress = initZoomProgress;
                exposureProgress = initExposureProgress;
                postInvalidate();
            }
        }
    };

    // 按下测光事件
    private void actionDownExposure(MotionEvent event) {
        // 焦距点
        eventX = event.getX();
        eventY = event.getY();
        isExposureThumbOnDragging = isThumbTouched(event, exposureBmpLeftPoint, exposureBmpRightPoint,
                exposureBmpTopPoint, exposureBmpBottomPoint);
        dy = exposureThumbCenterY - event.getY();
    }

    /**
     * 识别thumb是否被有效点击
     */
    private synchronized boolean isThumbTouched(MotionEvent event, float left, float right, float top, float bottom) {
        float x = event.getX();
        float y = event.getY();
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    /**
     * 识别焦点框是否被有效点击
     */
    private synchronized boolean isFocusTouched(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        if (mCameraEngine.getCurrentCameraIndex() == 1) {
            return x >= focus2BmpLeftPoint && x <= focus2BmpRightPoint && y >= focus2BmpTopPoint && y <= focus2BmpBottomPoint;
        }
        return x >= focusBmpLeftPoint && x <= focusBmpRightPoint && y >= focusBmpTopPoint && y <= focusBmpBottomPoint;
    }

    // 曝光的调节
    private void actionMoveProgressExposure(MotionEvent event) {
        if (isExposureThumbOnDragging && !isClear() && mCameraEngine.isAutoExposure()) {
            exposureThumbCenterY = event.getY() + dy;
            if (exposureThumbCenterY < exposureTop) {
                exposureThumbCenterY = exposureTop;
            }
            if (exposureThumbCenterY > exposureBottom) {
                exposureThumbCenterY = exposureBottom;
            }
            // 计算曝光的进度
            exposureProgress = - ((exposureThumbCenterY - exposureTop) * exposureDelta / exposureTrackLength + exposureMin);
            // 设置相机的曝光
            if (exposureProgress > exposureMax) exposureProgress = exposureMax;
            if (exposureProgress < exposureMin) exposureProgress = exposureMin;
            if (mFocusListener != null) mFocusListener.onExposure(exposureProgress, new float[4], new PointF[1]);
            postInvalidate();
        }
    }

    // 双手势缩放
    private void actionMoveZoom(MotionEvent event) {
        // 后置摄像头支持缩放
        if (mode == ZOOM) {// 缩放
            float endDis = distance(event);// 结束距离
            gainValue = (endDis-startDis) / 15 / 90;
            gainValue = zoomProgress + gainValue;
            if (gainValue >= zoomMax) {
                gainValue = zoomMax;
            }else if (gainValue <= 0) {
                gainValue = 0;
            }
            if (mFocusListener != null) mFocusListener.onZoom(gainValue, new PointF[2]);
            postInvalidate();
        }
    }

    /**
     * 计算两点之间的距离
     *
     * @param event
     * @return
     */
    private float distance(MotionEvent event) {
        if (event.getPointerCount() >= 2) {
            float dx = event.getX(1) - event.getX(0);
            float dy = event.getY(1) - event.getY(0);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
        return 0;
    }

    public interface OnFocusListener {
        void onZoom(float zoomValue, PointF[] zoomPoint);
        void onExposure(float exposureValue, float[] bounds, PointF[] exposurePoint);
    }

    public void resetExposure(int orientation, float scale) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currX = currX * scale;
            currX2 = currX2 * scale;
        } else {
            currY = currY * scale;
            currY2 = currY2 * scale;
        }

        if (mCameraView.dual()) {
            mCameraEngine.unlockExposure();
            mCameraEngine.unlockFocus();
            if (mCameraView.getCameraOptions() != null) {
                float minValue = mCameraView.getCameraOptions().getExposureCorrectionMinValue();
                float maxValue = mCameraView.getCameraOptions().getExposureCorrectionMaxValue();
                float[] bounds = new float[]{minValue, maxValue};
                mCameraEngine.setExposureCorrection(mCameraEngine.getExposureCorrectionValue(0), bounds, new PointF[] {new PointF(currX, currY)}, false, mCameraEngine.getCurrentCameraIndex(new PointF(currX, currY)));
                mCameraEngine.setExposureCorrection(mCameraEngine.getExposureCorrectionValue(1), bounds, new PointF[] {new PointF(currX, currY)}, false, mCameraEngine.getCurrentCameraIndex(new PointF(currX2, currY2)));
            }
        }
    }

    /**
     * 重置视图
     */
    public void resetView() {
        if (mCameraEngine == null) mCameraEngine = (Camera2Engine) mCameraView.getCameraEngine();
        mCameraEngine.unlockAll();
        SensorController.getInstance(mCameraView.getContext()).unlockFocus();
        SensorController.getInstance(mCameraView.getContext()).unlockCamera2Focus();
        setClear(true, true);
        // 重置点击坐标
        currX = currY = currX2 = currY2 = 0;
        postInvalidate();
    }

    private boolean isClear() {
        if (mCameraView.dual() && mCameraEngine.getCurrentCameraIndex() == 1) return camera2Clear;
        else return isClear;
    }

    /**
     *
     * @param clear 设置是否清除
     * @param allClear 是否全部清除
     */
    private void setClear(boolean clear, boolean allClear) {
        if (allClear) {
            isClear = clear;
            camera2Clear = clear;
        } else {
            if (mCameraView.dual() && mCameraEngine.getCurrentCameraIndex() == 1)  camera2Clear = clear;
            else isClear = clear;
        }
    }

    private boolean flip;

    /**
     * 切换状态
     * @param orientation
     * @param flip
     */
    public void switchDualCamera(int orientation, boolean flip) {
        handler.removeMessages(CLEAR_VIEW);
        if (mCameraEngine.isAutoExposure(mCameraEngine.getCurrentCameraIndex(new PointF(currX, currY)))) {
            isClear = true;
        }
        if (mCameraEngine.isAutoExposure(mCameraEngine.getCurrentCameraIndex(new PointF(currX2, currY2)))) camera2Clear = true;
        this.flip = flip;
        if (flip) {
            float halfWidth = getWidth() / 2f;
            float halfHeight = getHeight() / 2f;

            // 坐标位置调换
            float currtX = currX;
            float currtY = currY;
            if (mCameraView.getDualInputTextureMode() == CameraPreview.DualInputTextureMode.PIP_MODE) {
                // 画中画模式, 按比例对调位置
                float left = getWidth() - getWidth() / 3.0f - getWidth() * 0.02f;
                float top = getHeight() - getHeight() / 3.0f - getWidth() * 0.02f;
                currtX = (currX2 - left) * 3.0f;
                currtY = (currY2 - top) * 3.0f;
                currX2 = currX / 3.0f + left;
                currY2 = currY / 3.0f + top;
                currX = currtX;
                currY = currtY;

                if (currX > left && currY > top) {
                    currX = left - DensityUtil.dp2px(32) - exposureBmp.getWidth();
                }
            } else {
                // 上下模式
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    currX = currX2 > halfWidth ? currX2 - halfWidth : currX2 + halfWidth;
                    currY = currY2;
                    currX2 = currtX > halfWidth ? currtX - halfWidth : currtX + halfWidth;
                    currY2 = currtY;
                } else {
                    currX = currX2;
                    currY = currY2 > halfHeight ? currY2 - halfHeight : currY2 + halfHeight;
                    currX2 = currtX;
                    currY2 = currtY > halfHeight ? currtY - halfHeight : currtY + halfHeight;
                }
            }

            // 锁定状态调换
            boolean isClear = this.isClear;
            this.isClear = camera2Clear;
            camera2Clear = isClear;

            postInvalidate();
        }
    }

}
