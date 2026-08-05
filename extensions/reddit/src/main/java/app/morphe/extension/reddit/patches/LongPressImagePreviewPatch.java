/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class LongPressImagePreviewPatch {
    private static final Set<Activity> ATTACHED_ACTIVITIES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> ATTACHED_VIEWS =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<View, TouchState> TOUCH_STATES = new WeakHashMap<>();
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
                            attachVisibleViews(activity, root, root);
                        }
                    }
            );
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to attach Reddit image preview", ex);
        }
    }

    private static void attachVisibleViews(Activity activity, View root, View view) {
        if (isPreviewCandidate(root, view)) {
            attachTouchListener(activity, root, view);
        }

        if (!(view instanceof ViewGroup)) {
            return;
        }

        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            attachVisibleViews(activity, root, group.getChildAt(i));
        }
    }

    private static boolean isPreviewCandidate(View root, View view) {
        if (view == activePreview || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) {
            return false;
        }

        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }

        int minSizePx = dp(root, 96);
        if (width < minSizePx || height < minSizePx) {
            return false;
        }

        int screenWidth = root.getResources().getDisplayMetrics().widthPixels;
        return view == root || width >= screenWidth / 3 || height >= screenWidth / 3;
    }

    private static void attachTouchListener(Activity activity, View root, View view) {
        synchronized (ATTACHED_VIEWS) {
            if (ATTACHED_VIEWS.contains(view)) {
                return;
            }
            ATTACHED_VIEWS.add(view);
        }

        int touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        int longPressTimeout = ViewConfiguration.getLongPressTimeout();
        view.setOnTouchListener((touchedView, event) -> {
            if (!Settings.LONG_PRESS_IMAGE_PREVIEW.get()) {
                hidePreview();
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    TouchState state = new TouchState(
                            event.getRawX(),
                            event.getRawY(),
                            touchSlop
                    );
                    synchronized (TOUCH_STATES) {
                        TOUCH_STATES.put(touchedView, state);
                    }
                    MAIN_HANDLER.postDelayed(
                            () -> showPreviewIfStillHolding(activity, root, touchedView, state),
                            longPressTimeout
                    );
                    break;
                case MotionEvent.ACTION_MOVE:
                    TouchState moveState;
                    synchronized (TOUCH_STATES) {
                        moveState = TOUCH_STATES.get(touchedView);
                    }
                    if (moveState != null) {
                        moveState.update(event.getRawX(), event.getRawY());
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    synchronized (TOUCH_STATES) {
                        TOUCH_STATES.remove(touchedView);
                    }
                    hidePreview();
                    break;
                default:
                    break;
            }

            return false;
        });
    }

    private static void showPreviewIfStillHolding(
            Activity activity,
            View root,
            View touchedView,
            TouchState state
    ) {
        TouchState current;
        synchronized (TOUCH_STATES) {
            current = TOUCH_STATES.get(touchedView);
        }

        if (current != state || state.movedTooFar || !Settings.LONG_PRESS_IMAGE_PREVIEW.get()) {
            return;
        }

        showPreview(activity, root, Math.round(state.rawX), Math.round(state.rawY));
    }

    private static void showPreview(Activity activity, View root, int rawX, int rawY) {
        try {
            hidePreview();

            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) {
                return;
            }

            Bitmap previewBitmap = capturePreviewBitmap(root, rawX, rawY);
            if (previewBitmap == null) {
                return;
            }

            ViewGroup decor = (ViewGroup) decorView;
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(Color.argb(220, 0, 0, 0));
            overlay.setClickable(false);
            overlay.setFocusable(false);

            ImageView preview = new ImageView(activity);
            preview.setImageDrawable(new BitmapDrawable(root.getResources(), previewBitmap));
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setAdjustViewBounds(true);

            int padding = dp(root, 16);
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
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to show Reddit image preview", ex);
        }
    }

    private static Bitmap capturePreviewBitmap(View root, int rawX, int rawY) {
        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }

        Bitmap full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(full);
        root.draw(canvas);

        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        int x = rawX - rootLocation[0];
        int y = rawY - rootLocation[1];

        int cropWidth = Math.min(width, Math.max(dp(root, 280), width - (dp(root, 24) * 2)));
        int cropHeight = Math.min(height, Math.max(dp(root, 220), height / 2));
        int left = clamp(x - cropWidth / 2, 0, Math.max(0, width - cropWidth));
        int top = clamp(y - cropHeight / 2, 0, Math.max(0, height - cropHeight));

        return Bitmap.createBitmap(full, left, top, cropWidth, cropHeight);
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static final class TouchState {
        final float startRawX;
        final float startRawY;
        final int touchSlop;
        float rawX;
        float rawY;
        boolean movedTooFar;

        TouchState(float rawX, float rawY, int touchSlop) {
            this.startRawX = rawX;
            this.startRawY = rawY;
            this.rawX = rawX;
            this.rawY = rawY;
            this.touchSlop = touchSlop;
        }

        void update(float rawX, float rawY) {
            this.rawX = rawX;
            this.rawY = rawY;

            float deltaX = rawX - startRawX;
            float deltaY = rawY - startRawY;
            movedTooFar = movedTooFar || deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop;
        }
    }
}
