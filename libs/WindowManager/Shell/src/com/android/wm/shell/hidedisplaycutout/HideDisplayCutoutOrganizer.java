/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.hidedisplaycutout;

import static android.view.Display.DEFAULT_DISPLAY;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.ArrayMap;
import android.util.Log;
import android.util.RotationUtils;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.R;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;

import java.io.PrintWriter;
import java.util.List;

/**
 * Manages the display areas of hide display cutout feature.
 */
class HideDisplayCutoutOrganizer extends DisplayAreaOrganizer {
    private static final String TAG = "HideDisplayCutoutOrganizer";

    private final Context mContext;
    private final DisplayController mDisplayController;

    @VisibleForTesting
    @GuardedBy("this")
    ArrayMap<WindowContainerToken, SurfaceControl> mDisplayAreaMap = new ArrayMap();
    // The default display bound in natural orientation.
    private final Rect mDefaultDisplayBounds = new Rect();
    @VisibleForTesting
    final Rect mCurrentDisplayBounds = new Rect();
    // The default display cutout in natural orientation.
    private Insets mDefaultCutoutInsets;
    private Insets mCurrentCutoutInsets;
    private boolean mIsDefaultPortrait;
    private int mStatusBarHeight;
    @VisibleForTesting
    int mOffsetX;
    @VisibleForTesting
    int mOffsetY;
    @VisibleForTesting
    int mRotation;

    /**
     * Handles rotation based on OnDisplayChangingListener callback.
     */
    private final DisplayChangeController.OnDisplayChangingListener mRotationController =
            (display, fromRotation, toRotation, wct) -> {
                mRotation = toRotation;
                updateBoundsAndOffsets(true /* enable */);
                final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                applyAllBoundsAndOffsets(wct, t);
                // Only apply t here since the server will do the wct.apply when the method
                // finishes.
                t.apply();
            };

    HideDisplayCutoutOrganizer(Context context, DisplayController displayController) {
        mContext = context;
        mDisplayController = displayController;
    }

    @Override
    public void onDisplayAreaAppeared(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        if (!addDisplayAreaInfoAndLeashToMap(displayAreaInfo, leash)) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        applyBoundsAndOffsets(displayAreaInfo.token, leash, wct, tx);
        applyTransaction(wct, tx);
    }

    @Override
    public void onDisplayAreaVanished(@NonNull DisplayAreaInfo displayAreaInfo) {
        synchronized (this) {
            if (!mDisplayAreaMap.containsKey(displayAreaInfo.token)) {
                Log.w(TAG, "Unrecognized token: " + displayAreaInfo.token);
                return;
            }

            final WindowContainerTransaction wct = new WindowContainerTransaction();
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            applyBoundsAndOffsets(
                    displayAreaInfo.token, mDisplayAreaMap.get(displayAreaInfo.token), wct, t);
            applyTransaction(wct, t);
            mDisplayAreaMap.remove(displayAreaInfo.token);
        }
    }

    private void updateDisplayAreaMap(List<DisplayAreaAppearedInfo> displayAreaInfos) {
        for (int i = 0; i < displayAreaInfos.size(); i++) {
            final DisplayAreaInfo info = displayAreaInfos.get(i).getDisplayAreaInfo();
            final SurfaceControl leash = displayAreaInfos.get(i).getLeash();
            addDisplayAreaInfoAndLeashToMap(info, leash);
        }
    }

    @VisibleForTesting
    boolean addDisplayAreaInfoAndLeashToMap(@NonNull DisplayAreaInfo displayAreaInfo,
            @NonNull SurfaceControl leash) {
        synchronized (this) {
            if (displayAreaInfo.displayId != DEFAULT_DISPLAY) {
                return false;
            }
            if (mDisplayAreaMap.containsKey(displayAreaInfo.token)) {
                Log.w(TAG, "Already appeared token: " + displayAreaInfo.token);
                return false;
            }
            mDisplayAreaMap.put(displayAreaInfo.token, leash);
            return true;
        }
    }

    /**
     * Enables hide display cutout.
     */
    void enableHideDisplayCutout() {
        mDisplayController.addDisplayChangingController(mRotationController);
        final Display display = mDisplayController.getDisplay(DEFAULT_DISPLAY);
        if (display != null) {
            mRotation = display.getRotation();
        }
        final List<DisplayAreaAppearedInfo> displayAreaInfos =
                registerOrganizer(DisplayAreaOrganizer.FEATURE_HIDE_DISPLAY_CUTOUT);
        updateDisplayAreaMap(displayAreaInfos);
        updateBoundsAndOffsets(true /* enabled */);
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        applyAllBoundsAndOffsets(wct, t);
        applyTransaction(wct, t);
    }

    /**
     * Disables hide display cutout.
     */
    void disableHideDisplayCutout() {
        updateBoundsAndOffsets(false /* enabled */);
        mDisplayController.removeDisplayChangingController(mRotationController);
        unregisterOrganizer();
    }

    @VisibleForTesting
    Insets getDisplayCutoutInsetsOfNaturalOrientation() {
        final Display display = mDisplayController.getDisplay(DEFAULT_DISPLAY);
        if (display == null) {
            return Insets.NONE;
        }
        DisplayCutout cutout = display.getCutout();
        Insets insets = cutout != null ? Insets.of(cutout.getSafeInsets()) : Insets.NONE;
        return mRotation != Surface.ROTATION_0
                ? RotationUtils.rotateInsets(insets, 4 /* total number of rotation */ - mRotation)
                : insets;
    }

