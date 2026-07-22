/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.commentscroll

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/RememberPostScrollPositionPatch;"

@Suppress("unused")
val rememberPostScrollPositionPatch = bytecodePatch(
    name = "Remember post scroll position",
    description = "Restores the comment list position when reopening the same post."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        CommentsListContentFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0, p1 }, $EXTENSION_CLASS->bindAndRestorePosition(Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        LazyListStateUpdateScrollFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0, p1, p2 }, $EXTENSION_CLASS->saveBoundPosition(Ljava/lang/Object;II)V
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
