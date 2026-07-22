/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.settings;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.morphe.extension.reddit.settings.preference.RedditPreferenceFragment;
import app.morphe.extension.reddit.ui.MorpheSettingsIconVectorDrawable;
import app.morphe.extension.shared.Logger;

@SuppressWarnings({"deprecation", "unused"})
public class RedditActivityHook {
    private static final Drawable MORPHE_ICON = MorpheSettingsIconVectorDrawable.getIcon();
    private static final String MORPHE_LABEL = "Morphe";
    private static final String MORPHE_SETTINGS_LAUNCHER =
            "app.morphe.extension.reddit.settings.MorpheSettingsLauncher";

    /**
     * Injection point.
     */
    public static Drawable getSettingIcon() {
        return MORPHE_ICON;
    }

    /**
     * Injection point.
     */
    public static String getSettingLabel() {
        return MORPHE_LABEL;
    }

    /**
     * Injection point.
     */
    public static boolean hook(Activity activity) {
        Intent intent = activity.getIntent();
        if (MORPHE_LABEL.equals(intent.getStringExtra("com.reddit.extra.initial_url"))) {
            initialize(activity);
            return true;
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static boolean hookLauncher(Activity activity) {
        ComponentName componentName = activity.getComponentName();
        if (componentName != null && MORPHE_SETTINGS_LAUNCHER.equals(componentName.getClassName())) {
            initialize(activity);
            return true;
        }

        return false;
    }

    /**
     * Injection point.
     */
    public static void initialize(Activity activity) {
        int fragmentId = View.generateViewId();
        FrameLayout fragment = new FrameLayout(activity);
        fragment.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        fragment.setId(fragmentId);

        LinearLayout linearLayout = new LinearLayout(activity);
        linearLayout.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        linearLayout.setFitsSystemWindows(true);
        linearLayout.setTransitionGroup(true);
        linearLayout.addView(fragment);
        linearLayout.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        activity.setContentView(linearLayout);

        activity.getFragmentManager()
                .beginTransaction()
                .replace(fragmentId, new RedditPreferenceFragment())
                .commit();
    }

    /**
     * Injection point.
     */
    public static void hookBuildVersionPreference(Object preferencesFragment) {
        try {
            Object preference = getBuildVersionPreference(preferencesFragment);
            if (preference == null) {
                return;
            }

            callPreferenceMethod(preference, "A", String.class, "Morphe Settings");
            setPreferenceClickListener(preference);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to hook Reddit build version preference", ex);
        }
    }

    /**
     * Injection point.
     */
    public static boolean isAcknowledgment(Enum<?> e) {
        return e != null && "ACKNOWLEDGMENTS".equals(e.name());
    }

    /**
     * Injection point.
     */
    public static Intent initializeByIntent(Context context) {
        Intent intent = new Intent();
        intent.setClassName(context, "com.reddit.webembed.browser.WebBrowserActivity");
        intent.putExtra("com.reddit.extra.initial_url", MORPHE_LABEL);
        return intent;
    }

    private static Object getBuildVersionPreference(Object preferencesFragment) throws ReflectiveOperationException {
        Class<?> fragmentClass = preferencesFragment.getClass();
        Field buildVersionKey = getResourceField("string", "key_pref_build_version");

        Method getStringKey = fragmentClass.getDeclaredMethod("m", int.class);
        getStringKey.setAccessible(true);
        Object key = getStringKey.invoke(preferencesFragment, buildVersionKey.getInt(null));

        Method findPreference = fragmentClass.getMethod("e0", CharSequence.class);
        findPreference.setAccessible(true);
        return findPreference.invoke(preferencesFragment, key);
    }

    private static Field getResourceField(String type, String name) throws ReflectiveOperationException {
        Class<?> resourceClass = Class.forName("com.reddit.frontpage.R$" + type);
        Field field = resourceClass.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void callPreferenceMethod(
            Object preference,
            String methodName,
            Class<?> parameterType,
            Object value
    ) throws ReflectiveOperationException {
        Method method = preference.getClass().getMethod(methodName, parameterType);
        method.setAccessible(true);
        method.invoke(preference, value);
    }

    private static void setPreferenceClickListener(Object preference) throws ReflectiveOperationException {
        Class<?> listenerClass = Class.forName("m720");
        Object listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    Object clickedPreference = args != null && args.length > 0 ? args[0] : preference;
                    Field contextField = clickedPreference.getClass().getField("a");
                    Context context = (Context) contextField.get(clickedPreference);
                    context.startActivity(new Intent(context, MorpheSettingsActivity.class));
                    return true;
                }
        );

        Field clickListenerField = preference.getClass().getField("g");
        clickListenerField.set(preference, listener);
    }
}
