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
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.morphe.extension.reddit.patches.LongPressImagePreviewPatch;
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
        LongPressImagePreviewPatch.attach(activity);

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
                attachBuildVersionLongPress(preferencesFragment);
                return;
            }

            callPreferenceMethod(preference, "A", String.class, "Morphe Settings");
            setPreferenceClickListener(preference);
            attachBuildVersionLongPress(preferencesFragment);
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
                    openMorpheSettings(context);
                    return true;
                }
        );

        Field clickListenerField = preference.getClass().getField("g");
        clickListenerField.set(preference, listener);
    }

    private static void attachBuildVersionLongPress(Object preferencesFragment) {
        try {
            View root = getFragmentView(preferencesFragment);
            if (root == null) {
                return;
            }

            Handler handler = new Handler(Looper.getMainLooper());
            for (int delay : new int[]{0, 250, 750, 1500}) {
                handler.postDelayed(() -> {
                    try {
                        attachBuildVersionLongPressToTree(root);
                    } catch (Throwable ex) {
                        Logger.printException(() -> "Failed to attach Morphe build version long press", ex);
                    }
                }, delay);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to schedule Morphe build version long press", ex);
        }
    }

    private static View getFragmentView(Object preferencesFragment) {
        try {
            Method getView = preferencesFragment.getClass().getMethod("getView");
            getView.setAccessible(true);
            return (View) getView.invoke(preferencesFragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean attachBuildVersionLongPressToTree(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (isBuildVersionText(text)) {
                View target = findPreferenceRow(view);
                target.setLongClickable(true);
                target.setOnLongClickListener(v -> {
                    openMorpheSettings(v.getContext());
                    return true;
                });
                view.setLongClickable(true);
                view.setOnLongClickListener(v -> {
                    openMorpheSettings(v.getContext());
                    return true;
                });
                return true;
            }
        }

        if (!(view instanceof ViewGroup)) {
            return false;
        }

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (attachBuildVersionLongPressToTree(group.getChildAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBuildVersionText(CharSequence text) {
        if (text == null) {
            return false;
        }

        String value = text.toString();
        return MORPHE_LABEL.equals(value)
                || "Morphe Settings".equals(value)
                || value.contains("2026.")
                || value.toLowerCase().contains("build version");
    }

    private static View findPreferenceRow(View view) {
        View current = view;
        for (int i = 0; i < 4 && current.getParent() instanceof View; i++) {
            current = (View) current.getParent();
            if (current.isClickable() || current.getHeight() >= view.getHeight() * 2) {
                return current;
            }
        }
        return view;
    }

    private static void openMorpheSettings(Context context) {
        Intent intent = new Intent(context, MorpheSettingsActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }
}
