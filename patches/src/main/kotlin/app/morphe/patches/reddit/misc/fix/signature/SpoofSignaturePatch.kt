/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.misc.fix.signature

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.fix.changepackageinstaller.changePackageInstallerPatch
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SpoofSignaturePatch;"

@Suppress("unused")
val spoofSignaturePatch = bytecodePatch(
    name = "Spoof signature",
    description = "Spoofs the signature of the app to fix notification issues."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(sharedExtensionPatch, changePackageInstallerPatch())

    execute {
        try {
            ApplicationFingerprint.classDef.setSuperClass(EXTENSION_CLASS)
        } catch (_: Exception) {
            Logger.getLogger(this::class.java.name).warning(
                "'Spoof signature' was skipped because the Reddit Application class could not be found."
            )
        }
    }
}
