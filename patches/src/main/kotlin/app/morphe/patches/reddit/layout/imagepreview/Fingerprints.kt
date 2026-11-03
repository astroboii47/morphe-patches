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

/** Reddit's stable post presentation model, including external-link thumbnail resolutions. */
internal object LinkPresentationModelConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/presentation/listing/model/LinkPresentationModel;",
    name = "<init>",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR)
)
