/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_25_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/ForceBottomNavigationPatch;"

@Suppress("unused")
val forceBottomNavigationPatch = bytecodePatch(
    name = "Force bottom navigation",
    description = "Adds an option to use the bottom navigation bar instead of the side navigation rail."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_25_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Force bottom navigation' is only needed for Reddit 2026.25.0 or later."
            )
        }

        BottomNavScreenSideNavLayoutFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->forceBottomNavigation(Z)Z
                move-result p1
            """
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
