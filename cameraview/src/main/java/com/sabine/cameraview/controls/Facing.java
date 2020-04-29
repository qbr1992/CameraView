package com.sabine.cameraview.controls;


import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sabine.cameraview.CameraUtils;
import com.sabine.cameraview.CameraView;

/**
 * Facing value indicates which camera sensor should be used for the current session.
 *
 * @see CameraView#setFacing(Facing)
 */
public enum Facing implements Control {

    /**
     * Back-facing camera sensor.
     */
    BACK_NORMAL(0),

    /**
     * Back-facing camera sensor.
     */
    BACK_TELE(1),

    /**
     * Back-facing camera sensor.
     */
    BACK_WIDE(2),

    /**
     * Front-facing camera sensor.
     */
    FRONT(3);

    @NonNull
    static Facing DEFAULT(@Nullable Context context) {
        if (context == null) {
            return BACK_NORMAL;
        } else if (CameraUtils.hasCameraFacing(context, BACK_NORMAL)) {
            return BACK_NORMAL;
        } else if (CameraUtils.hasCameraFacing(context, BACK_TELE)) {
            return BACK_TELE;
        } else if (CameraUtils.hasCameraFacing(context, BACK_WIDE)) {
            return BACK_WIDE;
        }else if (CameraUtils.hasCameraFacing(context, FRONT)) {
            return FRONT;
        } else {
            // The controller will throw a CameraException.
            // This device has no cameras.
            return BACK_NORMAL;
        }
    }

    private int value;

    Facing(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    @Nullable
    static Facing fromValue(int value) {
        Facing[] list = Facing.values();
        for (Facing action : list) {
            if (action.value() == value) {
                return action;
            }
        }
        return null;
    }
}
