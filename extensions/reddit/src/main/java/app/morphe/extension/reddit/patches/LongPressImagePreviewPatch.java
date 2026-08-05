/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class LongPressImagePreviewPatch {
    private static final Set<Activity> ATTACHED_ACTIVITIES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> ATTACHED_IMAGE_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static View activePreview;

    private LongPressImagePreviewPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static void attach(Activity activity) {
        if (!isPatchIncluded()) {
            return;
        }

        try {
            synchronized (ATTACHED_ACTIVITIES) {
                if (ATTACHED_ACTIVITIES.contains(activity)) {
                    return;
                }
                ATTACHED_ACTIVITIES.add(activity);
            }

            View root = activity.getWindow().getDecorView();
            root.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        private long lastScanMs;

                        @Override
                        public void onGlobalLayout() {
                            if (!Settings.LONG_PRESS_IMAGE_PREVIEW.get()) {
                                return;
                            }

                            long now = System.currentTimeMillis();
                            if (now - lastScanMs < 300L) {
                                return;
                            }
                            lastScanMs = now;
                            attachVisibleImages(activity, root);
                        }
                    }
            );
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to attach Reddit image preview", ex);
        }
    }

    private static void attachVisibleImages(Activity activity, View view) {
        if (view instanceof ImageView && isPreviewCandidate((ImageView) view)) {
            attachImage(activity, (ImageView) view);
            return;
        }

        if (!(view instanceof ViewGroup)) {
            return;
        }

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            attachVisibleImages(activity, group.getChildAt(i));
        }
    }

    private static boolean isPreviewCandidate(ImageView imageView) {
        if (imageView.getVisibility() != View.VISIBLE || imageView.getDrawable() == null) {
            return false;
        }

        int minImageSizePx = dp(imageView, 96);
        int width = imageView.getWidth();
        int height = imageView.getHeight();
        if (width < minImageSizePx || height < minImageSizePx) {
            return false;
        }

        int screenWidth = imageView.getResources().getDisplayMetrics().widthPixels;
        return width >= screenWidth / 3 || height >= screenWidth / 3;
    }

    private static void attachImage(Activity activity, ImageView imageView) {
        synchronized (ATTACHED_IMAGE_VIEWS) {
            if (ATTACHED_IMAGE_VIEWS.contains(imageView)) {
                return;
            }
            ATTACHED_IMAGE_VIEWS.add(imageView);
        }

        imageView.setLongClickable(true);
        imageView.setOnLongClickListener(view -> {
            if (!Settings.LONG_PRESS_IMAGE_PREVIEW.get()) {
                return false;
            }

            Drawable drawable = ((ImageView) view).getDrawable();
            if (drawable == null) {
                return false;
            }

            showPreview(activity, view, drawable);
            return true;
        });
    }

    private static void showPreview(Activity activity, View sourceView, Drawable sourceDrawable) {
        try {
            hidePreview();

            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) {
                return;
            }

            ViewGroup decor = (ViewGroup) decorView;
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(Color.argb(220, 0, 0, 0));
            overlay.setClickable(true);
            overlay.setFocusable(false);
            overlay.setOnClickListener(view -> hidePreview());

            ImageView preview = new ImageView(activity);
            Drawable previewDrawable = sourceDrawable.getConstantState() != null
                    ? sourceDrawable.getConstantState().newDrawable().mutate()
                    : sourceDrawable;
            preview.setImageDrawable(previewDrawable);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setAdjustViewBounds(true);

            int padding = dp(sourceView, 16);
            overlay.setPadding(padding, padding, padding, padding);
            overlay.addView(preview, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
            ));

            decor.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            activePreview = overlay;
            closeWhenReleased(sourceView, overlay);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to show Reddit image preview", ex);
        }
    }

    private static void closeWhenReleased(View sourceView, View preview) {
        MAIN_HANDLER.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activePreview != preview) {
                    return;
                }

                if (!sourceView.isPressed()) {
                    hidePreview();
                    return;
                }

                MAIN_HANDLER.postDelayed(this, 16L);
            }
        }, 16L);
    }

    private static void hidePreview() {
        View preview = activePreview;
        if (preview == null) {
            return;
        }

        activePreview = null;
        ViewParent parent = preview.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(preview);
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
