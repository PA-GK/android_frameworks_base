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
package com.android.wm.shell.pip.phone;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.PIP_PINCH_RESIZE;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_BOTTOM;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_LEFT;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_NONE;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_RIGHT;
import static com.android.internal.policy.TaskResizingAlgorithm.CTRL_TOP;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.view.BatchedInputEventReceiver;
import android.view.Choreographer;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;

import androidx.annotation.VisibleForTesting;

import com.android.internal.policy.TaskResizingAlgorithm;
import com.android.wm.shell.R;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipUiEventLogger;

import java.io.PrintWriter;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Helper on top of PipTouchHandler that handles inputs OUTSIDE of the PIP window, which is used to
 * trigger dynamic resize.
 */
public class PipResizeGestureHandler {

    private static final String TAG = "PipResizeGestureHandler";
    private static final float PINCH_THRESHOLD = 0.05f;
    private static final float STARTING_SCALE_FACTOR = 1.0f;

    private final Context mContext;
    private final PipBoundsAlgorithm mPipBoundsAlgorithm;
    private final PipMotionHelper mMotionHelper;
    private final PipBoundsState mPipBoundsState;
    private final int mDisplayId;
    private final Executor mMainExecutor;
    private final ScaleGestureDetector mScaleGestureDetector;
    private final Region mTmpRegion = new Region();

    private final PointF mDownPoint = new PointF();
    private final Point mMaxSize = new Point();
    private final Point mMinSize = new Point();
    private final Rect mLastResizeBounds = new Rect();
    private final Rect mUserResizeBounds = new Rect();
    private final Rect mLastDownBounds = new Rect();
    private final Rect mDragCornerSize = new Rect();
    private final Rect mTmpTopLeftCorner = new Rect();
    private final Rect mTmpTopRightCorner = new Rect();
    private final Rect mTmpBottomLeftCorner = new Rect();
    private final Rect mTmpBottomRightCorner = new Rect();
    private final Rect mDisplayBounds = new Rect();
    private final Function<Rect, Rect> mMovementBoundsSupplier;
    private final Runnable mUpdateMovementBoundsRunnable;

    private int mDelta;
    private float mTouchSlop;
    private boolean mAllowGesture;
    private boolean mIsAttached;
    private boolean mIsEnabled;
    private boolean mEnablePinchResize;
    private boolean mIsSysUiStateValid;
    private boolean mThresholdCrossed;
    private boolean mUsingPinchToZoom = false;
    private float mScaleFactor = STARTING_SCALE_FACTOR;

    private InputMonitor mInputMonitor;
    private InputEventReceiver mInputEventReceiver;
    private PipTaskOrganizer mPipTaskOrganizer;
    private PipMenuActivityController mPipMenuActivityController;
    private PipUiEventLogger mPipUiEventLogger;

    private int mCtrlType;

