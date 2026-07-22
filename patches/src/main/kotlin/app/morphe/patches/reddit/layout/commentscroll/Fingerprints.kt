/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.commentscroll

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object CommentsListContentFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/comments/presentation/composables/d;",
    name = "c",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Landroidx/compose/foundation/lazy/b;",
        "Z",
        "Loev;",
        "Lu0m0;",
        "I",
        "I",
        "Licb;",
        "I"
    )
)

internal object LazyListStateUpdateScrollFingerprint : Fingerprint(
    definingClass = "Landroidx/compose/foundation/lazy/b;",
    name = "k",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("I", "I", "Z")
)

internal object LazyListStateLayoutUpdateFingerprint : Fingerprint(
    definingClass = "Landroidx/compose/foundation/lazy/b;",
    name = "g",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Lu2q;", "Z", "Z")
)
