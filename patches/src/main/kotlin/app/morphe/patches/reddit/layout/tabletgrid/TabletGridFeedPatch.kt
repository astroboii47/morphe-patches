/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4923
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.tabletgrid

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

internal const val FEED_TYPE_CLASS = "Lcom/reddit/feeds/data/FeedType;"

@Suppress("unused")
val tabletGridFeedPatch = patch(
    name = "Phone grid feed",
    description = "Shows supported feeds as a two-column grid on phones and tablets."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    execute {
        // A phone has room for two grid columns. Avoid a runtime setting class so this
        // patch can coexist with Reddit patches provided by other Morphe sources.
        CalculateCrossAxisCellSizesFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        const/16 v$register, 0x2
                    """
                )
            }
        }

        // Show the list feed with the grid feed composable that search results use.
        val gridFeed = SearchGridFeedFingerprint.instructionMatches[1].methodReference
        val gridParameters = gridFeed.parameterTypes.map { it.toString() }
        val footerField = SearchGridFeedFooterFingerprint.instructionMatches[1].fieldReference
        val paddingType = gridParameters[4]

        LatestFeedScreenFingerprint.instructionMatches[1].getMethodCalled().apply {
            val listParameters = parameterTypes.map { it.toString() }

            // Some app versions sort the list feed parameters by type,
            // so the list feed arguments are found by type and not by position.
            // Each argument used is the first of its type, and the first float is the top padding.
            fun listRegister(type: String) = "p${listParameters.indexOf(type)}"

            // The leading grid parameters are the same in all app versions.
            // The number of boolean flags and Compose int flags after them differ.
            // Padding is set to v4 before the other arguments.
            val setGridArguments = gridParameters.mapIndexedNotNull { index, type ->
                val register = "v$index"
                when (index) {
                    // Feed state, event sink, the first function and modifier.
                    0, 1, 2, 3 -> "move-object/from16 $register, ${listRegister(type)}"
                    4 -> null // Padding.
                    5 -> "const/4 $register, 0x0" // Item spacing.
                    6 -> "const/4 $register, 0x0" // Arrangement.
                    7 -> "sget-object $register, $footerField"
                    else -> when (type) {
                        "Z" -> "const/4 $register, 0x1"
                        "I" -> "const/4 $register, 0x0"
                        // Same object the list feed gets, such as the Compose composer.
                        else -> "move-object/from16 $register, ${listRegister(type)}"
                    }
                }
            }.joinToString("\n")

            addInstructionsWithLabels(
                0,
                """
                    move/from16 v0, ${listRegister("F")}
                    const/high16 v1, 0x41000000 # 8.0f
                    add-float/2addr v1, v0
                    const/high16 v0, 0x41800000 # 16.0f
                    new-instance v4, $paddingType
                    invoke-direct { v4, v0, v1, v0, v0 }, $paddingType-><init>(FFFF)V
                    $setGridArguments
                    invoke-static/range { v0 .. v${gridParameters.size - 1} }, $gridFeed
                    return-void

                """
            )
        }
    }
}
