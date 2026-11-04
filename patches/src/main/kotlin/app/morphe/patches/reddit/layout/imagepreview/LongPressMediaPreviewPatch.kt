/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.imagepreview

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.extension.hooks.RedditActivityOnCreateFingerprint
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/LongPressMediaPreviewPatch;"

@Suppress("unused")
val longPressMediaPreviewPatch = bytecodePatch(
    name = "Long press media preview",
    description = "Shows a large image while you hold that image in the Reddit feed."
) {
    compatibleWith(COMPATIBILITY_REDDIT)
    dependsOn(sharedExtensionPatch)

    execute {
        // Install the touch wrapper on Reddit's real feed activity, not the Morphe settings view.
        RedditActivityOnCreateFingerprint.method.addInstruction(
            RedditActivityOnCreateFingerprint.method.implementation!!.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            },
            "invoke-static { p0 }, $EXTENSION_CLASS->attach(Landroid/app/Activity;)V"
        )

        FeedSelfImageConstructorFingerprint.method.addInstruction(
            FeedSelfImageConstructorFingerprint.method.implementation!!.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            },
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->registerImageModel(Ljava/lang/Object;)V"
        )
        FeedTitleWithThumbnailConstructorFingerprint.method.addInstruction(
            FeedTitleWithThumbnailConstructorFingerprint.method.implementation!!.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            },
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->registerLinkThumbnailModel(Ljava/lang/Object;)V"
        )
        FeedTitleConstructorFingerprint.method.addInstruction(
            FeedTitleConstructorFingerprint.method.implementation!!.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            },
            "invoke-static { p1 }, $EXTENSION_CLASS->registerTitleModel(Ljava/lang/Object;)V"
        )
        LinkPreviewCardDataConstructorFingerprint.method.addInstruction(
            LinkPreviewCardDataConstructorFingerprint.method.implementation!!.instructions.indexOfLast {
                it.opcode == Opcode.RETURN_VOID
            },
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->registerLinkPreviewCardModel(Ljava/lang/Object;)V"
        )
        FeedSelfImageRenderFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->registerImageModel(Ljava/lang/Object;)V"
        )
        FeedTitleWithThumbnailRenderFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->registerLinkThumbnailModel(Ljava/lang/Object;)V"
        )
        LinkPreviewCardRenderFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->registerLinkPreviewCardModel(Ljava/lang/Object;)V"
        )
        // Reddit keeps exact feed media IDs in Compose TestTags. Export those tags so the
        // touch handler can identify the held thumbnail without coordinates or title matching.
        TestTagSemanticsFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, Lkhe0;->a(Llhe0;)V"
        )
        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
