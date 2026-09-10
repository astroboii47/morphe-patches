package app.morphe.patches.nothing.gallery.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    val COMPATIBILITY_NOTHING_GALLERY = Compatibility(
        name = "Nothing Gallery",
        packageName = "com.nothing.gallery",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xDC4448,
        signatures = setOf(
            "8a508115864dc16731c003f2d7775fd721a7e931e03e502873dcef0f1f9add73"
        ),
        targets = listOf(
            AppTarget(
                version = "3.1.2.0819",
                minSdk = 34
            )
        )
    )
}
