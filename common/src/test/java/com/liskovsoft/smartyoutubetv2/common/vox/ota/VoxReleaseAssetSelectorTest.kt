package com.liskovsoft.smartyoutubetv2.common.vox.ota

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoxReleaseAssetSelectorTest {

    private lateinit var context: Context

    private val sampleAssets = listOf(
        VoxReleaseAsset("SmartTube_vot_32.71-vox.9.3_arm64-v8a.apk", "https://github.com/arm64.apk", 32_000_000L),
        VoxReleaseAsset("SmartTube_vot_32.71-vox.9.3_armeabi-v7a.apk", "https://github.com/v7a.apk", 30_000_000L),
        VoxReleaseAsset("SmartTube_vot_32.71-vox.9.3_universal.apk", "https://github.com/universal.apk", 65_000_000L),
        VoxReleaseAsset("SHA256SUMS.txt", "https://github.com/sums.txt", 400L),
        VoxReleaseAsset("smarttube_vox.json", "https://github.com/manifest.json", 2000L),
        VoxReleaseAsset("SmartTube_tizen.wgt", "https://github.com/tizen.wgt", 10_000_000L)
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("vox_ota_variant_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun testStickyArm64ToArm64() {
        val selected = VoxReleaseAssetSelector.selectAsset(
            sampleAssets,
            preferredVariant = VoxReleaseAssetSelector.Variant.ARM64_V8A,
            primaryAbi = "arm64-v8a"
        )
        assertNotNull(selected)
        assertEquals("SmartTube_vot_32.71-vox.9.3_arm64-v8a.apk", selected?.name)
    }

    @Test
    fun testStickyArmV7ToArmV7() {
        val selected = VoxReleaseAssetSelector.selectAsset(
            sampleAssets,
            preferredVariant = VoxReleaseAssetSelector.Variant.ARMEABI_V7A,
            primaryAbi = "armeabi-v7a"
        )
        assertNotNull(selected)
        assertEquals("SmartTube_vot_32.71-vox.9.3_armeabi-v7a.apk", selected?.name)
    }

    @Test
    fun testStickyUniversalToUniversal() {
        val selected = VoxReleaseAssetSelector.selectAsset(
            sampleAssets,
            preferredVariant = VoxReleaseAssetSelector.Variant.UNIVERSAL,
            primaryAbi = "arm64-v8a"
        )
        assertNotNull(selected)
        assertEquals("SmartTube_vot_32.71-vox.9.3_universal.apk", selected?.name)
    }

    @Test
    fun testLegacyUnknownFallbackToUniversal() {
        val selected = VoxReleaseAssetSelector.selectAsset(
            sampleAssets,
            preferredVariant = null,
            primaryAbi = "armeabi-v7a"
        )
        assertNotNull(selected)
        assertEquals("SmartTube_vot_32.71-vox.9.3_universal.apk", selected?.name)
    }

    @Test
    fun testX86EmulatorSelectsUniversal() {
        val selected = VoxReleaseAssetSelector.selectAsset(
            sampleAssets,
            preferredVariant = null,
            primaryAbi = "x86"
        )
        assertNotNull(selected)
        assertEquals("SmartTube_vot_32.71-vox.9.3_universal.apk", selected?.name)
    }

    @Test
    fun testMissingPreferredVariantFallsBackToUniversal() {
        val universalOnly = listOf(
            VoxReleaseAsset("SmartTube_vot_32.71-vox.9.3_universal.apk", "https://github.com/univ.apk", 65_000_000L),
            VoxReleaseAsset("SHA256SUMS.txt", "https://github.com/sums.txt", 400L)
        )
        val selected = VoxReleaseAssetSelector.selectAsset(
            universalOnly,
            preferredVariant = VoxReleaseAssetSelector.Variant.ARM64_V8A,
            primaryAbi = "arm64-v8a"
        )
        assertNotNull(selected)
        assertEquals("SmartTube_vot_32.71-vox.9.3_universal.apk", selected?.name)
    }

    @Test
    fun testNonApkExtensionsIgnored() {
        val nonApks = listOf(
            VoxReleaseAsset("SHA256SUMS.txt", "https://github.com/sums.txt", 400L),
            VoxReleaseAsset("smarttube_vox.json", "https://github.com/manifest.json", 2000L),
            VoxReleaseAsset("app.wgt", "https://github.com/app.wgt", 10_000_000L)
        )
        val selected = VoxReleaseAssetSelector.selectAsset(nonApks, preferredVariant = null, primaryAbi = "arm64-v8a")
        assertNull(selected)
    }

    @Test
    fun testFindSha256SumsAsset() {
        val sums = VoxReleaseAssetSelector.findSha256SumsAsset(sampleAssets)
        assertNotNull(sums)
        assertEquals("SHA256SUMS.txt", sums?.name)
    }

    @Test
    fun testPendingAndConfirmedVariantCommit() {
        assertNull(VoxReleaseAssetSelector.getConfirmedVariant(context))
        assertNull(VoxReleaseAssetSelector.getPendingVariant(context))

        // Set pending variant before install: target versionCode = 2446050
        VoxReleaseAssetSelector.setPendingVariant(
            context,
            VoxReleaseAssetSelector.Variant.ARM64_V8A,
            2446050L
        )
        assertEquals(VoxReleaseAssetSelector.Variant.ARM64_V8A, VoxReleaseAssetSelector.getPendingVariant(context))
        assertEquals(2446050L, VoxReleaseAssetSelector.getPendingVersionCode(context))
        assertNull(VoxReleaseAssetSelector.getConfirmedVariant(context))

        // Attempt commit before upgrade (installed version is still 2446032) -> should NOT commit
        VoxReleaseAssetSelector.commitPendingVariantIfUpgraded(context, 2446032L)
        assertNull(VoxReleaseAssetSelector.getConfirmedVariant(context))
        assertEquals(VoxReleaseAssetSelector.Variant.ARM64_V8A, VoxReleaseAssetSelector.getPendingVariant(context))

        // App starts after successful update: installed version is 2446050 -> commits!
        VoxReleaseAssetSelector.commitPendingVariantIfUpgraded(context, 2446050L)
        assertEquals(VoxReleaseAssetSelector.Variant.ARM64_V8A, VoxReleaseAssetSelector.getConfirmedVariant(context))
        assertNull(VoxReleaseAssetSelector.getPendingVariant(context))
        assertEquals(0L, VoxReleaseAssetSelector.getPendingVersionCode(context))
    }
}
