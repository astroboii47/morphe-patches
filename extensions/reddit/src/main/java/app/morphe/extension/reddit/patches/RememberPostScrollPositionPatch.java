/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class RememberPostScrollPositionPatch {
    private static final int MAX_POSITIONS = 64;
    private static final Map<String, Position> POSITIONS = new LinkedHashMap<String, Position>(MAX_POSITIONS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Position> eldest) {
            return size() > MAX_POSITIONS;
        }
    };
    private static final Map<Object, String> BOUND_LISTS = new WeakHashMap<>();
    private static final Map<Object, RestoreAttempt> RESTORE_ATTEMPTS = new WeakHashMap<>();
    private static final int MAX_RESTORE_ATTEMPTS = 12;

    private RememberPostScrollPositionPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void bindAndRestorePosition(Object provider, Object lazyListState) {
        if (!isPatchIncluded() && !Settings.REMEMBER_POST_SCROLL_POSITION.get()) {
            return;
        }

        try {
            String key = getPostKey(provider);
            if (key == null || lazyListState == null) {
                return;
            }

            synchronized (BOUND_LISTS) {
                BOUND_LISTS.put(lazyListState, key);
            }

            restorePosition(key, lazyListState);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to bind Reddit post scroll position", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void saveBoundPosition(Object lazyListState, int index, int offset) {
        if (!isPatchIncluded() && !Settings.REMEMBER_POST_SCROLL_POSITION.get()) {
            return;
        }

        try {
            String key;
            synchronized (BOUND_LISTS) {
                key = BOUND_LISTS.get(lazyListState);
            }
            if (key == null) {
                return;
            }

            synchronized (POSITIONS) {
                POSITIONS.put(key, new Position(Math.max(0, index), Math.max(0, offset)));
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to save Reddit post scroll position", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void saveBoundPositionFromLayout(Object lazyListState, Object layoutInfo) {
        if (!isPatchIncluded() && !Settings.REMEMBER_POST_SCROLL_POSITION.get()) {
            return;
        }

        try {
            Position position = getPositionFromLayoutInfo(layoutInfo);
            if (position == null) {
                return;
            }

            saveBoundPosition(lazyListState, position.index, position.offset);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to save Reddit post scroll position from layout", ex);
        }
    }

    private static void restorePosition(String key, Object lazyListState) {
        try {
            synchronized (RESTORE_ATTEMPTS) {
                RestoreAttempt attempt = RESTORE_ATTEMPTS.get(lazyListState);
                if (attempt != null && key.equals(attempt.key) && attempt.count >= MAX_RESTORE_ATTEMPTS) {
                    return;
                }

                if (attempt == null || !key.equals(attempt.key)) {
                    attempt = new RestoreAttempt(key);
                }
                attempt.count++;
                RESTORE_ATTEMPTS.put(lazyListState, attempt);
            }

            Position position;
            synchronized (POSITIONS) {
                position = POSITIONS.get(key);
            }
            if (position == null || (position.index <= 0 && position.offset <= 0)) {
                return;
            }

            Method requestScrollToItem = lazyListState.getClass().getDeclaredMethod("i", int.class, int.class);
            requestScrollToItem.setAccessible(true);
            requestScrollToItem.invoke(lazyListState, position.index, position.offset);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to restore Reddit post scroll position", ex);
        }
    }

    private static String getPostKey(Object provider) throws ReflectiveOperationException {
        if (provider == null) {
            return null;
        }

        Field paramsField = provider.getClass().getDeclaredField("f");
        paramsField.setAccessible(true);
        Object params = paramsField.get(provider);
        if (params == null) {
            return null;
        }

        Field idField = params.getClass().getDeclaredField("a");
        idField.setAccessible(true);
        Object value = idField.get(params);
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    private static Position getPositionFromLayoutInfo(Object layoutInfo) throws ReflectiveOperationException {
        if (layoutInfo == null) {
            return null;
        }

        Field firstItemField = layoutInfo.getClass().getDeclaredField("a");
        firstItemField.setAccessible(true);
        Object firstItem = firstItemField.get(layoutInfo);
        if (firstItem == null) {
            return null;
        }

        Field indexField = firstItem.getClass().getDeclaredField("a");
        indexField.setAccessible(true);
        int index = indexField.getInt(firstItem);

        int offset = 0;
        try {
            Field offsetField = firstItem.getClass().getDeclaredField("h");
            offsetField.setAccessible(true);
            offset = offsetField.getInt(firstItem);
        } catch (NoSuchFieldException ignored) {
            // Restoring to the first visible comment is still useful if offset storage changes.
        }

        return new Position(Math.max(0, index), Math.max(0, offset));
    }

    private static final class RestoreAttempt {
        final String key;
        int count;

        RestoreAttempt(String key) {
            this.key = key;
        }
    }

    private static final class Position {
        final int index;
        final int offset;

        Position(int index, int offset) {
            this.index = index;
            this.offset = offset;
        }
    }
}
