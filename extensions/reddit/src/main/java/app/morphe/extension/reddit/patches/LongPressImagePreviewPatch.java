/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.os.SystemClock;
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
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final List<String> RECENT_MEDIA_TITLES = new ArrayList<>();
    private static final List<String> RECENT_MEDIA_URLS = new ArrayList<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService IMAGE_LOADER = Executors.newSingleThreadExecutor();
    private static final AtomicInteger PREVIEW_GENERATION = new AtomicInteger();
    private static boolean REDISPATCHING_FEED_KEY;
    private static boolean FEED_HANDOFF_DONE;
    private static boolean LIFECYCLE_CALLBACKS_REGISTERED;
    private static int LAST_PREVIEW_Y;
    private static boolean DISPATCHING_PREVIEW_CANCEL;
    private static View activePreviewRoot;
    private static int activePreviewX = Integer.MIN_VALUE;
    private static int activePreviewY = Integer.MIN_VALUE;
    private static View nativePostReturnRoot;
    private static int nativePostReturnX = Integer.MIN_VALUE;
    private static int nativePostReturnY = Integer.MIN_VALUE;
    private static boolean nativePostHeldOpen;
    private static long nativePostOpenedAt;
    private static Activity currentActivity;
    private static int postFocusRestoreGeneration;
    private static boolean touchNativePostHeld;
    private static final String[] MEDIA_TAG_PREFIXES = new String[]{
            "feed_media_content_self_image_",
            "feed_media_content_video_",
            "feed_media_content_self_",
            "feed_promoted_letterbox_media_content_video_"
    };
    private static final int MAX_ACCESSIBILITY_NODE_DEPTH = 48;
    private static final int MAX_CACHED_MEDIA_URLS = 300;
    private static final String LOG_TAG = "MorphePreview";
    private static final boolean DEBUG_LOGS = false;
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
            currentActivity = activity;
            synchronized (ATTACHED_ACTIVITIES) {
                if (ATTACHED_ACTIVITIES.contains(activity)) {
                    return;
                }
                ATTACHED_ACTIVITIES.add(activity);
            }

            ensureWindowCallback(activity);
            ensureLifecycleCallbacks(activity);
            activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() ->
                    ensureWindowCallback(activity)
            );
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to attach Reddit image preview", ex);
        }
    }

    public static void registerMediaPreview(String linkId, Object mediaPreview) {
        String url = extractUrl(mediaPreview);
        url = RedditComposeFocusBridge.preferVideoUrl(mediaPreview, url);
        RedditComposeFocusBridge.registerPreviewMedia(linkId, url, mediaPreview);
        debugLog("media hook linkId=" + linkId + " mediaClass="
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
            if (!RedditComposeFocusBridge.isUsablePreviewUrl(normalizedUrl)) {
                return;
            }
            String existingUrl = MEDIA_URLS.get(linkId);
            if (existingUrl != null
                    && RedditComposeFocusBridge.isVideoPreviewUrl(existingUrl)
                    && !RedditComposeFocusBridge.isVideoPreviewUrl(normalizedUrl)) {
                return;
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

        RedditComposeFocusBridge.registerPreviewTitle(linkId, title);
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
        url = RedditComposeFocusBridge.preferVideoUrl(mediaPreview, url);
        RedditComposeFocusBridge.registerPreviewMedia(title, url, mediaPreview);
        if (title == null || title.length() == 0 || url == null || url.length() == 0) {
            if (title != null && title.length() != 0 && mediaPreview != null) {
                debugLog("cache miss title=\"" + title + "\" mediaClass="
                        + mediaPreview.getClass().getName() + " media=" + mediaPreview);
            }
            return;
        }

        synchronized (TITLE_MEDIA_URLS) {
            cacheTitleMediaUrl(title, normalizeUrl(url));
        }
    }

    public static void registerTitleThumbnailElement(Object titleElement, Object thumbnail) {
        debugLog("title thumbnail hook titleClass="
                + (titleElement != null ? titleElement.getClass().getName() : "null")
                + " thumbnailClass=" + (thumbnail != null ? thumbnail.getClass().getName() : "null"));
        registerPostMedia(extractTitle(titleElement), thumbnail);
    }

    public static void registerCompactPostPreview(String linkId, Object preview) {
        String title = extractStringField(preview, "e");
        String url = extractUrl(readField(preview, "h"));
        if (url == null) {
            url = extractUrl(preview);
        }

        url = RedditComposeFocusBridge.preferVideoUrl(preview, url);
        RedditComposeFocusBridge.cachePostBodyFromModel(title, preview);
        RedditComposeFocusBridge.registerPreviewMedia(linkId, url, preview);
        debugLog("compact preview hook linkId=" + linkId
                + " title=\"" + title + "\" url=" + summarizeUrl(url));

        if (title != null) {
            registerPostTitle(linkId, title);
        }
        registerMediaUrl(linkId, url);
        if (title != null && url != null) {
            synchronized (TITLE_MEDIA_URLS) {
                cacheTitleMediaUrl(title, normalizeUrl(url));
            }
        }
    }

    public static void registerPostPreviewBase(
            String title,
            Object onPost,
            Object onSubredditPost,
            Object onProfilePost
    ) {
        String url = extractUrl(onPost);
        if (url == null) {
            url = extractUrl(onSubredditPost);
        }
        if (url == null) {
            url = extractUrl(onProfilePost);
        }

        RedditComposeFocusBridge.registerPreviewBase(title, onPost, onSubredditPost, onProfilePost);
        RedditComposeFocusBridge.cachePostBodyFromModels(title, onPost, onSubredditPost, onProfilePost);
        debugLog("post preview base hook title=\"" + title + "\" url=" + summarizeUrl(url));
        if (title == null || title.length() == 0 || url == null || url.length() == 0) {
            return;
        }

        synchronized (TITLE_MEDIA_URLS) {
            cacheTitleMediaUrl(title, normalizeUrl(url));
        }
    }

    public static void registerFeedImageSection(Object imageElement) {
        String linkId = extractStringField(imageElement, "e");
        String url = extractUrl(readField(readField(imageElement, "a"), "i"));
        if (url == null) {
            url = extractUrl(readField(imageElement, "i"));
        }
        if (url == null) {
            url = extractUrl(imageElement);
        }

        debugLog("image section hook linkId=" + linkId + " url=" + summarizeUrl(url));
        registerMediaUrl(linkId, url);
    }

    public static void registerFeedVideoSection(Object videoElement) {
        String linkId = extractStringField(videoElement, "e");
        String url = extractStringField(videoElement, "k");
        if (url == null || !looksLikeMediaUrl(url)) {
            url = extractUrl(readField(videoElement, "w"));
        }
        if (url == null) {
            url = extractUrl(readField(videoElement, "j"));
        }
        if (url == null) {
            url = extractUrl(videoElement);
        }
        url = RedditComposeFocusBridge.preferVideoUrl(videoElement, url);

        debugLog("video section hook linkId=" + linkId + " url=" + summarizeUrl(url));
        registerMediaUrl(linkId, url);
    }

    public static void registerCellMediaSource(Object source) {
        String url = extractUrl(source);
        if (url == null || url.length() == 0) {
            return;
        }

        synchronized (RECENT_MEDIA_URLS) {
            RECENT_MEDIA_URLS.remove(url);
            RECENT_MEDIA_URLS.add(normalizeUrl(url));
            if (RECENT_MEDIA_URLS.size() > MAX_CACHED_MEDIA_URLS) {
                RECENT_MEDIA_URLS.remove(0);
            }
        }
        debugLog("cell media source hook url=" + summarizeUrl(url));
    }

    public static void registerTitleWithThumbnailCell(Object cell) {
        String title = extractTitleCellTitle(readField(cell, "b"));
        String url = extractUrl(readField(cell, "c"));
        debugLog("title thumbnail cell hook title=\"" + title + "\" url=" + summarizeUrl(url));
        if (title == null || title.length() == 0 || url == null || url.length() == 0) {
            return;
        }

        synchronized (TITLE_MEDIA_URLS) {
            cacheTitleMediaUrl(title, normalizeUrl(url));
        }
    }

    public static void registerClassicCell(Object cell) {
        String linkId = extractStringField(cell, "a");
        String title = extractTitleCellTitle(readField(cell, "b"));
        Object thumbnailCell = readField(readField(cell, "e"), "b");
        String url = extractUrl(readField(thumbnailCell, "d"));
        if (url == null) {
            url = extractUrl(thumbnailCell);
        }

        RedditComposeFocusBridge.registerPreviewMedia(linkId, url, thumbnailCell);
        debugLog("classic cell hook linkId=" + linkId
                + " title=\"" + title + "\" url=" + summarizeUrl(url));
        if (title != null && title.length() != 0) {
            registerPostTitle(linkId, title);
        }
        registerMediaUrl(linkId, url);
        if (title != null && title.length() != 0 && url != null && url.length() != 0) {
            synchronized (TITLE_MEDIA_URLS) {
                cacheTitleMediaUrl(title, normalizeUrl(url));
            }
        }
    }

    public static void registerMediaSource(String path, String obfuscatedPath, boolean shouldObfuscate, Object size) {
        String url = shouldObfuscate && obfuscatedPath != null && obfuscatedPath.length() != 0
                ? obfuscatedPath
                : path;
        if (url == null || !looksLikeMediaUrl(url)) {
            return;
        }

        int width = extractIntField(size, "a");
        int height = extractIntField(size, "b");
        if (Math.max(width, height) < 96) {
            return;
        }

        synchronized (RECENT_MEDIA_URLS) {
            RECENT_MEDIA_URLS.remove(url);
            RECENT_MEDIA_URLS.add(normalizeUrl(url));
            if (RECENT_MEDIA_URLS.size() > MAX_CACHED_MEDIA_URLS) {
                RECENT_MEDIA_URLS.remove(0);
            }
        }
        debugLog("media source hook size=" + width + "x" + height + " url=" + summarizeUrl(url));
    }

    public static void registerMediaSourceObject(Object mediaSource) {
        if (mediaSource == null) {
            return;
        }

        String path = extractStringField(mediaSource, "a");
        String obfuscatedPath = extractStringField(mediaSource, "b");
        Object shouldObfuscate = readField(mediaSource, "c");
        registerMediaSource(
                path,
                obfuscatedPath,
                shouldObfuscate instanceof Boolean && (Boolean) shouldObfuscate,
                readField(mediaSource, "d")
        );
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

    private static void ensureLifecycleCallbacks(Activity activity) {
        if (LIFECYCLE_CALLBACKS_REGISTERED) {
            return;
        }
        try {
            Application application = activity.getApplication();
            if (application == null) {
                return;
            }
            LIFECYCLE_CALLBACKS_REGISTERED = true;
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity createdActivity, Bundle savedInstanceState) {
                    attach(createdActivity);
                }

                @Override
                public void onActivityStarted(Activity startedActivity) {
                    attach(startedActivity);
                }

                @Override
                public void onActivityResumed(Activity resumedActivity) {
                    currentActivity = resumedActivity;
                    attach(resumedActivity);
                }

                @Override
                public void onActivityPaused(Activity pausedActivity) {
                }

                @Override
                public void onActivityStopped(Activity stoppedActivity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity destroyedActivity) {
                    if (currentActivity == destroyedActivity) {
                        currentActivity = null;
                    }
                }
            });
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to register Reddit preview activity lifecycle", throwable);
        }
    }

    private static boolean handleTouchEvent(Activity activity, MotionEvent event) {
        if (!Settings.LONG_PRESS_IMAGE_PREVIEW.get()) {
            hidePreview();
            return false;
        }
        if (DISPATCHING_PREVIEW_CANCEL && event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            return false;
        }

        boolean hadPreview = activePreview != null;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                FEED_HANDOFF_DONE = false;
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
                if (touchNativePostHeld && nativePostHeldOpen) {
                    touchNativePostHeld = false;
                    synchronized (TOUCH_STATES) {
                        TOUCH_STATES.remove(activity);
                    }
                    closeNativePostDetail(activity);
                    return true;
                }
                TouchState endState;
                synchronized (TOUCH_STATES) {
                    endState = TOUCH_STATES.remove(activity);
                }
                if (endState != null && endState.nativePostShown) {
                    touchNativePostHeld = false;
                    closeNativePostDetail(activity);
                    return true;
                }
                if (hadPreview) {
                    hidePreview();
                }
                if (endState != null && endState.previewShown) {
                    return true;
                }
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
        int rawX = Math.round(state.rawX);
        int rawY = Math.round(state.rawY);
        if (!hasDirectMediaPreviewAtPoint(root, rawX, rawY)) {
            int[] postPoint = RedditComposeFocusBridge.getPostPreviewPointAt(root, rawX, rawY);
            if (postPoint != null && openNativePostDetailForTouch(activity, root, postPoint[0], postPoint[1])) {
                state.nativePostShown = true;
                touchNativePostHeld = true;
                cancelUnderlyingLongPress(root, rawX, rawY);
                return;
            }
        }

        showPreview(activity, root, rawX, rawY);
        if (activePreview != null) {
            state.previewShown = true;
            cancelUnderlyingLongPress(root, rawX, rawY);
        }
    }

    private static boolean hasDirectMediaPreviewAtPoint(View root, int rawX, int rawY) {
        String linkId = findMediaLinkIdAtPoint(root, rawX, rawY);
        if (linkId != null) {
            return true;
        }
        int[] location = new int[2];
        root.getLocationOnScreen(location);
        return findCompactMediaBounds(root, rawX - location[0], rawY - location[1]) != null;
    }

    private static void cancelUnderlyingLongPress(View root, int rawX, int rawY) {
        try {
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_CANCEL,
                    rawX - location[0],
                    rawY - location[1],
                    0
            );
            DISPATCHING_PREVIEW_CANCEL = true;
            try {
                root.dispatchTouchEvent(cancel);
            } finally {
                DISPATCHING_PREVIEW_CANCEL = false;
                cancel.recycle();
            }
        } catch (Throwable throwable) {
            DISPATCHING_PREVIEW_CANCEL = false;
            Log.w(LOG_TAG, "cancel long press failed", throwable);
        }
    }

    private static void showPreview(Activity activity, View root, int rawX, int rawY) {
        showPreview(activity, root, rawX, rawY, false);
    }

    private static void showFocusedPreview(Activity activity, View root, int rawX, int rawY) {
        showPreview(activity, root, rawX, rawY, true);
    }

    private static void showPreview(Activity activity, View root, int rawX, int rawY, boolean focusedOnly) {
        try {
            hidePreview();

            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) {
                return;
            }

            ViewGroup decor = (ViewGroup) decorView;
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(Color.argb(220, 0, 0, 0));
            configurePreviewOverlay(overlay);

            int padding = dp(root, 16);
            overlay.setPadding(padding, padding, padding, padding);

            String mediaUrl = focusedOnly
                    ? RedditComposeFocusBridge.getFocusedPostModelMediaPreview(root)
                    : getMediaUrlAtPoint(root, rawX, rawY);
            if (mediaUrl == null) {
                int[] postPoint = RedditComposeFocusBridge.getPostPreviewPointAt(root, rawX, rawY);
                if (!focusedOnly && postPoint != null) {
                    rawX = postPoint[0];
                    rawY = postPoint[1];
                }

                String modelMediaUrl = RedditComposeFocusBridge.getPostModelMediaPreviewAt(root, rawX, rawY);
                mediaUrl = modelMediaUrl != null ? modelMediaUrl : getMediaUrlAtPoint(root, rawX, rawY);
            }
            if (mediaUrl == null) {
                String postUrl = focusedOnly
                        ? RedditComposeFocusBridge.getFocusedPostEmbedUrl(root)
                        : RedditComposeFocusBridge.getPostEmbedUrlAt(root, rawX, rawY);
                String textPreview = focusedOnly
                        ? RedditComposeFocusBridge.getFocusedPostModelTextPreview(root)
                        : RedditComposeFocusBridge.getPostModelTextPreviewAt(root, rawX, rawY);
                if (focusedOnly && textPreview == null) {
                    textPreview = RedditComposeFocusBridge.getPostModelTextPreviewAt(root, rawX, rawY);
                }
                if (textPreview == null) {
                    textPreview = RedditComposeFocusBridge.getPostTextPreviewAt(root, rawX, rawY);
                }
                if (textPreview == null || textPreview.trim().length() == 0) {
                    textPreview = RedditComposeFocusBridge.getCachedTextPreviewForPostUrl(postUrl);
                }
                if (textPreview == null || textPreview.trim().length() == 0) {
                    textPreview = postUrl != null && postUrl.length() > 0 ? "Loading post text..." : null;
                }
                if (textPreview != null && textPreview.trim().length() > 0) {
                    Log.i(LOG_TAG, "showing text preview");
                    View textView = RedditComposeFocusBridge.createTextPreviewView(activity, textPreview);
                    attachPreviewDismissHandlers(textView);
                    overlay.addView(textView, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                    ));
                    decor.addView(overlay, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                    ));
                    activePreview = overlay;
                    rememberPreviewFocus(root, rawX, rawY);
                    overlay.requestFocus();
                    maybeUpgradeTextPreview(postUrl, textView, PREVIEW_GENERATION.get());
                    return;
                }
                Log.i(LOG_TAG, "no real media url for preview");
                return;
            }

            Log.i(LOG_TAG, "showing image preview");
            if (RedditComposeFocusBridge.isVideoPreviewUrl(mediaUrl)) {
                Log.i(LOG_TAG, "showing video preview");
                View videoView = RedditComposeFocusBridge.createVideoPreviewView(activity, mediaUrl);
                attachPreviewDismissHandlers(videoView);
                overlay.addView(videoView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                ));
                decor.addView(overlay, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                activePreview = overlay;
                rememberPreviewFocus(root, rawX, rawY);
                overlay.requestFocus();
                return;
            }

            ImageView preview = new ImageView(activity);
            preview.setBackgroundColor(Color.TRANSPARENT);
            preview.setAdjustViewBounds(true);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            attachPreviewDismissHandlers(preview);
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
            rememberPreviewFocus(root, rawX, rawY);
            overlay.requestFocus();
            loadPreviewImage(mediaUrl, preview, PREVIEW_GENERATION.incrementAndGet());
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to show Reddit image preview", ex);
        }
    }

    private static void rememberPreviewFocus(View root, int rawX, int rawY) {
        activePreviewRoot = root;
        activePreviewX = rawX;
        activePreviewY = rawY;
    }

    private static void configurePreviewOverlay(FrameLayout overlay) {
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        attachPreviewDismissHandlers(overlay);
    }

    private static void attachPreviewDismissHandlers(View view) {
        view.setOnTouchListener((target, touchEvent) -> {
            if (touchEvent.getActionMasked() == MotionEvent.ACTION_UP
                    || touchEvent.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                hidePreview();
            }
            return true;
        });
        view.setOnKeyListener((target, keyCode, keyEvent) -> {
            if (keyEvent.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_P
                    || keyCode == KeyEvent.KEYCODE_M
                    || keyCode == KeyEvent.KEYCODE_G
                    || keyCode == KeyEvent.KEYCODE_T
                    || keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                hidePreview();
                return true;
            }
            return false;
        });
    }

    private static void loadPreviewImage(String mediaUrl, ImageView preview, int generation) {
        IMAGE_LOADER.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = downloadBitmap(mediaUrl);
            } catch (Throwable ex) {
                Logger.printException(() -> "Failed to load Reddit image preview", ex);
            }

            Bitmap loadedBitmap = bitmap;
            MAIN_HANDLER.post(() -> {
                if (activePreview == null || PREVIEW_GENERATION.get() != generation) {
                    if (loadedBitmap != null) {
                        loadedBitmap.recycle();
                    }
                    return;
                }

                if (loadedBitmap != null) {
                    preview.setImageBitmap(loadedBitmap);
                } else {
                    hidePreview();
                }
            });
        });
    }

    private static Bitmap downloadBitmap(String mediaUrl) throws Exception {
        URL url = new URL(mediaUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(true);
        try (InputStream inputStream = connection.getInputStream()) {
            return BitmapFactory.decodeStream(inputStream);
        } finally {
            connection.disconnect();
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
                    mediaUrl = RedditComposeFocusBridge.upgradePreviewMedia(linkId, null, mediaUrl);
                    return RedditComposeFocusBridge.isUsablePreviewUrl(mediaUrl) ? mediaUrl : null;
                }
            }
        }

        CharSequence description = findPostDescriptionAtPoint(root, rawX, rawY);
        if (description == null) {
            description = findNearestPostDescription(root, rawY);
        }
        if (description == null) {
            Log.i(LOG_TAG, "no post description at " + rawX + "," + rawY + " cacheSize=" + TITLE_MEDIA_URLS.size());
            return null;
        }

        String text = description.toString();
        synchronized (TITLE_MEDIA_URLS) {
            Log.i(LOG_TAG, "pressed row=\"" + text + "\" cacheSize=" + TITLE_MEDIA_URLS.size());
            String bestTitle = null;
            int bestScore = 0;
            for (String title : TITLE_MEDIA_URLS.keySet()) {
                int score = RedditComposeFocusBridge.rowTitleMatchScore(text, title);
                if (score > bestScore || (score == bestScore && score > 0 && (bestTitle == null || title.length() > bestTitle.length()))) {
                    bestTitle = title;
                    bestScore = score;
                }
            }

            Log.i(LOG_TAG, "matched title=\"" + bestTitle + "\" score=" + bestScore);
            if (bestTitle != null) {
                return RedditComposeFocusBridge.upgradePreviewMedia(null, bestTitle, TITLE_MEDIA_URLS.get(bestTitle));
            }
        }

        return null;
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
                String childLinkId = findMediaLinkIdInView(group.getChildAt(i), rawX, rawY);
                if (childLinkId != null) {
                    return childLinkId;
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
                CharSequence childDescription = findPostDescriptionInView(group.getChildAt(i), rawX, rawY);
                if (childDescription != null) {
                    return childDescription;
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
        String matchedPrefix = null;
        int index = -1;
        for (String prefix : MEDIA_TAG_PREFIXES) {
            index = text.indexOf(prefix);
            if (index >= 0) {
                matchedPrefix = prefix;
                break;
            }
        }
        if (index < 0) {
            return null;
        }

        int start = index + matchedPrefix.length();
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

    private static String extractTitleCellTitle(Object titleCell) {
        Object titleCellFragment = readField(titleCell, "b");
        String title = extractStringField(titleCellFragment, "b");
        if (title != null) {
            return title;
        }
        return extractTitle(titleCell);
    }

    private static CharSequence findNearestPostDescription(View root, int rawY) {
        ArrayList<DescriptionBounds> descriptions = new ArrayList<>();
        collectPostDescriptions(root, descriptions);
        collectAccessibilityPostDescriptions(root, descriptions);
        DescriptionBounds best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (DescriptionBounds item : descriptions) {
            int centerY = item.bounds.centerY();
            int distance = Math.abs(centerY - rawY);
            boolean sameRow = rawY >= item.bounds.top - dp(root, 72)
                    && rawY <= item.bounds.bottom + dp(root, 72);
            if ((sameRow || best == null) && distance < bestDistance) {
                best = item;
                bestDistance = distance;
            }
        }
        return best != null ? best.description : null;
    }

    private static void collectAccessibilityPostDescriptions(View view, List<DescriptionBounds> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return;
        }

        try {
            AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
            collectPostDescriptionsFromNode(node, out, 0);
        } catch (Throwable ignored) {
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectAccessibilityPostDescriptions(group.getChildAt(i), out);
            }
        }
    }

    private static void collectPostDescriptionsFromNode(
            AccessibilityNodeInfo node,
            List<DescriptionBounds> out,
            int depth
    ) {
        if (node == null || depth > MAX_ACCESSIBILITY_NODE_DEPTH) {
            return;
        }

        try {
            CharSequence description = node.getContentDescription();
            if (isPostDescription(description)) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) {
                    out.add(new DescriptionBounds(description, bounds));
                }
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                } catch (Throwable ignored) {
                }
                collectPostDescriptionsFromNode(child, out, depth + 1);
            }
        } finally {
            node.recycle();
        }
    }

    private static void collectPostDescriptions(View view, List<DescriptionBounds> out) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return;
        }

        CharSequence description = view.getContentDescription();
        if (isPostDescription(description)) {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            out.add(new DescriptionBounds(
                    description,
                    new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight())
            ));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectPostDescriptions(group.getChildAt(i), out);
            }
        }
    }

    private static String extractStringField(Object instance, String fieldName) {
        Object value = readField(instance, fieldName);
        return value instanceof String ? (String) value : null;
    }

    private static Object readField(Object instance, String fieldName) {
        if (instance == null) {
            return null;
        }

        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int extractIntField(Object instance, String fieldName) {
        Object value = readField(instance, fieldName);
        return value instanceof Integer ? (Integer) value : 0;
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
            if (value instanceof String && looksLikeMediaUrl((String) value)) {
                return (String) value;
            }
        } catch (Throwable ignored) {
        }

        for (String fieldName : new String[]{"h", "c", "i", "j", "k", "f", "b", "d", "e"}) {
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
        if (title == null || title.length() == 0 || url == null || url.length() == 0) {
            return;
        }
        if (!RedditComposeFocusBridge.isUsablePreviewUrl(url)) {
            return;
        }
        if (TITLE_MEDIA_URLS.size() > MAX_CACHED_MEDIA_URLS) {
            TITLE_MEDIA_URLS.clear();
            RECENT_MEDIA_TITLES.clear();
        }
        TITLE_MEDIA_URLS.put(title, url);
        RECENT_MEDIA_TITLES.remove(title);
        RECENT_MEDIA_TITLES.add(title);
        if (RECENT_MEDIA_TITLES.size() > MAX_CACHED_MEDIA_URLS) {
            RECENT_MEDIA_TITLES.remove(0);
        }
        debugLog("cached title=\"" + title + "\" url=" + summarizeUrl(url));
    }

    private static String getRecentMediaUrlAtPoint(View root, int rawX, int rawY) {
        if (rawX < root.getWidth() * 0.55f) {
            return null;
        }

        synchronized (TITLE_MEDIA_URLS) {
            if (RECENT_MEDIA_TITLES.isEmpty()) {
                return null;
            }

            int visibleCount = Math.min(RECENT_MEDIA_TITLES.size(), 6);
            int firstIndex = RECENT_MEDIA_TITLES.size() - visibleCount;
            int top = dp(root, 120);
            int bottom = Math.max(top + 1, root.getHeight() - dp(root, 80));
            int row = clamp((rawY - top) * visibleCount / (bottom - top), 0, visibleCount - 1);
            String title = RECENT_MEDIA_TITLES.get(firstIndex + row);
            String url = TITLE_MEDIA_URLS.get(title);
            debugLog("recent media fallback title=\"" + title + "\" url=" + summarizeUrl(url));
            return RedditComposeFocusBridge.upgradePreviewMedia(null, title, url);
        }
    }

    private static String getRecentTextPreviewAtPoint(View root, int rawX, int rawY) {
        if (rawX < root.getWidth() * 0.40f) {
            return null;
        }

        synchronized (TITLE_MEDIA_URLS) {
            if (RECENT_MEDIA_TITLES.isEmpty()) {
                return null;
            }

            int visibleCount = Math.min(RECENT_MEDIA_TITLES.size(), 6);
            int firstIndex = RECENT_MEDIA_TITLES.size() - visibleCount;
            int top = dp(root, 120);
            int bottom = Math.max(top + 1, root.getHeight() - dp(root, 80));
            int row = clamp((rawY - top) * visibleCount / (bottom - top), 0, visibleCount - 1);
            String title = RECENT_MEDIA_TITLES.get(firstIndex + row);
            String body = RedditComposeFocusBridge.getPreviewBodyForTitle(title);
            debugLog("recent text fallback title=\"" + title + "\" body=" + (body == null ? 0 : body.length()));
            return body;
        }
    }

    private static String getRecentSourceUrlAtPoint(View root, int rawX, int rawY) {
        if (rawX < root.getWidth() * 0.55f) {
            return null;
        }

        synchronized (RECENT_MEDIA_URLS) {
            if (RECENT_MEDIA_URLS.isEmpty()) {
                return null;
            }

            int visibleCount = Math.min(RECENT_MEDIA_URLS.size(), 6);
            int firstIndex = RECENT_MEDIA_URLS.size() - visibleCount;
            int top = dp(root, 120);
            int bottom = Math.max(top + 1, root.getHeight() - dp(root, 80));
            int row = clamp((rawY - top) * visibleCount / (bottom - top), 0, visibleCount - 1);
            String url = RECENT_MEDIA_URLS.get(firstIndex + row);
            debugLog("recent source fallback url=" + summarizeUrl(url));
            return url;
        }
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

    private static void debugLog(String message) {
        if (DEBUG_LOGS) {
            Log.i(LOG_TAG, message);
        }
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
        PREVIEW_GENERATION.incrementAndGet();
        View preview = activePreview;
        if (preview == null) {
            return;
        }

        activePreview = null;
        View restoreRoot = activePreviewRoot;
        int restoreX = activePreviewX;
        int restoreY = activePreviewY;
        activePreviewRoot = null;
        activePreviewX = Integer.MIN_VALUE;
        activePreviewY = Integer.MIN_VALUE;
        preview.clearFocus();
        destroyWebViews(preview);
        ViewParent parent = preview.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(preview);
        }
        restorePostFocus(restoreRoot, restoreX, restoreY);
    }

    private static void hidePreview(Activity activity) {
        hidePreview();
    }

    private static void restorePostFocus(View root, int rawX, int rawY) {
        restorePostFocusDelayed(root, rawX, rawY, 32L);
    }

    private static void restorePostFocusDelayed(View root, int rawX, int rawY, long delayMillis) {
        if (root == null || rawX == Integer.MIN_VALUE || rawY == Integer.MIN_VALUE) {
            return;
        }
        MAIN_HANDLER.postDelayed(() -> RedditComposeFocusBridge.focusPostUnitAt(root, rawX, rawY), delayMillis);
    }

    private static void restorePostFocusWindow(Activity activity, int rawX, int rawY) {
        if (activity == null || rawX == Integer.MIN_VALUE || rawY == Integer.MIN_VALUE) {
            return;
        }
        final int generation = ++postFocusRestoreGeneration;
        long[] delays = new long[]{220L, 420L, 720L, 1040L};
        for (long delay : delays) {
            MAIN_HANDLER.postDelayed(() -> {
                if (generation != postFocusRestoreGeneration) {
                    return;
                }
                Activity targetActivity = currentActivity != null ? currentActivity : activity;
                View root = targetActivity.getWindow().getDecorView();
                if (RedditComposeFocusBridge.focusPostUnitAt(root, rawX, rawY)) {
                    FEED_HANDOFF_DONE = true;
                }
            }, delay);
        }
    }

    private static void destroyWebViews(View view) {
        if (view instanceof WebView) {
            WebView webView = (WebView) view;
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Throwable ignored) {
            }
            return;
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            destroyWebViews(group.getChildAt(i));
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    public static boolean handleKeyboardFeedFocusKey(Activity activity, KeyEvent event) {
        Log.w("MorpheRedditKeys", "handleKeyboardFeedFocusKey entry");
        if (REDISPATCHING_FEED_KEY) {
            return false;
        }

        int keyCode = event.getKeyCode();
        int mappedKeyCode = keyCode;
        int direction = View.FOCUS_DOWN;
        switch (keyCode) {
            case KeyEvent.KEYCODE_M:
                if (nativePostHeldOpen) {
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        long heldMillis = SystemClock.uptimeMillis() - nativePostOpenedAt;
                        if (heldMillis >= 120L) {
                            closeNativePostDetail(activity);
                        } else {
                            Log.i(LOG_TAG, "ignored early native reddit post release held=" + heldMillis);
                        }
                    }
                    return true;
                }
                return handlePostModalKey(activity, event);
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_I:
                mappedKeyCode = KeyEvent.KEYCODE_DPAD_UP;
                direction = View.FOCUS_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_K:
                mappedKeyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                direction = View.FOCUS_DOWN;
                break;
            case KeyEvent.KEYCODE_J:
                mappedKeyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                break;
            case KeyEvent.KEYCODE_L:
                mappedKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                break;
            case KeyEvent.KEYCODE_O:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    rememberFocusedPostReturn(activity.getWindow().getDecorView());
                }
                mappedKeyCode = KeyEvent.KEYCODE_DPAD_CENTER;
                break;
            case KeyEvent.KEYCODE_U:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    FEED_HANDOFF_DONE = false;
                    int returnX = nativePostReturnX;
                    int returnY = nativePostReturnY;
                    activity.onBackPressed();
                    restorePostFocusWindow(activity, returnX, returnY);
                }
                return true;
            case KeyEvent.KEYCODE_N:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    RedditComposeFocusBridge.clickNextCommentButton(activity.getWindow().getDecorView());
                }
                return true;
            case KeyEvent.KEYCODE_P:
                return handlePreviewKey(activity, event);
            case KeyEvent.KEYCODE_G:
                return handleWebPostModalKey(activity, event);
            case KeyEvent.KEYCODE_T:
                return handleTextCardPreviewKey(activity, event);
            default:
                return false;
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }

        postFocusRestoreGeneration++;
        View root = activity.getWindow().getDecorView();
        updatePreviewTargetY(root, mappedKeyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1
                : mappedKeyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0);
        if (FEED_HANDOFF_DONE) {
            redispatchFeedKey(activity, mappedKeyCode);
            return true;
        }

        return focusFeedContent(activity, root, direction, mappedKeyCode);
    }

    private static boolean handlePreviewKey(Activity activity, KeyEvent event) {
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP) {
            hidePreview(activity);
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN || activePreview != null) {
            return true;
        }

        View root = activity.getWindow().getDecorView();
        int[] point = RedditComposeFocusBridge.getFocusedPostPreviewPoint(root);
        if (point != null) {
            showFocusedPreview(activity, root, point[0], point[1]);
            return true;
        }

        int[] location = new int[2];
        root.getLocationOnScreen(location);
        showPreview(activity, root, location[0] + ((root.getWidth() * 3) / 4), updatePreviewTargetY(root, 0));
        return true;
    }

    private static boolean handlePostModalKey(Activity activity, KeyEvent event) {
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP) {
            if (nativePostHeldOpen) {
                closeNativePostDetail(activity);
            } else {
                hidePreview(activity);
            }
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN || activePreview != null) {
            return true;
        }

        View root = activity.getWindow().getDecorView();
        int[] point = RedditComposeFocusBridge.getFocusedPostPreviewPoint(root);
        if (point != null) {
            openFocusedNativePostDetail(activity, root, point[0], point[1]);
            return true;
        }

        int[] location = new int[2];
        root.getLocationOnScreen(location);
        int rawX = location[0] + ((root.getWidth() * 3) / 4);
        int rawY = updatePreviewTargetY(root, 0);
        String postUrl = RedditComposeFocusBridge.getPostEmbedUrlAt(root, rawX, rawY);
        if (postUrl == null || postUrl.length() == 0) {
            int[] postPoint = RedditComposeFocusBridge.getPostPreviewPointAt(root, rawX, rawY);
            if (postPoint != null) {
                postUrl = RedditComposeFocusBridge.getPostEmbedUrlAt(root, postPoint[0], postPoint[1]);
                rawX = postPoint[0];
                rawY = postPoint[1];
            }
        }
        openNativePostDetail(activity, root, rawX, rawY, postUrl);
        return true;
    }

    private static boolean openFocusedNativePostDetail(Activity activity, View root, int rawX, int rawY) {
        try {
            rememberNativePostReturn(root, rawX, rawY);
            nativePostHeldOpen = true;
            nativePostOpenedAt = SystemClock.uptimeMillis();
            hidePreview();
            redispatchFeedKey(activity, KeyEvent.KEYCODE_DPAD_CENTER);
            Log.i(LOG_TAG, "opened native reddit post via focused row");
            return true;
        } catch (Throwable throwable) {
            nativePostHeldOpen = false;
            Logger.printException(() -> "Failed to open focused Reddit post detail", throwable);
            return false;
        }
    }

    private static boolean openNativePostDetailForTouch(Activity activity, View root, int rawX, int rawY) {
        try {
            rememberNativePostReturn(root, rawX, rawY);
            nativePostHeldOpen = true;
            nativePostOpenedAt = SystemClock.uptimeMillis();
            hidePreview();
            if (!RedditComposeFocusBridge.focusPostUnitAt(root, rawX, rawY)) {
                nativePostHeldOpen = false;
                return false;
            }
            FEED_HANDOFF_DONE = true;
            redispatchFeedKey(activity, KeyEvent.KEYCODE_DPAD_CENTER);
            Log.i(LOG_TAG, "opened native reddit post via touch row");
            return true;
        } catch (Throwable throwable) {
            nativePostHeldOpen = false;
            Logger.printException(() -> "Failed to open touched Reddit post detail", throwable);
            return false;
        }
    }

    private static boolean openNativePostDetail(Activity activity, View root, int rawX, int rawY, String postUrl) {
        if (postUrl == null || postUrl.length() == 0) {
            return false;
        }
        try {
            rememberNativePostReturn(root, rawX, rawY);
            nativePostHeldOpen = true;
            nativePostOpenedAt = SystemClock.uptimeMillis();
            hidePreview();
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(postUrl));
            intent.setPackage(activity.getPackageName());
            activity.startActivity(intent);
            Log.i(LOG_TAG, "opened native reddit post " + postUrl);
            return true;
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to open native Reddit post detail", throwable);
            return false;
        }
    }

    private static void rememberNativePostReturn(View root, int rawX, int rawY) {
        nativePostReturnRoot = root;
        nativePostReturnX = rawX;
        nativePostReturnY = rawY;
    }

    private static void rememberFocusedPostReturn(View root) {
        int[] point = RedditComposeFocusBridge.getFocusedPostPreviewPoint(root);
        if (point != null) {
            rememberNativePostReturn(root, point[0], point[1]);
        }
    }

    private static void closeNativePostDetail(Activity activity) {
        nativePostHeldOpen = false;
        touchNativePostHeld = false;
        int returnX = nativePostReturnX;
        int returnY = nativePostReturnY;
        Log.i(LOG_TAG, "closing native reddit post");
        try {
            Activity targetActivity = currentActivity != null ? currentActivity : activity;
            targetActivity.onBackPressed();
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to close native Reddit post detail", throwable);
        }
        restorePostFocusWindow(activity, returnX, returnY);
    }

    private static boolean handleTextCardPreviewKey(Activity activity, KeyEvent event) {
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP) {
            hidePreview(activity);
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN || activePreview != null) {
            return true;
        }

        View root = activity.getWindow().getDecorView();
        int[] point = RedditComposeFocusBridge.getFocusedPostPreviewPoint(root);
        if (point != null && showTextCardPreview(activity, root, point[0], point[1], true)) {
            return true;
        }

        int[] location = new int[2];
        root.getLocationOnScreen(location);
        showTextCardPreview(
                activity,
                root,
                location[0] + ((root.getWidth() * 3) / 4),
                updatePreviewTargetY(root, 0),
                false
        );
        return true;
    }

    private static boolean showTextCardPreview(Activity activity, View root, int rawX, int rawY, boolean focusedOnly) {
        try {
            hidePreview();

            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) {
                return false;
            }

            String textPreview = focusedOnly
                    ? RedditComposeFocusBridge.getFocusedPostModelTextPreview(root)
                    : RedditComposeFocusBridge.getPostModelTextPreviewAt(root, rawX, rawY);
            String postUrl = focusedOnly
                    ? RedditComposeFocusBridge.getFocusedPostEmbedUrl(root)
                    : RedditComposeFocusBridge.getPostEmbedUrlAt(root, rawX, rawY);
            if (focusedOnly && textPreview == null) {
                textPreview = RedditComposeFocusBridge.getPostModelTextPreviewAt(root, rawX, rawY);
            }
            if (textPreview == null) {
                textPreview = RedditComposeFocusBridge.getPostTextPreviewAt(root, rawX, rawY);
            }
            if (textPreview == null || textPreview.trim().length() == 0) {
                textPreview = RedditComposeFocusBridge.getFocusedPostTextPreview(root);
            }
            if (textPreview == null || textPreview.trim().length() == 0) {
                textPreview = RedditComposeFocusBridge.getCachedTextPreviewForPostUrl(postUrl);
            }
            if (textPreview == null || textPreview.trim().length() == 0) {
                textPreview = postUrl != null && postUrl.length() > 0 ? "Loading post text..." : null;
            }
            if (textPreview == null || textPreview.trim().length() == 0) {
                Log.i(LOG_TAG, "no selected post text for card");
                return false;
            }

            ViewGroup decor = (ViewGroup) decorView;
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(Color.argb(230, 0, 0, 0));
            configurePreviewOverlay(overlay);

            int horizontalPadding = Math.max(dp(root, 12), root.getWidth() / 30);
            int verticalPadding = Math.max(dp(root, 12), root.getHeight() / 30);
            overlay.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

            View textView = RedditComposeFocusBridge.createTextCardPreviewView(activity, textPreview);
            attachPreviewDismissHandlers(textView);
            overlay.addView(textView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
            ));
            decor.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            activePreview = overlay;
            rememberPreviewFocus(root, rawX, rawY);
            overlay.requestFocus();
            maybeUpgradeTextPreview(postUrl, textView, PREVIEW_GENERATION.get(), true);
            return true;
        } catch (Throwable throwable) {
            Logger.printException(() -> "Failed to show Reddit text card preview", throwable);
            return false;
        }
    }

    private static void maybeUpgradeTextPreview(String postUrl, View textView, int generation) {
        maybeUpgradeTextPreview(postUrl, textView, generation, false);
    }

    private static void maybeUpgradeTextPreview(String postUrl, View textView, int generation, boolean forceFresh) {
        if (postUrl == null || postUrl.length() == 0 || textView == null) {
            return;
        }
        IMAGE_LOADER.execute(() -> {
            String fetched = null;
            for (int attempt = 0; attempt < 18; attempt++) {
                fetched = RedditComposeFocusBridge.getCachedTextPreviewForPostUrl(postUrl);
                if (fetched != null && fetched.trim().length() > 0) {
                    break;
                }
                if (attempt == 0) {
                    fetched = forceFresh
                            ? RedditComposeFocusBridge.fetchFreshTextPreviewForPostUrl(postUrl)
                            : RedditComposeFocusBridge.fetchTextPreviewForPostUrl(postUrl);
                    if (fetched != null && fetched.trim().length() > 0) {
                        break;
                    }
                }
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            if (fetched == null || fetched.trim().length() == 0) {
                return;
            }
            String finalFetched = fetched;
            MAIN_HANDLER.post(() -> {
                if (activePreview == null || PREVIEW_GENERATION.get() != generation) {
                    return;
                }
                RedditComposeFocusBridge.updateTextPreviewView(textView, finalFetched);
            });
        });
    }

    private static boolean handleWebPostModalKey(Activity activity, KeyEvent event) {
        int action = event.getAction();
        if (action == KeyEvent.ACTION_UP) {
            hidePreview(activity);
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN || activePreview != null) {
            return true;
        }

        View root = activity.getWindow().getDecorView();
        int[] point = RedditComposeFocusBridge.getFocusedPostPreviewPoint(root);
        if (point != null && showPostEmbedPreview(activity, root, point[0], point[1], true)) {
            return true;
        }

        int[] location = new int[2];
        root.getLocationOnScreen(location);
        showPostEmbedPreview(
                activity,
                root,
                location[0] + ((root.getWidth() * 3) / 4),
                updatePreviewTargetY(root, 0),
                false
        );
        return true;
    }

    private static boolean showPostEmbedPreview(Activity activity, View root, int rawX, int rawY, boolean focusedOnly) {
        try {
            hidePreview();

            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof ViewGroup)) {
                return false;
            }

            String postUrl = focusedOnly
                    ? RedditComposeFocusBridge.getFocusedPostEmbedUrl(root)
                    : RedditComposeFocusBridge.getPostEmbedUrlAt(root, rawX, rawY);
            if (postUrl == null || postUrl.length() == 0) {
                int[] postPoint = RedditComposeFocusBridge.getPostPreviewPointAt(root, rawX, rawY);
                if (!focusedOnly && postPoint != null) {
                    postUrl = RedditComposeFocusBridge.getPostEmbedUrlAt(root, postPoint[0], postPoint[1]);
                }
            }
            if (postUrl == null || postUrl.length() == 0) {
                Log.i(LOG_TAG, "no reddit post url for modal");
                return false;
            }

            ViewGroup decor = (ViewGroup) decorView;
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(Color.argb(230, 0, 0, 0));
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setFocusableInTouchMode(false);

            int horizontalPadding = Math.max(dp(root, 10), root.getWidth() / 28);
            int verticalPadding = Math.max(dp(root, 10), root.getHeight() / 24);
            overlay.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            int minModalHeight = Math.max(dp(root, 220), root.getHeight() / 4);
            int maxModalHeight = root.getHeight() - (verticalPadding * 2);
            View modalView = RedditComposeFocusBridge.createPostEmbedView(
                    activity,
                    postUrl,
                    minModalHeight,
                    maxModalHeight
            );
            modalView.setFocusable(false);
            modalView.setFocusableInTouchMode(false);
            overlay.addView(modalView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    minModalHeight,
                    Gravity.CENTER
            ));
            decor.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            activePreview = overlay;
            rememberPreviewFocus(root, rawX, rawY);
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to show Reddit post modal", ex);
            return false;
        }
    }

    private static boolean focusFeedContent(Activity activity, View root, int direction, int keyCode) {
        if (root == null) {
            return false;
        }

        if (isFeedFocus(root.findFocus(), root)) {
            return false;
        }

        if (!RedditComposeFocusBridge.resetComposeFocusForKey(root, keyCode)) {
            return false;
        }

        FEED_HANDOFF_DONE = true;
        return true;
    }

    private static boolean isFeedFocus(View view, View root) {
        return view != null && isGoodFeedFocusTarget(view, root);
    }

    private static boolean isGoodFeedFocusTarget(View view, View root) {
        if (!view.isFocusable() && !view.isFocusableInTouchMode() && !view.isClickable()) {
            return false;
        }

        return isPostDescription(view.getContentDescription());
    }

    private static boolean focusVisiblePostNode(View root, int direction) {
        AccessibilityFocusCandidate candidate = new AccessibilityFocusCandidate();
        AccessibilityNodeInfo node = null;
        try {
            node = root.createAccessibilityNodeInfo();
            collectFocusablePostNode(root, node, candidate, direction, 0);
            if (candidate.node == null) {
                return false;
            }

            return candidate.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    || candidate.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to focus Reddit feed post", ex);
            return false;
        } finally {
            if (candidate.node != null) {
                candidate.node.recycle();
            }
        }
    }

    private static void collectFocusablePostNode(
            View root,
            AccessibilityNodeInfo node,
            AccessibilityFocusCandidate candidate,
            int direction,
            int depth
    ) {
        if (node == null || depth > MAX_ACCESSIBILITY_NODE_DEPTH) {
            return;
        }

        boolean keepNode = false;
        try {
            CharSequence description = node.getContentDescription();
            if (isPostDescription(description)) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                int score = scorePostFocusCandidate(root, bounds, direction);
                if (score > candidate.score) {
                    if (candidate.node != null) {
                        candidate.node.recycle();
                    }
                    candidate.node = node;
                    candidate.score = score;
                    keepNode = true;
                }
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                } catch (Throwable ignored) {
                }
                collectFocusablePostNode(root, child, candidate, direction, depth + 1);
            }
        } finally {
            if (!keepNode && candidate.node != node) {
                node.recycle();
            }
        }
    }

    private static int scorePostFocusCandidate(View root, Rect bounds, int direction) {
        if (bounds.isEmpty()) {
            return Integer.MIN_VALUE;
        }

        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        int relativeCenterY = bounds.centerY() - rootLocation[1];
        int topLimit = dp(root, 96);
        int bottomLimit = root.getHeight() - dp(root, 96);
        if (relativeCenterY < topLimit || relativeCenterY > bottomLimit) {
            return Integer.MIN_VALUE;
        }

        int targetY = direction == View.FOCUS_UP ? bottomLimit : topLimit;
        int distance = Math.abs(relativeCenterY - targetY);
        return 100000 - distance + Math.min(bounds.height(), root.getHeight());
    }

    private static void synthesizeFeedFocusTouch(View root, int direction) {
        try {
            float x = root.getWidth() * 0.5f;
            float yRatio = direction == View.FOCUS_UP ? 0.72f : 0.32f;
            float y = clamp(
                    Math.round(root.getHeight() * yRatio),
                    dp(root, 128),
                    root.getHeight() - dp(root, 128)
            );
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent cancel = MotionEvent.obtain(now, now + 8, MotionEvent.ACTION_CANCEL, x, y, 0);
            try {
                root.dispatchTouchEvent(down);
                root.dispatchTouchEvent(cancel);
            } finally {
                down.recycle();
                cancel.recycle();
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to synthesize Reddit feed focus touch", ex);
        }
    }

    private static void collectBottomRightNavCandidate(View view, int minBottom, Object[] out, int[] bestScore) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return;
        }

        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int left = location[0];
        int bottom = location[1] + height;
        if (bottom >= minBottom && left >= 1000 && (view.isFocusable() || view.isClickable())) {
            int score = (bottom * 10000) + left + width;
            if (score > bestScore[0]) {
                bestScore[0] = score;
                out[0] = view;
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                collectBottomRightNavCandidate(group.getChildAt(i), minBottom, out, bestScore);
            }
        }
    }

    private static boolean focusBottomRightNav(View root) {
        if (root == null) {
            return DEBUG_LOGS;
        }

        Object[] out = new Object[1];
        collectBottomRightNavCandidate(
                root,
                root.getHeight() - dp(root, 160),
                out,
                new int[]{Integer.MIN_VALUE}
        );
        Object candidate = out[0];
        return candidate instanceof View && ((View) candidate).requestFocus();
    }

    private static void scheduleFeedKey(Activity activity, int keyCode, long delayMillis) {
        MAIN_HANDLER.postDelayed(new FeedKeyRunnable(activity, keyCode), delayMillis);
    }

    private static void synthesizeBottomRightTabHandoff(Activity activity, View root, int keyCode) {
        FEED_HANDOFF_DONE = true;
        RedditKeyInjector.handoff(activity, keyCode);
    }

    private static int updatePreviewTargetY(View root, int direction) {
        if (root == null) {
            return 0;
        }

        ArrayList<DescriptionBounds> descriptions = new ArrayList<>();
        collectPostDescriptions(root, descriptions);
        collectAccessibilityPostDescriptions(root, descriptions);

        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        int targetY = LAST_PREVIEW_Y > 0 ? LAST_PREVIEW_Y : rootLocation[1] + root.getHeight() / 3;
        int tolerance = dp(root, 24);
        int bestY = 0;
        int bestDistance = Integer.MAX_VALUE;

        for (DescriptionBounds item : descriptions) {
            int centerY = item.bounds.centerY();
            int distance;
            if (direction > 0) {
                distance = centerY - targetY;
                if (distance <= tolerance) {
                    continue;
                }
            } else if (direction < 0) {
                distance = targetY - centerY;
                if (distance <= tolerance) {
                    continue;
                }
            } else {
                distance = Math.abs(centerY - targetY);
            }

            if (distance < bestDistance) {
                bestDistance = distance;
                bestY = centerY;
            }
        }

        if (bestY <= 0) {
            bestY = targetY;
        }
        LAST_PREVIEW_Y = bestY;
        return bestY;
    }

    public static void redispatchFeedKey(Activity activity, int keyCode) {
        long now = SystemClock.uptimeMillis();
        REDISPATCHING_FEED_KEY = true;
        try {
            activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            activity.dispatchKeyEvent(new KeyEvent(now, now + 8, KeyEvent.ACTION_UP, keyCode, 0));
        } finally {
            REDISPATCHING_FEED_KEY = false;
        }
    }

    public static final class FeedKeyRunnable implements Runnable {
        private final Activity activity;
        private final int keyCode;

        FeedKeyRunnable(Activity activity, int keyCode) {
            this.activity = activity;
            this.keyCode = keyCode;
        }

        @Override
        public void run() {
            LongPressImagePreviewPatch.redispatchFeedKey(activity, keyCode);
        }
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
            if (handleKeyboardFeedFocusKey(activity, event)) {
                return true;
            }
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
        boolean previewShown;
        boolean nativePostShown;

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

    private static final class DescriptionBounds {
        final CharSequence description;
        final Rect bounds;

        DescriptionBounds(CharSequence description, Rect bounds) {
            this.description = description;
            this.bounds = bounds;
        }
    }

    private static final class AccessibilityFocusCandidate {
        AccessibilityNodeInfo node;
        int score = Integer.MIN_VALUE;
    }
}
