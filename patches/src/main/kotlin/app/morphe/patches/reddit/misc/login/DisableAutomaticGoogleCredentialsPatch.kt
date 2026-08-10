/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.login

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT

private object PrepareGoogleSignInFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/auth/login/impl/credentialsmanager/d;",
    name = "c",
    returnType = "Ljava/lang/Object;"
)

@Suppress("unused")
val disableAutomaticGoogleCredentialsPatch = bytecodePatch(
    name = "Disable automatic Google credentials",
    description = "Prevents an automatic Google credential request from interrupting normal Reddit login."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    execute {
        PrepareGoogleSignInFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                return-object v0
            """
        )
    }
}
