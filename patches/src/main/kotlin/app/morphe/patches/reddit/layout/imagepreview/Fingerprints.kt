/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.imagepreview

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object CompactSelfImageConstructorFingerprint : Fingerprint(
    definingClass = "Ladg0;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Lst6;",
        "Lqu00;",
        "Lcom/reddit/feeds/caching/data/DataSourceType;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z"
    )
)

internal object CompactVideoConstructorFingerprint : Fingerprint(
    definingClass = "Lbdg0;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Lst6;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Lqu00;",
        "Ljava/lang/String;"
    )
)

internal object PostSelfImageElementConstructorFingerprint : Fingerprint(
    definingClass = "Lck10;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Lqu00;",
        "Lst6;",
        "Lno0;",
        "Lst6;",
        "Z",
        "Z",
        "Lswn;",
        "Z",
        "Lcom/reddit/feeds/caching/data/DataSourceType;"
    )
)

internal object ThumbnailUiModelConstructorFingerprint : Fingerprint(
    definingClass = "Lcl9;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "F",
        "Z",
        "Z",
        "F",
        "Z",
        "Z",
        "Lcom/reddit/domain/model/OverlayData;",
        "Ljava/lang/String;"
    )
)
