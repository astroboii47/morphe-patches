/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.imagepreview

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/LongPressImagePreviewPatch;"

@Suppress("unused")
val longPressImagePreviewPatch = bytecodePatch(
    name = "Long press image preview",
    description = "Shows a large image preview while holding a feed image."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        fun hookConstructor(
            fingerprint: app.morphe.patcher.Fingerprint,
            instructions: String
        ) {
            fingerprint.method.apply {
                val insertIndex = implementation!!.instructions.indexOfLast {
                    it.opcode == Opcode.RETURN_VOID
                }
                check(insertIndex >= 0) {
                    "Could not find constructor return instruction"
                }

                addInstructions(insertIndex, instructions)
            }
        }

        fun hookMethodStart(
            fingerprint: app.morphe.patcher.Fingerprint,
            instructions: String
        ) {
            fingerprint.method.addInstructions(0, instructions)
        }

        hookConstructor(
            CompactSelfImageConstructorFingerprint,
            """
                invoke-static { p4, p1 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            CompactVideoConstructorFingerprint,
            """
                invoke-static { p2, p1 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            PostSelfImageElementConstructorFingerprint,
            """
                invoke-static { p1, p5 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            ThumbnailUiModelConstructorFingerprint,
            """
                invoke-static { p1, p3 }, $EXTENSION_CLASS->registerMediaUrl(Ljava/lang/String;Ljava/lang/String;)V
            """
        )

        hookConstructor(
            PostTitleWithThumbnailSectionConstructorFingerprint,
            """
                invoke-static { p2, p6 }, $EXTENSION_CLASS->registerPostMedia(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            TitleWithThumbnailElementConstructorFingerprint,
            """
                invoke-static { p5, p7 }, $EXTENSION_CLASS->registerTitleThumbnailElement(Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            CompactLinkConstructorFingerprint,
            """
                invoke-static { p6, p1 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            PostTitleElementConstructorFingerprint,
            """
                invoke-static { p1, p5 }, $EXTENSION_CLASS->registerPostTitle(Ljava/lang/String;Ljava/lang/String;)V
            """
        )

        hookConstructor(
            PostMediaWebsiteElementConstructorFingerprint,
            """
                invoke-static { p4, p1 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            GalleryElementConstructorFingerprint,
            """
                invoke-static { p1, p6 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            CompactPostPreviewItemConstructorFingerprint,
            """
                invoke-static { p1, p4 }, $EXTENSION_CLASS->registerCompactPostPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            CompactPostPreviewConstructorFingerprint,
            """
                invoke-static { p5, p8 }, $EXTENSION_CLASS->registerPostMedia(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            CompactPostPreviewBaseInfoConstructorFingerprint,
            """
                invoke-static { p7, p8, p9, p10 }, $EXTENSION_CLASS->registerPostPreviewBase(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            FeedImageSectionConstructorFingerprint,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->registerFeedImageSection(Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            FeedVideoSectionConstructorFingerprint,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->registerFeedVideoSection(Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            CellMediaSourceFragmentConstructorFingerprint,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->registerCellMediaSource(Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            TitleWithThumbnailCellFragmentConstructorFingerprint,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->registerTitleWithThumbnailCell(Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            TitleWithThumbnailCollapsedCellFragmentConstructorFingerprint,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->registerTitleWithThumbnailCell(Ljava/lang/Object;)V
            """
        )

        hookConstructor(
            ClassicCellFragmentConstructorFingerprint,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->registerClassicCell(Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            ComposePostImageMethodFingerprint,
            """
                move-object/from16 v0, p1
                invoke-static { v0 }, $EXTENSION_CLASS->registerMediaSourceObject(Ljava/lang/Object;)V
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
