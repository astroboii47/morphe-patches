/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.imagepreview

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

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

        hookMethodStart(
            CompactSelfImageConstructorFingerprint,
            """
                invoke-static { p4, p1 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            CompactVideoConstructorFingerprint,
            """
                invoke-static { p2, p1 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            PostSelfImageElementConstructorFingerprint,
            """
                invoke-static { p1, p5 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            ThumbnailUiModelConstructorFingerprint,
            """
                invoke-static { p1, p3 }, $EXTENSION_CLASS->registerMediaUrl(Ljava/lang/String;Ljava/lang/String;)V
            """
        )

        hookMethodStart(
            PostTitleWithThumbnailSectionConstructorFingerprint,
            """
                invoke-static { p2, p6 }, $EXTENSION_CLASS->registerPostMedia(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            TitleWithThumbnailElementConstructorFingerprint,
            """
                invoke-static { p5, p7 }, $EXTENSION_CLASS->registerTitleThumbnailElement(Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            CompactLinkConstructorFingerprint,
            """
                invoke-static { p6, p1 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            PostTitleElementConstructorFingerprint,
            """
                invoke-static { p1, p5 }, $EXTENSION_CLASS->registerPostTitle(Ljava/lang/String;Ljava/lang/String;)V
            """
        )

        hookMethodStart(
            PostMediaWebsiteElementConstructorFingerprint,
            """
                invoke-static { p4, p1 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            GalleryElementConstructorFingerprint,
            """
                invoke-static { p1, p6 }, $EXTENSION_CLASS->registerMediaPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            CompactPostPreviewItemConstructorFingerprint,
            """
                invoke-static { p1, p4 }, $EXTENSION_CLASS->registerCompactPostPreview(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            CompactPostPreviewConstructorFingerprint,
            """
                invoke-static { p5, p8 }, $EXTENSION_CLASS->registerPostMedia(Ljava/lang/String;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            CompactPostPreviewBaseInfoConstructorFingerprint,
            """
                invoke-static { p7, p8, p9, p10 }, $EXTENSION_CLASS->registerPostPreviewBase(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
            FeedImageSectionConstructorFingerprint,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->registerFeedImageSection(Ljava/lang/Object;)V
            """
        )

        hookMethodStart(
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

        runCatching {
            hookMethodStart(
                LinkPresentationModelToPostUnitFingerprint,
                """
                    invoke-static { p1 }, Lapp/morphe/extension/reddit/patches/RedditComposeFocusBridge;->registerPostUnitModel(Ljava/lang/Object;)V
                """
            )
        }

        hookConstructor(
            LinkPresentationModelConstructorFingerprint,
            """
                invoke-static { p0 }, Lapp/morphe/extension/reddit/patches/RedditComposeFocusBridge;->registerPostUnitModel(Ljava/lang/Object;)V
            """
        )

        LinkJsonAdapterFromJsonFingerprint.method.apply {
            val returns = implementation!!.instructions.mapIndexedNotNull { index, instruction ->
                if (instruction.opcode == Opcode.RETURN_OBJECT) {
                    index to (instruction as OneRegisterInstruction).registerA
                } else {
                    null
                }
            }
            check(returns.isNotEmpty()) {
                "Could not find LinkJsonAdapter.fromJson return-object instruction"
            }
            returns.asReversed().forEach { (index, register) ->
                addInstructions(
                    index,
                    """
                        move-object/from16 v0, v$register
                        invoke-static { v0 }, Lapp/morphe/extension/reddit/patches/RedditComposeFocusBridge;->cacheLinkModel(Ljava/lang/Object;)V
                    """
                )
            }
        }

        AppCompatDispatchKeyEventFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p1 }, $EXTENSION_CLASS->handleKeyboardFeedFocusKey(Landroid/app/Activity;Landroid/view/KeyEvent;)Z
                move-result v0
                if-eqz v0, :morphe_reddit_key_continue
                const/4 v0, 0x1
                return v0
                :morphe_reddit_key_continue
                nop
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
