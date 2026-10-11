package com.doxigo.tv30

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    @Test
    fun onlyNewerStableNumericVersionsAreUpdates() {
        assertTrue(AppUpdates.newerVersion("v1.10.0", "1.9.9"))
        assertTrue(AppUpdates.newerVersion("2.0.0", "1.99.99"))
        assertTrue(AppUpdates.newerVersion("1.0.0.1", "1.0"))
        assertFalse(AppUpdates.newerVersion("v1.0.0", "1.0"))
        assertFalse(AppUpdates.newerVersion("1.2.0", "1.3.0"))
        assertFalse(AppUpdates.newerVersion("1.4.0-beta.1", "1.3.0"))
        assertFalse(AppUpdates.newerVersion("999999999999.0", "1.0.0"))
        assertFalse(AppUpdates.newerVersion("2.0.0", "not a version"))
    }

    @Test
    fun onlyThisRepositoryHttpsApkAssetIsAccepted() {
        val asset = "https://github.com/soeleia/30namaTV/releases/download/v1.1.0/30namaTV.apk"
        assertTrue(AppUpdates.trustedAsset(asset, "v1.1.0"))
        for (bad in listOf(asset.replace("https:", "http:"), asset.replace("github.com", "github.com.evil.example"),
            asset.replace("soeleia", "another-owner"), asset.replace("30namaTV.apk", "other.apk"),
            asset.replace("github.com", "user@github.com"), asset + "?redirect=1", asset + "#fragment",
            asset.replace("github.com", "github.com:444"))) assertFalse(bad, AppUpdates.trustedAsset(bad, "v1.1.0"))
        assertFalse(AppUpdates.trustedAsset(asset, "v1.2.0"))
        assertFalse(AppUpdates.trustedAsset(asset, "../../outside"))
    }

    @Test
    fun apkMustMatchPackageVersionAndEveryCurrentSigner() {
        val signers = setOf("maintainer-certificate")
        fun failure(name: String? = "com.doxigo.tv30", code: Long = 3, certs: Set<String> = signers,
                    versionMatches: Boolean = true) = AppUpdates.candidateFailure("com.doxigo.tv30", 2, signers,
            name, code, certs, versionMatches)
        assertNull(failure())
        assertEquals(AppUpdates.Failure.INVALID_APK, failure(name = "another.package"))
        assertEquals(AppUpdates.Failure.INVALID_APK, failure(name = null))
        assertEquals(AppUpdates.Failure.INVALID_APK, failure(code = 2))
        assertEquals(AppUpdates.Failure.INVALID_APK, failure(code = 1))
        assertEquals(AppUpdates.Failure.INVALID_APK, failure(versionMatches = false))
        assertEquals(AppUpdates.Failure.INCOMPATIBLE_SIGNATURE, failure(certs = emptySet()))
        assertEquals(AppUpdates.Failure.INCOMPATIBLE_SIGNATURE, failure(certs = setOf("different-certificate")))
        assertEquals(AppUpdates.Failure.INCOMPATIBLE_SIGNATURE, failure(certs = signers + "extra-certificate"))
    }

    @Test
    fun startupChecksAtMostDailyAndRecoversFromClockChanges() {
        val day = 24 * 60 * 60 * 1000L
        assertTrue(AppUpdates.checkDue(day * 2, 0))
        assertFalse(AppUpdates.checkDue(day * 2 - 1, day))
        assertTrue(AppUpdates.checkDue(day * 2, day))
        assertTrue(AppUpdates.checkDue(day - 1, day))
    }
}
