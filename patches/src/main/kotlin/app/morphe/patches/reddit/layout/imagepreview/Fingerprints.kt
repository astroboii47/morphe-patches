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
    definingClass = "Ldefpackage/adg0;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ldefpackage/st6;",
        "Ldefpackage/qu00;",
        "Lcom/reddit/feeds/caching/data/DataSourceType;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z"
    )
)

internal object CompactVideoConstructorFingerprint : Fingerprint(
    definingClass = "Ldefpackage/bdg0;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ldefpackage/st6;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Ldefpackage/qu00;",
        "Ljava/lang/String;"
    )
)

internal object PostSelfImageElementConstructorFingerprint : Fingerprint(
    definingClass = "Ldefpackage/ck10;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Ldefpackage/qu00;",
        "Ldefpackage/st6;",
        "Ldefpackage/no0;",
        "Ldefpackage/st6;",
        "Z",
        "Z",
        "Ldefpackage/swn;",
        "Z",
        "Lcom/reddit/feeds/caching/data/DataSourceType;"
    )
)

internal object ThumbnailUiModelConstructorFingerprint : Fingerprint(
    definingClass = "Ldefpackage/cl9;",
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
