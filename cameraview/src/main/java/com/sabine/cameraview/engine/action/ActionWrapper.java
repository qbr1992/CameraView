package com.sabine.cameraview.engine.action;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * A simple wrapper around a {@link BaseAction}.
 * This can be used to add functionality around a base action.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public abstract class ActionWrapper extends BaseAction {

    /**
     * Should return the wrapped action.
     * @return the wrapped action
     */
    @NonNull
    public abstract BaseAction getAction();

    @Override
    protected void onStart(@NonNull ActionHolder holder) {
        super.onStart(holder);
        if (getAction() != null) {
            getAction().addCallback(new ActionCallback() {
                @Override
                public void onActionStateChanged(@NonNull Action action, int state) {
                    setState(state);
                    if (state == STATE_COMPLETED) {
                        action.removeCallback(this);
                    }
                }
            });
            getAction().onStart(holder);
        }
    }

    @Override
    protected void onAbort(@NonNull ActionHolder holder) {
        super.onAbort(holder);
        if (getAction() != null) getAction().onAbort(holder);
    }

    @Override
    public void onCaptureStarted(@NonNull ActionHolder holder, @NonNull CaptureRequest request) {
        super.onCaptureStarted(holder, request);
        if (getAction() != null) {
            getAction().onCaptureStarted(holder, request);
        }
    }

    @Override
    public void onCaptureProgressed(@NonNull ActionHolder holder,
                                    @NonNull CaptureRequest request,
                                    @NonNull CaptureResult result) {
        super.onCaptureProgressed(holder, request, result);
        if (getAction() != null) getAction().onCaptureProgressed(holder, request, result);
    }

    @Override
    public void onCaptureCompleted(@NonNull ActionHolder holder,
                                   @NonNull CaptureRequest request,
                                   @NonNull TotalCaptureResult result) {
        super.onCaptureCompleted(holder, request, result);
        if (getAction() != null) {
            getAction().onCaptureCompleted(holder, request, result);
        }
    }
}
