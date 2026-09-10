package app.morphe.patches.nothing.gallery.misc.preinstall

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object EntryActivityOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/nothing/gallery/activity/EntryActivity;",
    name = "onResume",
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf()
)
