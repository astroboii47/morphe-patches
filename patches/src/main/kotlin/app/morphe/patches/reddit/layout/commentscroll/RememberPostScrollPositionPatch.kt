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
import app.morphe.util.findFreeRegister
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
                invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->bindAndRestorePosition(Ljava/lang/Object;Ljava/lang/Object;)V
            """
        )

        listOf(
            CommentsListContentWithoutStateFingerprint.method,
            CommentsListScrollTargetFingerprint.method
        ).forEach { method ->
            val providerRegister = method.findFreeRegister(0)
            val stateRegister = method.findFreeRegister(0, providerRegister)
            method.addInstructions(
                0,
                """
                    move-object/from16 v$providerRegister, p0
                    move-object/from16 v$stateRegister, p1
                    invoke-static { v$providerRegister, v$stateRegister }, $EXTENSION_CLASS->bindAndRestorePosition(Ljava/lang/Object;Ljava/lang/Object;)V
                """
            )
        }

        LazyListStateUpdateScrollFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p3 }, $EXTENSION_CLASS->saveBoundPosition(Ljava/lang/Object;IIZ)V
            """
        )

        LazyListStateLayoutUpdateFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p2 }, $EXTENSION_CLASS->saveBoundPositionFromLayout(Ljava/lang/Object;Ljava/lang/Object;Z)V
            """
        )

        listOf(
            CommentsScreenDetachFingerprint.method,
            AdaptiveCommentsScreenDetachFingerprint.method,
            ArticleCommentsScreenDetachFingerprint.method
        ).forEach { method ->
            method.addInstructions(
                0,
                """
                    invoke-static { p0 }, $EXTENSION_CLASS->saveOnPostExit(Ljava/lang/Object;)V
                """
            )
        }

        CommentsRenderedHandlerFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->restoreOnCommentsRendered(Ljava/lang/Object;)V
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
