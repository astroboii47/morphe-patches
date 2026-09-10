package app.morphe.patches.nothing.gallery.misc.preinstall

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.nothing.gallery.shared.Constants.COMPATIBILITY_NOTHING_GALLERY
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

@Suppress("unused")
val removePreInstalledWarningPatch = bytecodePatch(
    name = "Remove pre-installed warning",
    description = "Removes the warning shown when Nothing Gallery is installed as a user app."
) {
    compatibleWith(COMPATIBILITY_NOTHING_GALLERY)

    execute {
        EntryActivityOnResumeFingerprint.method.apply {
            listOf(
                "Lcom/nothing/gallery/g;->b0()Z",
                "Lcom/nothing/gallery/g;->X()Z"
            ).forEach { statusMethod ->
                val invokeIndex = indexOfFirstInstructionOrThrow(
                    methodCall(smali = statusMethod)
                )
                val resultIndex = invokeIndex + 1
                val resultInstruction = getInstruction<OneRegisterInstruction>(resultIndex)

                check(resultInstruction.opcode == Opcode.MOVE_RESULT) {
                    "Expected a boolean result after $statusMethod"
                }

                replaceInstruction(
                    resultIndex,
                    "const/4 v${resultInstruction.registerA}, 0x1"
                )
            }
        }
    }
}
