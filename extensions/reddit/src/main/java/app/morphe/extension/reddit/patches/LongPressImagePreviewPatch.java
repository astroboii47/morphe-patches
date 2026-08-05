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
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class LongPressImagePreviewPatch {
    private static final Set<Activity> ATTACHED_ACTIVITIES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Activity, TouchState> TOUCH_STATES = new WeakHashMap<>();
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

            ensureWindowCallback(activity);
            activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() ->
                    ensureWindowCallback(activity)
            );
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to attach Reddit image preview", ex);
        }
    }

    private static void ensureWindowCallback(Activity activity) {
        Window window = activity.getWindow();
        Window.Callback callback = window.getCallback();
        if (callback instanceof PreviewWindowCallback) {
            ((PreviewWindowCallback) callback).activity = activity;
            return;
        }

        window.setCallback(new PreviewWindowCallback(activity, callback));
    }

    private static boolean handleTouchEvent(Activity activity, MotionEvent event) {
        if (!Settings.LONG_PRESS_IMAGE_PREVIEW.get()) {
            hidePreview();
            return false;
        }

        boolean hadPreview = activePreview != null;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                TouchState state = new TouchState(
                        event.getRawX(),
                        event.getRawY(),
                        ViewConfiguration.get(activity).getScaledTouchSlop()
                );
                synchronized (TOUCH_STATES) {
                    TOUCH_STATES.put(activity, state);
                }
                MAIN_HANDLER.postDelayed(
                        () -> showPreviewIfStillHolding(activity, state),
                        ViewConfiguration.getLongPressTimeout()
                );
                break;
            case MotionEvent.ACTION_MOVE:
                TouchState moveState;
                synchronized (TOUCH_STATES) {
                    moveState = TOUCH_STATES.get(activity);
                }
                if (moveState != null) {
                    moveState.update(event.getRawX(), event.getRawY());
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                synchronized (TOUCH_STATES) {
                    TOUCH_STATES.remove(activity);
                }
                hidePreview();
                break;
            default:
                break;
        }

        return hadPreview || activePreview != null;
    }

    private static void showPreviewIfStillHolding(
            Activity activity,
            TouchState state
    ) {
        TouchState current;
        synchronized (TOUCH_STATES) {
            current = TOUCH_STATES.get(activity);
        }

        if (current != state || state.movedTooFar || !Settings.LONG_PRESS_IMAGE_PREVIEW.get()) {
            return;
        }

        View root = activity.getWindow().getDecorView();
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

        Rect cropBounds = findCompactMediaBounds(root, x, y);
        if (cropBounds != null) {
            return Bitmap.createBitmap(
                    full,
                    cropBounds.left,
                    cropBounds.top,
                    cropBounds.width(),
                    cropBounds.height()
            );
        }

        int cropWidth = Math.min(width, dp(root, 180));
        int cropHeight = Math.min(height, dp(root, 180));
        int left = clamp(x - cropWidth / 2, 0, Math.max(0, width - cropWidth));
        int top = clamp(y - cropHeight / 2, 0, Math.max(0, height - cropHeight));

        return Bitmap.createBitmap(full, left, top, cropWidth, cropHeight);
    }

    private static Rect findCompactMediaBounds(View root, int x, int y) {
        Rect bounds = new Rect();
        if (!findSmallestViewBoundsContainingPoint(root, root, x, y, bounds)) {
            return null;
        }

        int minMediaSize = dp(root, 56);
        int maxMediaSize = dp(root, 260);
        int width = bounds.width();
        int height = bounds.height();
        if (width < minMediaSize || height < minMediaSize ||
                width > maxMediaSize || height > maxMediaSize) {
            return null;
        }

        int inset = dp(root, 2);
        bounds.inset(-inset, -inset);
        bounds.left = clamp(bounds.left, 0, root.getWidth() - 1);
        bounds.top = clamp(bounds.top, 0, root.getHeight() - 1);
        bounds.right = clamp(bounds.right, bounds.left + 1, root.getWidth());
        bounds.bottom = clamp(bounds.bottom, bounds.top + 1, root.getHeight());
        return bounds;
    }

    private static boolean findSmallestViewBoundsContainingPoint(
            View root,
            View view,
            int x,
            int y,
            Rect outBounds
    ) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }

        int[] rootLocation = new int[2];
        int[] viewLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        view.getLocationOnScreen(viewLocation);

        Rect viewBounds = new Rect(
                viewLocation[0] - rootLocation[0],
                viewLocation[1] - rootLocation[1],
                viewLocation[0] - rootLocation[0] + view.getWidth(),
                viewLocation[1] - rootLocation[1] + view.getHeight()
        );
        if (!viewBounds.contains(x, y)) {
            return false;
        }

        boolean foundChild = false;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                foundChild = findSmallestViewBoundsContainingPoint(
                        root,
                        group.getChildAt(i),
                        x,
                        y,
                        outBounds
                ) || foundChild;
                if (foundChild) {
                    break;
                }
            }
        }

        if (!foundChild) {
            outBounds.set(viewBounds);
        }
        return true;
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

    private static final class PreviewWindowCallback implements Window.Callback {
        Activity activity;
        private final Window.Callback delegate;

        PreviewWindowCallback(Activity activity, Window.Callback delegate) {
            this.activity = activity;
            this.delegate = delegate;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return delegate != null && delegate.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return delegate != null && delegate.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (handleTouchEvent(activity, event)) {
                return true;
            }
            return delegate != null && delegate.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return delegate != null && delegate.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return delegate != null && delegate.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return delegate != null && delegate.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return delegate != null ? delegate.onCreatePanelView(featureId) : null;
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return delegate != null && delegate.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return delegate != null && delegate.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return delegate != null && delegate.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return delegate != null && delegate.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
            if (delegate != null) {
                delegate.onWindowAttributesChanged(attrs);
            }
        }

        @Override
        public void onContentChanged() {
            if (delegate != null) {
                delegate.onContentChanged();
            }
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (delegate != null) {
                delegate.onWindowFocusChanged(hasFocus);
            }
        }

        @Override
        public void onAttachedToWindow() {
            if (delegate != null) {
                delegate.onAttachedToWindow();
            }
        }

        @Override
        public void onDetachedFromWindow() {
            if (delegate != null) {
                delegate.onDetachedFromWindow();
            }
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu) {
            if (delegate != null) {
                delegate.onPanelClosed(featureId, menu);
            }
        }

        @Override
        public boolean onSearchRequested() {
            return delegate != null && delegate.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return delegate != null && delegate.onSearchRequested(searchEvent);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return delegate != null ? delegate.onWindowStartingActionMode(callback) : null;
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return delegate != null ? delegate.onWindowStartingActionMode(callback, type) : null;
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            if (delegate != null) {
                delegate.onActionModeStarted(mode);
            }
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
            if (delegate != null) {
                delegate.onActionModeFinished(mode);
            }
        }

        @Override
        public void onProvideKeyboardShortcuts(
                List<KeyboardShortcutGroup> data,
                Menu menu,
                int deviceId
        ) {
            if (delegate != null) {
                delegate.onProvideKeyboardShortcuts(data, menu, deviceId);
            }
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture) {
            if (delegate != null) {
                delegate.onPointerCaptureChanged(hasCapture);
            }
        }
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
