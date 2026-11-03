/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shows a feed image at full size while the image itself is held. */
@SuppressWarnings("unused")
public final class LongPressMediaPreviewPatch {
    private static final int MAX_MEDIA = 160;
    private static final String IMAGE_TAG = "feed_media_content_self_image_";
    private static final String LINK_THUMBNAIL_TAG = "feed_post_title_with_thumbnail_";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IMAGES = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MorpheMediaPreview");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, String> MEDIA_BY_LINK_ID = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(MAX_MEDIA + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_MEDIA;
                }
            }
    );
    private static final Map<Activity, PreviewWindowCallback> CALLBACKS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean dispatchingCancel;

    private LongPressMediaPreviewPatch() {
    }

    public static boolean isPatchIncluded() {
        return false;
    }

    /** Injection point called by the Reddit activity hook. */
    public static void attach(Activity activity) {
        if (!isPatchIncluded() || activity == null || activity.isFinishing()) return;
        synchronized (CALLBACKS) {
            if (CALLBACKS.containsKey(activity)) return;
            Window window = activity.getWindow();
            if (window == null) return;
            PreviewWindowCallback callback = new PreviewWindowCallback(activity, window.getCallback());
            window.setCallback(callback);
            CALLBACKS.put(activity, callback);
        }
    }

    /** Injection point from Reddit's self-image feed model. */
    public static void registerImageModel(Object model) {
        if (model == null) return;
        String linkId = readStringField(model, "e");
        String url = firstImageUrl(readField(model, "i"), readField(model, "k"));
        if (linkId != null && url != null) MEDIA_BY_LINK_ID.put(linkId, url);
    }

    /** Injection point from Reddit's feed title-with-thumbnail section. */
    public static void registerLinkThumbnailModel(Object model) {
        if (model == null) return;
        String linkId = readStringField(model, "a");
        Object thumbnail = readField(model, "f");
        String url = firstImageUrl(readField(thumbnail, "i"));
        if (linkId != null && url != null) MEDIA_BY_LINK_ID.put(linkId, url);
    }

    private static final class PreviewWindowCallback implements Window.Callback {
        private final Activity activity;
        private final Window.Callback delegate;
        private final int touchSlop;
        private TouchState touch;
        private View preview;

        PreviewWindowCallback(Activity activity, Window.Callback delegate) {
            this.activity = activity;
            this.delegate = delegate;
            touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            boolean consumed = handleTouch(event);
            return consumed || delegate.dispatchTouchEvent(event);
        }

        private boolean handleTouch(MotionEvent event) {
            if (dispatchingCancel && event.getActionMasked() == MotionEvent.ACTION_CANCEL) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dismissPreview();
                    touch = new TouchState(event.getRawX(), event.getRawY());
                    MAIN.postDelayed(touch.show, ViewConfiguration.getLongPressTimeout());
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (touch != null && moved(event, touch)) touch.cancel();
                    return preview != null;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean wasShowing = preview != null;
                    if (touch != null) touch.cancel();
                    dismissPreview();
                    return wasShowing;
                default:
                    return preview != null;
            }
        }

        private boolean moved(MotionEvent event, TouchState state) {
            return Math.abs(event.getRawX() - state.x) > touchSlop ||
                    Math.abs(event.getRawY() - state.y) > touchSlop;
        }

        private void showIfStillHolding(TouchState state) {
            if (touch != state || state.cancelled || activity.isFinishing()) return;
            View root = activity.getWindow().getDecorView();
            String linkId;
            try {
                linkId = findImageLinkId(root, Math.round(state.x), Math.round(state.y));
            } catch (RuntimeException ignored) {
                // Accessibility implementations differ between Reddit/Android builds. A failed
                // lookup must leave the original touch untouched, never crash the application.
                return;
            }
            String url = linkId == null ? null : MEDIA_BY_LINK_ID.get(linkId);
            if (url == null) return;
            showPreview(root, url);
            state.previewShown = true;
            cancelUnderlyingLongPress(root, state.x, state.y);
        }

        private void showPreview(View root, String url) {
            if (!(root instanceof ViewGroup)) return;
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackground(new ColorDrawable(0xEE000000));
            overlay.setClickable(true);
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setAdjustViewBounds(true);
            overlay.addView(image, new FrameLayout.LayoutParams(-1, -1));
            ((ViewGroup) root).addView(overlay, new ViewGroup.LayoutParams(-1, -1));
            preview = overlay;
            IMAGES.execute(() -> loadImage(url, bitmap -> MAIN.post(() -> {
                if (preview == overlay && bitmap != null) image.setImageBitmap(bitmap);
            })));
        }

        private void dismissPreview() {
            if (preview != null) {
                ViewGroup parent = (ViewGroup) preview.getParent();
                if (parent != null) parent.removeView(preview);
                preview = null;
            }
        }

        private final class TouchState {
            final float x;
            final float y;
            boolean cancelled;
            boolean previewShown;
            final Runnable show = () -> showIfStillHolding(this);

            TouchState(float x, float y) {
                this.x = x;
                this.y = y;
            }

            void cancel() {
                cancelled = true;
                MAIN.removeCallbacks(show);
            }
        }

        @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) { return delegate != null && delegate.dispatchKeyEvent(event); }
        @Override public boolean dispatchKeyShortcutEvent(android.view.KeyEvent event) { return delegate != null && delegate.dispatchKeyShortcutEvent(event); }
        @Override public boolean dispatchTrackballEvent(MotionEvent event) { return delegate != null && delegate.dispatchTrackballEvent(event); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return delegate != null && delegate.dispatchGenericMotionEvent(event); }
        @Override public boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) { return delegate != null && delegate.dispatchPopulateAccessibilityEvent(event); }
        @Override public View onCreatePanelView(int featureId) { return delegate == null ? null : delegate.onCreatePanelView(featureId); }
        @Override public boolean onCreatePanelMenu(int featureId, android.view.Menu menu) { return delegate != null && delegate.onCreatePanelMenu(featureId, menu); }
        @Override public boolean onPreparePanel(int featureId, View view, android.view.Menu menu) { return delegate != null && delegate.onPreparePanel(featureId, view, menu); }
        @Override public boolean onMenuOpened(int featureId, android.view.Menu menu) { return delegate != null && delegate.onMenuOpened(featureId, menu); }
        @Override public boolean onMenuItemSelected(int featureId, android.view.MenuItem item) { return delegate != null && delegate.onMenuItemSelected(featureId, item); }
        @Override public void onWindowAttributesChanged(android.view.WindowManager.LayoutParams attrs) { if (delegate != null) delegate.onWindowAttributesChanged(attrs); }
        @Override public void onContentChanged() { if (delegate != null) delegate.onContentChanged(); }
        @Override public void onWindowFocusChanged(boolean hasFocus) { if (delegate != null) delegate.onWindowFocusChanged(hasFocus); }
        @Override public void onAttachedToWindow() { if (delegate != null) delegate.onAttachedToWindow(); }
        @Override public void onDetachedFromWindow() { dismissPreview(); if (delegate != null) delegate.onDetachedFromWindow(); }
        @Override public void onPanelClosed(int featureId, android.view.Menu menu) { if (delegate != null) delegate.onPanelClosed(featureId, menu); }
        @Override public boolean onSearchRequested() { return delegate != null && delegate.onSearchRequested(); }
        @Override public boolean onSearchRequested(android.view.SearchEvent event) { return delegate != null && delegate.onSearchRequested(event); }
        @Override public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback) { return delegate == null ? null : delegate.onWindowStartingActionMode(callback); }
        @Override public android.view.ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback, int type) { return delegate == null ? null : delegate.onWindowStartingActionMode(callback, type); }
        @Override public void onActionModeStarted(android.view.ActionMode mode) { if (delegate != null) delegate.onActionModeStarted(mode); }
        @Override public void onActionModeFinished(android.view.ActionMode mode) { if (delegate != null) delegate.onActionModeFinished(mode); }
        @Override public void onProvideKeyboardShortcuts(List<android.view.KeyboardShortcutGroup> data, android.view.Menu menu, int deviceId) { if (delegate != null) delegate.onProvideKeyboardShortcuts(data, menu, deviceId); }
        @Override public void onPointerCaptureChanged(boolean hasCapture) { if (delegate != null) delegate.onPointerCaptureChanged(hasCapture); }
    }

    private interface BitmapConsumer { void accept(Bitmap bitmap); }

    private static void loadImage(String url, BitmapConsumer consumer) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            try (InputStream stream = connection.getInputStream()) {
                consumer.accept(BitmapFactory.decodeStream(stream));
            }
        } catch (Exception ignored) {
            consumer.accept(null);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String findImageLinkId(View root, int x, int y) {
        return findImageLinkId(root, x, y, 0);
    }

    private static String findImageLinkId(View view, int x, int y, int depth) {
        if (view == null || depth > 12 || !view.isShown()) return null;
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        if (x < position[0] || y < position[1] || x >= position[0] + view.getWidth() || y >= position[1] + view.getHeight()) return null;
        String linkId = findImageLinkId(view.getAccessibilityNodeProvider(), x, y, depth);
        if (linkId != null) return linkId;
        linkId = findImageLinkId(view.createAccessibilityNodeInfo(), null, x, y, depth);
        if (linkId != null) return linkId;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                linkId = findImageLinkId(group.getChildAt(i), x, y, depth + 1);
                if (linkId != null) return linkId;
            }
        }
        return null;
    }

    private static String findImageLinkId(AccessibilityNodeProvider provider, int x, int y, int depth) {
        if (provider == null) return null;
        android.view.accessibility.AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(View.NO_ID);
        return findImageLinkId(node, provider, x, y, depth);
    }

    private static String findImageLinkId(android.view.accessibility.AccessibilityNodeInfo node, AccessibilityNodeProvider provider, int x, int y, int depth) {
        if (node == null || depth > 20) return null;
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty() && !bounds.contains(x, y)) {
            node.recycle();
            return null;
        }
        try {
            String[] candidates = {String.valueOf(node.getViewIdResourceName()), String.valueOf(node.getContentDescription()), String.valueOf(node.getText())};
            for (String candidate : candidates) {
                String linkId = taggedLinkId(candidate, IMAGE_TAG);
                if (linkId == null) linkId = taggedLinkId(candidate, LINK_THUMBNAIL_TAG);
                if (linkId != null) return linkId;
            }
            int[] childIds = provider == null ? null : childVirtualIds(node);
            int childCount = childIds == null ? node.getChildCount() : childIds.length;
            for (int i = childCount - 1; i >= 0; i--) {
                android.view.accessibility.AccessibilityNodeInfo child = null;
                if (provider != null) {
                    child = provider.createAccessibilityNodeInfo(childIds[i]);
                } else {
                    try {
                        child = node.getChild(i);
                    } catch (IllegalStateException ignored) {
                        // Provider-backed Compose nodes are handled above without hidden APIs.
                    }
                }
                String result = findImageLinkId(child, provider, x, y, depth + 1);
                if (result != null) return result;
            }
            return null;
        } finally {
            node.recycle();
        }
    }

    /**
     * Reads Compose virtual child IDs from the node's public Parcelable representation. Android 16
     * blocks the hidden getChildId method, while getChild() rejects provider-created unsealed nodes.
     * The parcel is the same stable data Android itself uses to transport accessibility nodes.
     */
    private static int[] childVirtualIds(android.view.accessibility.AccessibilityNodeInfo node) {
        Parcel parcel = Parcel.obtain();
        try {
            android.view.accessibility.AccessibilityNodeInfo copy =
                    android.view.accessibility.AccessibilityNodeInfo.obtain(node);
            copy.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            long fields = parcel.readLong();

            if (hasField(fields, 0)) parcel.readInt();
            if (hasField(fields, 1)) parcel.readLong();
            if (hasField(fields, 2)) parcel.readInt();
            if (hasField(fields, 3)) parcel.readLong();
            if (hasField(fields, 4)) parcel.readLong();
            if (hasField(fields, 5)) parcel.readLong();
            if (hasField(fields, 6)) {
                int count = parcel.readInt();
                for (int i = 0; i < count; i++) parcel.readLong();
            }
            if (hasField(fields, 7)) parcel.readLong();
            if (hasField(fields, 8)) parcel.readLong();
            if (hasField(fields, 9)) parcel.readLong();
            if (hasField(fields, 10)) parcel.readInt();
            if (!hasField(fields, 11)) return new int[0];

            int count = parcel.readInt();
            if (count <= 0 || count > 10000) return new int[0];
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                ids[i] = (int) (parcel.readLong() >> 32);
            }
            return ids;
        } catch (RuntimeException ignored) {
            return new int[0];
        } finally {
            parcel.recycle();
        }
    }

    private static boolean hasField(long fields, int index) {
        return (fields & (1L << index)) != 0;
    }

    private static String taggedLinkId(String candidate, String prefix) {
        int index = candidate.indexOf(prefix);
        if (index < 0) return null;
        return candidate.substring(index + prefix.length()).replaceAll("[^A-Za-z0-9_-].*$", "");
    }

    private static void cancelUnderlyingLongPress(View root, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        MotionEvent cancel = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_CANCEL, x - location[0], y - location[1], 0
        );
        dispatchingCancel = true;
        try {
            root.dispatchTouchEvent(cancel);
        } finally {
            dispatchingCancel = false;
            cancel.recycle();
        }
    }

    private static Object readField(Object object, String name) {
        try {
            Field field = object.getClass().getField(name);
            return field.get(object);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readStringField(Object object, String name) {
        Object value = readField(object, name);
        return value instanceof String ? (String) value : null;
    }

    private static String firstImageUrl(Object... sources) {
        for (Object source : sources) {
            if (source == null) continue;
            String first = readStringField(source, "a");
            String second = readStringField(source, "b");
            // Reddit's media source chooses b when its c flag is set.
            boolean useSecond = Boolean.TRUE.equals(readField(source, "c"));
            if (useSecond && isImageUrl(second)) return second;
            if (isImageUrl(first)) return first;
            if (isImageUrl(second)) return second;
        }
        return null;
    }

    private static boolean isImageUrl(String value) {
        return value != null && value.startsWith("https://") &&
                (value.contains("i.redd.it/") || value.contains("preview.redd.it/") ||
                        value.contains("external-preview.redd.it/"));
    }
}
