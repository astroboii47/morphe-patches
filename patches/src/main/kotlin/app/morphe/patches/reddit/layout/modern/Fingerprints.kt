/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.modern

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object HomeRevampVariantFingerprint : Fingerprint(
    definingClass = "/HomeRevampVariant;",
    name = "isEnabled",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf()
)

internal object HomeRevampM1SearchBarFingerprint : Fingerprint(
    definingClass = "Lkz0;",
    name = "E",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Lkotlin/jvm/functions/Function0;",
        "Loev;",
        "Ljava/lang/String;",
        "Z",
        "Z",
        "Licb;",
        "I"
    )
)

internal object HomeRevampTopBarBuilderFingerprint : Fingerprint(
    definingClass = "Li11;",
    name = "B",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Lswn;",
        "Landroidx/compose/foundation/pager/d;",
        "Lemd0;",
        "Lkotlin/jvm/functions/Function1;",
        "Loev;",
        "Z",
        "Z",
        "Z",
        "Lkotlin/jvm/functions/Function0;",
        "Lwmw;",
        "Lkotlin/jvm/functions/Function0;",
        "Landroidx/compose/runtime/internal/a;",
        "Landroidx/compose/runtime/internal/a;",
        "Lw1j;",
        "Landroidx/compose/runtime/internal/a;",
        "Licb;",
        "I"
    )
)
