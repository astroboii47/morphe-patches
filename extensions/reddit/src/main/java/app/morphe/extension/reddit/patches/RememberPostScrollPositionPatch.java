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
    private static final Map<Object, Object> PROVIDER_LISTS = new WeakHashMap<>();
    private static final Map<Object, String> PROVIDER_KEYS = new WeakHashMap<>();
    private static final Map<Object, Position> LATEST_POSITIONS = new WeakHashMap<>();
    private static final Map<String, Object> LISTS_BY_KEY = new LinkedHashMap<String, Object>(MAX_POSITIONS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
            return size() > MAX_POSITIONS;
        }
    };
    private static final Map<String, Position> DRAFT_POSITIONS = new LinkedHashMap<String, Position>(MAX_POSITIONS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Position> eldest) {
            return size() > MAX_POSITIONS;
        }
    };
    private static final Map<Object, String> RESTORE_CHECKED_RENDER_HANDLERS = new WeakHashMap<>();
    private static final Map<Object, Position> PENDING_RESTORES = new WeakHashMap<>();
    private static final Map<Object, Integer> RESTORE_RETRIES = new WeakHashMap<>();
    private static final Map<Object, Long> SUPPRESS_SAVE_UNTIL = new WeakHashMap<>();
    private static final Map<Object, Long> LAST_LAYOUT_SAVE_MS = new WeakHashMap<>();
    private static final int RESTORE_OFFSET_TOLERANCE_PX = 24;
    private static final int MAX_RESTORE_RETRIES = 4;
    private static final long RESTORE_SETTLE_MS = 1800L;
    private static final long LAYOUT_SAVE_THROTTLE_MS = 75L;

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
            synchronized (PROVIDER_LISTS) {
                PROVIDER_LISTS.put(provider, lazyListState);
            }
            synchronized (PROVIDER_KEYS) {
                PROVIDER_KEYS.put(provider, key);
            }
            synchronized (LISTS_BY_KEY) {
                LISTS_BY_KEY.put(key, lazyListState);
            }

        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to bind Reddit post scroll position", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void saveBoundPosition(Object lazyListState, int index, int offset, boolean requestRemeasure) {
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

            int safeIndex = Math.max(0, index);
            int safeOffset = Math.max(0, offset);
            if (safeIndex == 0 && safeOffset == 0) {
                return;
            }

            Position incoming = new Position(safeIndex, safeOffset);
            synchronized (LATEST_POSITIONS) {
                LATEST_POSITIONS.put(lazyListState, incoming);
            }
            synchronized (DRAFT_POSITIONS) {
                DRAFT_POSITIONS.put(key, incoming);
            }

            synchronized (PENDING_RESTORES) {
                Position pending = PENDING_RESTORES.get(lazyListState);
                if (pending != null) {
                    synchronized (SUPPRESS_SAVE_UNTIL) {
                        Long suppressUntil = SUPPRESS_SAVE_UNTIL.get(lazyListState);
                        if (suppressUntil != null && System.currentTimeMillis() < suppressUntil) {
                            if (!incoming.isNear(pending) && incoming.isBefore(pending) &&
                                    retryRestore(lazyListState, pending)) {
                                return;
                            }
                            return;
                        }
                    }
                    PENDING_RESTORES.remove(lazyListState);
                    synchronized (RESTORE_RETRIES) {
                        RESTORE_RETRIES.remove(lazyListState);
                    }
                }
            }

            synchronized (SUPPRESS_SAVE_UNTIL) {
                Long suppressUntil = SUPPRESS_SAVE_UNTIL.get(lazyListState);
                if (suppressUntil != null) {
                    if (System.currentTimeMillis() < suppressUntil) {
                        return;
                    }
                    SUPPRESS_SAVE_UNTIL.remove(lazyListState);
                }
            }

            synchronized (POSITIONS) {
                POSITIONS.put(key, incoming);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to save Reddit post scroll position", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void saveBoundPositionFromLayout(Object lazyListState, Object layoutInfo, boolean scrollPass) {
        if (!isPatchIncluded() && !Settings.REMEMBER_POST_SCROLL_POSITION.get()) {
            return;
        }

        try {
            if (!canSampleLayoutPosition(lazyListState)) {
                return;
            }

            Position position = getPositionFromLayoutInfo(layoutInfo);
            if (position == null) {
                return;
            }

            saveBoundPosition(lazyListState, position.index, position.offset, true);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to save Reddit post scroll position from layout", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void saveOnPostExit(Object screen) {
        if (!isPatchIncluded() && !Settings.REMEMBER_POST_SCROLL_POSITION.get()) {
            return;
        }

        try {
            Object provider = getCommentsProvider(screen);
            if (provider == null) {
                return;
            }

            String key = null;
            try {
                key = getPostKey(provider);
            } catch (Throwable ignored) {
            }
            if (key == null) {
                synchronized (PROVIDER_KEYS) {
                    key = PROVIDER_KEYS.get(provider);
                }
            }

            Object lazyListState;
            synchronized (PROVIDER_LISTS) {
                lazyListState = PROVIDER_LISTS.get(provider);
            }
            if (key == null && lazyListState != null) {
                synchronized (BOUND_LISTS) {
                    key = BOUND_LISTS.get(lazyListState);
                }
            }
            if (key == null) {
                return;
            }
            if (lazyListState == null) {
                synchronized (LISTS_BY_KEY) {
                    lazyListState = LISTS_BY_KEY.get(key);
                }
            }

            Position position = null;
            if (lazyListState != null) {
                try {
                    position = getPositionFromLazyListState(lazyListState);
                } catch (Throwable ignored) {
                }
            }
            if (position == null && lazyListState != null) {
                synchronized (LATEST_POSITIONS) {
                    position = LATEST_POSITIONS.get(lazyListState);
                }
            }
            if (position == null) {
                synchronized (DRAFT_POSITIONS) {
                    position = DRAFT_POSITIONS.get(key);
                }
            }
            if (position == null || (position.index <= 0 && position.offset <= 0)) {
                return;
            }

            synchronized (POSITIONS) {
                POSITIONS.put(key, position);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to save Reddit post scroll position on exit", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void restoreOnCommentsRendered(Object handler) {
        if (!isPatchIncluded() && !Settings.REMEMBER_POST_SCROLL_POSITION.get()) {
            return;
        }

        try {
            String key = getPostKeyFromCommentsParamsField(handler, "a");
            if (key == null) {
                return;
            }

            Position position = getSavedPosition(key);
            if (position == null || (position.index <= 0 && position.offset <= 0)) {
                return;
            }

            Object lazyListState;
            synchronized (LISTS_BY_KEY) {
                lazyListState = LISTS_BY_KEY.get(key);
            }
            if (lazyListState == null) {
                return;
            }

            synchronized (RESTORE_CHECKED_RENDER_HANDLERS) {
                String checked = RESTORE_CHECKED_RENDER_HANDLERS.get(handler);
                if (key.equals(checked)) {
                    return;
                }
                RESTORE_CHECKED_RENDER_HANDLERS.put(handler, key);
            }
            requestRestore(lazyListState, position);
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to restore Reddit post scroll position on comments rendered", ex);
        }
    }

    private static boolean canSampleLayoutPosition(Object lazyListState) {
        long now = System.currentTimeMillis();
        synchronized (LAST_LAYOUT_SAVE_MS) {
            Long lastSampleMs = LAST_LAYOUT_SAVE_MS.get(lazyListState);
            if (lastSampleMs != null && now - lastSampleMs < LAYOUT_SAVE_THROTTLE_MS) {
                return false;
            }
            LAST_LAYOUT_SAVE_MS.put(lazyListState, now);
            return true;
        }
    }

    private static void requestRestore(Object lazyListState, Position position) {
        try {
            Method requestScrollToItem = lazyListState.getClass().getDeclaredMethod("i", int.class, int.class);
            requestScrollToItem.setAccessible(true);
            requestScrollToItem.invoke(lazyListState, position.index, position.offset);
            synchronized (PENDING_RESTORES) {
                PENDING_RESTORES.put(lazyListState, position);
            }
            synchronized (RESTORE_RETRIES) {
                RESTORE_RETRIES.put(lazyListState, 0);
            }
            synchronized (SUPPRESS_SAVE_UNTIL) {
                SUPPRESS_SAVE_UNTIL.put(lazyListState, System.currentTimeMillis() + RESTORE_SETTLE_MS);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to restore Reddit post scroll position", ex);
        }
    }

    private static boolean retryRestore(Object lazyListState, Position position) {
        synchronized (RESTORE_RETRIES) {
            Integer retries = RESTORE_RETRIES.get(lazyListState);
            int retryCount = retries != null ? retries : 0;
            if (retryCount >= MAX_RESTORE_RETRIES) {
                return false;
            }
            RESTORE_RETRIES.put(lazyListState, retryCount + 1);
        }

        try {
            Method requestScrollToItem = lazyListState.getClass().getDeclaredMethod("i", int.class, int.class);
            requestScrollToItem.setAccessible(true);
            requestScrollToItem.invoke(lazyListState, position.index, position.offset);
            synchronized (SUPPRESS_SAVE_UNTIL) {
                SUPPRESS_SAVE_UNTIL.put(lazyListState, System.currentTimeMillis() + RESTORE_SETTLE_MS);
            }
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Failed to retry Reddit post scroll restore", ex);
            return false;
        }
    }

    private static Position getSavedPosition(String key) {
        synchronized (POSITIONS) {
            return POSITIONS.get(key);
        }
    }

    private static Object getCommentsProvider(Object screen) throws ReflectiveOperationException {
        Object provider = invokeNoArg(screen, "n5");
        if (provider == null) {
            provider = invokeNoArg(screen, "o5");
        }
        return unwrapLazy(provider);
    }

    private static Object invokeNoArg(Object instance, String methodName) throws ReflectiveOperationException {
        if (instance == null) {
            return null;
        }

        try {
            Method method = instance.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(instance);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Object unwrapLazy(Object provider) {
        if (provider == null) {
            return null;
        }

        try {
            Method get = provider.getClass().getDeclaredMethod("get");
            get.setAccessible(true);
            Object value = get.invoke(provider);
            return value != null ? value : provider;
        } catch (Throwable ignored) {
            return provider;
        }
    }

    private static String getPostKeyFromCommentsParamsField(Object instance, String fieldName) throws ReflectiveOperationException {
        if (instance == null) {
            return null;
        }

        Field field = instance.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return getPostKeyFromCommentsParams(field.get(instance));
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

        return getPostKeyFromCommentsParams(params);
    }

    private static String getPostKeyFromCommentsParams(Object params) throws IllegalAccessException {
        if (params == null) {
            return null;
        }

        String linkId = getStringField(params, "d0");
        if (linkId != null) {
            return linkId;
        }

        String linkKindWithId = getStringField(params, "a");
        if (linkKindWithId != null) {
            return linkKindWithId;
        }

        return getStringField(params, "b");
    }

    private static String getStringField(Object instance, String fieldName) throws IllegalAccessException {
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(instance);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
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

        Field offsetField = layoutInfo.getClass().getDeclaredField("b");
        offsetField.setAccessible(true);
        int offset = offsetField.getInt(layoutInfo);

        return new Position(Math.max(0, index), Math.max(0, offset));
    }

    private static Position getPositionFromLazyListState(Object lazyListState) throws ReflectiveOperationException {
        if (lazyListState == null) {
            return null;
        }

        Field scrollPositionField = lazyListState.getClass().getDeclaredField("e");
        scrollPositionField.setAccessible(true);
        Object scrollPosition = scrollPositionField.get(lazyListState);
        if (scrollPosition == null) {
            return null;
        }

        Method getIndex = scrollPosition.getClass().getDeclaredMethod("a");
        getIndex.setAccessible(true);
        Method getOffset = scrollPosition.getClass().getDeclaredMethod("b");
        getOffset.setAccessible(true);

        int index = ((Number) getIndex.invoke(scrollPosition)).intValue();
        int offset = ((Number) getOffset.invoke(scrollPosition)).intValue();
        return new Position(Math.max(0, index), Math.max(0, offset));
    }

    private static float getScrollDelta(Object layoutInfo) throws ReflectiveOperationException {
        if (layoutInfo == null) {
            return 0f;
        }

        Field scrollDeltaField = layoutInfo.getClass().getDeclaredField("d");
        scrollDeltaField.setAccessible(true);
        return scrollDeltaField.getFloat(layoutInfo);
    }

    private static final class Position {
        final int index;
        final int offset;

        Position(int index, int offset) {
            this.index = index;
            this.offset = offset;
        }

        boolean isBefore(Position other) {
            return index < other.index ||
                    (index == other.index && offset + RESTORE_OFFSET_TOLERANCE_PX < other.offset);
        }

        boolean isNear(Position other) {
            return index == other.index &&
                    Math.abs(offset - other.offset) <= RESTORE_OFFSET_TOLERANCE_PX;
        }
    }

}
