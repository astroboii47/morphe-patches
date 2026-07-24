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
    private static final Map<Object, String> RESTORE_CHECKED_LISTS = new WeakHashMap<>();
    private static final Map<Object, Position> PENDING_RESTORES = new WeakHashMap<>();
    private static final Map<Object, Long> SUPPRESS_SAVE_UNTIL = new WeakHashMap<>();
    private static final Map<Object, Long> LAST_LAYOUT_SAVE_MS = new WeakHashMap<>();
    private static final int RESTORE_OFFSET_TOLERANCE_PX = 24;
    private static final long RESTORE_SETTLE_MS = 2500L;
    private static final long LAYOUT_SAVE_THROTTLE_MS = 250L;

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

            if (markRestoreChecked(lazyListState, key)) {
                restorePosition(key, lazyListState);
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

        if (!requestRemeasure) {
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
            synchronized (PENDING_RESTORES) {
                Position pending = PENDING_RESTORES.get(lazyListState);
                if (pending != null) {
                    if (incoming.isNear(pending)) {
                        PENDING_RESTORES.remove(lazyListState);
                        synchronized (SUPPRESS_SAVE_UNTIL) {
                            SUPPRESS_SAVE_UNTIL.put(
                                    lazyListState,
                                    System.currentTimeMillis() + RESTORE_SETTLE_MS
                            );
                        }
                    } else {
                        synchronized (SUPPRESS_SAVE_UNTIL) {
                            Long suppressUntil = SUPPRESS_SAVE_UNTIL.get(lazyListState);
                            if (suppressUntil != null && System.currentTimeMillis() < suppressUntil) {
                                return;
                            }
                        }
                        PENDING_RESTORES.remove(lazyListState);
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

    private static boolean markRestoreChecked(Object lazyListState, String key) {
        synchronized (RESTORE_CHECKED_LISTS) {
            String checkedKey = RESTORE_CHECKED_LISTS.get(lazyListState);
            if (key.equals(checkedKey)) {
                return false;
            }
            RESTORE_CHECKED_LISTS.put(lazyListState, key);
            return true;
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

    private static void restorePosition(String key, Object lazyListState) {
        try {
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
            synchronized (PENDING_RESTORES) {
                PENDING_RESTORES.put(lazyListState, position);
            }
            synchronized (SUPPRESS_SAVE_UNTIL) {
                SUPPRESS_SAVE_UNTIL.put(lazyListState, System.currentTimeMillis() + RESTORE_SETTLE_MS);
            }
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
