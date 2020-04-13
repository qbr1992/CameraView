package com.sabine.cameraview.size;

import android.media.CamcorderProfile;
import android.util.Range;

import androidx.annotation.NonNull;

/**
 * A simple class representing a size, with width and height values.
 */
public class Size implements Comparable<Size> {

    private final int mWidth;
    private final int mHeight;
    private int mFps;

    public Size(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    /**
     * Returns a flipped size, with height equal to this size's width
     * and width equal to this size's height.
     *
     * @return a flipped size
     */
    @SuppressWarnings("SuspiciousNameCombination")
    public Size flip() {
        return new Size(mHeight, mWidth);
    }

    public int getFps() {
        return mFps;
    }

    public void setFps(int fps) {
        mFps = fps;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (this == o) {
            return true;
        }
        if (o instanceof Size) {
            Size size = (Size) o;
            return mWidth == size.mWidth && mHeight == size.mHeight;
        }
        return false;
    }

    @NonNull
    @Override
    public String toString() {
        return mWidth + "x" + mHeight;
    }

    @Override
    public int hashCode() {
        return mHeight ^ ((mWidth << (Integer.SIZE / 2)) | (mWidth >>> (Integer.SIZE / 2)));
    }

    @Override
    public int compareTo(@NonNull Size another) {
        return mWidth * mHeight - another.mWidth * another.mHeight;
    }

    public boolean hasHighSpeedCamcorder(int cameraId) {
        return hasHighSpeedCamcorder(this, cameraId);
    }

    public boolean hasHighSpeedCamcorder(Size size, int cameraID) {
        if (size.getWidth() == 720 && size.getHeight() == 480) {
            return CamcorderProfile.hasProfile(cameraID, CamcorderProfile.QUALITY_HIGH_SPEED_480P);
        } else if (size.getWidth() == 1280 && size.getHeight() == 720) {
            return CamcorderProfile.hasProfile(cameraID, CamcorderProfile.QUALITY_HIGH_SPEED_720P);
        } else if (size.getWidth() == 1920 && size.getHeight() == 1080) {
            return CamcorderProfile.hasProfile(cameraID, CamcorderProfile.QUALITY_HIGH_SPEED_1080P);
        } else if (size.getWidth() == 3840 && size.getHeight() == 2160) {
            return CamcorderProfile.hasProfile(cameraID, CamcorderProfile.QUALITY_HIGH_SPEED_2160P);
        } else {
            return false;
        }
    }

}
