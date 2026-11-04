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
