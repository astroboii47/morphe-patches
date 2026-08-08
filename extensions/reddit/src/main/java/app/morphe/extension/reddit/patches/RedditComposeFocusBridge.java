package app.morphe.extension.reddit.patches;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.FrameLayout;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.graphics.Rect;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RedditComposeFocusBridge {
    private static final String TAG = "MorpheComposeFocus";
    private static final Map<String, String> POST_BODIES = new HashMap<String, String>();
    private static final Map<String, Object> POST_MODELS_BY_TITLE = new HashMap<String, Object>();
    private static final Map<String, Object> POST_MODELS_BY_ID = new HashMap<String, Object>();
    private static final Map<String, PreviewRecord> PREVIEWS_BY_KEY = new HashMap<String, PreviewRecord>();
    private static final Map<String, PreviewRecord> PREVIEWS_BY_TITLE = new HashMap<String, PreviewRecord>();
    private static final int MAX_POST_BODIES = 300;
    private static final int MAX_POST_MODELS = 300;
    private static final String TEXT_PREVIEW_SEPARATOR = "\n\u0001\n";
    private static final Pattern RICHTEXT_TEXT_PATTERN = Pattern.compile("\\\"t\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\\\\\"])*)\\\"");

    private RedditComposeFocusBridge() {}

    private static final class PreviewRecord {
        String key;
        String title;
        String mediaUrl;
        String body;
    }

    public static boolean moveFocus(View root, int direction) {
        try {
            ArrayList<View> composeViews = new ArrayList<View>();
            collectComposeViews(root, composeViews);
            if (composeViews.isEmpty()) {
                Log.w(TAG, "compose views not found");
                return false;
            }

            int androidDirection = View.FOCUS_DOWN;
            int composeDirection = 6;
            if (direction == 5) {
                androidDirection = View.FOCUS_UP;
                composeDirection = 5;
            } else if (direction == 3) {
                androidDirection = View.FOCUS_LEFT;
                composeDirection = 3;
            } else if (direction == 4) {
                androidDirection = View.FOCUS_RIGHT;
                composeDirection = 4;
            }

            boolean any = false;
            for (int i = composeViews.size() - 1; i >= 0; i--) {
                View compose = composeViews.get(i);
                compose.setFocusable(true);
                compose.setFocusableInTouchMode(true);
                Log.w(TAG, "try compose[" + i + "] " + compose.getWidth() + "x" + compose.getHeight());
                if (takeComposeFocus(compose, composeDirection)) {
                    Log.w(TAG, "takeComposeFocus success index=" + i + " direction=" + composeDirection);
                    return true;
                }
                boolean fallback = compose.requestFocus(androidDirection, null) || compose.isFocused() || compose.hasFocus();
                Log.w(TAG, "requestFocus fallback index=" + i + " result=" + fallback + " focused=" + compose.isFocused() + " hasFocus=" + compose.hasFocus());
                any = any || fallback;
            }
            return any;
        } catch (Throwable throwable) {
            Log.w(TAG, "moveFocus failed", throwable);
            return false;
        }
    }

    public static boolean tinyScroll(View root) {
        if (root == null) {
            return false;
        }
        View target = findComposeView(root);
        if (target == null) {
            target = root;
        }
        ArrayList<MotionEvent> events = new ArrayList<MotionEvent>();
        try {
            long now = SystemClock.uptimeMillis();
            float x = target.getWidth() * 0.5f;
            float y1 = target.getHeight() * 0.63f;
            float y2 = target.getHeight() * 0.36f;
            events.add(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y1, 0));
            events.add(MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_MOVE, x, y1 - ((y1 - y2) * 0.35f), 0));
            events.add(MotionEvent.obtain(now, now + 32, MotionEvent.ACTION_MOVE, x, y1 - ((y1 - y2) * 0.70f), 0));
            events.add(MotionEvent.obtain(now, now + 48, MotionEvent.ACTION_MOVE, x, y2, 0));
            events.add(MotionEvent.obtain(now, now + 64, MotionEvent.ACTION_UP, x, y2, 0));
            for (MotionEvent event : events) {
                target.dispatchTouchEvent(event);
            }
            Log.w(TAG, "wake scroll dispatched target=" + target.getClass().getName() + " y1=" + y1 + " y2=" + y2);
            return true;
        } catch (Throwable throwable) {
            Log.w(TAG, "tinyScroll failed", throwable);
            return false;
        } finally {
            for (MotionEvent event : events) {
                event.recycle();
            }
        }
    }

    public static boolean clearCurrentFocus(View root) {
        try {
            if (root == null) {
                return false;
            }
            View focused = root.findFocus();
            if (focused == null) {
                Log.d(TAG, "clearCurrentFocus no focused view");
                return true;
            }
            focused.clearFocus();
            Log.d(TAG, "clearCurrentFocus cleared " + focused.getClass().getName());
            return true;
        } catch (Throwable throwable) {
            Log.d(TAG, "clearCurrentFocus failed", throwable);
            return false;
        }
    }

    public static boolean clearFocusAndSendSystemKey(View root, final int keyCode) {
        if (!clearCurrentFocus(root)) {
            return false;
        }
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(80L);
                        new Instrumentation().sendKeyDownUpSync(keyCode);
                        Log.w(TAG, "sendSystemKey keyCode=" + keyCode);
                    } catch (Throwable throwable) {
                        Log.w(TAG, "sendSystemKey failed", throwable);
                    }
                }
            }, "MorpheRedditFocusKey").start();
            return true;
        } catch (Throwable throwable) {
            Log.w(TAG, "clearFocusAndSendSystemKey failed", throwable);
            return false;
        }
    }

    public static boolean wakeFeedAndMoveFocus(final View root, final int direction) {
        if (root == null) {
            return false;
        }
        if (!tinyScroll(root)) {
            return false;
        }
        try {
            root.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        int keyCode = KeyEvent.KEYCODE_DPAD_DOWN;
                        if (direction == 5) {
                            keyCode = KeyEvent.KEYCODE_DPAD_UP;
                        } else if (direction == 3) {
                            keyCode = KeyEvent.KEYCODE_DPAD_LEFT;
                        } else if (direction == 4) {
                            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                        }
                        setRedditPatchRedispatching(true);
                        long now = SystemClock.uptimeMillis();
                        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
                        KeyEvent up = new KeyEvent(now, now + 16, KeyEvent.ACTION_UP, keyCode, 0);
                        boolean handledDown;
                        boolean handledUp;
                        try {
                            Activity activity = findActivity(root.getContext());
                            if (activity != null) {
                                handledDown = activity.dispatchKeyEvent(down);
                                handledUp = activity.dispatchKeyEvent(up);
                            } else {
                                handledDown = root.dispatchKeyEvent(down);
                                handledUp = root.dispatchKeyEvent(up);
                            }
                        } finally {
                            setRedditPatchRedispatching(false);
                        }
                        Log.w(TAG, "wake dispatched guarded activity key=" + keyCode + " down=" + handledDown + " up=" + handledUp);
                    } catch (Throwable throwable) {
                        setRedditPatchRedispatching(false);
                        Log.w(TAG, "wake activity key failed", throwable);
                    }
                }
            }, 220L);
            return true;
        } catch (Throwable throwable) {
            Log.w(TAG, "wakeFeedAndMoveFocus failed", throwable);
            return false;
        }
    }

    public static boolean resetComposeFocusForKey(View root, int keyCode) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
            return false;
        }
        int direction = 6;

        try {
            ArrayList<View> composeViews = new ArrayList<View>();
            collectComposeViews(root, composeViews);
            if (composeViews.isEmpty()) {
                Log.w(TAG, "resetComposeFocus no compose views");
                return false;
            }

            for (int i = composeViews.size() - 1; i >= 0; i--) {
                View compose = composeViews.get(i);
                Method getFocusOwner = compose.getClass().getMethod("getFocusOwner");
                Object owner = getFocusOwner.invoke(compose);
                if (owner == null) {
                    continue;
                }
                try {
                    Method clearFocus = owner.getClass().getDeclaredMethod("f");
                    clearFocus.setAccessible(true);
                    clearFocus.invoke(owner);
                    Log.w(TAG, "resetComposeFocus cleared index=" + i);
                } catch (Throwable clearThrowable) {
                    Log.w(TAG, "resetComposeFocus clear failed index=" + i, clearThrowable);
                }
                if (focusFirstPostUnitViaProvider(compose, i)) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "resetComposeFocus failed", throwable);
        }
        return false;
    }

    public static int[] getFocusedPostPreviewPoint(View root) {
        try {
            ArrayList<View> composeViews = new ArrayList<View>();
            collectComposeViews(root, composeViews);
            for (int i = composeViews.size() - 1; i >= 0; i--) {
                View compose = composeViews.get(i);
                AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
                if (provider == null) {
                    continue;
                }
                Object delegate = readField(provider, "a");
                if (delegate == null) {
                    continue;
                }
                Rect bounds = findFocusedPostUnitBounds(compose.getClass().getClassLoader(), provider, delegate);
                if (bounds == null || bounds.height() <= 0) {
                    continue;
                }
                int x = bounds.left + Math.max(1, (bounds.width() * 3) / 4);
                int y = bounds.top + Math.max(1, bounds.height() / 2);
                Log.w(TAG, "focusedPostPreviewPoint bounds=" + bounds + " point=" + x + "," + y);
                return new int[] { x, y };
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "focusedPostPreviewPoint failed", throwable);
        }
        return null;
    }

    public static int[] getPostPreviewPointAt(View root, int rawX, int rawY) {
        try {
            ArrayList<View> composeViews = new ArrayList<View>();
            collectComposeViews(root, composeViews);
            for (int i = composeViews.size() - 1; i >= 0; i--) {
                View compose = composeViews.get(i);
                AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
                if (provider == null) {
                    continue;
                }
                Object delegate = readField(provider, "a");
                if (delegate == null) {
                    continue;
                }
                Rect bounds = findPostUnitBoundsAt(compose.getClass().getClassLoader(), provider, delegate, rawX, rawY);
                if (bounds == null || bounds.height() <= 0) {
                    continue;
                }
                int x = bounds.left + Math.max(1, (bounds.width() * 3) / 4);
                int y = bounds.top + Math.max(1, bounds.height() / 2);
                Log.w(TAG, "touchedPostPreviewPoint touch=" + rawX + "," + rawY + " bounds=" + bounds + " point=" + x + "," + y);
                return new int[] { x, y };
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "touchedPostPreviewPoint failed", throwable);
        }
        return null;
    }

    public static boolean clickNextCommentButton(View root) {
        try {
            ArrayList<View> composeViews = new ArrayList<View>();
            collectComposeViews(root, composeViews);
            for (int i = composeViews.size() - 1; i >= 0; i--) {
                View compose = composeViews.get(i);
                AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
                if (provider == null) {
                    continue;
                }
                Object delegate = readField(provider, "a");
                if (delegate != null && clickComposeNodeMatching(compose.getClass().getClassLoader(), provider, delegate, "next comment")) {
                    Log.w(TAG, "nextComment compose click index=" + i);
                    return true;
                }
            }

            AccessibilityNodeInfo info = root == null ? null : root.createAccessibilityNodeInfo();
            if (info == null) {
                Log.w(TAG, "nextComment no root node");
                return false;
            }
            sealNode(info);
            boolean clicked = clickMatchingNode(info, "next comment");
            Log.w(TAG, "nextComment clicked=" + clicked);
            return clicked;
        } catch (Throwable throwable) {
            Log.w(TAG, "nextComment failed", throwable);
            return false;
        }
    }

    public static String getFocusedPostTextPreview(View root) {
        try {
            ArrayList<View> composeViews = new ArrayList<View>();
            collectComposeViews(root, composeViews);
            for (int i = composeViews.size() - 1; i >= 0; i--) {
                View compose = composeViews.get(i);
                AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
                if (provider == null) {
                    continue;
                }
                Object delegate = readField(provider, "a");
                if (delegate == null) {
                    continue;
                }
                String text = findFocusedPostUnitText(compose.getClass().getClassLoader(), provider, delegate);
                if (text != null && text.trim().length() > 0) {
                    return text;
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "focusedPostText failed", throwable);
        }
        return null;
    }

    public static String getFocusedPostModelMediaPreview(View root) {
        try {
            Object focusedModel = findFocusedPostUnitModel(root);
            String focusedId = modelKindWithId(focusedModel);
            if (focusedId != null && focusedId.length() > 0) {
                synchronized (PREVIEWS_BY_KEY) {
                    PreviewRecord byId = PREVIEWS_BY_KEY.get(focusedId);
                    if (byId != null && isUsablePreviewMedia(byId.mediaUrl)) {
                        Log.w(TAG, "focusedPreview id media id=" + focusedId + " " + summarizeUrl(byId.mediaUrl));
                        return byId.mediaUrl;
                    }
                }
            }
            String video = extractModelVideoUrl(focusedModel);
            if (video != null && video.length() > 0) {
                Log.w(TAG, "focusedPreview direct model video id=" + focusedId + " " + summarizeUrl(video));
                return video;
            }
            String image = extractModelImageUrl(focusedModel);
            if (isUsablePreviewMedia(image)) {
                Log.w(TAG, "focusedPreview direct model image id=" + focusedId + " " + summarizeUrl(image));
                return image;
            }

            String rowText = getFocusedPostTextPreview(root);
            PreviewRecord record = previewRecordForRowText(rowText);
            if (record != null && isUsablePreviewMedia(record.mediaUrl)) {
                Log.w(TAG, "focusedPreview media title=\"" + record.title + "\" " + summarizeUrl(record.mediaUrl));
                return record.mediaUrl;
            }
            Object model = registeredModelForRowText(rowText);
            video = extractModelVideoUrl(model);
            if (video != null && video.length() > 0) {
                Log.w(TAG, "focusedPreview model video " + summarizeUrl(video));
                return video;
            }
            image = extractModelImageUrl(model);
            if (isUsablePreviewMedia(image)) {
                Log.w(TAG, "focusedPreview model image " + summarizeUrl(image));
                return image;
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "focusedPreviewMedia failed", throwable);
        }
        return null;
    }

    public static String getFocusedPostModelTextPreview(View root) {
        try {
            Object focusedModel = findFocusedPostUnitModel(root);
            String focusedId = modelKindWithId(focusedModel);
            if (focusedId != null && focusedId.length() > 0) {
                synchronized (PREVIEWS_BY_KEY) {
                    PreviewRecord byId = PREVIEWS_BY_KEY.get(focusedId);
                    if (byId != null && byId.body != null && byId.body.trim().length() > 0) {
                        Log.w(TAG, "focusedPreview id text id=" + focusedId + " length=" + byId.body.length());
                        return buildTextPreviewText(byId, null, byId.body);
                    }
                }
            }
            String directBody = extractModelBodyText(focusedModel);
            if (directBody != null && directBody.trim().length() > 0) {
                Log.w(TAG, "focusedPreview direct model text id=" + focusedId + " length=" + directBody.length());
                PreviewRecord record = previewRecord(focusedId, modelTitle(focusedModel));
                return buildTextPreviewText(record, null, directBody);
            }

            String rowText = getFocusedPostTextPreview(root);
            PreviewRecord record = previewRecordForRowText(rowText);
            if (record != null && record.body != null && record.body.trim().length() > 0) {
                Log.w(TAG, "focusedPreview text title=\"" + record.title + "\" length=" + record.body.length());
                return buildTextPreviewText(record, rowText, record.body);
            }
            String body = getCachedBodyForRowText(rowText);
            if (body != null && body.trim().length() > 0) {
                Log.w(TAG, "focusedPreview cached text length=" + body.length());
                return buildTextPreviewText(record, rowText, body);
            }
            body = extractModelBodyText(registeredModelForRowText(rowText));
            if (body != null && body.trim().length() > 0) {
                Log.w(TAG, "focusedPreview model text length=" + body.length());
                return buildTextPreviewText(record, rowText, body);
            }
            String fallback = buildRowFallbackPreview(record, rowText);
            if (fallback != null && fallback.length() > 0) {
                Log.w(TAG, "focusedPreview row metadata fallback length=" + fallback.length());
                return fallback;
            }
            if (looksLikeBody(rowText)) {
                Log.w(TAG, "focusedPreview row body length=" + rowText.trim().length());
                return rowText.trim();
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "focusedPreviewText failed", throwable);
        }
        return null;
    }

    public static String getPostTextPreviewAt(View root, int rawX, int rawY) {
        try {
            ArrayList<View> composeViews = new ArrayList<View>();
            collectComposeViews(root, composeViews);
            for (int i = composeViews.size() - 1; i >= 0; i--) {
                View compose = composeViews.get(i);
                AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
                if (provider == null) {
                    continue;
                }
                Object delegate = readField(provider, "a");
                if (delegate == null) {
                    continue;
                }
                String text = findPostUnitTextAt(compose.getClass().getClassLoader(), provider, delegate, rawX, rawY);
                if (text != null && text.trim().length() > 0) {
                    String body = getCachedBodyForRowText(text);
                    if (body != null && body.trim().length() > 0) {
                        Log.w(TAG, "postTextPreview body length=" + body.length());
                        return buildTextPreviewText(previewRecordForRowText(text), text, body);
                    }
                }
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "touchedPostText failed", throwable);
        }
        return null;
    }

    public static String getPostModelMediaPreviewAt(View root, int rawX, int rawY) {
        try {
            Object model = findPostUnitModelAt(root, rawX, rawY);
            if (model == null) {
                model = findRegisteredModelForPostUnit(root, rawX, rawY);
            }
            PreviewRecord record = findPreviewRecordForPostUnit(root, rawX, rawY);
            if (record != null && isUsablePreviewMedia(record.mediaUrl)) {
                Log.w(TAG, "previewRecord media title=\"" + record.title + "\" " + summarizeUrl(record.mediaUrl));
                return record.mediaUrl;
            }
            String video = extractModelVideoUrl(model);
            if (video != null && video.length() > 0) {
                Log.w(TAG, "postUnitModel video " + summarizeUrl(video));
                return video;
            }
            String image = extractModelImageUrl(model);
            if (isUsablePreviewMedia(image)) {
                Log.w(TAG, "postUnitModel image " + summarizeUrl(image));
                return image;
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "postUnitModelMedia failed", throwable);
        }
        return null;
    }

    public static String getPostModelTextPreviewAt(View root, int rawX, int rawY) {
        try {
            Object model = findPostUnitModelAt(root, rawX, rawY);
            if (model == null) {
                model = findRegisteredModelForPostUnit(root, rawX, rawY);
            }
            String rowText = findPostUnitTextForPreview(root, rawX, rawY);
            PreviewRecord record = findPreviewRecordForPostUnit(root, rawX, rawY);
            if (record != null && record.body != null && record.body.trim().length() > 0) {
                Log.w(TAG, "previewRecord text title=\"" + record.title + "\" length=" + record.body.length());
                return buildTextPreviewText(record, rowText, record.body);
            }
            String body = extractModelBodyText(model);
            if (body != null && body.trim().length() > 0) {
                Log.w(TAG, "postUnitModel text length=" + body.length());
                return buildTextPreviewText(record, rowText, body);
            }
            String fallback = buildRowFallbackPreview(record, rowText);
            if (fallback != null && fallback.length() > 0) {
                Log.w(TAG, "postUnit row metadata fallback length=" + fallback.length());
                return fallback;
            }
            if (looksLikeBody(rowText)) {
                Log.w(TAG, "postUnit row body length=" + rowText.trim().length());
                return rowText.trim();
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "postUnitModelText failed", throwable);
        }
        return null;
    }

    public static void registerPreviewTitle(String key, String title) {
        if ((key == null || key.length() == 0) && (title == null || title.length() == 0)) {
            return;
        }
        try {
            PreviewRecord record = previewRecord(key, title);
            if (key != null && key.length() > 0) {
                record.key = key;
            }
            if (title != null && title.length() > 0) {
                record.title = title;
            }
            storePreviewRecord(record);
            Log.w(TAG, "previewTitle key=" + key + " title=\"" + title + "\"");
        } catch (Throwable throwable) {
            Log.w(TAG, "previewTitle failed", throwable);
        }
    }

    public static void registerPreviewMedia(String keyOrTitle, String url, Object source) {
        if ((keyOrTitle == null || keyOrTitle.length() == 0) && (url == null || url.length() == 0)) {
            return;
        }
        try {
            String media = preferVideoUrl(source, url);
            String image = extractModelImageUrl(source);
            if (image != null && image.length() > 0 && shouldReplaceMedia(media, image)) {
                media = image;
            }
            PreviewRecord record = previewRecord(keyOrTitle, keyOrTitle);
            if (keyOrTitle != null && keyOrTitle.length() > 0) {
                if (record.key == null) {
                    record.key = keyOrTitle;
                }
                if (record.title == null) {
                    record.title = keyOrTitle;
                }
            }
            if (media != null && media.length() > 0 && shouldReplaceMedia(record.mediaUrl, media)) {
                record.mediaUrl = media;
            }
            String body = extractModelBodyText(source);
            if (body == null || body.trim().length() == 0) {
                body = extractPostBody(source, 0);
            }
            if (body != null && body.trim().length() > 0) {
                record.body = body.trim();
            }
            storePreviewRecord(record);
            Log.w(TAG, "previewMedia keyOrTitle=" + keyOrTitle + " media=" + summarizeUrl(record.mediaUrl) + " body=" + (record.body == null ? 0 : record.body.length()));
        } catch (Throwable throwable) {
            Log.w(TAG, "previewMedia failed", throwable);
        }
    }

    public static void registerPreviewBase(String title, Object first, Object second, Object third) {
        try {
            PreviewRecord record = previewRecord(title, title);
            if (title != null && title.length() > 0) {
                record.title = title;
            }
            String media = preferVideoUrl(first, null);
            if (media == null || media.length() == 0) {
                media = preferVideoUrl(second, null);
            }
            if (media == null || media.length() == 0) {
                media = preferVideoUrl(third, null);
            }
            if (media == null || media.length() == 0) {
                media = extractModelImageUrl(first);
            }
            if (media == null || media.length() == 0) {
                media = extractModelImageUrl(second);
            }
            if (media == null || media.length() == 0) {
                media = extractModelImageUrl(third);
            }
            if (media != null && media.length() > 0 && shouldReplaceMedia(record.mediaUrl, media)) {
                record.mediaUrl = media;
            }
            String body = extractPostBody(first, 0);
            if (body == null || body.trim().length() == 0) {
                body = extractPostBody(second, 0);
            }
            if (body == null || body.trim().length() == 0) {
                body = extractPostBody(third, 0);
            }
            if (body != null && body.trim().length() > 0) {
                record.body = body.trim();
            }
            storePreviewRecord(record);
            Log.w(TAG, "previewBase title=\"" + title + "\" media=" + summarizeUrl(record.mediaUrl) + " body=" + (record.body == null ? 0 : record.body.length()));
        } catch (Throwable throwable) {
            Log.w(TAG, "previewBase failed", throwable);
        }
    }

    private static PreviewRecord previewRecord(String key, String title) {
        synchronized (PREVIEWS_BY_KEY) {
            PreviewRecord record = null;
            if (key != null && key.length() > 0) {
                record = PREVIEWS_BY_KEY.get(key);
            }
            if (record == null && title != null && title.length() > 0) {
                synchronized (PREVIEWS_BY_TITLE) {
                    record = PREVIEWS_BY_TITLE.get(title);
                }
            }
            if (record == null) {
                record = new PreviewRecord();
                record.key = key;
                record.title = title;
            }
            return record;
        }
    }

    private static void storePreviewRecord(PreviewRecord record) {
        if (record == null) {
            return;
        }
        synchronized (PREVIEWS_BY_KEY) {
            if (PREVIEWS_BY_KEY.size() > MAX_POST_MODELS) {
                PREVIEWS_BY_KEY.clear();
            }
            if (record.key != null && record.key.length() > 0) {
                PREVIEWS_BY_KEY.put(record.key, record);
            }
        }
        synchronized (PREVIEWS_BY_TITLE) {
            if (PREVIEWS_BY_TITLE.size() > MAX_POST_MODELS) {
                PREVIEWS_BY_TITLE.clear();
            }
            if (record.title != null && record.title.length() > 0) {
                PREVIEWS_BY_TITLE.put(record.title, record);
            }
        }
    }

    private static void storePostBody(String title, String id, String body) {
        if (body == null) {
            return;
        }
        String trimmed = body.trim();
        if (trimmed.length() == 0) {
            return;
        }
        synchronized (POST_BODIES) {
            if (POST_BODIES.size() > MAX_POST_BODIES) {
                POST_BODIES.clear();
            }
            if (title != null && title.length() > 0) {
                POST_BODIES.put(title, trimmed);
            }
            if (id != null && id.length() > 0) {
                POST_BODIES.put(id, trimmed);
            }
        }
        PreviewRecord record = previewRecord(id, title);
        if (id != null && id.length() > 0) {
            record.key = id;
        }
        if (title != null && title.length() > 0) {
            record.title = title;
        }
        record.body = trimmed;
        storePreviewRecord(record);
    }

    public static String upgradePreviewMedia(String key, String title, String fallbackUrl) {
        try {
            PreviewRecord record = null;
            if (key != null && key.length() > 0) {
                synchronized (PREVIEWS_BY_KEY) {
                    record = PREVIEWS_BY_KEY.get(key);
                }
            }
            if (record == null && title != null && title.length() > 0) {
                synchronized (PREVIEWS_BY_TITLE) {
                    record = PREVIEWS_BY_TITLE.get(title);
                }
            }
            if (record == null || !isUsablePreviewMedia(record.mediaUrl)) {
                return fallbackUrl;
            }
            if (fallbackUrl == null || shouldReplaceMedia(fallbackUrl, record.mediaUrl)) {
                Log.w(TAG, "upgradedPreviewMedia key=" + key + " title=\"" + title + "\" "
                        + summarizeUrl(fallbackUrl) + " -> " + summarizeUrl(record.mediaUrl));
                return record.mediaUrl;
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "upgradePreviewMedia failed", throwable);
        }
        return fallbackUrl;
    }

    public static String getPreviewBodyForTitle(String title) {
        if (title == null || title.length() == 0) {
            return null;
        }
        try {
            synchronized (PREVIEWS_BY_TITLE) {
                PreviewRecord record = PREVIEWS_BY_TITLE.get(title);
                if (record != null && record.body != null && record.body.trim().length() > 0) {
                    return record.body.trim();
                }
            }
            synchronized (POST_BODIES) {
                String body = POST_BODIES.get(title);
                return body != null && body.trim().length() > 0 ? body.trim() : null;
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "getPreviewBodyForTitle failed", throwable);
            return null;
        }
    }

    private static String buildTextPreviewText(PreviewRecord record, String rowText, String body) {
        String trimmedBody = body == null ? "" : body.trim();
        String title = record == null ? null : record.title;
        if (title == null || title.trim().length() == 0) {
            title = titleFromRowText(rowText);
        }
        String meta = metadataFromRowText(rowText);
        StringBuilder builder = new StringBuilder();
        if (title != null && title.trim().length() > 0 && !samePreviewText(title, trimmedBody)) {
            builder.append(title.trim()).append(TEXT_PREVIEW_SEPARATOR);
        }
        if (meta != null && meta.length() > 0) {
            builder.append(meta).append(TEXT_PREVIEW_SEPARATOR);
        }
        builder.append(trimmedBody);
        return builder.toString().trim();
    }

    private static String buildRowFallbackPreview(PreviewRecord record, String rowText) {
        String title = record == null ? null : record.title;
        if (title == null || title.trim().length() == 0) {
            title = titleFromRowText(rowText);
        }
        String meta = metadataFromRowText(rowText);
        StringBuilder builder = new StringBuilder();
        if (title != null && title.trim().length() > 0) {
            builder.append(title.trim());
        }
        if (meta != null && meta.length() > 0) {
            if (builder.length() > 0) {
                builder.append(TEXT_PREVIEW_SEPARATOR);
            }
            builder.append(meta);
        }
        return builder.length() > 0 ? builder.toString() : null;
    }

    private static boolean samePreviewText(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return first.trim().equals(second.trim());
    }

    private static String titleFromRowText(String rowText) {
        if (rowText == null) {
            return null;
        }
        String[] pieces = rowText.split(",");
        String best = null;
        for (String piece : pieces) {
            String text = piece.trim();
            String lower = text.toLowerCase(Locale.US);
            if (text.length() == 0
                    || lower.startsWith("from ")
                    || lower.startsWith("posted ")
                    || lower.contains(" upvote")
                    || lower.contains(" comment")
                    || lower.startsWith("shared ")) {
                continue;
            }
            if (best == null || text.length() > best.length()) {
                best = text;
            }
        }
        return best;
    }

    private static String metadataFromRowText(String rowText) {
        if (rowText == null) {
            return null;
        }
        String[] pieces = rowText.split(",");
        String subreddit = null;
        String age = null;
        String votes = null;
        String comments = null;
        String shares = null;
        String reposts = null;
        for (String piece : pieces) {
            String text = piece.trim();
            String lower = text.toLowerCase(Locale.US);
            if (lower.startsWith("from ") && subreddit == null) {
                subreddit = "r/" + text.substring(5).trim();
            } else if (lower.startsWith("posted ") && age == null) {
                age = text.substring(7).trim();
            } else if (lower.contains(" upvote") && votes == null) {
                votes = text;
            } else if (lower.contains(" comment") && comments == null) {
                comments = text;
            } else if (lower.startsWith("shared ") && shares == null) {
                shares = text;
            } else if (lower.startsWith("reposted ") && reposts == null) {
                reposts = text;
            }
        }
        StringBuilder builder = new StringBuilder();
        appendMeta(builder, subreddit);
        appendMeta(builder, age);
        appendMeta(builder, votes);
        appendMeta(builder, comments);
        appendMeta(builder, shares);
        appendMeta(builder, reposts);
        return builder.length() > 0 ? builder.toString() : null;
    }

    private static void appendMeta(StringBuilder builder, String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("  |  ");
        }
        builder.append(value);
    }

    private static boolean shouldReplaceMedia(String oldUrl, String newUrl) {
        if (newUrl == null || newUrl.length() == 0) {
            return false;
        }
        if (isAvatarPreviewUrl(newUrl)) {
            return false;
        }
        if (oldUrl == null || oldUrl.length() == 0) {
            return true;
        }
        if (isVideoPreviewUrl(newUrl) && !isVideoPreviewUrl(oldUrl)) {
            return true;
        }
        if (isVideoPreviewUrl(oldUrl) && !isVideoPreviewUrl(newUrl)) {
            return false;
        }
        return imagePreviewScore(newUrl) > imagePreviewScore(oldUrl);
    }

    private static PreviewRecord findPreviewRecordForPostUnit(View root, int rawX, int rawY) throws Exception {
        String rowText = findPostUnitTextForPreview(root, rawX, rawY);
        return previewRecordForRowText(rowText);
    }

    private static PreviewRecord previewRecordForRowText(String rowText) {
        if (rowText == null || rowText.length() == 0) {
            return null;
        }
        String id = firstThingId(rowText);
        if (id != null) {
            synchronized (PREVIEWS_BY_KEY) {
                PreviewRecord byId = PREVIEWS_BY_KEY.get(id);
                if (byId != null) {
                    Log.w(TAG, "previewRecord matched id=" + id);
                    return byId;
                }
            }
        }
        synchronized (PREVIEWS_BY_TITLE) {
            String bestTitle = null;
            int bestScore = 0;
            for (String title : PREVIEWS_BY_TITLE.keySet()) {
                int score = rowTitleMatchScore(rowText, title);
                if (score > bestScore || (score == bestScore && score > 0 && (bestTitle == null || title.length() > bestTitle.length()))) {
                    bestTitle = title;
                    bestScore = score;
                }
            }
            if (bestTitle != null) {
                Log.w(TAG, "previewRecord matched title=\"" + bestTitle + "\" score=" + bestScore);
                return PREVIEWS_BY_TITLE.get(bestTitle);
            }
        }
        Log.w(TAG, "previewRecord no match row=\"" + compact(rowText) + "\" previews=" + PREVIEWS_BY_TITLE.size());
        return null;
    }

    public static void registerPostUnitModel(Object model) {
        try {
            if (model == null) {
                return;
            }
            String title = modelTitle(model);
            String id = modelKindWithId(model);
            if ((title == null || title.length() == 0) && (id == null || id.length() == 0)) {
                return;
            }
            synchronized (POST_MODELS_BY_TITLE) {
                if (POST_MODELS_BY_TITLE.size() > MAX_POST_MODELS) {
                    POST_MODELS_BY_TITLE.clear();
                }
                if (title != null && title.length() > 0) {
                    POST_MODELS_BY_TITLE.put(title, model);
                }
            }
            synchronized (POST_MODELS_BY_ID) {
                if (POST_MODELS_BY_ID.size() > MAX_POST_MODELS) {
                    POST_MODELS_BY_ID.clear();
                }
                if (id != null && id.length() > 0) {
                    POST_MODELS_BY_ID.put(id, model);
                }
            }
            String body = extractModelBodyText(model);
            if (title != null && title.length() > 0 && body != null && body.trim().length() > 0) {
                storePostBody(title, id, body);
            }
            String video = extractModelVideoUrl(model);
            String image = extractModelImageUrl(model);
            PreviewRecord record = previewRecord(id, title);
            if (id != null && id.length() > 0) {
                record.key = id;
            }
            if (title != null && title.length() > 0) {
                record.title = title;
            }
            if (video != null && video.length() > 0) {
                record.mediaUrl = video;
            } else if (isUsablePreviewMedia(image)) {
                record.mediaUrl = image;
            }
            if (body != null && body.trim().length() > 0) {
                record.body = body.trim();
            }
            storePreviewRecord(record);
            Log.w(TAG, "registeredPostUnit id=" + id + " title=\"" + title + "\" body=" + (body == null ? 0 : body.length()) + " video=" + (video != null) + " image=" + (image != null));
        } catch (Throwable throwable) {
            Log.w(TAG, "registerPostUnitModel failed", throwable);
        }
    }

    public static boolean isVideoPreviewUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return isDirectPlayableVideoUrl(lower);
    }

    public static boolean isTextBodyPreview(String text) {
        return looksLikeBody(text);
    }

    public static void cachePostBodyFromModels(String title, Object first, Object second, Object third) {
        try {
            if (title == null || title.length() == 0) {
                return;
            }
            String body = extractPostBody(first, 0);
            if (body == null || body.length() == 0) {
                body = extractPostBody(second, 0);
            }
            if (body == null || body.length() == 0) {
                body = extractPostBody(third, 0);
            }
            if (body == null || body.trim().length() == 0) {
                return;
            }
            storePostBody(title, null, body);
            Log.w(TAG, "cachedPostBody title=\"" + title + "\" length=" + body.length());
        } catch (Throwable throwable) {
            Log.w(TAG, "cachePostBody failed", throwable);
        }
    }

    public static void cachePostBodyFromModel(String title, Object model) {
        cachePostBodyFromModels(title, model, null, null);
    }

    public static void cachePresentationPostBody(Object model) {
        try {
            if (model == null) {
                return;
            }
            String title = asString(invokeNoArg(model, "getTitle"));
            if (title == null || title.length() == 0) {
                title = asString(readField(model, "G0"));
            }
            String body = asString(readField(model, "W0"));
            if (!looksLikeBody(body)) {
                Object link = readField(model, "A2");
                body = linkBodyText(link);
                if (!looksLikeBody(body)) {
                    body = asString(invokeNoArg(link, "getSelftext"));
                }
            }
            if (title == null || title.length() == 0 || !looksLikeBody(body)) {
                return;
            }
            storePostBody(title, null, body);
            Log.w(TAG, "cachedPresentationBody title=\"" + title + "\" length=" + body.length());
        } catch (Throwable throwable) {
            Log.w(TAG, "cachePresentationPostBody failed", throwable);
        }
    }

    public static void cacheLinkModel(Object link) {
        try {
            String title = linkTitle(link);
            String id = modelKindWithId(link);
            if ((title == null || title.length() == 0) && (id == null || id.length() == 0)) {
                return;
            }
            String body = linkBodyText(link);
            if (body == null || body.trim().length() == 0) {
                body = extractPostBody(link, 0);
            }
            if (body == null || body.trim().length() == 0) {
                body = null;
            }
            String video = extractModelVideoUrl(link);
            String image = extractModelImageUrl(link);
            if (body != null) {
                storePostBody(title, id, body);
            }
            PreviewRecord record = previewRecord(id, title);
            if (id != null && id.length() > 0) {
                record.key = id;
            }
            if (title != null && title.length() > 0) {
                record.title = title;
            }
            if (body != null && body.trim().length() > 0) {
                record.body = body.trim();
            }
            if (video != null && video.length() > 0) {
                record.mediaUrl = video;
            } else if (isUsablePreviewMedia(image) && shouldReplaceMedia(record.mediaUrl, image)) {
                record.mediaUrl = image;
            }
            storePreviewRecord(record);
            Log.w(TAG, "cachedLinkModel title=\"" + title + "\" id=" + id
                    + " body=" + (body != null ? body.length() : 0)
                    + " media=" + summarizeUrl(record.mediaUrl));
        } catch (Throwable throwable) {
            Log.w(TAG, "cacheLinkModel failed", throwable);
        }
    }

    public static String linkTitle(Object link) {
        Object title = invokeNoArg(link, "getTitle");
        return title instanceof CharSequence ? title.toString() : null;
    }

    public static String linkVideoUrl(Object link) {
        try {
            Object media = invokeNoArg(link, "getMedia");
            Object redditVideo = invokeNoArg(media, "getRedditVideo");
            String direct = firstPlayableVideoString(
                    invokeNoArg(redditVideo, "getPackagedMp4Url"),
                    invokeNoArg(redditVideo, "getFallbackUrl"),
                    invokeNoArg(redditVideo, "getFallbackURL"),
                    invokeNoArg(redditVideo, "getFallBackUrl")
            );
            if (direct != null && direct.length() > 0) {
                Log.w(TAG, "linkVideoUrl direct " + summarizeUrl(direct));
                return direct;
            }
            direct = bestPlaybackMp4Url(invokeNoArg(redditVideo, "getPlaybackMp4s"));
            if (direct != null && direct.length() > 0) {
                Log.w(TAG, "linkVideoUrl playback " + summarizeUrl(direct));
                return direct;
            }
            String recursive = findVideoUrl(link, 0, new IdentityHashMap<Object, Boolean>());
            if (recursive != null && recursive.length() > 0 && isDirectPlayableVideoUrl(recursive) && !isHlsPreviewUrl(recursive)) {
                Log.w(TAG, "linkVideoUrl recursive " + summarizeUrl(recursive));
                return recursive;
            }
            String hls = asString(invokeNoArg(redditVideo, "getHlsUrl"));
            if (hls != null && hls.length() > 0 && isDirectPlayableVideoUrl(hls)) {
                Log.w(TAG, "linkVideoUrl hls " + summarizeUrl(hls));
                return hls;
            }
            String dash = asString(invokeNoArg(redditVideo, "getDashUrl"));
            if (dash != null && dash.length() > 0 && isDirectPlayableVideoUrl(dash)) {
                Log.w(TAG, "linkVideoUrl dash " + summarizeUrl(dash));
                return dash;
            }
            direct = firstPlayableVideoString(
                    invokeNoArg(redditVideo, "getDownloadUrl"),
                    invokeNoArg(redditVideo, "getScrubbedMediaUrl"),
                    invokeNoArg(redditVideo, "getScrubberMediaUrl"),
                    invokeNoArg(redditVideo, "getScrubberMediaURL")
            );
            if (direct != null && direct.length() > 0) {
                Log.w(TAG, "linkVideoUrl secondary " + summarizeUrl(direct));
                return direct;
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "linkVideoUrl failed", throwable);
        }
        return null;
    }

    public static String preferVideoUrl(Object model, String fallback) {
        try {
            String video = findVideoUrl(model, 0, new IdentityHashMap<Object, Boolean>());
            if (video != null && video.length() > 0) {
                Log.w(TAG, "preferredVideoUrl " + summarizeUrl(video));
                return video;
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "preferVideoUrl failed", throwable);
        }
        return fallback;
    }

    private static String linkBodyText(Object link) {
        String[] methods = new String[] {
                "getSelftext", "getSelfText", "getRawBodyText", "getBodyText", "getMarkdown",
                "getRichtext", "getRichText", "getRtjson", "getBody",
                "getSelfTextHtml", "getSelftextHtml"
        };
        for (String method : methods) {
            String body = extractBodyCandidate(invokeNoArg(link, method), 0);
            if (looksLikeBody(body)) {
                return body;
            }
        }
        Object rtjson = invokeNoArg(link, "getRtjson");
        String richText = extractBodyCandidate(invokeNoArg(rtjson, "getRichTextString"), 0);
        if (looksLikeBody(richText)) {
            return richText;
        }
        return extractPostBody(link, 0);
    }

    private static Object findPostUnitModelAt(View root, int rawX, int rawY) throws Exception {
        ArrayList<View> composeViews = new ArrayList<View>();
        collectComposeViews(root, composeViews);
        for (int i = composeViews.size() - 1; i >= 0; i--) {
            View compose = composeViews.get(i);
            AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
            if (provider == null) {
                continue;
            }
            Object delegate = readField(provider, "a");
            if (delegate == null) {
                continue;
            }
            Object wrapper = findPostUnitWrapperAt(compose.getClass().getClassLoader(), provider, delegate, rawX, rawY);
            if (wrapper == null) {
                continue;
            }
            Object model = findInterestingRedditModel(wrapper, 0, new IdentityHashMap<Object, Boolean>());
            if (model != null) {
                Log.w(TAG, "postUnitModel found " + model.getClass().getName());
                return model;
            }
            Log.w(TAG, "postUnitModel no model wrapper=" + wrapper.getClass().getName());
            logInterestingClassNames(wrapper, 0, new IdentityHashMap<Object, Boolean>(), new int[] {0});
        }
        return null;
    }

    private static Object findFocusedPostUnitModel(View root) throws Exception {
        ArrayList<View> composeViews = new ArrayList<View>();
        collectComposeViews(root, composeViews);
        for (int i = composeViews.size() - 1; i >= 0; i--) {
            View compose = composeViews.get(i);
            AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
            if (provider == null) {
                continue;
            }
            Object delegate = readField(provider, "a");
            if (delegate == null) {
                continue;
            }
            Object wrapper = findFocusedPostUnitWrapper(compose.getClass().getClassLoader(), provider, delegate);
            if (wrapper == null) {
                continue;
            }
            Object model = findInterestingRedditModel(wrapper, 0, new IdentityHashMap<Object, Boolean>());
            if (model != null) {
                Log.w(TAG, "focusedPostUnitModel found " + model.getClass().getName() + " id=" + modelKindWithId(model));
                return model;
            }
            Log.w(TAG, "focusedPostUnitModel no model wrapper=" + wrapper.getClass().getName());
            logInterestingClassNames(wrapper, 0, new IdentityHashMap<Object, Boolean>(), new int[] {0});
        }
        return null;
    }

    private static Object findRegisteredModelForPostUnit(View root, int rawX, int rawY) throws Exception {
        String rowText = findPostUnitTextForPreview(root, rawX, rawY);
        if (rowText == null || rowText.length() == 0) {
            return null;
        }
        String id = firstThingId(rowText);
        if (id != null) {
            synchronized (POST_MODELS_BY_ID) {
                Object byId = POST_MODELS_BY_ID.get(id);
                if (byId != null) {
                    Log.w(TAG, "registeredPostUnit matched id=" + id);
                    return byId;
                }
            }
        }
        Object byTitle = registeredModelForRowText(rowText);
        if (byTitle != null) {
            return byTitle;
        }
        Log.w(TAG, "registeredPostUnit no match row=\"" + compact(rowText) + "\"");
        return null;
    }

    private static String findPostUnitTextForPreview(View root, int rawX, int rawY) throws Exception {
        ArrayList<View> composeViews = new ArrayList<View>();
        collectComposeViews(root, composeViews);
        for (int i = composeViews.size() - 1; i >= 0; i--) {
            View compose = composeViews.get(i);
            AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
            if (provider == null) {
                continue;
            }
            Object delegate = readField(provider, "a");
            if (delegate == null) {
                continue;
            }
            String text = findPostUnitTextAt(compose.getClass().getClassLoader(), provider, delegate, rawX, rawY);
            if (text != null && text.trim().length() > 0) {
                return text;
            }
        }
        return null;
    }

    private static Object findFocusedPostUnitWrapper(ClassLoader loader, AccessibilityNodeProvider provider, Object delegate) throws Exception {
        Object accessibilityDelegate = readField(delegate, "i");
        if (accessibilityDelegate == null) {
            accessibilityDelegate = delegate;
        }
        Method semanticsMapMethod = accessibilityDelegate.getClass().getDeclaredMethod("s");
        semanticsMapMethod.setAccessible(true);
        Object map = semanticsMapMethod.invoke(accessibilityDelegate);
        if (map == null) {
            return null;
        }
        Object[] values = (Object[]) readField(map, "c");
        if (values == null) {
            return null;
        }
        Class<?> semanticsKeys = loader.loadClass("androidx.compose.ui.semantics.d");
        Object testTagKey = readStaticField(semanticsKeys, "A");
        Object best = null;
        int bestHeight = Integer.MAX_VALUE;
        for (Object wrapper : values) {
            if (wrapper == null) {
                continue;
            }
            Object node = readField(wrapper, "a");
            if (node == null) {
                continue;
            }
            Object config = readField(node, "d");
            Object tag = getSemanticsValue(config, testTagKey);
            if (!"post_unit".equals(String.valueOf(tag))) {
                continue;
            }
            Object id = readField(node, "f");
            if (!(id instanceof Integer)) {
                continue;
            }
            AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(((Integer) id).intValue());
            if (info == null) {
                continue;
            }
            sealNode(info);
            if (!info.isFocused()) {
                continue;
            }
            Rect bounds = new Rect();
            info.getBoundsInScreen(bounds);
            if (bounds.height() > 0 && bounds.height() < bestHeight) {
                best = wrapper;
                bestHeight = bounds.height();
            }
        }
        return best;
    }

    private static Object registeredModelForRowText(String rowText) {
        synchronized (POST_MODELS_BY_TITLE) {
            String bestTitle = null;
            int bestScore = 0;
            for (String title : POST_MODELS_BY_TITLE.keySet()) {
                int score = rowTitleMatchScore(rowText, title);
                if (score > bestScore || (score == bestScore && score > 0 && (bestTitle == null || title.length() > bestTitle.length()))) {
                    bestTitle = title;
                    bestScore = score;
                }
            }
            if (bestTitle != null) {
                Log.w(TAG, "registeredPostUnit matched title=\"" + bestTitle + "\" score=" + bestScore);
                return POST_MODELS_BY_TITLE.get(bestTitle);
            }
        }
        return null;
    }

    private static Object findPostUnitWrapperAt(ClassLoader loader, AccessibilityNodeProvider provider, Object delegate, int rawX, int rawY) throws Exception {
        Object accessibilityDelegate = readField(delegate, "i");
        if (accessibilityDelegate == null) {
            accessibilityDelegate = delegate;
        }
        Method semanticsMapMethod = accessibilityDelegate.getClass().getDeclaredMethod("s");
        semanticsMapMethod.setAccessible(true);
        Object map = semanticsMapMethod.invoke(accessibilityDelegate);
        if (map == null) {
            return null;
        }
        Object[] values = (Object[]) readField(map, "c");
        if (values == null) {
            return null;
        }
        Class<?> semanticsKeys = loader.loadClass("androidx.compose.ui.semantics.d");
        Object testTagKey = readStaticField(semanticsKeys, "A");
        Object best = null;
        int bestHeight = Integer.MAX_VALUE;
        for (Object wrapper : values) {
            if (wrapper == null) {
                continue;
            }
            Object node = readField(wrapper, "a");
            if (node == null) {
                continue;
            }
            Object config = readField(node, "d");
            Object tag = getSemanticsValue(config, testTagKey);
            if (!"post_unit".equals(String.valueOf(tag))) {
                continue;
            }
            Object id = readField(node, "f");
            if (!(id instanceof Integer)) {
                continue;
            }
            Rect bounds = getProviderBounds(provider, ((Integer) id).intValue());
            if (bounds == null || !bounds.contains(rawX, rawY)) {
                continue;
            }
            if (bounds.height() < bestHeight) {
                best = wrapper;
                bestHeight = bounds.height();
            }
        }
        return best;
    }

    private static void logInterestingClassNames(Object value, int depth, IdentityHashMap<Object, Boolean> seen, int[] count) {
        if (value == null || depth > 5 || count[0] >= 40) {
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Enum) {
            return;
        }
        if (seen.containsKey(value)) {
            return;
        }
        seen.put(value, Boolean.TRUE);
        String name = type.getName();
        if (name.contains("reddit") || name.contains("Link") || name.contains("Post") || name.contains("Media") || name.contains("Video")) {
            Log.w(TAG, "postUnitGraph depth=" + depth + " class=" + name);
            count[0]++;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                logInterestingClassNames(item, depth + 1, seen, count);
            }
            return;
        }
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                logInterestingClassNames(item, depth + 1, seen, count);
            }
            return;
        }
        if (type.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                logInterestingClassNames(java.lang.reflect.Array.get(value, i), depth + 1, seen, count);
            }
            return;
        }
        Class<?> current = type;
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    logInterestingClassNames(field.get(value), depth + 1, seen, count);
                    if (count[0] >= 40) {
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
    }

    private static Object findInterestingRedditModel(Object value, int depth, IdentityHashMap<Object, Boolean> seen) throws Exception {
        if (value == null || depth > 10) {
            return null;
        }
        Class<?> type = value.getClass();
        String name = type.getName();
        if (name.equals("com.reddit.presentation.listing.model.LinkPresentationModel")
                || name.equals("com.reddit.domain.model.Link")
                || name.equals("com.reddit.presentation.listing.model.Mp4LinkPreviewPresentationModel")
                || name.equals("com.reddit.domain.media.model.Mp4PreviewParams")) {
            return value;
        }
        if (type.isPrimitive() || value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof Enum) {
            return null;
        }
        if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")) {
            return null;
        }
        if (seen.containsKey(value)) {
            return null;
        }
        seen.put(value, Boolean.TRUE);
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                Object found = findInterestingRedditModel(item, depth + 1, seen);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                Object found = findInterestingRedditModel(item, depth + 1, seen);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (type.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object found = findInterestingRedditModel(java.lang.reflect.Array.get(value, i), depth + 1, seen);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        Class<?> current = type;
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object found = findInterestingRedditModel(field.get(value), depth + 1, seen);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static String extractModelVideoUrl(Object model) throws Exception {
        if (model == null) {
            return null;
        }
        String name = model.getClass().getName();
        if (name.equals("com.reddit.domain.media.model.Mp4PreviewParams")) {
            String dash = asString(readField(model, "b"));
            if (dash != null && dash.length() > 0) {
                return dash;
            }
            String mp4 = asString(readField(model, "e"));
            if (mp4 != null && mp4.length() > 0) {
                return mp4;
            }
            String imgur = asString(readField(model, "i"));
            if (imgur != null && imgur.length() > 0) {
                return imgur;
            }
        }
        if (name.equals("com.reddit.presentation.listing.model.Mp4LinkPreviewPresentationModel")) {
            return extractModelVideoUrl(readField(model, "a"));
        }
        if (name.equals("com.reddit.presentation.listing.model.LinkPresentationModel")) {
            String fromMp4 = extractModelVideoUrl(readField(model, "T0"));
            if (fromMp4 != null && fromMp4.length() > 0) {
                return fromMp4;
            }
            return linkVideoUrl(readField(model, "A2"));
        }
        if (name.equals("com.reddit.domain.model.Link")) {
            return linkVideoUrl(model);
        }
        return null;
    }

    private static String extractModelImageUrl(Object model) throws Exception {
        if (model == null) {
            return null;
        }
        String direct = findBestImageUrl(model);
        return direct != null && direct.length() > 0 ? direct : null;
    }

    private static String extractModelBodyText(Object model) {
        if (model == null) {
            return null;
        }
        try {
            String name = model.getClass().getName();
            if (name.equals("com.reddit.presentation.listing.model.LinkPresentationModel")) {
                String self = asString(readField(model, "W0"));
                if (looksLikeBody(self)) {
                    return self;
                }
                return linkBodyText(readField(model, "A2"));
            }
            if (name.equals("com.reddit.domain.model.Link")) {
                return linkBodyText(model);
            }
            String body = extractPostBody(model, 0);
            if (looksLikeBody(body)) {
                return body;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String modelTitle(Object model) {
        String title = asString(invokeNoArg(model, "getTitle"));
        if (title == null || title.length() == 0) {
            try {
                title = asString(readField(model, "G0"));
            } catch (Throwable ignored) {
            }
        }
        if ((title == null || title.length() == 0) && model != null && model.getClass().getName().equals("com.reddit.domain.model.Link")) {
            title = linkTitle(model);
        }
        return title;
    }

    private static String modelKindWithId(Object model) {
        String id = asString(invokeNoArg(model, "getKindWithId"));
        if (id == null || id.length() == 0) {
            try {
                id = asString(readField(model, "r"));
            } catch (Throwable ignored) {
            }
        }
        return id;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value instanceof CharSequence ? value.toString() : null;
    }

    private static String firstNonEmptyString(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asString(value);
            if (text != null && text.length() > 0) {
                return text;
            }
        }
        return null;
    }

    private static String firstPlayableVideoString(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asString(value);
            if (text != null) {
                text = cleanMediaUrl(text);
            }
            if (isDirectPlayableVideoUrl(text)) {
                return text;
            }
        }
        return null;
    }

    private static String bestPlaybackMp4Url(Object playbackMp4s) {
        try {
            Object permutations = invokeNoArg(playbackMp4s, "getPermutations");
            if (!(permutations instanceof Iterable)) {
                return null;
            }
            String bestUrl = null;
            int bestScore = Integer.MIN_VALUE;
            for (Object permutation : (Iterable<?>) permutations) {
                String url = asString(invokeNoArg(permutation, "getUrl"));
                if (url != null) {
                    url = cleanMediaUrl(url);
                }
                if (!isDirectPlayableVideoUrl(url)) {
                    continue;
                }
                int score = 0;
                Object height = invokeNoArg(permutation, "getHeight");
                if (height instanceof Number) {
                    score += ((Number) height).intValue() * 10;
                }
                Object bitrate = invokeNoArg(permutation, "getBitrateBps");
                if (bitrate instanceof Number) {
                    score += ((Number) bitrate).intValue() / 1000;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestUrl = url;
                }
            }
            return bestUrl;
        } catch (Throwable throwable) {
            Log.w(TAG, "bestPlaybackMp4 failed", throwable);
            return null;
        }
    }

    public static WebView createMediaWebView(Context context, String url) {
        WebView webView = new WebView(context);
        webView.setBackgroundColor(0);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        String escaped = escapeHtml(url);
        String tag = isVideoPreviewUrl(url)
                ? "<video id=\"v\" src=\"" + escaped + "\" autoplay muted loop playsinline controls preload=\"auto\"></video>"
                + "<div id=\"hud\"><span id=\"time\">0:00 / 0:00</span><div id=\"track\"><div id=\"fill\"></div></div></div>"
                : "<img src=\"" + escaped + "\" />";
        String html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;width:100%;height:100%;background:transparent;overflow:hidden;}"
                + "body{display:flex;align-items:center;justify-content:center;}img,video{max-width:100%;max-height:100%;object-fit:contain;}"
                + "#hud{position:fixed;left:18px;right:18px;bottom:18px;display:flex;align-items:center;gap:10px;"
                + "padding:8px 10px;border-radius:10px;background:rgba(0,0,0,.62);color:white;font:14px sans-serif;}"
                + "#track{height:4px;flex:1;background:rgba(255,255,255,.28);border-radius:3px;overflow:hidden;}"
                + "#fill{height:100%;width:0;background:white;border-radius:3px;}</style>"
                + "</head><body>" + tag
                + "<script>var v=document.getElementById('v'),t=document.getElementById('time'),f=document.getElementById('fill');"
                + "function fmt(s){if(!isFinite(s)||s<0)return '0:00';s=Math.floor(s);return Math.floor(s/60)+':'+String(s%60).padStart(2,'0');}"
                + "function tick(){if(v&&t&&f){var d=v.duration||0,c=v.currentTime||0;t.textContent=fmt(c)+' / '+fmt(d);f.style.width=d?Math.min(100,(c/d)*100)+'%':'0';}}"
                + "if(v){v.muted=true;v.playsInline=true;v.addEventListener('timeupdate',tick);v.addEventListener('loadedmetadata',tick);"
                + "setInterval(tick,250);setTimeout(function(){v.play().catch(function(){});tick();},50);}</script>"
                + "</body></html>";
        webView.loadDataWithBaseURL("https://www.reddit.com/", html, "text/html", "UTF-8", null);
        return webView;
    }

    public static View createVideoPreviewView(Context context, String url) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(0xff000000);
        frame.addView(createMediaWebView(context, url), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return frame;
    }

    public static View createTextPreviewView(Context context, String text) {
        android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xff111111);
        android.widget.TextView textView = new android.widget.TextView(context);
        textView.setText(styledPreviewText(text));
        textView.setTextColor(0xffffffff);
        textView.setTextSize(18.0f);
        textView.setLineSpacing(0.0f, 1.12f);
        int padding = (int) (20.0f * context.getResources().getDisplayMetrics().density);
        textView.setPadding(padding, padding, padding, padding);
        textView.setBackgroundColor(0xff111111);
        textView.setGravity(android.view.Gravity.START);
        scrollView.addView(textView, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scrollView;
    }

    private static CharSequence styledPreviewText(String text) {
        String value = text == null ? "" : text;
        String[] parts = value.split(TEXT_PREVIEW_SEPARATOR, -1);
        SpannableStringBuilder builder = new SpannableStringBuilder();
        if (parts.length > 0 && parts[0].length() > 0) {
            int start = builder.length();
            builder.append(parts[0].trim());
            int end = builder.length();
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new RelativeSizeSpan(1.2f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (parts.length > 1 && parts[1].length() > 0) {
            if (builder.length() > 0) {
                builder.append("\n");
            }
            int start = builder.length();
            builder.append(parts[1].trim());
            int end = builder.length();
            builder.setSpan(new RelativeSizeSpan(0.78f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.setSpan(new ForegroundColorSpan(0xffa7b3ba), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        String body;
        if (parts.length > 2) {
            body = parts[2];
        } else if (parts.length > 1) {
            body = "";
        } else {
            body = value;
        }
        if (body != null && body.trim().length() > 0) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            appendMarkdownText(builder, body.trim());
        }
        return builder.length() > 0 ? builder : value;
    }

    private static void appendMarkdownText(SpannableStringBuilder builder, String markdown) {
        if (markdown == null || markdown.length() == 0) {
            return;
        }
        int index = 0;
        while (index < markdown.length()) {
            int bold = markdown.indexOf("**", index);
            int italic = markdown.indexOf('*', index);
            if (bold < 0 && italic < 0) {
                builder.append(markdown.substring(index));
                return;
            }
            boolean useBold = bold >= 0 && (italic < 0 || bold <= italic);
            int markerStart = useBold ? bold : italic;
            if (markerStart > index) {
                builder.append(markdown.substring(index, markerStart));
            }
            String marker = useBold ? "**" : "*";
            int contentStart = markerStart + marker.length();
            int markerEnd = markdown.indexOf(marker, contentStart);
            if (markerEnd < 0) {
                builder.append(markdown.substring(markerStart));
                return;
            }
            int spanStart = builder.length();
            builder.append(markdown.substring(contentStart, markerEnd));
            int spanEnd = builder.length();
            if (spanEnd > spanStart) {
                builder.setSpan(
                        new StyleSpan(useBold ? Typeface.BOLD : Typeface.ITALIC),
                        spanStart,
                        spanEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
            }
            index = markerEnd + marker.length();
        }
    }

    private static boolean focusFirstPostUnitViaProvider(View compose, int index) {
        try {
            AccessibilityNodeProvider provider = compose.getAccessibilityNodeProvider();
            if (provider == null) {
                Log.w(TAG, "postUnitProvider missing index=" + index);
                return false;
            }

            Object delegate = readField(provider, "a");
            if (delegate == null) {
                Log.w(TAG, "postUnitProvider no delegate index=" + index + " provider=" + provider.getClass().getName());
                return false;
            }

            Object postId = findFirstPostUnitId(compose.getClass().getClassLoader(), provider, delegate);
            if (!(postId instanceof Integer)) {
                Log.w(TAG, "postUnitProvider no semantics post index=" + index + " delegate=" + delegate.getClass().getName());
                return false;
            }
            int id = ((Integer) postId).intValue();
            boolean focus = provider.performAction(id, AccessibilityNodeInfo.ACTION_FOCUS, null);
            Log.w(TAG, "postUnitProvider semanticsAction index=" + index + " id=" + id + " focus=" + focus);
            return focus;
        } catch (Throwable throwable) {
            Log.w(TAG, "postUnitProvider failed", throwable);
            return false;
        }
    }

    private static Object findFirstPostUnitId(ClassLoader loader, AccessibilityNodeProvider provider, Object delegate) throws Exception {
        Object accessibilityDelegate = readField(delegate, "i");
        if (accessibilityDelegate == null) {
            accessibilityDelegate = delegate;
        }
        Method semanticsMapMethod = accessibilityDelegate.getClass().getDeclaredMethod("s");
        semanticsMapMethod.setAccessible(true);
        Object map = semanticsMapMethod.invoke(accessibilityDelegate);
        if (map == null) {
            return null;
        }

        int[] keys = (int[]) readField(map, "b");
        Object[] values = (Object[]) readField(map, "c");
        if (keys == null || values == null) {
            return null;
        }

        Class<?> semanticsKeys = loader.loadClass("androidx.compose.ui.semantics.d");
        Object testTagKey = readStaticField(semanticsKeys, "A");
        Integer bestId = null;
        Rect bestBounds = new Rect();
        int bestTop = Integer.MAX_VALUE;
        for (int i = 0; i < values.length && i < keys.length; i++) {
            Object wrapper = values[i];
            if (wrapper == null) {
                continue;
            }
            Object node = readField(wrapper, "a");
            if (node == null) {
                continue;
            }
            Object config = readField(node, "d");
            Object tag = getSemanticsValue(config, testTagKey);
            if ("post_unit".equals(String.valueOf(tag))) {
                Object id = readField(node, "f");
                if (!(id instanceof Integer)) {
                    continue;
                }
                Rect bounds = getProviderBounds(provider, ((Integer) id).intValue());
                Log.w(TAG, "postUnitProvider candidate id=" + id + " mapKey=" + keys[i] + " bounds=" + bounds);
                if (bounds == null || bounds.height() <= 0 || bounds.bottom <= 120) {
                    continue;
                }
                if (bounds.top < bestTop) {
                    bestTop = bounds.top;
                    bestId = (Integer) id;
                    bestBounds.set(bounds);
                }
            }
        }
        if (bestId != null) {
            Log.w(TAG, "postUnitProvider selected id=" + bestId + " bounds=" + bestBounds);
        }
        return bestId;
    }

    private static Rect findFocusedPostUnitBounds(ClassLoader loader, AccessibilityNodeProvider provider, Object delegate) throws Exception {
        Object accessibilityDelegate = readField(delegate, "i");
        if (accessibilityDelegate == null) {
            accessibilityDelegate = delegate;
        }
        Method semanticsMapMethod = accessibilityDelegate.getClass().getDeclaredMethod("s");
        semanticsMapMethod.setAccessible(true);
        Object map = semanticsMapMethod.invoke(accessibilityDelegate);
        if (map == null) {
            return null;
        }

        Object[] values = (Object[]) readField(map, "c");
        if (values == null) {
            return null;
        }

        Class<?> semanticsKeys = loader.loadClass("androidx.compose.ui.semantics.d");
        Object testTagKey = readStaticField(semanticsKeys, "A");
        for (Object wrapper : values) {
            if (wrapper == null) {
                continue;
            }
            Object node = readField(wrapper, "a");
            if (node == null) {
                continue;
            }
            Object config = readField(node, "d");
            Object tag = getSemanticsValue(config, testTagKey);
            if (!"post_unit".equals(String.valueOf(tag))) {
                continue;
            }
            Object id = readField(node, "f");
            if (!(id instanceof Integer)) {
                continue;
            }
            AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(((Integer) id).intValue());
            if (info == null) {
                continue;
            }
            sealNode(info);
            if (!info.isFocused()) {
                continue;
            }
            Rect bounds = new Rect();
            info.getBoundsInScreen(bounds);
            return bounds;
        }
        return null;
    }

    private static Rect findPostUnitBoundsAt(ClassLoader loader, AccessibilityNodeProvider provider, Object delegate, int rawX, int rawY) throws Exception {
        Object accessibilityDelegate = readField(delegate, "i");
        if (accessibilityDelegate == null) {
            accessibilityDelegate = delegate;
        }
        Method semanticsMapMethod = accessibilityDelegate.getClass().getDeclaredMethod("s");
        semanticsMapMethod.setAccessible(true);
        Object map = semanticsMapMethod.invoke(accessibilityDelegate);
        if (map == null) {
            return null;
        }

        Object[] values = (Object[]) readField(map, "c");
        if (values == null) {
            return null;
        }

        Class<?> semanticsKeys = loader.loadClass("androidx.compose.ui.semantics.d");
        Object testTagKey = readStaticField(semanticsKeys, "A");
        Rect best = null;
        for (Object wrapper : values) {
            if (wrapper == null) {
                continue;
            }
            Object node = readField(wrapper, "a");
            if (node == null) {
                continue;
            }
            Object config = readField(node, "d");
            Object tag = getSemanticsValue(config, testTagKey);
            if (!"post_unit".equals(String.valueOf(tag))) {
                continue;
            }
            Object id = readField(node, "f");
            if (!(id instanceof Integer)) {
                continue;
            }
            Rect bounds = getProviderBounds(provider, ((Integer) id).intValue());
            if (bounds == null || !bounds.contains(rawX, rawY)) {
                continue;
            }
            if (best == null || bounds.height() < best.height()) {
                best = bounds;
            }
        }
        return best;
    }

    private static String findFocusedPostUnitText(ClassLoader loader, AccessibilityNodeProvider provider, Object delegate) throws Exception {
        return findPostUnitText(loader, provider, delegate, Integer.MIN_VALUE, Integer.MIN_VALUE, true);
    }

    private static String findPostUnitTextAt(ClassLoader loader, AccessibilityNodeProvider provider, Object delegate, int rawX, int rawY) throws Exception {
        return findPostUnitText(loader, provider, delegate, rawX, rawY, false);
    }

    private static String findPostUnitText(ClassLoader loader, AccessibilityNodeProvider provider, Object delegate, int rawX, int rawY, boolean focusedOnly) throws Exception {
        Object accessibilityDelegate = readField(delegate, "i");
        if (accessibilityDelegate == null) {
            accessibilityDelegate = delegate;
        }
        Method semanticsMapMethod = accessibilityDelegate.getClass().getDeclaredMethod("s");
        semanticsMapMethod.setAccessible(true);
        Object map = semanticsMapMethod.invoke(accessibilityDelegate);
        if (map == null) {
            return null;
        }

        Object[] values = (Object[]) readField(map, "c");
        if (values == null) {
            return null;
        }

        Class<?> semanticsKeys = loader.loadClass("androidx.compose.ui.semantics.d");
        Object testTagKey = readStaticField(semanticsKeys, "A");
        String best = null;
        int bestHeight = Integer.MAX_VALUE;
        for (Object wrapper : values) {
            if (wrapper == null) {
                continue;
            }
            Object node = readField(wrapper, "a");
            if (node == null) {
                continue;
            }
            Object config = readField(node, "d");
            Object tag = getSemanticsValue(config, testTagKey);
            if (!"post_unit".equals(String.valueOf(tag))) {
                continue;
            }
            Object id = readField(node, "f");
            if (!(id instanceof Integer)) {
                continue;
            }
            AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(((Integer) id).intValue());
            if (info == null) {
                continue;
            }
            sealNode(info);
            Rect bounds = new Rect();
            info.getBoundsInScreen(bounds);
            if (focusedOnly) {
                if (!info.isFocused()) {
                    continue;
                }
            } else if (!bounds.contains(rawX, rawY)) {
                continue;
            }
            String text = readableTreeText(provider, info, 0);
            if (text == null || text.length() == 0) {
                text = readableSemanticsText(loader, config);
            }
            if (text != null && text.length() > 0 && bounds.height() < bestHeight) {
                Log.w(TAG, "postUnit row text=\"" + summarizeText(text) + "\"");
                best = text;
                bestHeight = bounds.height();
            }
        }
        return best;
    }

    private static String readableSemanticsText(ClassLoader loader, Object config) {
        if (loader == null || config == null) {
            return "";
        }
        try {
            Class<?> semanticsKeys = loader.loadClass("androidx.compose.ui.semantics.d");
            StringBuilder builder = new StringBuilder();
            Map<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
            for (Field field : semanticsKeys.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!java.lang.reflect.Modifier.isStatic(modifiers)) {
                    continue;
                }
                field.setAccessible(true);
                Object key = field.get(null);
                Object value = getSemanticsValue(config, key);
                appendReadableSemanticsValue(builder, value, 0, seen);
            }
            return normalizeWhitespace(builder.toString());
        } catch (Throwable throwable) {
            Log.w(TAG, "readableSemanticsText failed", throwable);
            return "";
        }
    }

    private static void appendReadableSemanticsValue(StringBuilder builder, Object value, int depth, Map<Object, Boolean> seen) {
        if (value == null || depth > 4) {
            return;
        }
        if (value instanceof CharSequence) {
            appendReadableSemanticsString(builder, value.toString());
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return;
        }
        if (seen.containsKey(value)) {
            return;
        }
        seen.put(value, Boolean.TRUE);
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                appendReadableSemanticsValue(builder, item, depth + 1, seen);
            }
            return;
        }
        if (type.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                appendReadableSemanticsValue(builder, java.lang.reflect.Array.get(value, i), depth + 1, seen);
            }
            return;
        }
        String className = type.getName();
        if (className.startsWith("java.") || className.startsWith("kotlin.") || className.startsWith("android.")) {
            String text = value.toString();
            if (!text.equals(className + "@" + Integer.toHexString(System.identityHashCode(value)))) {
                appendReadableSemanticsString(builder, text);
            }
            return;
        }
        for (Field field : type.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                appendReadableSemanticsValue(builder, field.get(value), depth + 1, seen);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void appendReadableSemanticsString(StringBuilder builder, String value) {
        String text = normalizeWhitespace(value);
        if (text.length() == 0 || "post_unit".equals(text)) {
            return;
        }
        if (looksLikeNoiseSemanticsText(text)) {
            return;
        }
        if (builder.indexOf(text) >= 0) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(text);
    }

    private static boolean looksLikeNoiseSemanticsText(String text) {
        String lower = text.toLowerCase(Locale.US);
        return lower.equals("button")
                || lower.equals("image")
                || lower.equals("selected")
                || lower.equals("clickable")
                || lower.equals("true")
                || lower.equals("false")
                || lower.startsWith("androidx.compose.")
                || lower.startsWith("kotlin.");
    }

    private static String normalizeWhitespace(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String normalizeForMatch(String value) {
        String text = normalizeWhitespace(value).toLowerCase(Locale.US);
        return text.replaceAll("[^\\p{Alnum}]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static boolean rowContainsTitle(String rowText, String title) {
        return rowTitleMatchScore(rowText, title) > 0;
    }

    public static int rowTitleMatchScore(String rowText, String title) {
        if (rowText == null || title == null || title.length() == 0) {
            return 0;
        }
        if (rowText.contains(title)) {
            return 100000 + title.length();
        }
        String normalizedRow = normalizeForMatch(rowText);
        String normalizedTitle = normalizeForMatch(title);
        if (normalizedTitle.length() == 0) {
            return 0;
        }
        if (normalizedRow.contains(normalizedTitle)) {
            return 90000 + normalizedTitle.length();
        }

        String[] tokens = normalizedTitle.split(" ");
        int total = 0;
        int matched = 0;
        int tokenChars = 0;
        int lastIndex = -1;
        boolean ordered = true;
        for (String token : tokens) {
            if (token.length() < 3) {
                continue;
            }
            total++;
            tokenChars += token.length();
            int index = normalizedRow.indexOf(token);
            if (index >= 0) {
                matched++;
                if (index < lastIndex) {
                    ordered = false;
                }
                lastIndex = index;
            }
        }
        if (total == 0 || matched < 2) {
            return 0;
        }
        int percent = (matched * 100) / total;
        if (percent < 70) {
            return 0;
        }
        return (ordered ? 50000 : 40000) + (percent * 100) + tokenChars;
    }

    private static String summarizeText(String value) {
        String text = normalizeWhitespace(value);
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }

    private static String getCachedBodyForRowText(String rowText) {
        if (rowText == null || rowText.length() == 0) {
            return null;
        }
        synchronized (POST_BODIES) {
            String bestTitle = null;
            int bestScore = 0;
            for (String title : POST_BODIES.keySet()) {
                int score = rowTitleMatchScore(rowText, title);
                if (score > bestScore || (score == bestScore && score > 0 && (bestTitle == null || title.length() > bestTitle.length()))) {
                    bestTitle = title;
                    bestScore = score;
                }
            }
            return bestTitle == null ? null : POST_BODIES.get(bestTitle);
        }
    }

    private static String extractPostBody(Object value, int depth) {
        if (value == null || depth > 5) {
            return null;
        }
        if (value instanceof CharSequence) {
            String text = value.toString();
            return looksLikeBody(text) ? text : null;
        }

        String[] fieldNames = new String[] {
                "rawBodyText_", "bodyText_", "rawBodyText", "bodyText",
                "rawBody", "body", "selfText", "selftext", "markdown", "richtext"
        };
        for (String name : fieldNames) {
            try {
                Object fieldValue = readField(value, name);
                String body = extractPostBody(fieldValue, depth + 1);
                if (body != null && body.length() > 0) {
                    return body;
                }
            } catch (Throwable ignored) {
            }
        }

        String rendered = value.toString();
        String parsed = parseBodyFromToString(rendered, "rawBodyText=");
        if (parsed == null) {
            parsed = parseBodyFromToString(rendered, "bodyText=");
        }
        if (parsed == null) {
            parsed = parseBodyFromToString(rendered, "selfText=");
        }
        if (parsed == null) {
            parsed = parseBodyFromToString(rendered, "selftext=");
        }
        if (parsed == null) {
            parsed = parseBodyFromToString(rendered, "markdown=");
        }
        if (parsed == null) {
            parsed = parseBodyFromToString(rendered, "richtext=");
        }
        return parsed;
    }

    private static String findVideoUrl(Object value, int depth, IdentityHashMap<Object, Boolean> seen) throws Exception {
        if (value == null || depth > 6) {
            return null;
        }
        if (value instanceof CharSequence) {
            String text = value.toString();
            String video = extractPlayableVideoUrlFromText(text);
            if (video != null) {
                return video;
            }
            return null;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof Number || value instanceof Boolean || value instanceof Enum) {
            return null;
        }
        if (seen.containsKey(value)) {
            return null;
        }
        seen.put(value, Boolean.TRUE);

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                String video = findVideoUrl(item, depth + 1, seen);
                if (video != null) {
                    return video;
                }
            }
            return null;
        }
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                String video = findVideoUrl(item, depth + 1, seen);
                if (video != null) {
                    return video;
                }
            }
            return null;
        }
        if (type.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                String video = findVideoUrl(java.lang.reflect.Array.get(value, i), depth + 1, seen);
                if (video != null) {
                    return video;
                }
            }
            return null;
        }

        String rendered = value.toString();
        String renderedVideo = extractPlayableVideoUrlFromText(rendered);
        if (renderedVideo != null) {
            return renderedVideo;
        }

        Class<?> current = type;
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    String video = findVideoUrl(field.get(value), depth + 1, seen);
                    if (video != null) {
                        return video;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static String findBestImageUrl(Object value) throws Exception {
        String[] best = new String[] {null};
        int[] bestScore = new int[] {Integer.MIN_VALUE};
        collectImageUrls(value, 0, new IdentityHashMap<Object, Boolean>(), best, bestScore);
        if (best[0] != null) {
            Log.w(TAG, "bestImageUrl score=" + bestScore[0] + " " + summarizeUrl(best[0]));
        }
        return best[0];
    }

    private static void collectImageUrls(Object value, int depth, IdentityHashMap<Object, Boolean> seen, String[] best, int[] bestScore) throws Exception {
        if (value == null || depth > 7) {
            return;
        }
        if (value instanceof CharSequence) {
            collectImageUrlsFromText(value.toString(), best, bestScore);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof Number || value instanceof Boolean || value instanceof Enum) {
            return;
        }
        if (seen.containsKey(value)) {
            return;
        }
        seen.put(value, Boolean.TRUE);

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                collectImageUrls(item, depth + 1, seen, best, bestScore);
            }
            return;
        }
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                collectImageUrls(item, depth + 1, seen, best, bestScore);
            }
            return;
        }
        if (type.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectImageUrls(java.lang.reflect.Array.get(value, i), depth + 1, seen, best, bestScore);
            }
            return;
        }

        collectImageUrlsFromText(value.toString(), best, bestScore);

        Class<?> current = type;
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    collectImageUrls(field.get(value), depth + 1, seen, best, bestScore);
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
    }

    private static void collectImageUrlsFromText(String text, String[] best, int[] bestScore) {
        if (text == null) {
            return;
        }
        String[] markers = new String[] {"https://", "http://"};
        for (String marker : markers) {
            int start = text.indexOf(marker);
            while (start >= 0) {
                int end = start;
                while (end < text.length()) {
                    char ch = text.charAt(end);
                    if (Character.isWhitespace(ch) || ch == ',' || ch == ')' || ch == ']' || ch == '"') {
                        break;
                    }
                    end++;
                }
                String url = cleanMediaUrl(text.substring(start, end));
                if (isImagePreviewUrl(url)) {
                    int score = imagePreviewScore(url);
                    if (score > bestScore[0]) {
                        best[0] = url;
                        bestScore[0] = score;
                    }
                }
                start = text.indexOf(marker, end);
            }
        }
    }

    private static String cleanMediaUrl(String url) {
        if (url == null) {
            return null;
        }
        return url.replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("\\/", "/")
                .replace("amp;", "")
                .trim();
    }

    private static int imagePreviewScore(String url) {
        if (url == null) {
            return Integer.MIN_VALUE;
        }
        String lower = url.toLowerCase(Locale.US);
        if (isUiAssetPreviewUrl(lower)) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        if (lower.contains("i.redd.it")) {
            score += 1000;
        }
        if (lower.contains("preview.redd.it")) {
            score += 700;
        }
        if (lower.contains("external-preview.redd.it")) {
            score -= 200;
        }
        if (lower.contains("thumbnail") || lower.contains("thumb")) {
            score -= 500;
        }
        if (lower.contains("avatar") || lower.contains("award") || lower.contains("badge")
                || lower.contains("icon") || lower.contains("logo") || lower.contains("snoo")) {
            score -= 1200;
        }
        if (lower.contains("width=216") || lower.contains("width=320")
                || lower.contains("height=216") || lower.contains("height=320")) {
            score -= 300;
        }
        if (lower.contains("width=") || lower.contains("height=") || lower.contains("crop=")) {
            score -= 150;
        }
        if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp")) {
            score += 100;
        }
        score += Math.min(url.length(), 500);
        return score;
    }

    private static String findImageUrl(Object value, int depth, IdentityHashMap<Object, Boolean> seen) throws Exception {
        if (value == null || depth > 6) {
            return null;
        }
        if (value instanceof CharSequence) {
            String text = value.toString();
            if (isImagePreviewUrl(text)) {
                return text;
            }
            return null;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof Number || value instanceof Boolean || value instanceof Enum) {
            return null;
        }
        if (seen.containsKey(value)) {
            return null;
        }
        seen.put(value, Boolean.TRUE);

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                String image = findImageUrl(item, depth + 1, seen);
                if (image != null) {
                    return image;
                }
            }
            return null;
        }
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                String image = findImageUrl(item, depth + 1, seen);
                if (image != null) {
                    return image;
                }
            }
            return null;
        }
        if (type.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                String image = findImageUrl(java.lang.reflect.Array.get(value, i), depth + 1, seen);
                if (image != null) {
                    return image;
                }
            }
            return null;
        }

        String renderedImage = extractImageUrlFromText(value.toString());
        if (renderedImage != null) {
            return renderedImage;
        }

        Class<?> current = type;
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    String image = findImageUrl(field.get(value), depth + 1, seen);
                    if (image != null) {
                        return image;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static String extractVideoUrlFromText(String text) {
        return extractPlayableVideoUrlFromText(text);
    }

    private static String extractPlayableVideoUrlFromText(String text) {
        if (text == null) {
            return null;
        }
        String[] markers = new String[] {"https://", "http://"};
        for (String marker : markers) {
            int start = text.indexOf(marker);
            while (start >= 0) {
                int end = start;
                while (end < text.length()) {
                    char ch = text.charAt(end);
                    if (Character.isWhitespace(ch) || ch == ',' || ch == ')' || ch == ']' || ch == '"') {
                        break;
                    }
                    end++;
                }
                String url = cleanMediaUrl(text.substring(start, end));
                if (isDirectPlayableVideoUrl(url)) {
                    return url;
                }
                start = text.indexOf(marker, end);
            }
        }
        return null;
    }

    private static String extractImageUrlFromText(String text) {
        if (text == null) {
            return null;
        }
        String[] markers = new String[] {"https://", "http://"};
        for (String marker : markers) {
            int start = text.indexOf(marker);
            while (start >= 0) {
                int end = start;
                while (end < text.length()) {
                    char ch = text.charAt(end);
                    if (Character.isWhitespace(ch) || ch == ',' || ch == ')' || ch == ']' || ch == '"') {
                        break;
                    }
                    end++;
                }
                String url = text.substring(start, end);
                if (isImagePreviewUrl(url)) {
                    return url;
                }
                start = text.indexOf(marker, end);
            }
        }
        return null;
    }

    private static boolean isImagePreviewUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        if (isVideoPreviewUrl(lower)) {
            return false;
        }
        if (isUiAssetPreviewUrl(lower)) {
            return false;
        }
        if (lower.contains("preview.redd.it") || lower.contains("i.redd.it")
                || lower.contains("external-preview.redd.it")) {
            return true;
        }
        return (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                || lower.contains(".webp") || lower.contains(".gif"))
                && !lower.contains("favicon")
                && !lower.contains("logo")
                && !lower.contains("profile")
                && !lower.contains("author")
                && !lower.contains("community");
    }

    private static boolean isUsablePreviewMedia(String url) {
        return url != null && url.length() > 0
                && !isUiAssetPreviewUrl(url)
                && (isVideoPreviewUrl(url) || isImagePreviewUrl(url));
    }

    private static boolean isHlsPreviewUrl(String url) {
        return url != null && url.toLowerCase(Locale.US).contains(".m3u8");
    }

    private static boolean isDirectPlayableVideoUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".m3u8")) {
            return true;
        }
        if (!lower.contains("v.redd.it")) {
            return false;
        }
        return lower.contains("dash_") || lower.contains("hlsplaylist") || lower.contains("dashplaylist")
                || lower.endsWith(".mp4") || lower.contains(".mp4?");
    }

    private static boolean isAvatarPreviewUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("redditstatic.com/avatars")
                || lower.contains("snoovatar/avatars")
                || lower.contains("/avatars/defaults/");
    }

    private static boolean isUiAssetPreviewUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return isAvatarPreviewUrl(lower)
                || lower.contains("emoji")
                || lower.contains("award")
                || lower.contains("badge")
                || lower.contains("achievement")
                || lower.contains("trophy")
                || lower.contains("avatar")
                || lower.contains("profile")
                || lower.contains("snoovatar")
                || lower.contains("icon")
                || lower.contains("favicon")
                || lower.contains("logo")
                || lower.contains("snoo")
                || lower.contains("redditstatic.com/")
                || lower.contains("styles.redditmedia.com/")
                || lower.contains("emoji.redditmedia.com/")
                || lower.contains("redditmedia.com/award")
                || lower.contains("redditmedia.com/gold")
                || lower.contains("redditmedia.com/trophy")
                || lower.contains("/subreddit_styles/")
                || lower.contains("/profile_images/");
    }

    private static String extractBodyCandidate(Object value, int depth) {
        if (value == null || depth > 4) {
            return null;
        }
        if (value instanceof CharSequence) {
            String text = value.toString();
            String parsed = parseBodyFromToString(text, "text=");
            if (looksLikeBody(parsed)) {
                return parsed;
            }
            parsed = parseBodyFromToString(text, "markdown=");
            if (looksLikeBody(parsed)) {
                return parsed;
            }
            parsed = parseRichTextBody(text);
            if (looksLikeBody(parsed)) {
                return parsed;
            }
            if (looksLikeBody(text)) {
                return text;
            }
            return null;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || value instanceof Number || value instanceof Boolean || value instanceof Enum) {
            return null;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                String body = extractBodyCandidate(item, depth + 1);
                if (looksLikeBody(body)) {
                    return body;
                }
            }
            return null;
        }
        if (value instanceof Map) {
            for (Object item : ((Map<?, ?>) value).values()) {
                String body = extractBodyCandidate(item, depth + 1);
                if (looksLikeBody(body)) {
                    return body;
                }
            }
            return null;
        }
        if (type.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                String body = extractBodyCandidate(java.lang.reflect.Array.get(value, i), depth + 1);
                if (looksLikeBody(body)) {
                    return body;
                }
            }
            return null;
        }
        String body = extractPostBody(value, depth + 1);
        if (looksLikeBody(body)) {
            return body;
        }
        return null;
    }

    private static String firstThingId(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf("t3_");
        if (start < 0) {
            return null;
        }
        int end = start + 3;
        while (end < text.length()) {
            char ch = text.charAt(end);
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                break;
            }
            end++;
        }
        return end > start + 3 ? text.substring(start, end) : null;
    }

    private static String summarizeUrl(String url) {
        if (url == null) {
            return "null";
        }
        return url.length() <= 96 ? url : url.substring(0, 96) + "...";
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "...";
    }

    private static boolean looksLikeBody(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 8) {
            return false;
        }
        if (trimmed.startsWith("From ") && trimmed.contains(", Posted ")) {
            return false;
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return false;
        }
        return true;
    }

    private static String readablePostRowFallback(String rowText) {
        if (rowText == null) {
            return null;
        }
        String text = normalizeWhitespace(rowText);
        if (text.length() == 0 || looksLikeBody(text)) {
            return text.length() == 0 ? null : text;
        }
        if (text.startsWith("From ") && text.contains(", Posted ")) {
            String[] parts = text.split(", ");
            StringBuilder builder = new StringBuilder();
            for (String part : parts) {
                String lower = part.toLowerCase(Locale.US);
                if (part.startsWith("From ")
                        || part.startsWith("Posted ")
                        || lower.startsWith("link domain:")
                        || lower.contains(" upvote")
                        || lower.contains(" comment")
                        || lower.startsWith("shared ")) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(part);
            }
            text = normalizeWhitespace(builder.toString());
        }
        if (text.length() < 8) {
            return null;
        }
        return text;
    }

    private static String parseBodyFromToString(String value, String marker) {
        if (value == null) {
            return null;
        }
        int start = value.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        int end = value.indexOf(", ", start);
        if (end < 0) {
            end = value.indexOf(')', start);
        }
        if (end <= start) {
            return null;
        }
        String body = value.substring(start, end);
        if ("null".equals(body)) {
            return null;
        }
        return looksLikeBody(body) ? body : null;
    }

    private static String parseRichTextBody(String value) {
        if (value == null || !value.contains("\"document\"") || !value.contains("\"t\"")) {
            return null;
        }
        try {
            Matcher matcher = RICHTEXT_TEXT_PATTERN.matcher(value);
            StringBuilder builder = new StringBuilder();
            while (matcher.find()) {
                String piece = unescapeJsonText(matcher.group(1));
                if (piece == null || piece.trim().length() == 0) {
                    continue;
                }
                if (builder.length() > 0 && !endsWithWhitespace(builder)) {
                    builder.append(' ');
                }
                builder.append(piece.trim());
            }
            String text = normalizeWhitespace(builder.toString());
            return looksLikeBody(text) ? text : null;
        } catch (Throwable throwable) {
            Log.w(TAG, "parseRichTextBody failed", throwable);
            return null;
        }
    }

    private static boolean endsWithWhitespace(StringBuilder builder) {
        return builder.length() > 0 && Character.isWhitespace(builder.charAt(builder.length() - 1));
    }

    private static String unescapeJsonText(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                builder.append(ch);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n':
                    builder.append('\n');
                    break;
                case 'r':
                    builder.append('\n');
                    break;
                case 't':
                    builder.append('\t');
                    break;
                case '"':
                    builder.append('"');
                    break;
                case '\\':
                    builder.append('\\');
                    break;
                case 'u':
                    if (i + 4 < value.length()) {
                        try {
                            builder.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        } catch (Throwable ignored) {
                        }
                    }
                    builder.append("\\u");
                    break;
                default:
                    builder.append(next);
                    break;
            }
        }
        return builder.toString();
    }

    private static boolean clickMatchingNode(AccessibilityNodeInfo node, String needle) {
        if (node == null) {
            return false;
        }
        String text = readableText(node).toLowerCase(Locale.US);
        if (text.contains(needle)) {
            if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null) {
                sealNode(parent);
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            sealNode(child);
            if (clickMatchingNode(child, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean clickComposeNodeMatching(ClassLoader loader, AccessibilityNodeProvider provider, Object delegate, String needle) throws Exception {
        Object accessibilityDelegate = readField(delegate, "i");
        if (accessibilityDelegate == null) {
            accessibilityDelegate = delegate;
        }
        Method semanticsMapMethod = accessibilityDelegate.getClass().getDeclaredMethod("s");
        semanticsMapMethod.setAccessible(true);
        Object map = semanticsMapMethod.invoke(accessibilityDelegate);
        if (map == null) {
            return false;
        }

        Object[] values = (Object[]) readField(map, "c");
        if (values == null) {
            return false;
        }

        for (Object wrapper : values) {
            if (wrapper == null) {
                continue;
            }
            Object node = readField(wrapper, "a");
            if (node == null) {
                continue;
            }
            Object id = readField(node, "f");
            if (!(id instanceof Integer)) {
                continue;
            }
            int virtualId = ((Integer) id).intValue();
            AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(virtualId);
            if (info == null) {
                continue;
            }
            sealNode(info);
            String text = readableText(info).toLowerCase(Locale.US);
            if (!text.contains(needle)) {
                continue;
            }
            boolean clicked = provider.performAction(virtualId, AccessibilityNodeInfo.ACTION_CLICK, null);
            Log.w(TAG, "nextComment provider id=" + virtualId + " text=\"" + text + "\" clicked=" + clicked);
            if (clicked) {
                return true;
            }
        }
        return false;
    }

    private static String readableText(AccessibilityNodeInfo info) {
        StringBuilder builder = new StringBuilder();
        CharSequence text = info.getText();
        if (text != null) {
            builder.append(text);
        }
        CharSequence description = info.getContentDescription();
        if (description != null) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(description);
        }
        return builder.toString().trim();
    }

    private static String readableTreeText(AccessibilityNodeProvider provider, AccessibilityNodeInfo info, int depth) {
        if (info == null || depth > 8) {
            return "";
        }
        StringBuilder builder = new StringBuilder(readableText(info));
        int count = info.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = info.getChild(i);
            } catch (Throwable ignored) {
            }
            if (child == null && provider != null) {
                int virtualId = getChildVirtualId(info, i);
                if (virtualId != Integer.MIN_VALUE) {
                    child = provider.createAccessibilityNodeInfo(virtualId);
                }
            }
            if (child == null) {
                continue;
            }
            sealNode(child);
            String childText = readableTreeText(provider, child, depth + 1);
            if (childText.length() > 0) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(childText);
            }
        }
        return builder.toString().trim();
    }

    private static int getChildVirtualId(AccessibilityNodeInfo node, int index) {
        try {
            Object childId = AccessibilityNodeInfo.class
                    .getMethod("getChildId", int.class)
                    .invoke(node, Integer.valueOf(index));
            if (!(childId instanceof Long)) {
                return Integer.MIN_VALUE;
            }
            try {
                Object virtualId = AccessibilityNodeInfo.class
                        .getMethod("getVirtualDescendantId", long.class)
                        .invoke(null, childId);
                return virtualId instanceof Integer ? ((Integer) virtualId).intValue() : Integer.MIN_VALUE;
            } catch (ReflectiveOperationException ignored) {
                return (int) (((Long) childId).longValue() >> 32);
            }
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static Rect getProviderBounds(AccessibilityNodeProvider provider, int id) {
        try {
            AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(id);
            if (node == null) {
                return null;
            }
            sealNode(node);
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds;
        } catch (Throwable throwable) {
            Log.w(TAG, "postUnitProvider bounds failed id=" + id, throwable);
            return null;
        }
    }

    private static void sealNode(AccessibilityNodeInfo node) {
        try {
            Method method = AccessibilityNodeInfo.class.getDeclaredMethod("setSealed", boolean.class);
            method.setAccessible(true);
            method.invoke(node, Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private static Object getSemanticsValue(Object config, Object key) {
        if (config == null || key == null) {
            return null;
        }
        try {
            Method method = config.getClass().getDeclaredMethod("c", key.getClass());
            method.setAccessible(true);
            return method.invoke(config, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static Object readStaticField(Class<?> type, String name) throws Exception {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static boolean directFeedFocusSearch(View root, View compose, Object owner, int direction, int index) {
        try {
            ClassLoader loader = compose.getClass().getClassLoader();
            Class<?> rectClass = loader.loadClass("h650");
            Constructor<?> rectConstructor = rectClass.getDeclaredConstructor(float.class, float.class, float.class, float.class);
            rectConstructor.setAccessible(true);

            float width = Math.max(root.getWidth(), compose.getWidth());
            float topBarBottom = Math.max(150f, root.getHeight() * 0.115f);
            Object sourceRect = rectConstructor.newInstance(0f, topBarBottom, width, topBarBottom + 1f);

            Class<?> refClass = loader.loadClass("kotlin.jvm.internal.Ref$ObjectRef");
            Object requestFocusSuccess = refClass.getDeclaredConstructor().newInstance();
            Class<?> callbackClass = loader.loadClass("androidx.compose.ui.focus.FocusOwnerImpl$moveFocus$focusSearchSuccess$1");
            Constructor<?> callbackConstructor = callbackClass.getDeclaredConstructor(refClass, int.class);
            callbackConstructor.setAccessible(true);
            Object callback = callbackConstructor.newInstance(requestFocusSuccess, direction);

            Class<?> functionClass = loader.loadClass("kotlin.jvm.functions.Function1");
            Method search = owner.getClass().getDeclaredMethod("h", int.class, rectClass, functionClass);
            search.setAccessible(true);
            Object result = search.invoke(owner, direction, sourceRect, callback);
            boolean handled = Boolean.TRUE.equals(result);
            Object callbackResult = refClass.getField("element").get(requestFocusSuccess);
            Log.w(TAG, "directFeedFocusSearch direction=" + direction + " index=" + index
                    + " y=" + topBarBottom + " result=" + handled + " callback=" + callbackResult);
            return handled;
        } catch (Throwable throwable) {
            Log.w(TAG, "directFeedFocusSearch failed", throwable);
            return false;
        }
    }

    private static void setRedditPatchRedispatching(boolean value) {
        try {
            java.lang.reflect.Field field = Class.forName("app.morphe.extension.reddit.patches.LongPressImagePreviewPatch")
                    .getDeclaredField("REDISPATCHING_FEED_KEY");
            field.setAccessible(true);
            field.setBoolean(null, value);
        } catch (Throwable throwable) {
            Log.w(TAG, "set redispatching failed", throwable);
        }
    }

    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private static boolean takeComposeFocus(View compose, final int direction) {
        try {
            Method getFocusOwner = compose.getClass().getMethod("getFocusOwner");
            Object owner = getFocusOwner.invoke(compose);
            if (owner == null) {
                Log.d(TAG, "focus owner null");
                return false;
            }

            ClassLoader loader = owner.getClass().getClassLoader();
            Log.d(TAG, "owner=" + owner.getClass().getName() + " direction=" + direction);
            Class<?> rectClass = loader.loadClass("h650");
            Object sourceRect = null;
            if (direction == 6) {
                float y = Math.max(220f, compose.getHeight() * 0.18f);
                Constructor<?> rectCtor = rectClass.getConstructor(float.class, float.class, float.class, float.class);
                sourceRect = rectCtor.newInstance(0f, y, (float) compose.getWidth(), y + 1f);
                Log.w(TAG, "sourceRect down y=" + y);
            }
            Class<?> functionClass = loader.loadClass("kotlin.jvm.functions.Function1");
            Object requestFocusCallback = Proxy.newProxyInstance(
                    functionClass.getClassLoader(),
                    new Class<?>[] { functionClass },
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (!"invoke".equals(method.getName()) || args == null || args.length == 0 || args[0] == null) {
                                return Boolean.FALSE;
                            }
                            Method requestFocus = args[0].getClass().getDeclaredMethod("z1", int.class);
                            requestFocus.setAccessible(true);
                            Object result = requestFocus.invoke(args[0], direction);
                            Log.d(TAG, "candidate=" + args[0].getClass().getName() + " request=" + result);
                            return Boolean.TRUE.equals(result);
                        }
                    });

            Method focusSearch = owner.getClass().getDeclaredMethod("h", int.class, rectClass, functionClass);
            focusSearch.setAccessible(true);
            Object result = focusSearch.invoke(owner, direction, sourceRect, requestFocusCallback);
            Log.d(TAG, "focusSearch result=" + result);
            return Boolean.TRUE.equals(result);
        } catch (Throwable throwable) {
            Log.d(TAG, "takeComposeFocus failed", throwable);
            return false;
        }
    }

    private static View findComposeView(View view) {
        if (view == null) {
            return null;
        }
        if ("androidx.compose.ui.platform.c".equals(view.getClass().getName())) {
            return view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View match = findComposeView(group.getChildAt(i));
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static void collectComposeViews(View view, List<View> out) {
        if (view == null) {
            return;
        }
        if ("androidx.compose.ui.platform.c".equals(view.getClass().getName())) {
            out.add(view);
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectComposeViews(group.getChildAt(i), out);
        }
    }
}
