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

/** The Compose renderer invoked for every currently visible compact link-preview card. */
internal object LinkPreviewCardRendererFingerprint : Fingerprint(
    definingClass = "Laxr;",
    name = "j",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    parameters = listOf(
        "Lycs;", "Ltri;", "Lsvb;", "Ljax;",
        "Lcom/reddit/linkpreviewcomponent/api/props/LinkPreviewLayout;", "Lyds;", "Li7c;", "I"
    )
)

/** Compact link-thumbnail data: h is the post ID and i is its exact wd7 thumbnail source. */
internal object CompactLinkMediaConstructorFingerprint : Fingerprint(
    definingClass = "Lr140;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Lo030;", "Lwd7;",
        "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Ljava/lang/String;",
        "Ljava/util/List;", "Lcom/reddit/useridentity/PublisherAttribution;",
        "Lcom/reddit/feeds/caching/data/DataSourceType;", "Ljava/lang/String;", "I"
    )
)

/** Runs on Reddit's canonical Link object whenever feed code reads its post identity. */
internal object DomainLinkIdentityFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Link;",
    name = "getKindWithId",
    returnType = "Ljava/lang/String;"
)

/** Runs whenever Reddit reads the full preview data for a feed Link. */
internal object DomainLinkPreviewFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/model/Link;",
    name = "getPreview",
    returnType = "Lcom/reddit/domain/model/Preview;"
)

/** Reddit's full Link-to-feed-row mapper retains complete preview images for link posts. */
internal object FeedDomainLinkMapperFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/feeds/impl/data/mapper/link/a;",
    name = "a",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(
        "Lcom/reddit/domain/model/Link;", "Ljava/util/List;", "Lvv80;", "Ldm80;",
        "Z", "I", "Z", "Z", "Z", "Z",
        "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    )
)

/** The active renderer-side feed-row assembler for Reddit 2026.40.0. */
internal object FeedRowAssemblerFingerprint : Fingerprint(
    definingClass = "Lol0;",
    name = "b",
    returnType = "Lrl0;",
    parameters = listOf("Lb7f;", "Lrp0;")
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

/** Reddit 2026.40.0's normal compact link-card title section. */
internal object FeedTitleWithLinkedPreviewConstructorFingerprint : Fingerprint(
    definingClass = "Ll6j0;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Ljava/lang/String;",
        "Ljava/lang/Integer;", "Lkxp;", "Loz4;"
    )
)

internal object FeedTitleWithLinkedPreviewRenderFingerprint : Fingerprint(
    definingClass = "Ll6j0;",
    name = "b",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList()
)

/** Reddit 2026.40.0's gallery/media section used by expanded and compact feed rows. */
internal object FeedGalleryConstructorFingerprint : Fingerprint(
    definingClass = "Lphk;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Lbik;", "Lzep;", "Z", "Z", "Ljava/lang/String;", "Ljava/lang/Float;", "Z", "Lw9k;"
    )
)

internal object FeedGalleryRenderFingerprint : Fingerprint(
    definingClass = "Lphk;",
    name = "b",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList()
)

/** The container that Reddit builds for every normal feed post. */
internal object FeedPostUnitConstructorFingerprint : Fingerprint(
    definingClass = "Lq0j;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(
        "Ljava/lang/String;", "Lzep;", "Ljava/lang/String;", "Lo030;",
        "Z", "Z", "Z", "Z", "Z", "Ljava/util/Set;", "Lzw6;"
    )
)

internal object LinkPreviewCardRenderFingerprint : Fingerprint(
    definingClass = "Lzcs;",
    name = "b",
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = emptyList()
)