    @VisibleForTesting
    Rect getDisplayBoundsOfNaturalOrientation() {
        Point realSize = new Point(0, 0);
        final Display display = mDisplayController.getDisplay(DEFAULT_DISPLAY);
        if (display != null) {
            display.getRealSize(realSize);
        }
        final boolean isDisplaySizeFlipped = isDisplaySizeFlipped();
        return new Rect(
                0,
                0,
                isDisplaySizeFlipped ? realSize.y : realSize.x,
                isDisplaySizeFlipped ? realSize.x : realSize.y);
    }

    private boolean isDisplaySizeFlipped() {
        return mRotation == Surface.ROTATION_90 || mRotation == Surface.ROTATION_270;
    }

    /**
     * Updates bounds and offsets according to current state.
     *
     * @param enabled whether the hide display cutout feature is enabled.
     */
    @VisibleForTesting
    void updateBoundsAndOffsets(boolean enabled) {
        if (!enabled) {
            resetBoundsAndOffsets();
        } else {
            initDefaultValuesIfNeeded();

            // Reset to default values.
            mCurrentDisplayBounds.set(mDefaultDisplayBounds);
            mOffsetX = 0;
            mOffsetY = 0;

            // Update bounds and insets according to the rotation.
            mCurrentCutoutInsets = RotationUtils.rotateInsets(mDefaultCutoutInsets, mRotation);
            if (isDisplaySizeFlipped()) {
                mCurrentDisplayBounds.set(
                        mCurrentDisplayBounds.top,
                        mCurrentDisplayBounds.left,
                        mCurrentDisplayBounds.bottom,
                        mCurrentDisplayBounds.right);
            }
            mCurrentDisplayBounds.inset(mCurrentCutoutInsets);

            // Replace the top bound with the max(status bar height, cutout height) if there is
            // cutout on the top side.
            mStatusBarHeight = getStatusBarHeight();
            if (mCurrentCutoutInsets.top != 0) {
                mCurrentDisplayBounds.top = Math.max(mStatusBarHeight, mCurrentCutoutInsets.top);
            }
            mOffsetX = mCurrentDisplayBounds.left;
            mOffsetY = mCurrentDisplayBounds.top;
        }
    }

    private void resetBoundsAndOffsets() {
        mCurrentDisplayBounds.setEmpty();
        mOffsetX = 0;
        mOffsetY = 0;
    }

    private void initDefaultValuesIfNeeded() {
        if (!mDefaultDisplayBounds.isEmpty()) {
            return;
        }
        mDefaultDisplayBounds.set(getDisplayBoundsOfNaturalOrientation());
        mDefaultCutoutInsets = getDisplayCutoutInsetsOfNaturalOrientation();
        mIsDefaultPortrait = mDefaultDisplayBounds.width() < mDefaultDisplayBounds.height();
    }

    private void applyAllBoundsAndOffsets(
            WindowContainerTransaction wct, SurfaceControl.Transaction t) {
        synchronized (this) {
            mDisplayAreaMap.forEach((token, leash) -> {
                applyBoundsAndOffsets(token, leash, wct, t);
            });
        }
    }

    @VisibleForTesting
    void applyBoundsAndOffsets(WindowContainerToken token, SurfaceControl leash,
            WindowContainerTransaction wct, SurfaceControl.Transaction t) {
        wct.setBounds(token, mCurrentDisplayBounds.isEmpty() ? null : mCurrentDisplayBounds);
        t.setPosition(leash, mOffsetX,  mOffsetY);
    }

    @VisibleForTesting
    void applyTransaction(WindowContainerTransaction wct, SurfaceControl.Transaction t) {
        applyTransaction(wct);
        t.apply();
    }

    private int getStatusBarHeight() {
        final boolean isLandscape =
                mIsDefaultPortrait ? isDisplaySizeFlipped() : !isDisplaySizeFlipped();
        return mContext.getResources().getDimensionPixelSize(
                isLandscape ? R.dimen.status_bar_height_landscape
                        : R.dimen.status_bar_height_portrait);
    }

    void dump(@NonNull PrintWriter pw) {
        final String prefix = "  ";
        pw.print(TAG);
        pw.println(" states: ");
        synchronized (this) {
            pw.print(prefix);
            pw.print("mDisplayAreaMap=");
            pw.println(mDisplayAreaMap);
        }
        pw.print(prefix);
        pw.print("getDisplayBoundsOfNaturalOrientation()=");
        pw.println(getDisplayBoundsOfNaturalOrientation());
        pw.print(prefix);
        pw.print("mDefaultDisplayBounds=");
        pw.println(mDefaultDisplayBounds);
        pw.print(prefix);
        pw.print("mCurrentDisplayBounds=");
        pw.println(mCurrentDisplayBounds);
        pw.print(prefix);
        pw.print("mDefaultCutoutInsets=");
        pw.println(mDefaultCutoutInsets);
        pw.print(prefix);
        pw.print("mCurrentCutoutInsets=");
        pw.println(mCurrentCutoutInsets);
        pw.print(prefix);
        pw.print("mRotation=");
        pw.println(mRotation);
        pw.print(prefix);
        pw.print("mStatusBarHeight=");
        pw.println(mStatusBarHeight);
        pw.print(prefix);
        pw.print("mOffsetX=");
        pw.println(mOffsetX);
        pw.print(prefix);
        pw.print("mOffsetY=");
        pw.println(mOffsetY);
    }
}
