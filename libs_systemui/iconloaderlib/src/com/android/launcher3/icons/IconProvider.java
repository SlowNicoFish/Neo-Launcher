/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.launcher3.icons;

import static android.content.Intent.ACTION_DATE_CHANGED;
import static android.content.Intent.ACTION_TIMEZONE_CHANGED;
import static android.content.Intent.ACTION_TIME_CHANGED;
import static android.content.res.Resources.ID_NULL;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.ArrayRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.BuildCompat;

import com.android.launcher3.icons.ThemedIconDrawable.ThemeData;
import com.android.launcher3.util.SafeCloseable;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Class to handle icon loading from different packages
 */
public class IconProvider {

    private final String ACTION_OVERLAY_CHANGED = "android.intent.action.OVERLAY_CHANGED";
    public static final int CONFIG_ICON_MASK_RES_ID = Resources.getSystem().getIdentifier(
            "config_icon_mask", "string", "android");

    protected static final String TAG_ICON = "icon";
    protected static final String ATTR_PACKAGE = "package";
    protected static final String ATTR_DRAWABLE = "drawable";

    private static final String TAG = "IconProvider";
    private static final boolean DEBUG = false;
    public static final boolean ATLEAST_T = BuildCompat.isAtLeastT();

    private static final String ICON_METADATA_KEY_PREFIX = ".dynamic_icons";

    private static final String SYSTEM_STATE_SEPARATOR = " ";
    protected static final String THEMED_ICON_MAP_FILE = "grayscale_icon_map";

    private static final Map<String, ThemeData> DISABLED_MAP = Collections.emptyMap();

    private Map<String, ThemeData> mThemedIconMap;

    protected final Context mContext;
    protected final ComponentName mCalendar;
    protected final ComponentName mClock;
    protected final List<ComponentName> dynamicCalendars = new ArrayList<>();

    protected static final int ICON_TYPE_DEFAULT = 0;
    protected static final int ICON_TYPE_CALENDAR = 1;
    protected static final int ICON_TYPE_CLOCK = 2;

    public IconProvider(Context context) {
        this(context, false);
    }

    public IconProvider(Context context, boolean supportsIconTheme) {
        mContext = context;
        mCalendar = parseComponentOrNull(context, R.string.calendar_component_name);
        mClock = parseComponentOrNull(context, R.string.clock_component_name);
        dynamicCalendars.addAll(parseComponents(context, R.array.dynamic_calendar_components_name));
        if (!supportsIconTheme) {
            // Initialize an empty map if theming is not supported
            mThemedIconMap = DISABLED_MAP;
        }
    }

    /**
     * Enables or disables icon theme support
     */
    public void setIconThemeSupported(boolean isSupported) {
        mThemedIconMap = isSupported ? null : DISABLED_MAP;
    }

    /**
     * Adds any modification to the provided systemState for dynamic icons. This system state
     * is used by caches to check for icon invalidation.
     */
    public String getSystemStateForPackage(String systemState, String packageName) {
        if (mCalendar != null && mCalendar.getPackageName().equals(packageName)) {
            return systemState + SYSTEM_STATE_SEPARATOR + getDay();
        } else {
            return systemState;
        }
    }

    /**
     * Loads the icon for the provided activity info
     */
    public Drawable getIcon(ActivityInfo info) {
        return getIcon(info, mContext.getResources().getConfiguration().densityDpi);
    }

    /**
     * Loads the icon for the provided activity info
     */
    public Drawable getIcon(ActivityInfo info, int iconDpi) {
        return getIconWithOverrides(info.applicationInfo.packageName, info.name,
                UserHandle.getUserHandleForUid(info.applicationInfo.uid),
                iconDpi, () -> loadActivityInfoIcon(info, iconDpi));
    }

    /**
     * Loads the icon for the provided LauncherActivityInfo
     */
    public Drawable getIcon(LauncherActivityInfo info, int iconDpi) {
        return getIconWithOverrides(info.getApplicationInfo().packageName, info.getName(), info.getUser(), iconDpi,
                () -> info.getIcon(iconDpi));
    }

    protected Drawable getIconWithOverrides(String packageName, String component, UserHandle user, int iconDpi,
                                            Supplier<Drawable> fallback) {

        ThemeData td = getThemeData(packageName, component);
        Drawable icon = null;

        if (mCalendar != null && mCalendar.getPackageName().equals(packageName)) {
            icon = loadCalendarDrawable(iconDpi, td);
        } else if (mClock != null
                && mClock.getPackageName().equals(packageName)
                && Process.myUserHandle().equals(user)) {
            icon = ClockDrawableWrapper.forPackage(mContext, mClock.getPackageName(), iconDpi, td);
        }
        if (icon == null) {
            icon = fallback.get();
            if (ATLEAST_T && icon instanceof AdaptiveIconDrawable aid && td != null) {
                if (aid.getMonochrome() == null) {
                    icon = new AdaptiveIconDrawable(aid.getBackground(),
                            aid.getForeground(), td.loadPaddedDrawable());
                }
            }
        }
        return icon;
    }

