/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

public final class RedditKeyInjector {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RedditKeyInjector() {
    }

    public static void handoff(Activity activity, int originalKeyCode) {
        send(KeyEvent.KEYCODE_DPAD_DOWN, 0);
        send(KeyEvent.KEYCODE_DPAD_RIGHT, 140);
        send(KeyEvent.KEYCODE_DPAD_RIGHT, 280);
        send(KeyEvent.KEYCODE_DPAD_RIGHT, 420);
        send(KeyEvent.KEYCODE_TAB, 620);
        send(originalKeyCode, 820);
    }

    private static void send(final int keyCode, long delayMs) {
        MAIN.postDelayed(() -> new Thread(() -> {
            try {
                new Instrumentation().sendKeyDownUpSync(keyCode);
            } catch (Throwable ignored) {
            }
        }, "morphe-reddit-key").start(), delayMs);
    }
}
