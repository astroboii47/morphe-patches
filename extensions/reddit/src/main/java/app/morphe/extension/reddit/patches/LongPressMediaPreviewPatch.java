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
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.view.accessibility.AccessibilityNodeProvider;

/** Shows a feed image at full size while the image itself is held. */
@SuppressWarnings("unused")
public final class LongPressMediaPreviewPatch {
    private static final int MAX_MEDIA = 160;
    private static final int MAX_EXTERNAL_HTML_BYTES = 512 * 1024;
    // Android refuses to draw very large bitmaps. This still exceeds any current phone display
    // while preventing a full-resolution source from crashing the Reddit process.
    private static final int MAX_PREVIEW_DIMENSION = 2560;
    private static final long MAX_PREVIEW_PIXELS = 10_000_000L;
    private static final String LOG_TAG = "MorpheMediaPreview";
    private static final String IMAGE_TAG = "feed_media_content_self_image_";
    private static final String LINK_THUMBNAIL_TAG = "feed_post_title_with_thumbnail_";
    private static final String LINK_PREVIEW_CARD_TAG = "feed_link_preview_card_";
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
    private static final Map<String, String> LINK_ID_BY_TITLE = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(MAX_MEDIA + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_MEDIA;
                }
            }
    );
    private static final Map<String, String> OUTBOUND_URL_BY_LINK_ID = Collections.synchronizedMap(
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
        try {
            ensureWindowCallback(activity);
            // Reddit replaces its window callback during its own startup. Reattach after that
            // setup completes so feed touch events reach the preview handler.
            MAIN.postDelayed(() -> {
                if (!activity.isFinishing()) ensureWindowCallback(activity);
            }, 1_000L);
        } catch (Throwable error) {
            Log.e(LOG_TAG, "attach failed", error);
        }
    }

    private static void ensureWindowCallback(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        Window.Callback current = window.getCallback();
        if (current instanceof PreviewWindowCallback) {
            CALLBACKS.put(activity, (PreviewWindowCallback) current);
            return;
        }
        PreviewWindowCallback callback = new PreviewWindowCallback(activity, current);
        window.setCallback(callback);
        CALLBACKS.put(activity, callback);
        Log.i(LOG_TAG, "touch callback attached to " + activity.getClass().getName());
    }

    /** Injection point from Reddit's self-image feed model. */
    public static void registerImageModel(Object model) {
        if (model == null) return;
        Object data = readField(model, "a");
        if (data == null) return;
        String linkId = readStringField(data, "e");
        Object primary = readField(data, "i");
        Object fallback = readField(data, "k");
        String url = firstImageUrl(primary, fallback);
        Log.i(LOG_TAG, "self media link=" + linkId + " primary="
                + (primary == null ? "null" : primary.getClass().getName())
                + " fallback=" + (fallback == null ? "null" : fallback.getClass().getName())
                + " url=" + (url != null));
        if (linkId != null && url != null) {
            cacheImage(linkId, url);
            Log.i(LOG_TAG, "cached self image " + linkId);
        }
    }

    /** Injection point from Reddit's feed title-with-thumbnail section. */
    public static void registerLinkThumbnailModel(Object model) {
        if (model == null) return;
        String linkId = readStringField(model, "a");
        String title = readStringField(model, "b");
        // b240.f is a sealed media-content variant (image/video/gallery), not the
        // thumbnail itself. Every rendered variant carries its source in i: wd7,
        // whose a/b fields are the usable media URLs.
        Object mediaContent = readField(model, "f");
        Object source = readField(mediaContent, "i");
        String url = firstImageUrl(source);
        Log.i(LOG_TAG, "compact media link=" + linkId + " type="
                + (mediaContent == null ? "null" : mediaContent.getClass().getName())
                + " source=" + (source == null ? "null" : source.getClass().getName())
                + " url=" + (url != null));
        if (linkId != null && title != null) LINK_ID_BY_TITLE.put(title, linkId);
        if (linkId != null && url != null) {
            cacheImage(linkId, url);
            Log.i(LOG_TAG, "cached thumbnail " + linkId);
        }
    }

    /** Injection point from Reddit v40's normal compact linked-preview title section. */
    public static void registerLinkedPreviewModel(Object model) {
        if (model == null) return;
        String linkId = readStringField(model, "a");
        String title = readStringField(model, "b");
        Object linkMeta = readField(model, "f");
        String url = firstImageUrl(linkMeta);
        if (url == null) url = readValue(readField(linkMeta, "d"));
        if (url == null) url = readValue(readField(linkMeta, "h"));
        if (linkId != null && title != null) LINK_ID_BY_TITLE.put(title, linkId);
        if (linkId != null && isImageUrl(url)) {
            cacheImage(linkId, url);
            Log.i(LOG_TAG, "cached linked preview " + linkId);
        } else {
            Log.i(LOG_TAG, "linked preview media=false link=" + linkId + " meta="
                    + (linkMeta == null ? "null" : linkMeta.getClass().getName()));
        }
    }

    /** Injection point from Reddit's gallery feed section. */
    public static void registerGalleryModel(Object model) {
        if (model == null) return;
        Object gallery = readField(model, "a");
        String linkId = readStringField(gallery, "e");
        String title = readStringField(gallery, "f");
        Object items = readField(gallery, "j");
        Object firstItem = items instanceof List && !((List<?>) items).isEmpty()
                ? ((List<?>) items).get(0) : null;
        Object source = readField(firstItem, "a");
        String url = firstImageUrl(source, readField(firstItem, "b"));
        if (linkId != null && title != null) LINK_ID_BY_TITLE.put(title, linkId);
        if (linkId != null && isImageUrl(url)) {
            cacheImage(linkId, url);
            Log.i(LOG_TAG, "cached gallery " + linkId);
        } else {
            Log.i(LOG_TAG, "gallery media=false link=" + linkId);
        }
    }

    /** Diagnostic reader for Reddit's real per-row section list. */
    public static void registerPostUnitModel(Object model) {
        if (model == null) return;
        String linkId = readStringField(model, "a");
        Object sections = readField(model, "b");
        if (sections instanceof List) {
            StringBuilder types = new StringBuilder();
            for (Object section : (List<?>) sections) {
                if (section == null) continue;
                if (types.length() > 0) types.append(',');
                types.append(section.getClass().getName());
            }
            Log.i(LOG_TAG, "post unit=" + linkId + " sections=" + types);
        }
    }

    /** Injection point from Reddit's feed title section. */
    public static void registerTitleModel(Object model) {
        if (model == null) return;
        String linkId = readStringField(model, "e");
        String title = Boolean.TRUE.equals(readField(model, "k"))
                ? readStringField(model, "j")
                : readStringField(model, "i");
        if (title == null) title = readStringField(model, "i");
        if (linkId != null && title != null) {
            LINK_ID_BY_TITLE.put(title, linkId);
            Log.i(LOG_TAG, "cached title " + linkId + " title=" + title);
        }
    }

    /** Injection point from Reddit v40's active compact LinkPreviewCardData model. */
    public static void registerLinkPreviewCardModel(Object model) {
        if (model == null) return;
        // The visible section wraps the card data in field "a". Accept either form so this
        // retained injection point can refresh media whenever the Compose card is rendered.
        Object candidate = readField(model, "a");
        if (candidate != null && !(candidate instanceof String)) model = candidate;
        // Link cards keep the post identifier as ParcelablePostIdentifier in b. Field a is
        // presentation data, so indexing by it made valid previews unreachable from the held row.
        String linkId = readValue(readField(model, "b"));
        String title = readStringField(model, "e");
        String url = readStringField(readField(model, "l"), "a");
        if (linkId != null && title != null) LINK_ID_BY_TITLE.put(title, linkId);
        if (linkId != null && isImageUrl(url)) {
            cacheImage(linkId, url);
            Log.i(LOG_TAG, "cached link preview " + linkId);
        } else {
            Log.i(LOG_TAG, "cached link title " + linkId + " media=false");
        }
    }

    /**
     * Runs at the Compose renderer that is proven to receive every visible link card. The card's
     * post identifier is its ParcelablePostIdentifier (field b), rather than field a.
     */
    public static void registerLinkPreviewCardRenderModel(Object model) {
        if (model == null) return;
        String linkId = readValue(readField(model, "b"));
        String title = readStringField(model, "e");
        if (linkId != null && title != null) LINK_ID_BY_TITLE.put(title, linkId);

        String[] candidates = {
                readStringField(model, "a"), readStringField(model, "i"),
                readStringField(model, "j"), readStringField(model, "m"),
                readStringField(readField(model, "l"), "a")
        };
        String url = null;
        for (String candidate : candidates) {
            if (isImageUrl(candidate)) {
                url = candidate;
                break;
            }
        }
        Log.i(LOG_TAG, "rendered link card id=" + linkId + " title=" + title
                + " media=" + (url != null) + " fields="
                + (candidates[0] != null) + "," + (candidates[1] != null) + ","
                + (candidates[2] != null) + "," + (candidates[3] != null) + ","
                + (candidates[4] != null));
        if (linkId != null && url != null) {
            cacheImage(linkId, url);
            Log.i(LOG_TAG, "cached rendered link media " + linkId);
        }
    }

    /** Captures compact link thumbnails before Compose renders the card. */
    public static void registerCompactLinkMediaModel(Object model) {
        if (model == null) return;
        // r140 inherits Reddit's stable getLinkId() contract. Its h field is optional and is
        // null on the normal compact-link rows currently displayed in the feed.
        String linkId = stringResult(model, "getLinkId");
        String title = readStringField(model, "f");
        String url = firstImageUrl(readField(model, "i"));
        if (linkId != null && title != null) LINK_ID_BY_TITLE.put(title, linkId);
        if (linkId != null && url != null) {
            cacheImage(linkId, url);
            Log.i(LOG_TAG, "cached compact link media " + linkId);
        } else {
            Log.i(LOG_TAG, "compact link media unavailable id=" + linkId);
        }
    }

    /** Records real post media while Reddit maps its full network Link into a feed row. */
    public static void registerDomainLink(Object link) {
        if (link == null) return;
        // This method is invoked from Link.getKindWithId itself, so calling the getter here
        // would recurse through the injected hook. Read its immutable backing field instead.
        String linkId = readStringField(link, "kindWithId");
        if (linkId == null || !linkId.startsWith("t3_")) return;

        String title = stringResult(link, "getTitle");
        if (title != null) LINK_ID_BY_TITLE.put(title, linkId);
        String outboundUrl = stringResult(link, "getUrl");
        if (isExternalPageUrl(outboundUrl)) OUTBOUND_URL_BY_LINK_ID.put(linkId, outboundUrl);

        String url = previewImageUrl(readField(link, "preview"));
        if (url == null) url = stringResult(readField(link, "thumbnailImage"), "getUrl");
        if (url != null && isImageUrl(url)) {
            if (cacheImage(linkId, url)) {
                Log.i(LOG_TAG, "cached domain preview " + linkId);
            }
        } else {
            Log.i(LOG_TAG, "domain preview unavailable " + linkId);
        }
    }

    /** Captures media from the fully assembled feed row Reddit actually renders. */
    public static void registerFeedRow(Object row) {
        if (row == null) return;
        Object title = readField(row, "i");
        String linkId = readStringField(title, "e");
        if (linkId == null || !linkId.startsWith("t3_")) return;
        String postTitle = readStringField(title, "i");
        if (postTitle != null) LINK_ID_BY_TITLE.put(postTitle, linkId);

        Object metadata = readField(row, "l");
        Object directMedia = readField(metadata, "D");
        String url = firstImageUrl(invokeNoArgs(directMedia, "h"));

        if (url == null) {
            Object items = readField(row, "j");
            if (items instanceof List && !((List<?>) items).isEmpty()) {
                Object first = ((List<?>) items).get(0);
                url = firstImageUrl(readField(first, "a"), readField(first, "b"));
            }
        }
        if (url != null) {
            cacheImage(linkId, url);
            Log.i(LOG_TAG, "cached assembled feed media " + linkId);
        } else {
            Log.i(LOG_TAG, "assembled feed media unavailable " + linkId);
        }
    }

    private static String previewImageUrl(Object preview) {
        Object images = invokeNoArgs(preview, "getImages");
        if (!(images instanceof List) || ((List<?>) images).isEmpty()) return null;
        Object image = ((List<?>) images).get(0);
        String source = stringResult(invokeNoArgs(image, "getSource"), "getUrl");
        if (isImageUrl(source)) return source;
        Object resolutions = invokeNoArgs(image, "getResolutions");
        if (resolutions instanceof List) {
            List<?> list = (List<?>) resolutions;
            for (int index = list.size() - 1; index >= 0; index--) {
                String candidate = stringResult(list.get(index), "getUrl");
                if (isImageUrl(candidate)) return candidate;
            }
        }
        return null;
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
                    Log.i(LOG_TAG, "touch down x=" + event.getRawX() + " y=" + event.getRawY());
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
            String outboundUrl = linkId == null ? null : OUTBOUND_URL_BY_LINK_ID.get(linkId);
            Log.i(LOG_TAG, "hold resolved linkId=" + linkId + " url=" + (url != null));
            if (url == null) return;
            showPreview(root, url, outboundUrl);
            state.previewShown = true;
            cancelUnderlyingLongPress(root, state.x, state.y);
        }

        private void showPreview(View root, String url, String outboundUrl) {
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
            Log.i(LOG_TAG, "preview overlay shown");
            IMAGES.execute(() -> {
                final long[] pixels = {0L};
                loadImage(url, bitmap -> {
                    if (bitmap != null) pixels[0] = bitmapPixels(bitmap);
                    MAIN.post(() -> {
                        Log.i(LOG_TAG, "preview image loaded=" + (bitmap != null));
                        if (preview == overlay && bitmap != null) image.setImageBitmap(bitmap);
                    });
                });
                String externalImage = findExternalPreviewImage(outboundUrl);
                if (externalImage == null) return;
                loadImage(externalImage, bitmap -> MAIN.post(() -> {
                    if (preview == overlay && bitmap != null && bitmapPixels(bitmap) > pixels[0]) {
                        image.setImageBitmap(bitmap);
                        Log.i(LOG_TAG, "upgraded external preview image");
                    }
                }));
            });
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
            connection = openImageConnection(url);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = connection.getInputStream()) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            connection.disconnect();
            connection = openImageConnection(url);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight);
            try (InputStream stream = connection.getInputStream()) {
                consumer.accept(BitmapFactory.decodeStream(stream, null, options));
            }
        } catch (Exception ignored) {
            consumer.accept(null);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection openImageConnection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static int imageSampleSize(int width, int height) {
        int sample = 1;
        while (width / sample > MAX_PREVIEW_DIMENSION || height / sample > MAX_PREVIEW_DIMENSION
                || ((long) width / sample) * ((long) height / sample) > MAX_PREVIEW_PIXELS) {
            sample *= 2;
        }
        return sample;
    }

    private static long bitmapPixels(Bitmap bitmap) {
        return (long) bitmap.getWidth() * bitmap.getHeight();
    }

    private static boolean isExternalPageUrl(String value) {
        return value != null && value.startsWith("https://") && !value.contains("reddit.com/")
                && !value.contains("redd.it/");
    }

    private static String findExternalPreviewImage(String pageUrl) {
        if (!isExternalPageUrl(pageUrl)) return null;
        HttpURLConnection connection = null;
        try {
            connection = openImageConnection(pageUrl);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36");
            byte[] body;
            try (InputStream stream = connection.getInputStream()) {
                body = readLimited(stream, MAX_EXTERNAL_HTML_BYTES);
            }
            String html = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            String imageUrl = metaImageUrl(html, pageUrl, "og:image");
            if (imageUrl == null) imageUrl = metaImageUrl(html, pageUrl, "twitter:image");
            return imageUrl;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int remaining = limit;
        while (remaining > 0) {
            int count = stream.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) break;
            output.write(buffer, 0, count);
            remaining -= count;
        }
        return output.toByteArray();
    }

    private static String metaImageUrl(String html, String pageUrl, String name) {
        Pattern tagPattern = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Matcher tags = tagPattern.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            if (!tag.matches("(?is).*\\b(?:property|name)\\s*=\\s*['\"]?" + Pattern.quote(name) + "['\"]?.*")) continue;
            Matcher content = Pattern.compile("(?is)\\bcontent\\s*=\\s*(['\"])(.*?)\\1").matcher(tag);
            if (!content.find()) continue;
            try {
                String value = new URI(pageUrl).resolve(content.group(2)).toString();
                if (value.startsWith("https://")) return value;
            } catch (Exception ignored) {
                // Ignore malformed publisher metadata and retain Reddit's preview.
            }
        }
        return null;
    }

    private static String findImageLinkId(View root, int x, int y) {
        return findImageLinkId(root, x, y, 0);
    }

    private static String findImageLinkId(View view, int x, int y, int depth) {
        // Reddit 2026.40 nests the Compose feed more than twelve Android views below the
        // activity decor on some devices. Stop at a defensive ceiling, not before the feed.
        if (view == null || depth > 40 || !view.isShown()) return null;
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        if (x < position[0] || y < position[1] || x >= position[0] + view.getWidth() || y >= position[1] + view.getHeight()) return null;
        Log.i(LOG_TAG, "hit view depth=" + depth + " class=" + view.getClass().getName());
        String linkId = findLinkIdInComposeSemantics(view, x, y);
        if (linkId != null) return linkId;
        android.view.accessibility.AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        linkId = findLinkIdByNodeResource(node, x, y, 0);
        if (linkId != null) {
            if (node != null) node.recycle();
            return linkId;
        }
        linkId = findLinkIdByVisiblePostRow(node, x, y);
        if (node != null) node.recycle();
        if (linkId != null) return linkId;
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) {
            Log.i(LOG_TAG, "provider=" + provider.getClass().getName());
            android.view.accessibility.AccessibilityNodeInfo providerRoot =
                    provider.createAccessibilityNodeInfo(View.NO_ID);
            Log.i(LOG_TAG, "provider root children=" +
                    (providerRoot == null ? -1 : providerRoot.getChildCount()));
            try {
                linkId = findLinkIdByNodeResource(providerRoot, x, y, 0);
                if (linkId != null) return linkId;
            } finally {
                if (providerRoot != null) providerRoot.recycle();
            }
        }
        linkId = findLinkIdByProviderText(provider, x, y);
        if (linkId != null) return linkId;
        linkId = findLinkIdInProviderTree(provider, x, y);
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

    /**
     * Reddit's feed is Compose. The Android provider deliberately does not implement text
     * searches, but the Compose view exposes its own SemanticsOwner. Reading that tree gives us
     * the exact TestTag and rendered bounds for the held media, without guessing which post is
     * nearby.
     */
    private static String findLinkIdInComposeSemantics(View view, int screenX, int screenY) {
        if (!"androidx.compose.ui.platform.c".equals(view.getClass().getName())) return null;
        try {
            Object owner = view.getClass().getMethod("getSemanticsOwner").invoke(view);
            if (owner == null) return null;
            Object root = owner.getClass().getMethod("a").invoke(owner);
            if (root == null) return null;
            Class<?> nodeClass = root.getClass();
            Method children = nodeClass.getMethod("i", boolean.class, boolean.class);
            Method config = nodeClass.getMethod("k");
            Method bounds = nodeClass.getMethod("g");
            Class<?> configClass = config.getReturnType();
            Class<?> keyClass = Class.forName("androidx.compose.ui.semantics.g");
            Object tagKey = Class.forName("androidx.compose.ui.semantics.d")
                    .getField("A").get(null);
            Object descriptionKey = Class.forName("androidx.compose.ui.semantics.d")
                    .getField("a").get(null);
            Method getConfigValue = configClass.getMethod("c", keyClass);
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            return findLinkIdInComposeNode(
                    root, children, config, bounds, getConfigValue, tagKey, descriptionKey,
                    screenX - location[0], screenY - location[1], new HashSet<>(), 0
            );
        } catch (Throwable error) {
            Log.e(LOG_TAG, "compose semantics unavailable", error);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static String findLinkIdInComposeNode(
            Object node,
            Method children,
            Method config,
            Method bounds,
            Method getConfigValue,
            Object tagKey,
            Object descriptionKey,
            float x,
            float y,
            Set<Integer> visited,
            int depth
    ) throws Exception {
        if (node == null || depth > 80 || !visited.add(System.identityHashCode(node))) return null;
        Object rect = bounds.invoke(node);
        Object semantics = config.invoke(node);
        Object value = null;
        Object description = null;
        if (semantics != null) {
            try {
                // SemanticsConfiguration.c intentionally throws when a node simply has no tag.
                value = getConfigValue.invoke(semantics, tagKey);
            } catch (java.lang.reflect.InvocationTargetException ignored) {
                // Continue into the node's children.
            }
            try {
                description = getConfigValue.invoke(semantics, descriptionKey);
            } catch (java.lang.reflect.InvocationTargetException ignored) {
                // Not every semantics node has a description.
            }
        }
        String mediaId = linkIdFromMediaTag(value instanceof String ? (String) value : null);
        if (rect != null) {
            Field left = rect.getClass().getField("a");
            Field top = rect.getClass().getField("b");
            Field right = rect.getClass().getField("c");
            Field bottom = rect.getClass().getField("d");
            float l = left.getFloat(rect), t = top.getFloat(rect);
            float r = right.getFloat(rect), b = bottom.getFloat(rect);
            if (mediaId != null) {
                Log.i(LOG_TAG, "Compose media bounds=" + l + "," + t + "," + r + "," + b + " tag=" + value);
                if (x >= l && x < r && y >= t && y < b) {
                    Log.i(LOG_TAG, "matched Compose media tag=" + value);
                    return mediaId;
                }
            }
            if (x >= l && x < r && y >= t && y < b) {
                Log.i(LOG_TAG, "Compose hit bounds=" + l + "," + t + "," + r + "," + b
                        + " desc=" + String.valueOf(description));
                String linkId = linkIdForSemanticsDescription(description);
                if (linkId != null) {
                    Log.i(LOG_TAG, "matched Compose post row=" + linkId);
                    return linkId;
                }
            }
        }
        List<Object> childNodes = (List<Object>) children.invoke(node, false, true);
        if (childNodes == null) return null;
        for (Object child : childNodes) {
            String linkId = findLinkIdInComposeNode(
                    child, children, config, bounds, getConfigValue, tagKey, descriptionKey,
                    x, y, visited, depth + 1
            );
            if (linkId != null) return linkId;
        }
        return null;
    }

    /**
     * Compose exposes the exact feed-media ID as a TestTag resource name. Android's normal
     * child API preserves that name, while the provider's search APIs do not on Android 16.
     */
    private static String findLinkIdByNodeResource(
            android.view.accessibility.AccessibilityNodeInfo node, int x, int y, int depth
    ) {
        if (node == null || depth > 80) return null;
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty() && !bounds.contains(x, y)) return null;
        String resource = node.getViewIdResourceName();
        String linkId = linkIdFromMediaTag(resource);
        if (linkId != null) {
            Log.i(LOG_TAG, "matched media test tag=" + resource);
            return linkId;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            android.view.accessibility.AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(index);
                linkId = findLinkIdByNodeResource(child, x, y, depth + 1);
                if (linkId != null) return linkId;
            } catch (RuntimeException ignored) {
                // Some Compose virtual nodes are recycled during a recomposition.
            } finally {
                if (child != null) child.recycle();
            }
        }
        return null;
    }

    private static String linkIdFromMediaTag(String resource) {
        if (resource == null) return null;
        int marker = resource.indexOf(IMAGE_TAG);
        if (marker < 0) marker = resource.indexOf(LINK_THUMBNAIL_TAG);
        if (marker < 0) marker = resource.indexOf(LINK_PREVIEW_CARD_TAG);
        if (marker < 0) return null;
        int start = marker + (resource.startsWith(IMAGE_TAG, marker)
                ? IMAGE_TAG.length() : resource.startsWith(LINK_THUMBNAIL_TAG, marker)
                ? LINK_THUMBNAIL_TAG.length() : LINK_PREVIEW_CARD_TAG.length());
        String candidate = resource.substring(start);
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0) candidate = candidate.substring(slash + 1);
        return candidate.startsWith("t3_") ? candidate : null;
    }

    private static String findLinkIdByVisiblePostRow(
            android.view.accessibility.AccessibilityNodeInfo root, int x, int y
    ) {
        // `post_unit` is Reddit's real Compose accessibility row. It spans both the post text
        // and its thumbnail, so resolving its descriptive child identifies the held image/link
        // without inferring a row from screen position or opening the outbound link.
        List<android.view.accessibility.AccessibilityNodeInfo> postRows;
        try {
            postRows = root == null ? null : root.findAccessibilityNodeInfosByViewId("post_unit");
        } catch (RuntimeException ignored) {
            postRows = null;
        }
        if (postRows != null) {
            android.graphics.Rect rowBounds = new android.graphics.Rect();
            for (android.view.accessibility.AccessibilityNodeInfo row : postRows) {
                if (row == null) continue;
                try {
                    row.getBoundsInScreen(rowBounds);
                    if (!rowBounds.contains(x, y)) continue;
                    // This is the same descriptive child exported to TalkBack/UI Automator,
                    // and avoids recursively decoding Compose's virtual-node graph.
                    List<android.view.accessibility.AccessibilityNodeInfo> details =
                            row.findAccessibilityNodeInfosByText("Posted");
                    if (details != null) {
                        for (android.view.accessibility.AccessibilityNodeInfo detail : details) {
                            if (detail == null) continue;
                            try {
                                String linkId = linkIdForTitle(
                                        String.valueOf(detail.getContentDescription())
                                );
                                if (linkId == null) {
                                    linkId = linkIdForTitle(String.valueOf(detail.getText()));
                                }
                                if (linkId != null) return linkId;
                            } finally {
                                detail.recycle();
                            }
                        }
                    }
                    String linkId = linkIdForTitle(String.valueOf(row.getContentDescription()));
                    if (linkId == null) linkId = linkIdForTitle(String.valueOf(row.getText()));
                    if (linkId != null) return linkId;
                } finally {
                    row.recycle();
                }
            }
        }

        return null;
    }

    private static String findLinkIdByProviderText(
            AccessibilityNodeProvider provider, int x, int y
    ) {
        if (provider == null) return null;
        List<Map.Entry<String, String>> titles;
        synchronized (LINK_ID_BY_TITLE) {
            titles = new ArrayList<>(LINK_ID_BY_TITLE.entrySet());
        }
        android.graphics.Rect bounds = new android.graphics.Rect();
        // Rendered rows are the newest entries, so inspect only the recent slice of the bounded
        // cache. This is a direct provider query, never a recursive Compose tree walk.
        for (int i = titles.size() - 1, checked = 0; i >= 0 && checked < 24; i--, checked++) {
            Map.Entry<String, String> entry = titles.get(i);
            List<android.view.accessibility.AccessibilityNodeInfo> nodes;
            try {
                nodes = provider.findAccessibilityNodeInfosByText(
                        entry.getKey(), AccessibilityNodeProvider.HOST_VIEW_ID
                );
            } catch (RuntimeException ignored) {
                continue;
            }
            if (nodes == null) continue;
            for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                if (node == null) continue;
                try {
                    node.getBoundsInScreen(bounds);
                    CharSequence description = node.getContentDescription();
                    CharSequence text = node.getText();
                    if (bounds.contains(x, y) &&
                            ((description != null && description.toString().contains(entry.getKey())) ||
                                    (text != null && text.toString().contains(entry.getKey())))) {
                        return entry.getValue();
                    }
                } finally {
                    node.recycle();
                }
            }
        }
        return null;
    }

    private static String findLinkIdInProviderTree(
            AccessibilityNodeProvider provider, int x, int y
    ) {
        if (provider == null) return null;
        android.view.accessibility.AccessibilityNodeInfo root =
                provider.createAccessibilityNodeInfo(View.NO_ID);
        if (root == null) return null;
        try {
            return findLinkIdInProviderTree(root, provider, x, y, new HashSet<>(), 0);
        } finally {
            root.recycle();
        }
    }

    private static String findLinkIdInProviderTree(
            android.view.accessibility.AccessibilityNodeInfo node,
            AccessibilityNodeProvider provider,
            int x,
            int y,
            Set<Integer> visited,
            int depth
    ) {
        if (node == null || depth > 80) return null;
        android.graphics.Rect bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty() && !bounds.contains(x, y)) return null;
        String linkId = linkIdForTitle(String.valueOf(node.getContentDescription()));
        if (linkId == null) linkId = linkIdForTitle(String.valueOf(node.getText()));
        if (linkId != null) return linkId;
        for (int childId : childVirtualIds(node)) {
            if (childId == Integer.MIN_VALUE || !visited.add(childId)) continue;
            android.view.accessibility.AccessibilityNodeInfo child =
                    provider.createAccessibilityNodeInfo(childId);
            if (child == null) continue;
            try {
                linkId = findLinkIdInProviderTree(child, provider, x, y, visited, depth + 1);
                if (linkId != null) return linkId;
            } finally {
                child.recycle();
            }
        }
        return null;
    }

    /**
     * Compose exposes virtual children through the parcelled AccessibilityNodeInfo structure.
     * Android 16 blocks the hidden getChildId API, while this is the same public parcel data the
     * old working build used.
     */
    private static int[] childVirtualIds(android.view.accessibility.AccessibilityNodeInfo node) {
        Parcel parcel = Parcel.obtain();
        try {
            android.view.accessibility.AccessibilityNodeInfo.obtain(node).writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            long fields = parcel.readLong();
            if ((fields & (1L << 0)) != 0) parcel.readInt();
            if ((fields & (1L << 1)) != 0) parcel.readLong();
            if ((fields & (1L << 2)) != 0) parcel.readInt();
            if ((fields & (1L << 3)) != 0) parcel.readLong();
            if ((fields & (1L << 4)) != 0) parcel.readLong();
            if ((fields & (1L << 5)) != 0) parcel.readLong();
            if ((fields & (1L << 6)) != 0) {
                int count = parcel.readInt();
                for (int index = 0; index < count; index++) parcel.readLong();
            }
            if ((fields & (1L << 7)) != 0) parcel.readLong();
            if ((fields & (1L << 8)) != 0) parcel.readLong();
            if ((fields & (1L << 9)) != 0) parcel.readLong();
            if ((fields & (1L << 10)) != 0) parcel.readInt();
            if ((fields & (1L << 11)) == 0) return new int[0];
            int count = parcel.readInt();
            if (count <= 0 || count > 10_000) return new int[0];
            int[] result = new int[count];
            for (int index = 0; index < count; index++) {
                result[index] = (int) (parcel.readLong() >> 32);
            }
            return result;
        } catch (RuntimeException ignored) {
            return new int[0];
        } finally {
            parcel.recycle();
        }
    }



    private static String linkIdForTitle(String candidate) {
        if (candidate == null || "null".equals(candidate)) return null;
        synchronized (LINK_ID_BY_TITLE) {
            for (Map.Entry<String, String> entry : LINK_ID_BY_TITLE.entrySet()) {
                String title = entry.getKey();
                if (title != null && !title.isEmpty() && candidate.contains(title)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String linkIdForSemanticsDescription(Object description) {
        if (description instanceof List) {
            synchronized (LINK_ID_BY_TITLE) {
                for (Object part : (List<?>) description) {
                    if (!(part instanceof String)) continue;
                    String exact = LINK_ID_BY_TITLE.get(part);
                    if (exact != null) return exact;
                }
            }
        }
        return linkIdForTitle(String.valueOf(description));
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
        if (object == null) return null;
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                // Continue through the model's inherited fields.
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String readStringField(Object object, String name) {
        Object value = readField(object, name);
        return value instanceof String ? (String) value : null;
    }

    private static Object invokeNoArgs(Object object, String methodName) {
        if (object == null) return null;
        try {
            return object.getClass().getMethod(methodName).invoke(object);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stringResult(Object object, String methodName) {
        Object result = invokeNoArgs(object, methodName);
        return result instanceof String ? (String) result : null;
    }

    private static String readValue(Object object) {
        if (object == null) return null;
        try {
            Object value = object.getClass().getMethod("getValue").invoke(object);
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstImageUrl(Object... sources) {
        String best = null;
        for (Object source : sources) {
            if (source == null) continue;
            String first = readStringField(source, "a");
            String second = readStringField(source, "b");
            // Feed rendering may deliberately choose a compact display variant. A held
            // preview should instead use the highest-resolution source Reddit supplied.
            if (isImageUrl(first) && (best == null || imageQuality(first) > imageQuality(best))) {
                best = first;
            }
            if (isImageUrl(second) && (best == null || imageQuality(second) > imageQuality(best))) {
                best = second;
            }
        }
        return best;
    }

    /**
     * Feed sections are mapped independently and often out of order. Preserve the best source
     * Reddit has supplied instead of allowing a late compact thumbnail to replace it.
     */
    private static boolean cacheImage(String linkId, String url) {
        if (linkId == null || !isImageUrl(url)) return false;
        synchronized (MEDIA_BY_LINK_ID) {
            String current = MEDIA_BY_LINK_ID.get(linkId);
            if (current != null && imageQuality(current) >= imageQuality(url)) return false;
            MEDIA_BY_LINK_ID.put(linkId, url);
            return true;
        }
    }

    private static int imageQuality(String url) {
        if (url == null) return 0;
        int quality = url.contains("i.redd.it/") ? 3_000_000
                : url.contains("preview.redd.it/") ? 2_000_000 : 1_000_000;
        int marker = url.indexOf("width=");
        if (marker < 0) return quality;
        marker += "width=".length();
        int end = marker;
        while (end < url.length() && Character.isDigit(url.charAt(end))) end++;
        try {
            return quality + Integer.parseInt(url.substring(marker, end));
        } catch (RuntimeException ignored) {
            return quality;
        }
    }

    private static boolean isImageUrl(String value) {
        return value != null && value.startsWith("https://") &&
                (value.contains("i.redd.it/") || value.contains("preview.redd.it/") ||
                        value.contains("external-preview.redd.it/"));
    }
}