    private Drawable loadActivityInfoIcon(ActivityInfo ai, int density) {
        final int iconRes = ai.getIconResource();
        Drawable icon = null;
        // Get the preferred density icon from the app's resources
        if (density != 0 && iconRes != 0) {
            try {
                final Resources resources = mContext.getPackageManager()
                        .getResourcesForApplication(ai.applicationInfo);
                icon = resources.getDrawableForDensity(iconRes, density);
            } catch (NameNotFoundException | Resources.NotFoundException exc) { }
        }
        // Get the default density icon
        if (icon == null) {
            icon = ai.loadIcon(mContext.getPackageManager());
        }
        return icon;
    }

    protected ThemeData getThemeDataForPackage(String packageName) {
        return null;
    }

    private Drawable loadCalendarDrawable(int iconDpi, @Nullable ThemeData td) {
        PackageManager pm = mContext.getPackageManager();
        try {
            final Bundle metadata = pm.getActivityInfo(
                    mCalendar,
                    PackageManager.GET_UNINSTALLED_PACKAGES | PackageManager.GET_META_DATA)
                    .metaData;
            final Resources resources = pm.getResourcesForApplication(mCalendar.getPackageName());
            final int id = getDynamicIconId(metadata, resources);
            if (id != ID_NULL) {
                if (DEBUG) Log.d(TAG, "Got icon #" + id);
                Drawable drawable = resources.getDrawableForDensity(id, iconDpi, null /* theme */);
                if (ATLEAST_T && drawable instanceof AdaptiveIconDrawable && td != null) {
                    AdaptiveIconDrawable aid = (AdaptiveIconDrawable) drawable;
                    if  (aid.getMonochrome() != null) {
                        return drawable;
                    }
                    if ("array".equals(td.mResources.getResourceTypeName(td.mResID))) {
                        TypedArray ta = td.mResources.obtainTypedArray(td.mResID);
                        int monoId = ta.getResourceId(IconProvider.getDay(), ID_NULL);
                        ta.recycle();
                        return monoId == ID_NULL ? drawable
                                : new AdaptiveIconDrawable(aid.getBackground(), aid.getForeground(),
                                        new ThemeData(td.mResources, mCalendar.getPackageName(), monoId).loadPaddedDrawable());
                    }
                }
                return drawable;
            }
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) {
                Log.d(TAG, "Could not get activityinfo or resources for package: "
                        + mCalendar.getPackageName());
            }
        }
        return null;
    }

    /**
     * @param metadata metadata of the default activity of Calendar
     * @param resources from the Calendar package
     * @return the resource id for today's Calendar icon; 0 if resources cannot be found.
     */
    private int getDynamicIconId(Bundle metadata, Resources resources) {
        if (metadata == null) {
            return ID_NULL;
        }
        String key = mCalendar.getPackageName() + ICON_METADATA_KEY_PREFIX;
        final int arrayId = metadata.getInt(key, ID_NULL);
        if (arrayId == ID_NULL) {
            return ID_NULL;
        }
        try {
            return resources.obtainTypedArray(arrayId).getResourceId(getDay(), ID_NULL);
        } catch (Resources.NotFoundException e) {
            if (DEBUG) {
                Log.d(TAG, "package defines '" + key + "' but corresponding array not found");
            }
            return ID_NULL;
        }
    }

    /**
     * @return Today's day of the month, zero-indexed.
     */
    protected static int getDay() {
        return Calendar.getInstance().get(Calendar.DAY_OF_MONTH) - 1;
    }

    private static ComponentName parseComponentOrNull(Context context, int resId) {
        String cn = context.getString(resId);
        return TextUtils.isEmpty(cn) ? null : ComponentName.unflattenFromString(cn);
    }

    private static List<ComponentName> parseComponents(Context context, @ArrayRes int resId) {
        final String[] componentResources = context.getResources().getStringArray(resId);
        final List<ComponentName> compList = new ArrayList<>();
        for (String component : componentResources) {
            compList.add(new ComponentName(component, ""));
        }
        return compList;
    }

    protected void updateMapWithDynamicIcons(Context context, Map<ComponentName, ThemeData> map) {
        final int resId = getDynamicCalendarResource(context);
        dynamicCalendars.forEach(dCal -> {
            ComponentName pkg = new ComponentName(dCal.getPackageName(), "");
            if (map.get(pkg) == null) {
                map.put(pkg, new ThemeData(context.getResources(), dCal.getPackageName(), resId));
            }
        });
    }

    protected ThemedIconDrawable.ThemeData getDynamicIconsFromMap(Context context, Map<ComponentName, ThemedIconDrawable.ThemeData> themeMap, ComponentName componentName) {
        if (dynamicCalendars.stream().anyMatch(s -> s.getPackageName().equalsIgnoreCase(componentName.getPackageName()))) {
            final int resId = getDynamicCalendarResource(context);
            return new ThemedIconDrawable.ThemeData(context.getResources(), componentName.getPackageName(), resId);
        }
        return null;
    }

    @SuppressLint("DiscouragedApi")
    @DrawableRes
    public int getDynamicCalendarResource(Context context) {
        return context.getResources().getIdentifier("themed_icon_calendar_" + Calendar.getInstance().get(Calendar.DAY_OF_MONTH), "drawable", context.getPackageName());
    }

    /**
     * Returns a string representation of the current system icon state
     */
    public String getSystemIconState() {
        return (CONFIG_ICON_MASK_RES_ID == ID_NULL
                ? "" : mContext.getResources().getString(CONFIG_ICON_MASK_RES_ID));
    }

    /**
     * Registers a callback to listen for various system dependent icon changes.
     */
    public SafeCloseable registerIconChangeListener(IconChangeListener listener, Handler handler) {
        return new IconChangeReceiver(listener, handler);
    }

    protected boolean isThemeEnabled() {
        return mThemedIconMap != DISABLED_MAP;
    }

    @Nullable
    protected final ThemeData getThemeData(@NonNull String packageName, @NonNull String component) {
        return getThemeData(new ComponentName(packageName, component));
    }

    @Nullable
    protected ThemeData getThemeData(@NonNull ComponentName componentName) {
        return getThemedIconMap().get(componentName.getPackageName());
    }

    private Map<String, ThemeData> getThemedIconMap() {
        if (mThemedIconMap != null) {
            return mThemedIconMap;
        }
        ArrayMap<String, ThemeData> map = new ArrayMap<>();
        try {
            Resources res = mContext.getResources();
            int resID = res.getIdentifier(THEMED_ICON_MAP_FILE, "xml", mContext.getPackageName());
            if (resID != 0) {
                XmlResourceParser parser = res.getXml(resID);
                final int depth = parser.getDepth();

                int type;

                while (((type = parser.next()) != XmlPullParser.END_TAG ||
                        parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG) {
                        continue;
                    }
                    if (TAG_ICON.equals(parser.getName())) {
                        String pkg = parser.getAttributeValue(null, ATTR_PACKAGE);
                        int iconId = parser.getAttributeResourceValue(null, ATTR_DRAWABLE, 0);
                        if (iconId != 0 && !TextUtils.isEmpty(pkg)) {
                            map.put(pkg, new ThemeData(res, mContext.getPackageName(), iconId));
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to parse icon map", e);
        }
        mThemedIconMap = map;
        return mThemedIconMap;
    }

    private class IconChangeReceiver extends BroadcastReceiver implements SafeCloseable {

        private final IconChangeListener mCallback;
        private String mIconState;

        IconChangeReceiver(IconChangeListener callback, Handler handler) {
            mCallback = callback;
            mIconState = getSystemIconState();


            IntentFilter packageFilter = new IntentFilter(ACTION_OVERLAY_CHANGED);
            packageFilter.addDataScheme("package");
            packageFilter.addDataSchemeSpecificPart("android", PatternMatcher.PATTERN_LITERAL);
            mContext.registerReceiver(this, packageFilter, null, handler);

            if (mCalendar != null || mClock != null) {
                final IntentFilter filter = new IntentFilter(ACTION_TIMEZONE_CHANGED);
                if (mCalendar != null) {
                    filter.addAction(Intent.ACTION_TIME_CHANGED);
                    filter.addAction(ACTION_DATE_CHANGED);
                }
                mContext.registerReceiver(this, filter, null, handler);
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case ACTION_TIMEZONE_CHANGED:
                    if (mClock != null) {
                        mCallback.onAppIconChanged(mClock.getPackageName(), Process.myUserHandle());
                    }
                    // follow through
                case ACTION_DATE_CHANGED:
                case ACTION_TIME_CHANGED:
                    if (mCalendar != null) {
                        for (UserHandle user
                                : context.getSystemService(UserManager.class).getUserProfiles()) {
                            mCallback.onAppIconChanged(mCalendar.getPackageName(), user);
                        }
                    }
                    break;
                case ACTION_OVERLAY_CHANGED: {
                    String newState = getSystemIconState();
                    if (!mIconState.equals(newState)) {
                        mIconState = newState;
                        mCallback.onSystemIconStateChanged(mIconState);
                    }
                    break;
                }
            }
        }

        @Override
        public void close() {
            mContext.unregisterReceiver(this);
        }
    }

    /**
     * Listener for receiving icon changes
     */
    public interface IconChangeListener {

        /**
         * Called when the icon for a particular app changes
         */
        void onAppIconChanged(String packageName, UserHandle user);

        /**
         * Called when the global icon state changed, which can typically affect all icons
         */
        void onSystemIconStateChanged(String iconState);
    }
}
