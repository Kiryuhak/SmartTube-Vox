package com.liskovsoft.smartyoutubetv2.common.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
class VoxShareLinkChooserTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("empty", ""))
    }

    @Test
    fun nullContextOrUrl_doesNotCrash() {
        Utils.showMultiChooser(null, null)
        Utils.showMultiChooser(context, null)
        Utils.showMultiChooser(null, Uri.parse("https://youtu.be/test"))
        Utils.copyToClipboard(null, null)
        Utils.copyToClipboard(context, null)
        Utils.copyToClipboard(null, "test")
    }

    @Test
    fun nonWebUri_rejectedAndDoesNotLaunchChooserOrPolluteClipboard() {
        val sensitiveUri = Uri.parse("file:///data/data/com.liskovsoft.smartyoutubetv2/databases/prefs.db")
        Utils.showMultiChooser(context, sensitiveUri)

        // Must not launch chooser for file:// or content:// URIs
        val startedIntent = ShadowApplication.getInstance().nextStartedActivity
        assertNull("Chooser must not be launched for non-http/https URI", startedIntent)

        // Must not copy to clipboard
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
        assertTrue("Clipboard must not contain sensitive local URI", clipText.isNullOrEmpty())
    }

    @Test
    fun copyToClipboard_populatesClipboardManager() {
        val testUrl = "https://youtu.be/dQw4w9WgXcQ?t=42"
        Utils.copyToClipboard(context, testUrl)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        assertNotNull(clipboard)
        assertTrue(clipboard!!.hasPrimaryClip())
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals(1, clip!!.itemCount)
        assertEquals(testUrl, clip.getItemAt(0).text.toString())
    }

    @Test
    fun showMultiChooser_withApplicationContext_addsNewTaskFlagAndLaunchesChooser() {
        val testUri = Uri.parse("https://youtu.be/sample123")
        Utils.showMultiChooser(context, testUri)

        // Verify that Chooser intent was started with FLAG_ACTIVITY_NEW_TASK
        val startedIntent = ShadowApplication.getInstance().nextStartedActivity
        assertNotNull("Chooser intent should be launched in Robolectric environment", startedIntent)
        assertEquals(Intent.ACTION_CHOOSER, startedIntent.action)
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK must be set when launching chooser from Application context",
            (startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0
        )
    }

    @Test
    fun showMultiChooser_whenChooserThrowsActivityNotFound_gracefullyFallsBackToClipboard() {
        val testUri = Uri.parse("https://youtu.be/fallbackTest")
        val throwingContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent?) {
                throw android.content.ActivityNotFoundException("No chooser activity on TV")
            }
        }

        // Should not throw
        Utils.showMultiChooser(throwingContext, testUri)

        // Verify fallback copied to clipboard
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        assertEquals(testUri.toString(), clipboard?.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun displayShareVideoDialog_constructsValidUrl() {
        val uri = Utils.convertToFullVideoUrl("abcVideo", 90)
        assertEquals("https://youtu.be/abcVideo?t=90", uri.toString())
    }

    @Test
    fun displaySharePlaylistDialog_constructsValidUrl() {
        val uri = Utils.convertToPlaylistUrl("PLtest_playlist")
        assertEquals("https://www.youtube.com/playlist?list=PLtest_playlist", uri.toString())
    }

    @Test
    fun displayShareChannelDialog_constructsValidUrl() {
        val uri = Utils.convertToFullChannelUrl("UCtest_channel")
        assertEquals("https://www.youtube.com/channel/UCtest_channel", uri.toString())
    }

    @Test
    fun displayShareEmbedVideoDialog_constructsValidUrl() {
        val uri = Utils.convertToEmbedVideoUrl("xyzVideo", 45)
        assertEquals("https://www.youtube.com/embed/xyzVideo?start=45", uri.toString())
    }
}
