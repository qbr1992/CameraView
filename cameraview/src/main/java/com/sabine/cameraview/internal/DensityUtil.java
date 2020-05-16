package com.sabine.cameraview.internal;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

/**
 * @ClassName UnitConversionUtil.java
 * @author lihanyang
 * @data 2017年3月2日上午10:34:43
 * @declaration
 * @version
 */
public class DensityUtil {

	// dp转px
	public static int dp2px(int dp) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
				Resources.getSystem().getDisplayMetrics());
	}

	// sp转px
	public static int sp2px(int sp) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
				Resources.getSystem().getDisplayMetrics());
	}

	public static void textSize(Context context, int textSize) {
		int size = (int) (context.getResources().getDisplayMetrics().density * textSize);
	}

	/**
	 * 根据手机的分辨率从 dp 的单位 转成为 px(像素)
	 * 
	 * @param context
	 * @param dpValue
	 * @return
	 */
	public static int dip2px(Context context, float dpValue) {
		final float scale = context.getResources().getDisplayMetrics().density;
		return (int) (dpValue * scale + 0.5f);
	}

	/**
	 * 根据手机的分辨率从 px(像素) 的单位 转成为 dp
	 * 
	 * @param context
	 * @param pxValue
	 * @return
	 */
	public static int px2dip(Context context, float pxValue) {
		final float scale = context.getResources().getDisplayMetrics().density;
		return (int) (pxValue / scale + 0.5f);
	}
}
