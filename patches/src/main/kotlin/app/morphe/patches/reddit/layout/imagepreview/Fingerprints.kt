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

internal object PostTitleWithThumbnailSectionConstructorFingerprint : Fingerprint(
    definingClass = "Lwr10;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Ljava/lang/String;",
        "I",
        "Lhpr;",
        "Lq9o;",
        "Z",
        "Lwvh;",
        "Ljava/lang/String;",
        "Ljava/lang/String;"
    )
)

internal object TitleWithThumbnailElementConstructorFingerprint : Fingerprint(
    definingClass = "Lpkg0;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Lqu00;",
        "Lsr10;",
        "Lgm20;",
        "Lrr10;",
        "Lq9o;",
        "Lcom/reddit/feeds/caching/data/DataSourceType;"
    )
)

internal object CompactLinkConstructorFingerprint : Fingerprint(
    definingClass = "Lzcg0;",
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
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z"
    )
)

internal object PostTitleElementConstructorFingerprint : Fingerprint(
    definingClass = "Lsr10;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Lqu00;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Z",
        "I",
        "Ljava/lang/String;",
        "Z",
        "Ljava/lang/Integer;",
        "Z"
    )
)

internal object PostMediaWebsiteElementConstructorFingerprint : Fingerprint(
    definingClass = "Lw110;",
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
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z"
    )
)

internal object GalleryElementConstructorFingerprint : Fingerprint(
    definingClass = "Lz4j;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Lqu00;",
        "I",
        "Ljava/util/List;",
        "I",
        "Z",
        "Lcom/reddit/feeds/caching/data/DataSourceType;"
    )
)

internal object CompactPostPreviewItemConstructorFingerprint : Fingerprint(
    definingClass = "Li0b;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Luya;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Z"
    )
)

internal object CompactPostPreviewConstructorFingerprint : Fingerprint(
    definingClass = "Luya;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Lsya;",
        "Lsya;",
        "Ltya;",
        "Ljava/lang/String;",
        "Luya;"
    )
)
