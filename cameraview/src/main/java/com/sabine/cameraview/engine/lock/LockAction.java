package com.sabine.cameraview.engine.lock;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.sabine.cameraview.engine.action.ActionWrapper;
import com.sabine.cameraview.engine.action.Actions;
import com.sabine.cameraview.engine.action.BaseAction;

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public class LockAction extends ActionWrapper {

    private final BaseAction action = Actions.together(
            new ExposureLock(),
            new FocusLock(),
            new WhiteBalanceLock()
    );

    @NonNull
    @Override
    public BaseAction getAction() {
        return action;
    }
}
