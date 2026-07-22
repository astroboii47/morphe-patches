/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import java.lang.reflect.Constructor;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public class DisableModernHomePatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean disableModernHome(boolean original) {
        return isPatchIncluded() ? false : (!Settings.DISABLE_MODERN_HOME.get() && original);
    }

    /**
     * Injection point.
     */
    public static boolean shouldDisableModernHome() {
        return isPatchIncluded() || Settings.DISABLE_MODERN_HOME.get();
    }

    /**
     * Injection point.
     */
    public static Object createAppBarSlot(Object fallbackSlot, Object unused, Object sidebarContent) {
        try {
            if (sidebarContent == null) {
                return fallbackSlot;
            }

            Class<?> slotClass = Class.forName("androidx.compose.runtime.internal.a");
            if (slotClass.isInstance(sidebarContent)) {
                return sidebarContent;
            }

            Constructor<?> constructor = slotClass.getDeclaredConstructor(Object.class, int.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(sidebarContent, 0x4815aa7, false);
        } catch (Throwable ignoredException) {
            return fallbackSlot;
        }
    }
}