    public PipResizeGestureHandler(Context context, PipBoundsAlgorithm pipBoundsAlgorithm,
            PipBoundsState pipBoundsState, PipMotionHelper motionHelper,
            PipTaskOrganizer pipTaskOrganizer, Function<Rect, Rect> movementBoundsSupplier,
            Runnable updateMovementBoundsRunnable, PipUiEventLogger pipUiEventLogger,
            PipMenuActivityController menuActivityController) {
        mContext = context;
        mDisplayId = context.getDisplayId();
        mMainExecutor = context.getMainExecutor();
        mPipBoundsAlgorithm = pipBoundsAlgorithm;
        mPipBoundsState = pipBoundsState;
        mMotionHelper = motionHelper;
        mPipTaskOrganizer = pipTaskOrganizer;
        mMovementBoundsSupplier = movementBoundsSupplier;
        mUpdateMovementBoundsRunnable = updateMovementBoundsRunnable;
        mPipMenuActivityController = menuActivityController;
        mPipUiEventLogger = pipUiEventLogger;

        context.getDisplay().getRealSize(mMaxSize);
        reloadResources();

        mScaleGestureDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.OnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        mScaleFactor *= detector.getScaleFactor();

                        if (!mThresholdCrossed
                                && (mScaleFactor > (STARTING_SCALE_FACTOR + PINCH_THRESHOLD)
                                || mScaleFactor < (STARTING_SCALE_FACTOR - PINCH_THRESHOLD))) {
                            mThresholdCrossed = true;
                            mInputMonitor.pilferPointers();
                        }
                        if (mThresholdCrossed) {
                            int height = Math.min(mMaxSize.y, Math.max(mMinSize.y,
                                    (int) (mScaleFactor * mLastDownBounds.height())));
                            int width = Math.min(mMaxSize.x, Math.max(mMinSize.x,
                                    (int) (mScaleFactor * mLastDownBounds.width())));
                            int top, bottom, left, right;

                            if ((mCtrlType & CTRL_TOP) != 0) {
                                top = mLastDownBounds.bottom - height;
                                bottom = mLastDownBounds.bottom;
                            } else {
                                top = mLastDownBounds.top;
                                bottom = mLastDownBounds.top + height;
                            }

                            if ((mCtrlType & CTRL_LEFT) != 0) {
                                left = mLastDownBounds.right - width;
                                right = mLastDownBounds.right;
                            } else {
                                left = mLastDownBounds.left;
                                right = mLastDownBounds.left + width;
                            }

                            mLastResizeBounds.set(left, top, right, bottom);
                            mPipTaskOrganizer.scheduleUserResizePip(mLastDownBounds,
                                    mLastResizeBounds,
                                    null);
                        }
                        return true;
                    }

                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        setCtrlTypeForPinchToZoom();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        mScaleFactor = STARTING_SCALE_FACTOR;
                        finishResize();
                    }
                });

        mEnablePinchResize = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                PIP_PINCH_RESIZE,
                /* defaultValue = */ false);
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI, mMainExecutor,
                new DeviceConfig.OnPropertiesChangedListener() {
                    @Override
                    public void onPropertiesChanged(DeviceConfig.Properties properties) {
                        if (properties.getKeyset().contains(PIP_PINCH_RESIZE)) {
                            mEnablePinchResize = properties.getBoolean(
                                    PIP_PINCH_RESIZE, /* defaultValue = */ false);
                        }
                    }
                });
    }

    public void onConfigurationChanged() {
        reloadResources();
    }

    /**
     * Called when SysUI state changed.
     *
     * @param isSysUiStateValid Is SysUI valid or not.
     */
    public void onSystemUiStateChanged(boolean isSysUiStateValid) {
        mIsSysUiStateValid = isSysUiStateValid;
    }

    private void reloadResources() {
        final Resources res = mContext.getResources();
        mDelta = res.getDimensionPixelSize(R.dimen.pip_resize_edge_size);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
    }

    private void resetDragCorners() {
        mDragCornerSize.set(0, 0, mDelta, mDelta);
        mTmpTopLeftCorner.set(mDragCornerSize);
        mTmpTopRightCorner.set(mDragCornerSize);
        mTmpBottomLeftCorner.set(mDragCornerSize);
        mTmpBottomRightCorner.set(mDragCornerSize);
    }

    private void disposeInputChannel() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitor != null) {
            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    void onActivityPinned() {
        mIsAttached = true;
        updateIsEnabled();
    }

    void onActivityUnpinned() {
        mIsAttached = false;
        mUserResizeBounds.setEmpty();
        updateIsEnabled();
    }

    private void updateIsEnabled() {
        boolean isEnabled = mIsAttached;
        if (isEnabled == mIsEnabled) {
            return;
        }
        mIsEnabled = isEnabled;
        disposeInputChannel();

        if (mIsEnabled) {
            // Register input event receiver
            mInputMonitor = InputManager.getInstance().monitorGestureInput(
                    "pip-resize", mDisplayId);
            mInputEventReceiver = new SysUiInputEventReceiver(
                    mInputMonitor.getInputChannel(), Looper.getMainLooper());
        }
    }

    private void onInputEvent(InputEvent ev) {
        // Don't allow resize when PiP is stashed.
        if (mPipBoundsState.isStashed()) {
            return;
        }

        if (ev instanceof MotionEvent) {
            if (mUsingPinchToZoom) {
                mScaleGestureDetector.onTouchEvent((MotionEvent) ev);
            } else {
                onDragCornerResize((MotionEvent) ev);
            }
        }
    }

    /**
     * Check whether the current x,y coordinate is within the region in which drag-resize should
     * start.
     * This consists of 4 small squares on the 4 corners of the PIP window, a quarter of which
     * overlaps with the PIP window while the rest goes outside of the PIP window.
     *  _ _           _ _
     * |_|_|_________|_|_|
     * |_|_|         |_|_|
     *   |     PIP     |
     *   |   WINDOW    |
     *  _|_           _|_
     * |_|_|_________|_|_|
     * |_|_|         |_|_|
     */
    public boolean isWithinTouchRegion(int x, int y) {
        final Rect currentPipBounds = mMotionHelper.getBounds();
        if (currentPipBounds == null) {
            return false;
        }
        resetDragCorners();
        mTmpTopLeftCorner.offset(currentPipBounds.left - mDelta / 2,
                currentPipBounds.top - mDelta /  2);
        mTmpTopRightCorner.offset(currentPipBounds.right - mDelta / 2,
                currentPipBounds.top - mDelta /  2);
        mTmpBottomLeftCorner.offset(currentPipBounds.left - mDelta / 2,
                currentPipBounds.bottom - mDelta /  2);
        mTmpBottomRightCorner.offset(currentPipBounds.right - mDelta / 2,
                currentPipBounds.bottom - mDelta /  2);

        mTmpRegion.setEmpty();
        mTmpRegion.op(mTmpTopLeftCorner, Region.Op.UNION);
        mTmpRegion.op(mTmpTopRightCorner, Region.Op.UNION);
        mTmpRegion.op(mTmpBottomLeftCorner, Region.Op.UNION);
        mTmpRegion.op(mTmpBottomRightCorner, Region.Op.UNION);

        return mTmpRegion.contains(x, y);
    }

    public boolean isUsingPinchToZoom() {
        return mEnablePinchResize;
    }

    public boolean willStartResizeGesture(MotionEvent ev) {
        if (isInValidSysUiState()) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Always pass the DOWN event to the ScaleGestureDetector
                    mScaleGestureDetector.onTouchEvent(ev);
                    if (isWithinTouchRegion((int) ev.getRawX(), (int) ev.getRawY())) {
                        return true;
                    }
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (mEnablePinchResize && ev.getPointerCount() == 2) {
                        mUsingPinchToZoom = true;
                        return true;
                    }
                    break;

                default:
                    break;
            }
        }
        return false;
    }

    private void setCtrlTypeForPinchToZoom() {
        final Rect currentPipBounds = mMotionHelper.getBounds();
        mLastDownBounds.set(mMotionHelper.getBounds());

        Rect movementBounds = mMovementBoundsSupplier.apply(currentPipBounds);
        mDisplayBounds.set(movementBounds.left,
                movementBounds.top,
                movementBounds.right + currentPipBounds.width(),
                movementBounds.bottom + currentPipBounds.height());

        if (currentPipBounds.left == mDisplayBounds.left) {
            mCtrlType |= CTRL_RIGHT;
        } else {
            mCtrlType |= CTRL_LEFT;
        }

        if (currentPipBounds.top > mDisplayBounds.top + mDisplayBounds.height()) {
            mCtrlType |= CTRL_TOP;
        } else {
            mCtrlType |= CTRL_BOTTOM;
        }
    }

    private void setCtrlType(int x, int y) {
        final Rect currentPipBounds = mMotionHelper.getBounds();

        Rect movementBounds = mMovementBoundsSupplier.apply(currentPipBounds);
        mDisplayBounds.set(movementBounds.left,
                movementBounds.top,
                movementBounds.right + currentPipBounds.width(),
                movementBounds.bottom + currentPipBounds.height());

        if (mTmpTopLeftCorner.contains(x, y) && currentPipBounds.top != mDisplayBounds.top
                && currentPipBounds.left != mDisplayBounds.left) {
            mCtrlType |= CTRL_LEFT;
            mCtrlType |= CTRL_TOP;
        }
        if (mTmpTopRightCorner.contains(x, y) && currentPipBounds.top != mDisplayBounds.top
                && currentPipBounds.right != mDisplayBounds.right) {
            mCtrlType |= CTRL_RIGHT;
            mCtrlType |= CTRL_TOP;
        }
        if (mTmpBottomRightCorner.contains(x, y)
                && currentPipBounds.bottom != mDisplayBounds.bottom
                && currentPipBounds.right != mDisplayBounds.right) {
            mCtrlType |= CTRL_RIGHT;
            mCtrlType |= CTRL_BOTTOM;
        }
        if (mTmpBottomLeftCorner.contains(x, y)
                && currentPipBounds.bottom != mDisplayBounds.bottom
                && currentPipBounds.left != mDisplayBounds.left) {
            mCtrlType |= CTRL_LEFT;
            mCtrlType |= CTRL_BOTTOM;
        }
    }

    private boolean isInValidSysUiState() {
        return mIsSysUiStateValid;
    }

    private void onDragCornerResize(MotionEvent ev) {
        int action = ev.getActionMasked();
        float x = ev.getX();
        float y = ev.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            final Rect currentPipBounds = mMotionHelper.getBounds();
            mLastResizeBounds.setEmpty();
            mAllowGesture = isInValidSysUiState() && isWithinTouchRegion((int) x, (int) y);
            if (mAllowGesture) {
                setCtrlType((int) x, (int) y);
                mDownPoint.set(x, y);
                mLastDownBounds.set(mMotionHelper.getBounds());
            }
            if (!currentPipBounds.contains((int) ev.getX(), (int) ev.getY())
                    && mPipMenuActivityController.isMenuVisible()) {
                mPipMenuActivityController.hideMenu();
            }

        } else if (mAllowGesture) {
            switch (action) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    // We do not support multi touch for resizing via drag
                    mAllowGesture = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    // Capture inputs
                    if (!mThresholdCrossed
                            && Math.hypot(x - mDownPoint.x, y - mDownPoint.y) > mTouchSlop) {
                        mThresholdCrossed = true;
                        // Reset the down to begin resizing from this point
                        mDownPoint.set(x, y);
                        mInputMonitor.pilferPointers();
                    }
                    if (mThresholdCrossed) {
                        if (mPipMenuActivityController.isMenuVisible()) {
                            mPipMenuActivityController.hideMenuWithoutResize();
                            mPipMenuActivityController.hideMenu();
                        }
                        final Rect currentPipBounds = mMotionHelper.getBounds();
                        mLastResizeBounds.set(TaskResizingAlgorithm.resizeDrag(x, y,
                                mDownPoint.x, mDownPoint.y, currentPipBounds, mCtrlType, mMinSize.x,
                                mMinSize.y, mMaxSize, true,
                                mLastDownBounds.width() > mLastDownBounds.height()));
                        mPipBoundsAlgorithm.transformBoundsToAspectRatio(mLastResizeBounds,
                                mPipBoundsState.getAspectRatio(), false /* useCurrentMinEdgeSize */,
                                true /* useCurrentSize */);
                        mPipTaskOrganizer.scheduleUserResizePip(mLastDownBounds, mLastResizeBounds,
                                null);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    finishResize();
                    break;
            }
        }
    }

    private void finishResize() {
        if (!mLastResizeBounds.isEmpty()) {
            mUserResizeBounds.set(mLastResizeBounds);
            mPipTaskOrganizer.scheduleFinishResizePip(mLastResizeBounds,
                    (Rect bounds) -> {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            mMotionHelper.synchronizePinnedStackBounds();
                            mUpdateMovementBoundsRunnable.run();
                            resetState();
                        });
                    });
            mPipUiEventLogger.log(
                    PipUiEventLogger.PipUiEventEnum.PICTURE_IN_PICTURE_RESIZE);
        } else {
            resetState();
        }
    }

    private void resetState() {
        mCtrlType = CTRL_NONE;
        mUsingPinchToZoom = false;
        mAllowGesture = false;
        mThresholdCrossed = false;
    }

    void setUserResizeBounds(Rect bounds) {
        mUserResizeBounds.set(bounds);
    }

    void invalidateUserResizeBounds() {
        mUserResizeBounds.setEmpty();
    }

    Rect getUserResizeBounds() {
        return mUserResizeBounds;
    }

    @VisibleForTesting public void updateMaxSize(int maxX, int maxY) {
        mMaxSize.set(maxX, maxY);
    }

    @VisibleForTesting public void updateMinSize(int minX, int minY) {
        mMinSize.set(minX, minY);
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mAllowGesture=" + mAllowGesture);
        pw.println(innerPrefix + "mIsAttached=" + mIsAttached);
        pw.println(innerPrefix + "mIsEnabled=" + mIsEnabled);
        pw.println(innerPrefix + "mEnablePinchResize=" + mEnablePinchResize);
        pw.println(innerPrefix + "mThresholdCrossed=" + mThresholdCrossed);
    }

    class SysUiInputEventReceiver extends BatchedInputEventReceiver {
        SysUiInputEventReceiver(InputChannel channel, Looper looper) {
            super(channel, looper, Choreographer.getSfInstance());
        }

        public void onInputEvent(InputEvent event) {
            PipResizeGestureHandler.this.onInputEvent(event);
            finishInputEvent(event, true);
        }
    }
}
