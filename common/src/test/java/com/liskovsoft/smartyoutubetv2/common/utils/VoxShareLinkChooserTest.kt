package com.liskovsoft.smartyoutubetv2.common.utils

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
class VoxShareLinkChooserTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
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
    fun showMultiChooser_withApplicationContext_addsNewTaskFlagAndDoesNotCrash() {
        val testUri = Uri.parse("https://youtu.be/sample123")
        Utils.showMultiChooser(context, testUri)

        // Verify that the link is always copied to clipboard
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        assertNotNull(clipboard)
        assertEquals(testUri.toString(), clipboard!!.primaryClip?.getItemAt(0)?.text?.toString())

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
    fun displayShareVideoDialog_constructsValidUrlAndCopies() {
        Utils.displayShareVideoDialog(context, "abcVideo", 90)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        assertEquals("https://youtu.be/abcVideo?t=90", clipboard?.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun displaySharePlaylistDialog_constructsValidUrlAndCopies() {
        Utils.displaySharePlaylistDialog(context, "PLtest_playlist")

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        assertEquals("https://www.youtube.com/playlist?list=PLtest_playlist", clipboard?.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun displayShareChannelDialog_constructsValidUrlAndCopies() {
        Utils.displayShareChannelDialog(context, "UCtest_channel")

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        assertEquals("https://www.youtube.com/channel/UCtest_channel", clipboard?.primaryClip?.getItemAt(0)?.text?.toString())
    }
}
