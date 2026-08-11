/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.layout.commentscroll.rememberPostScrollPositionPatch
import app.morphe.patches.reddit.layout.imagepreview.longPressImagePreviewPatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_25_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/ForceBottomNavigationPatch;"

@Suppress("unused")
val forceBottomNavigationPatch = bytecodePatch(
    name = "Force bottom navigation",
    description = "Adds an option to use the bottom navigation bar instead of the side navigation rail."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        settingsPatch,
        versionCheckPatch,
        longPressImagePreviewPatch,
        rememberPostScrollPositionPatch
    )

    execute {
        if (!is_2026_25_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Force bottom navigation' is only needed for Reddit 2026.25.0 or later."
            )
        }

        BottomNavScreenContentFingerprint.method.apply {
            val navStackFeaturesStringIndex = instructions.indexOfFirst { instruction ->
                instruction.getReference<StringReference>()?.string == "navStackFeatures"
            }

            if (navStackFeaturesStringIndex < 0) {
                throw PatchException("Could not find navStackFeatures anchor in BottomNavScreen content")
            }

            val booleanValueIndicesAfterNavStackFeatures =
                instructions.drop(navStackFeaturesStringIndex + 1).mapIndexedNotNull { offset, instruction ->
                    if (instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                            instruction.getReference<MethodReference>()?.toString() ==
                            "Ljava/lang/Boolean;->booleanValue()Z"
                    ) {
                        navStackFeaturesStringIndex + 1 + offset
                    } else {
                        null
                    }
                }

            val sideNavBooleanValueIndices =
                booleanValueIndicesAfterNavStackFeatures.take(2).takeIf { it.size == 2 }
                    ?: throw PatchException("Could not find side navigation booleans in BottomNavScreen content")

            sideNavBooleanValueIndices.asReversed().forEach { booleanValueIndex ->
                val booleanRegister =
                    getInstruction<OneRegisterInstruction>(booleanValueIndex + 1).registerA

                addInstructions(
                    booleanValueIndex + 2,
                    """
                        const/16 v$booleanRegister, 0x0
                    """
                )
            }

            val navStackContentIndex = instructions.indexOfFirst { instruction ->
                val reference = instruction.getReference<MethodReference>()

                reference != null &&
                    instruction.opcode == Opcode.INVOKE_STATIC_RANGE &&
                    reference.definingClass == "Lcom/reddit/navstack/m;" &&
                    reference.name == "c" &&
                    reference.returnType == "V"
            }

            if (navStackContentIndex < 0) {
                throw PatchException("Could not find navstack content call")
            }

            addInstructions(
                navStackContentIndex,
                """
                    const/high16 v1, 0x3f800000
                    invoke-static {v13, v1}, Lhyc0;->d(Loev;F)Loev;
                    move-result-object v1
                    const/4 v6, 0x0
                """
            )
        }

        NavHostInsetsFingerprint.method.apply {
            repeat(4) {
                val railInsetIndex = indexOfFirstLiteralInstructionOrThrow(96.0f)
                val railInsetRegister = getInstruction<OneRegisterInstruction>(railInsetIndex).registerA

                replaceInstruction(railInsetIndex, "const/high16 v$railInsetRegister, 0x0")
            }
        }

        NavStackEntryContentInsetsFingerprint.method.apply {
            val railInsetIndex = indexOfFirstLiteralInstructionOrThrow(96.0f)
            val railInsetRegister = getInstruction<OneRegisterInstruction>(railInsetIndex).registerA

            replaceInstruction(railInsetIndex, "const/high16 v$railInsetRegister, 0x0")
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
