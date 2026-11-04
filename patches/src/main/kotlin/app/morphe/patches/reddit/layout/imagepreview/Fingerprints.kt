/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.imagepreview

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/** Reddit 2026.40.0's self-image feed section. */
internal object FeedSelfImageConstructorFingerprint : Fingerprint(
    definingClass = "Lrbp;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Lpt30;", "Z", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;",
        "Z", "Z", "Z", "Lw6c;", "Lsvb;", "Z", "Z", "Lsvb;", "Lmfq;", "Z",
        "Ljava/lang/Float;", "Z", "Lqt30;"
    )
)

/** Reddit 2026.40.0's feed title-with-thumbnail section. */
internal object FeedTitleWithThumbnailConstructorFingerprint : Fingerprint(
    definingClass = "Lb240;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Ljava/lang/String;", "I",
        "Lpzo0;", "Lkxp;", "Z", "Ll7j;", "Ljava/lang/String;", "Ljava/lang/String;"
    )
)

/** Reddit 2026.40.0's feed title section. */
internal object FeedTitleConstructorFingerprint : Fingerprint(
    definingClass = "Lz0j;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Lx140;", "Lkotlin/jvm/functions/Function2;", "Z")
)

/** Reddit 2026.40.0's active compact link-preview card data. */
internal object LinkPreviewCardDataConstructorFingerprint : Fingerprint(
    definingClass = "Lycs;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;", "Lo030;", "Ljava/lang/String;", "Z",
        "Ljava/lang/String;", "I", "Z", "Lkxp;", "Ljava/lang/String;",
        "Ljava/lang/String;", "Lcom/reddit/linkpreviewcomponent/api/props/LinkPreviewLayout;",
        "Lyds;", "Ljava/lang/String;", "Lcom/reddit/publisher/LinkPostContainersVariant;"
    )
)

/** Compose's TestTag semantics node used by Reddit 2026.40.0. */
internal object TestTagSemanticsFingerprint : Fingerprint(
    definingClass = "Lymi0;",
    name = "c",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Llhe0;")
)

/** Render methods run whenever these media sections are visible, including after process restore. */
internal object FeedSelfImageRenderFingerprint : Fingerprint(
    definingClass = "Lrbp;",
    name = "b",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList()
)

internal object FeedTitleWithThumbnailRenderFingerprint : Fingerprint(
    definingClass = "Lb240;",
    name = "b",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList()
)

internal object LinkPreviewCardRenderFingerprint : Fingerprint(
    definingClass = "Lzcs;",
    name = "b",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList()
)
