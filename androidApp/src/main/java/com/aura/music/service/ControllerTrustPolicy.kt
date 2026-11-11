package com.aura.music.service

import android.content.Context
import android.content.pm.PackageManager
import androidx.media3.session.MediaSession

/** UID comes from Media3's Binder identity; hints and claimed package names alone never grant access. */
internal class ControllerTrustPolicy(private val context: Context) {
    fun isInternal(controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == context.applicationInfo.uid && controller.packageName == context.packageName

    fun allows(controller: MediaSession.ControllerInfo): Boolean {
        if (isInternal(controller) || controller.isTrusted) return true
        val pins = RELEASE_CERTIFICATES[controller.packageName] ?: return false
        val pm = context.packageManager
        if (controller.uid < 0 || pm.getPackagesForUid(controller.uid)?.contains(controller.packageName) != true) return false
        return pins.any { pin -> runCatching {
            pm.hasSigningCertificate(controller.packageName,
                pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), PackageManager.CERT_INPUT_SHA256)
        }.getOrDefault(false) }
    }

    companion object {
        // Public RELEASE signing certificate digests from android/uamp allowed_media_browser_callers.xml.
        // No debug certificates, wildcard packages or trust based on a package name alone.
        private val RELEASE_CERTIFICATES = mapOf(
            "com.google.android.projection.gearhead" to listOf(
                "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
                "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295"),
            "com.google.android.googlequicksearchbox" to listOf(
                "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83")
        )
    }
}
