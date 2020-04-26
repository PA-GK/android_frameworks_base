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

package com.android.server.notification;

import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED;

import android.annotation.NonNull;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.os.Binder;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Helper for querying shortcuts.
 */
public class ShortcutHelper {
    private static final String TAG = "ShortcutHelper";

    private static final IntentFilter SHARING_FILTER = new IntentFilter();
    static {
        try {
            SHARING_FILTER.addDataType("*/*");
        } catch (IntentFilter.MalformedMimeTypeException e) {
            Slog.e(TAG, "Bad mime type", e);
        }
    }

    /**
     * Listener to call when a shortcut we're tracking has been removed.
     */
    interface ShortcutListener {
        void onShortcutRemoved(String key);
    }

    private LauncherApps mLauncherAppsService;
    private ShortcutListener mShortcutListener;
    private ShortcutServiceInternal mShortcutServiceInternal;

    // Key: packageName Value: <shortcutId, notifId>
    private HashMap<String, HashMap<String, String>> mActiveShortcutBubbles = new HashMap<>();
    private boolean mLauncherAppsCallbackRegistered;

    // Bubbles can be created based on a shortcut, we need to listen for changes to
    // that shortcut so that we may update the bubble appropriately.
    private final LauncherApps.Callback mLauncherAppsCallback = new LauncherApps.Callback() {
        @Override
        public void onPackageRemoved(String packageName, UserHandle user) {
        }

        @Override
        public void onPackageAdded(String packageName, UserHandle user) {
        }

        @Override
        public void onPackageChanged(String packageName, UserHandle user) {
        }

        @Override
        public void onPackagesAvailable(String[] packageNames, UserHandle user,
                boolean replacing) {
        }

        @Override
        public void onPackagesUnavailable(String[] packageNames, UserHandle user,
                boolean replacing) {
        }

        @Override
        public void onShortcutsChanged(@NonNull String packageName,
                @NonNull List<ShortcutInfo> shortcuts, @NonNull UserHandle user) {
            HashMap<String, String> shortcutBubbles = mActiveShortcutBubbles.get(packageName);
            ArrayList<String> bubbleKeysToRemove = new ArrayList<>();
            if (shortcutBubbles != null) {
                // If we can't find one of our bubbles in the shortcut list, that bubble needs
                // to be removed.
                for (String shortcutId : shortcutBubbles.keySet()) {
                    boolean foundShortcut = false;
                    for (int i = 0; i < shortcuts.size(); i++) {
                        if (shortcuts.get(i).getId().equals(shortcutId)) {
                            foundShortcut = true;
                            break;
                        }
                    }
                    if (!foundShortcut) {
                        bubbleKeysToRemove.add(shortcutBubbles.get(shortcutId));
                    }
                }
            }

            // Let NoMan know about the updates
            for (int i = 0; i < bubbleKeysToRemove.size(); i++) {
                // update flag bubble
                String bubbleKey = bubbleKeysToRemove.get(i);
                if (mShortcutListener != null) {
                    mShortcutListener.onShortcutRemoved(bubbleKey);
                }
            }
        }
    };

    ShortcutHelper(LauncherApps launcherApps, ShortcutListener listener,
            ShortcutServiceInternal shortcutServiceInternal) {
        mLauncherAppsService = launcherApps;
        mShortcutListener = listener;
        mShortcutServiceInternal = shortcutServiceInternal;
    }

    @VisibleForTesting
    void setLauncherApps(LauncherApps launcherApps) {
        mLauncherAppsService = launcherApps;
    }

    @VisibleForTesting
    void setShortcutServiceInternal(ShortcutServiceInternal shortcutServiceInternal) {
        mShortcutServiceInternal = shortcutServiceInternal;
    }

    /**
     * Returns whether the given shortcut info is a conversation shortcut.
     */
    public static boolean isConversationShortcut(
            ShortcutInfo shortcutInfo, ShortcutServiceInternal mShortcutServiceInternal,
            int callingUserId) {
        if (shortcutInfo == null || !shortcutInfo.isLongLived() || !shortcutInfo.isEnabled()) {
            return false;
        }
        return mShortcutServiceInternal.isSharingShortcut(callingUserId, "android",
                shortcutInfo.getPackage(), shortcutInfo.getId(), shortcutInfo.getUserId(),
                SHARING_FILTER);
    }

    /**
     * Only returns shortcut info if it's found and if it's a conversation shortcut.
     */
    ShortcutInfo getValidShortcutInfo(String shortcutId, String packageName, UserHandle user) {
        if (mLauncherAppsService == null) {
            return null;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            if (shortcutId == null || packageName == null || user == null) {
                return null;
            }
            LauncherApps.ShortcutQuery query = new LauncherApps.ShortcutQuery();
            query.setPackage(packageName);
            query.setShortcutIds(Arrays.asList(shortcutId));
            query.setQueryFlags(FLAG_MATCH_DYNAMIC | FLAG_MATCH_PINNED | FLAG_MATCH_CACHED);
            List<ShortcutInfo> shortcuts = mLauncherAppsService.getShortcuts(query, user);
            ShortcutInfo info = shortcuts != null && shortcuts.size() > 0
                    ? shortcuts.get(0)
                    : null;
            if (isConversationShortcut(info, mShortcutServiceInternal, user.getIdentifier())) {
                return info;
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Shortcut based bubbles require some extra work to listen for shortcut changes.
     *
     * @param r the notification record to check
     * @param removedNotification true if this notification is being removed
     * @param handler handler to register the callback with
     */
    void maybeListenForShortcutChangesForBubbles(NotificationRecord r, boolean removedNotification,
            Handler handler) {
        final String shortcutId = r.getNotification().getBubbleMetadata() != null
                ? r.getNotification().getBubbleMetadata().getShortcutId()
                : null;
        if (shortcutId == null) {
            return;
        }
        if (r.getNotification().isBubbleNotification() && !removedNotification) {
            // Must track shortcut based bubbles in case the shortcut is removed
            HashMap<String, String> packageBubbles = mActiveShortcutBubbles.get(
                    r.getSbn().getPackageName());
            if (packageBubbles == null) {
                packageBubbles = new HashMap<>();
            }
            packageBubbles.put(shortcutId, r.getKey());
            mActiveShortcutBubbles.put(r.getSbn().getPackageName(), packageBubbles);
            if (!mLauncherAppsCallbackRegistered) {
                mLauncherAppsService.registerCallback(mLauncherAppsCallback, handler);
                mLauncherAppsCallbackRegistered = true;
            }
        } else {
            // No longer track shortcut
            HashMap<String, String> packageBubbles = mActiveShortcutBubbles.get(
                    r.getSbn().getPackageName());
            if (packageBubbles != null) {
                packageBubbles.remove(shortcutId);
            }
            if (packageBubbles != null && packageBubbles.isEmpty()) {
                mActiveShortcutBubbles.remove(r.getSbn().getPackageName());
            }
            if (mLauncherAppsCallbackRegistered && mActiveShortcutBubbles.isEmpty()) {
                mLauncherAppsService.unregisterCallback(mLauncherAppsCallback);
                mLauncherAppsCallbackRegistered = false;
            }
        }
    }
}
