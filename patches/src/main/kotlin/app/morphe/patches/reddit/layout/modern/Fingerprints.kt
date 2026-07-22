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

internal object HomePagerMainNavigationButtonFingerprint : Fingerprint(
    definingClass = "La4n;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "Ljava/lang/Object;",
        "Ljava/lang/Object;",
        "Ljava/lang/Object;"
    )
)
