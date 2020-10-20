/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.window;

import android.app.ActivityManager;
import android.content.pm.ParceledListSlice;
import android.window.ITaskOrganizer;
import android.window.TaskAppearedInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

/** @hide */
interface ITaskOrganizerController {

    /**
     * Register a TaskOrganizer to manage all the tasks with supported windowing modes.
     *
     * @return a list of the tasks that should be managed by the organizer, not including tasks
     *         created via {@link #createRootTask}.
     */
    ParceledListSlice<TaskAppearedInfo> registerTaskOrganizer(ITaskOrganizer organizer);

    /**
     * Unregisters a previously registered task organizer.
     */
    void unregisterTaskOrganizer(ITaskOrganizer organizer);

    /**
     * Creates a persistent root task in WM for a particular windowing-mode.
     * {@link TaskOrganizer#onTaskAppeared} won't be called since we are returning
     * {@link TaskAppearedInfo} here.
     */
    TaskAppearedInfo createRootTask(int displayId, int windowingMode);

    /** Deletes a persistent root task in WM */
    boolean deleteRootTask(in WindowContainerToken task);

    /** Gets direct child tasks (ordered from top-to-bottom) */
    List<ActivityManager.RunningTaskInfo> getChildTasks(in WindowContainerToken parent,
            in int[] activityTypes);

    /** Gets all root tasks on a display (ordered from top-to-bottom) */
    List<ActivityManager.RunningTaskInfo> getRootTasks(int displayId, in int[] activityTypes);

    /** Get the root task which contains the current ime target */
    WindowContainerToken getImeTarget(int display);

    /**
     * Set's the root task to launch new tasks into on a display. {@code null} means no launch root
     * and thus new tasks just end up directly on the display.
     */
    void setLaunchRoot(int displayId, in WindowContainerToken root);

    /**
     * Requests that the given task organizer is notified when back is pressed on the root activity
     * of one of its controlled tasks.
     */
    void setInterceptBackPressedOnTaskRoot(in WindowContainerToken task,
            boolean interceptBackPressed);
}
