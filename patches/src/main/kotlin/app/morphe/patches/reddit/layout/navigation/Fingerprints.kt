/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val ADD_METHOD_CALL = methodCall(
    opcode = Opcode.INVOKE_INTERFACE,
    smali = "Ljava/util/List;->add(Ljava/lang/Object;)Z"
)

internal object BottomNavScreenListBuilderFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L"),
    filters = listOf(
        newInstance("Ljava/util/ArrayList;"),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            smali = "Ljava/util/Iterator;->hasNext()Z"
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Lcom/reddit/launch/bottomnav/BottomNavTab;"
        )
    )
)

internal object BottomNavScreenSideNavLayoutFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    name = "n5",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Z",
        "Landroidx/compose/runtime/internal/a;",
        "Z",
        "Landroidx/compose/runtime/internal/a;",
        "L",
        "Landroidx/compose/runtime/internal/a;",
        "L",
        "I"
    )
)

internal object BottomNavScreenContentFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    name = "s5",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L", "L", "I"),
    filters = listOf(
        string("navStackFeatures"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Ljava/lang/Boolean;->booleanValue()Z"
        )
    )
)

internal object NavHostInsetsFingerprint : Fingerprint(
    definingClass = "Lrr5;",
    name = "a",
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf("Z", "L"),
    filters = listOf(
        literal(96.0f),
        opcode(Opcode.INVOKE_VIRTUAL)
    )
)

internal object NavStackEntryContentInsetsFingerprint : Fingerprint(
    definingClass = "Lnr5;",
    name = "a",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L", "Landroidx/compose/runtime/internal/a;", "L", "I"),
    filters = listOf(
        literal(96.0f),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            smali = "Lyan0;->p(Loev;FFFFI)Loev;"
        )
    )
)

internal object HomeFeedContentFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/feeds/home/impl/ui/a;",
    name = "invoke",
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L", "L"),
    filters = listOf(
        string("home_screen_surface")
    )
)

internal object NavStackEntryRendererFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/navstack/h;",
    name = "invoke",
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("L", "L", "L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Lcom/reddit/navstack/m;->o(Lojw;ILandroidx/compose/runtime/snapshots/SnapshotStateList;ZZ)Lgad;"
        )
    )
)

internal object BottomNavScreenResourceBuilderLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            parameters = listOf("Ljava/lang/String;", "L")
        ),
        ADD_METHOD_CALL
    ),
    strings = listOf("answersFeatures")
)
