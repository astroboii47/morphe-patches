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
import android.util.Log;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class LongPressImagePreviewPatch {
    private static final Set<Activity> ATTACHED_ACTIVITIES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Activity, TouchState> TOUCH_STATES = new WeakHashMap<>();
    private static final Map<String, String> MEDIA_URLS = new HashMap<>();
    private static final Map<String, String> LINK_TITLES = new HashMap<>();
    private static final Map<String, String> TITLE_MEDIA_URLS = new HashMap<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String SELF_IMAGE_TAG_PREFIX = "feed_media_content_self_image_";
    private static final int MAX_ACCESSIBILITY_NODE_DEPTH = 12;
    private static final int MAX_CACHED_MEDIA_URLS = 300;
    private static final String LOG_TAG = "MorphePreview";
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

    public static void registerMediaPreview(String linkId, Object mediaPreview) {
        String url = extractUrl(mediaPreview);
        Log.i(LOG_TAG, "media hook linkId=" + linkId + " mediaClass="
                + (mediaPreview != null ? mediaPreview.getClass().getName() : "null")
                + " url=" + summarizeUrl(url));
        registerMediaUrl(linkId, url);
    }

    public static void registerMediaUrl(String linkId, String url) {
        if (linkId == null || url == null || url.length() == 0) {
            return;
        }

        String normalizedUrl = normalizeUrl(url);
        synchronized (MEDIA_URLS) {
            if (MEDIA_URLS.size() > MAX_CACHED_MEDIA_URLS) {
                MEDIA_URLS.clear();
            }
            MEDIA_URLS.put(linkId, normalizedUrl);
        }

        synchronized (TITLE_MEDIA_URLS) {
            String title;
            synchronized (LINK_TITLES) {
                title = LINK_TITLES.get(linkId);
            }
            if (title != null) {
                cacheTitleMediaUrl(title, normalizedUrl);
            }
        }
    }

    public static void registerPostTitle(String linkId, String title) {
        if (linkId == null || title == null || title.length() == 0) {
            return;
        }

        synchronized (LINK_TITLES) {
            if (LINK_TITLES.size() > MAX_CACHED_MEDIA_URLS) {
                LINK_TITLES.clear();
            }
            LINK_TITLES.put(linkId, title);
        }

        synchronized (MEDIA_URLS) {
            String url = MEDIA_URLS.get(linkId);
            if (url != null) {
                synchronized (TITLE_MEDIA_URLS) {
                    cacheTitleMediaUrl(title, url);
                }
            }
        }
    }

    public static void registerPostMedia(String title, Object mediaPreview) {
        String url = extractUrl(mediaPreview);
        if (title == null || title.length() == 0 || url == null || url.length() == 0) {
            if (title != null && title.length() != 0 && mediaPreview != null) {
                Log.i(LOG_TAG, "cache miss title=\"" + title + "\" mediaClass="
                        + mediaPreview.getClass().getName() + " media=" + mediaPreview);
            }
            return;
        }

        synchronized (TITLE_MEDIA_URLS) {
            cacheTitleMediaUrl(title, normalizeUrl(url));
        }
    }

    public static void registerTitleThumbnailElement(Object titleElement, Object thumbnail) {
        Log.i(LOG_TAG, "title thumbnail hook titleClass="
                + (titleElement != null ? titleElement.getClass().getName() : "null")
                + " thumbnailClass=" + (thumbnail != null ? thumbnail.getClass().getName() : "null"));
        registerPostMedia(extractTitle(titleElement), thumbnail);
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

            ViewGroup decor = (ViewGroup) decorView;
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(Color.argb(220, 0, 0, 0));
            overlay.setClickable(false);
            overlay.setFocusable(false);

            int padding = dp(root, 16);
            overlay.setPadding(padding, padding, padding, padding);

            String mediaUrl = getMediaUrlAtPoint(root, rawX, rawY);
            if (mediaUrl == null) {
                Log.i(LOG_TAG, "no real media url for preview");
                return;
            }

            Log.i(LOG_TAG, "showing url=" + summarizeUrl(mediaUrl));
            WebView preview = new WebView(activity);
            preview.setBackgroundColor(Color.TRANSPARENT);
            preview.setVerticalScrollBarEnabled(false);
            preview.setHorizontalScrollBarEnabled(false);
            WebSettings settings = preview.getSettings();
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setBuiltInZoomControls(false);
            settings.setDisplayZoomControls(false);
            preview.loadDataWithBaseURL(
                    "https://www.reddit.com/",
                    buildPreviewHtml(mediaUrl),
                    "text/html",
                    "UTF-8",
                    null
            );
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

    private static String getMediaUrlAtPoint(View root, int rawX, int rawY) {
        String linkId = findMediaLinkIdAtPoint(root, rawX, rawY);
        if (linkId != null) {
            synchronized (MEDIA_URLS) {
                String mediaUrl = MEDIA_URLS.get(linkId);
                if (mediaUrl != null) {
                    return mediaUrl;
                }
            }
        }

        CharSequence description = findPostDescriptionAtPoint(root, rawX, rawY);
        if (description == null) {
            Log.i(LOG_TAG, "no post description at " + rawX + "," + rawY + " cacheSize=" + TITLE_MEDIA_URLS.size());
            return null;
        }

        String text = description.toString();
        synchronized (TITLE_MEDIA_URLS) {
            Log.i(LOG_TAG, "pressed row=\"" + text + "\" cacheSize=" + TITLE_MEDIA_URLS.size());
            String bestTitle = null;
            for (String title : TITLE_MEDIA_URLS.keySet()) {
                if (text.contains(title) && (bestTitle == null || title.length() > bestTitle.length())) {
                    bestTitle = title;
                }
            }

            Log.i(LOG_TAG, "matched title=\"" + bestTitle + "\"");
            return bestTitle != null ? TITLE_MEDIA_URLS.get(bestTitle) : null;
        }
    }

    private static String findMediaLinkIdAtPoint(View root, int rawX, int rawY) {
        try {
            return findMediaLinkIdInView(root, rawX, rawY);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to find Reddit media accessibility node", ex);
            return null;
        }
    }

    private static String findMediaLinkIdInView(View view, int rawX, int rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        Rect bounds = new Rect(
                location[0],
                location[1],
                location[0] + view.getWidth(),
                location[1] + view.getHeight()
        );
        if (!bounds.contains(rawX, rawY)) {
            return null;
        }

        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) {
            AccessibilityNodeInfo rootNode = provider.createAccessibilityNodeInfo(View.NO_ID);
            String linkId = findMediaLinkIdInNode(provider, rootNode, rawX, rawY, 0);
            if (linkId != null) {
                return linkId;
            }
        }

        AccessibilityNodeInfo viewNode = view.createAccessibilityNodeInfo();
        String linkId = findMediaLinkIdInNode(null, viewNode, rawX, rawY, 0);
        if (linkId != null) {
            return linkId;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                String linkId = findMediaLinkIdInView(group.getChildAt(i), rawX, rawY);
                if (linkId != null) {
                    return linkId;
                }
            }
        }

        return extractLinkIdFromText(view.getContentDescription());
    }

    private static CharSequence findPostDescriptionAtPoint(View root, int rawX, int rawY) {
        try {
            return findPostDescriptionInView(root, rawX, rawY);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to find Reddit post accessibility node", ex);
            return null;
        }
    }

    private static CharSequence findPostDescriptionInView(View view, int rawX, int rawY) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        Rect bounds = new Rect(
                location[0],
                location[1],
                location[0] + view.getWidth(),
                location[1] + view.getHeight()
        );
        if (!bounds.contains(rawX, rawY)) {
            return null;
        }

        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) {
            AccessibilityNodeInfo rootNode = provider.createAccessibilityNodeInfo(View.NO_ID);
            CharSequence description = findPostDescriptionInNode(provider, rootNode, rawX, rawY, 0);
            if (description != null) {
                return description;
            }
        }

        AccessibilityNodeInfo viewNode = view.createAccessibilityNodeInfo();
        CharSequence description = findPostDescriptionInNode(null, viewNode, rawX, rawY, 0);
        if (description != null) {
            return description;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                CharSequence description = findPostDescriptionInView(group.getChildAt(i), rawX, rawY);
                if (description != null) {
                    return description;
                }
            }
        }

        return isPostDescription(view.getContentDescription()) ? view.getContentDescription() : null;
    }

    private static CharSequence findPostDescriptionInNode(
            AccessibilityNodeProvider provider,
            AccessibilityNodeInfo node,
            int rawX,
            int rawY,
            int depth
    ) {
        if (node == null || depth > MAX_ACCESSIBILITY_NODE_DEPTH) {
            return null;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty() && !bounds.contains(rawX, rawY)) {
            node.recycle();
            return null;
        }

        CharSequence contentDescription = node.getContentDescription();
        if (isPostDescription(contentDescription)) {
            node.recycle();
            return contentDescription;
        }

        int childCount = node.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
            }

            if (child == null && provider != null) {
                int virtualId = getChildVirtualId(node, i);
                if (virtualId != Integer.MIN_VALUE) {
                    child = provider.createAccessibilityNodeInfo(virtualId);
                }
            }

            CharSequence description = findPostDescriptionInNode(provider, child, rawX, rawY, depth + 1);
            if (description != null) {
                node.recycle();
                return description;
            }
        }

        node.recycle();
        return null;
    }

    private static boolean isPostDescription(CharSequence value) {
        if (value == null) {
            return false;
        }

        String text = value.toString();
        return text.startsWith("From ") && text.contains(", Posted ") && text.contains(" upvote");
    }

    private static String findMediaLinkIdInNode(
            AccessibilityNodeProvider provider,
            AccessibilityNodeInfo node,
            int rawX,
            int rawY,
            int depth
    ) {
        if (node == null || depth > MAX_ACCESSIBILITY_NODE_DEPTH) {
            return null;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty() && !bounds.contains(rawX, rawY)) {
            node.recycle();
            return null;
        }

        String linkId = extractLinkIdFromText(node.getViewIdResourceName());
        if (linkId == null) {
            linkId = extractLinkIdFromText(node.getContentDescription());
        }
        if (linkId == null) {
            linkId = extractLinkIdFromText(node.getText());
        }
        if (linkId != null) {
            node.recycle();
            return linkId;
        }

        int childCount = node.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
            }

            if (child == null && provider != null) {
                int virtualId = getChildVirtualId(node, i);
                if (virtualId != Integer.MIN_VALUE) {
                    child = provider.createAccessibilityNodeInfo(virtualId);
                }
            }

            linkId = findMediaLinkIdInNode(provider, child, rawX, rawY, depth + 1);
            if (linkId != null) {
                node.recycle();
                return linkId;
            }
        }

        node.recycle();
        return null;
    }

    private static int getChildVirtualId(AccessibilityNodeInfo node, int index) {
        try {
            Object childId = AccessibilityNodeInfo.class
                    .getMethod("getChildId", int.class)
                    .invoke(node, index);
            if (!(childId instanceof Long)) {
                return Integer.MIN_VALUE;
            }

            try {
                Object virtualId = AccessibilityNodeInfo.class
                        .getMethod("getVirtualDescendantId", long.class)
                        .invoke(null, childId);
                if (virtualId instanceof Integer) {
                    return (Integer) virtualId;
                }
            } catch (ReflectiveOperationException ignored) {
                return (int) (((Long) childId) >> 32);
            }
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    private static String extractLinkIdFromText(CharSequence value) {
        if (value == null) {
            return null;
        }

        String text = value.toString();
        int index = text.indexOf(SELF_IMAGE_TAG_PREFIX);
        if (index < 0) {
            return null;
        }

        int start = index + SELF_IMAGE_TAG_PREFIX.length();
        int end = start;
        while (end < text.length()) {
            char ch = text.charAt(end);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '-') {
                break;
            }
            end++;
        }

        return end > start ? text.substring(start, end) : null;
    }

    private static String extractUrl(Object mediaPreview) {
        return extractUrl(mediaPreview, 0);
    }

    private static String extractTitle(Object titleElement) {
        if (titleElement == null) {
            return null;
        }

        try {
            Field field = titleElement.getClass().getDeclaredField("i");
            field.setAccessible(true);
            Object value = field.get(titleElement);
            if (value instanceof String) {
                return (String) value;
            }
        } catch (Throwable ignored) {
        }

        String value = titleElement.toString();
        String marker = "title=";
        int start = value.indexOf(marker);
        if (start < 0) {
            return null;
        }

        start += marker.length();
        int end = value.indexOf(", translatedTitle=", start);
        return end > start ? value.substring(start, end) : null;
    }

    private static String extractUrl(Object mediaPreview, int depth) {
        if (mediaPreview == null) {
            return null;
        }

        if (mediaPreview instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) mediaPreview) {
                String url = extractUrl(item, depth + 1);
                if (url != null) {
                    return url;
                }
            }
        }

        try {
            Method method = mediaPreview.getClass().getDeclaredMethod("b");
            method.setAccessible(true);
            Object value = method.invoke(mediaPreview);
            if (value instanceof String) {
                return (String) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Field field = mediaPreview.getClass().getDeclaredField("a");
            field.setAccessible(true);
            Object value = field.get(mediaPreview);
            if (value instanceof String) {
                return (String) value;
            }
        } catch (Throwable ignored) {
        }

        for (String fieldName : new String[]{"c", "i", "j", "k", "f"}) {
            try {
                Field field = mediaPreview.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(mediaPreview);
                if (value instanceof String) {
                    String stringValue = (String) value;
                    if (looksLikeMediaUrl(stringValue)) {
                        return stringValue;
                    }
                } else if (depth < 3) {
                    String url = extractUrl(value, depth + 1);
                    if (url != null) {
                        return url;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static void cacheTitleMediaUrl(String title, String url) {
        if (TITLE_MEDIA_URLS.size() > MAX_CACHED_MEDIA_URLS) {
            TITLE_MEDIA_URLS.clear();
        }
        TITLE_MEDIA_URLS.put(title, url);
        Log.i(LOG_TAG, "cached title=\"" + title + "\" url=" + summarizeUrl(url));
    }

    private static boolean looksLikeMediaUrl(String value) {
        String lower = value.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("file://") || lower.startsWith("content://");
    }

    private static String normalizeUrl(String url) {
        return url.replace("&amp;", "&");
    }

    private static String buildPreviewHtml(String mediaUrl) {
        String escapedUrl = escapeHtml(mediaUrl);
        boolean video = isVideoUrl(mediaUrl);
        String media = video
                ? "<video src=\"" + escapedUrl + "\" autoplay muted loop playsinline></video>"
                : "<img src=\"" + escapedUrl + "\" />";
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;width:100%;height:100%;background:transparent;overflow:hidden;}"
                + "body{display:flex;align-items:center;justify-content:center;}"
                + "img,video{max-width:100%;max-height:100%;object-fit:contain;}</style></head><body>"
                + media
                + "</body></html>";
    }

    private static boolean isVideoUrl(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".m3u8")
                || lower.contains("v.redd.it");
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String summarizeUrl(String url) {
        if (url == null || url.length() < 140) {
            return url;
        }
        return url.substring(0, 140) + "...";
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
