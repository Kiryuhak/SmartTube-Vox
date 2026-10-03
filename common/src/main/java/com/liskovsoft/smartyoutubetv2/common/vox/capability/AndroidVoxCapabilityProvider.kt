package com.liskovsoft.smartyoutubetv2.common.vox.capability

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.liskovsoft.sharedutils.mylogger.Log

/**
 * Поставщик возможностей устройства на базе Android MediaCodec, AudioTrack и Display APIs.
 */
class AndroidVoxCapabilityProvider(private val context: Context) {

    private val appContext: Context = context.applicationContext

    fun scanDeviceCapabilities(): VoxDeviceProfile {
        val platform = detectPlatform()
        val manufacturer = Build.MANUFACTURER ?: "Unknown"
        val model = Build.MODEL ?: "Unknown"
        val osName = "Android"
        val osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        val videoCodecs = scanVideoCodecs()
        val audioCodecs = scanAudioCodecs()
        val display = scanDisplayCapabilities()
        val audioOutput = scanAudioOutputCapabilities()

        val profile = VoxDeviceProfile(
            schemaVersion = VoxDeviceProfile.SCHEMA_VERSION,
            platform = platform,
            manufacturer = manufacturer,
            model = model,
            osName = osName,
            osVersion = osVersion,
            videoCodecs = videoCodecs,
            audioCodecs = audioCodecs,
            display = display,
            audioOutput = audioOutput,
            scannedAtTimestampMs = System.currentTimeMillis()
        )

        Log.d(TAG, "Device profile scanned successfully: platform=${platform.id}, model=$model")
        return profile
    }

    private fun detectPlatform(): VoxPlatform {
        return try {
            val uiModeManager = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val isTv = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
                    || appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || appContext.packageManager.hasSystemFeature("android.hardware.type.television")

            if (isTv) {
                val fingerprint = (Build.FINGERPRINT ?: "").lowercase()
                val brand = (Build.BRAND ?: "").lowercase()
                if (fingerprint.contains("google/google_tv") || brand.contains("google")) {
                    VoxPlatform.GOOGLE_TV
                } else {
                    VoxPlatform.ANDROID_TV
                }
            } else {
                VoxPlatform.UNKNOWN
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting platform", e)
            VoxPlatform.ANDROID_TV
        }
    }

    private fun scanVideoCodecs(): Map<String, VideoCodecCapability> {
        val resultMap = mutableMapOf<String, VideoCodecCapability>()
        val targetMimes = mapOf(
            "avc" to "video/avc",
            "hevc" to "video/hevc",
            "vp9" to "video/x-vnd.on2.vp9",
            "av1" to "video/av01"
        )

        try {
            val codecList = getMediaCodecInfos()

            for ((key, mime) in targetMimes) {
                var foundSupported = false
                var isHw = false
                var maxWidth = 0
                var maxHeight = 0
                var maxFps = 0

                for (info in codecList) {
                    if (info.isEncoder) continue

                    val types = info.supportedTypes ?: continue
                    for (type in types) {
                        if (type.equals(mime, ignoreCase = true)) {
                            foundSupported = true
                            try {
                                val caps = info.getCapabilitiesForType(type)
                                val videoCaps = caps?.videoCapabilities
                                if (videoCaps != null) {
                                    val widthUpper = videoCaps.supportedWidths?.upper ?: 0
                                    val heightUpper = videoCaps.supportedHeights?.upper ?: 0
                                    val fpsUpper = videoCaps.supportedFrameRates?.upper ?: 0

                                    if (widthUpper > maxWidth) maxWidth = widthUpper
                                    if (heightUpper > maxHeight) maxHeight = heightUpper
                                    if (fpsUpper > maxFps) maxFps = fpsUpper
                                }

                                if (isHardwareAccelerated(info)) {
                                    isHw = true
                                }
                            } catch (ignored: Exception) {
                            }
                        }
                    }
                }

                resultMap[key] = VideoCodecCapability(
                    codec = key,
                    capability = if (foundSupported) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED,
                    hardwareAccelerated = isHw,
                    maxWidth = if (maxWidth > 0) maxWidth else if (foundSupported) 1920 else 0,
                    maxHeight = if (maxHeight > 0) maxHeight else if (foundSupported) 1080 else 0,
                    maxFps = if (maxFps > 0) maxFps else if (foundSupported) 60 else 0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning video codecs", e)
        }

        return resultMap
    }

    private fun scanAudioCodecs(): Map<String, AudioCodecCapability> {
        val resultMap = mutableMapOf<String, AudioCodecCapability>()
        val targetMimes = mapOf(
            "aac" to "audio/mp4a-latm",
            "opus" to "audio/opus",
            "ac3" to "audio/ac3",
            "eac3" to "audio/eac3",
            "eac3_joc" to "audio/eac3-joc"
        )

        try {
            val codecList = getMediaCodecInfos()

            for ((key, mime) in targetMimes) {
                var foundDecode = false

                for (info in codecList) {
                    if (info.isEncoder) continue
                    val types = info.supportedTypes ?: continue
                    for (type in types) {
                        if (type.equals(mime, ignoreCase = true)) {
                            foundDecode = true
                            break
                        }
                    }
                    if (foundDecode) break
                }

                val passthrough = checkAudioPassthrough(key)

                resultMap[key] = AudioCodecCapability(
                    codec = key,
                    decodeCapability = if (foundDecode) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED,
                    passthroughCapability = passthrough
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning audio codecs", e)
        }

        return resultMap
    }

    private fun checkAudioPassthrough(codecKey: String): TriStateCapability {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val encoding = when (codecKey) {
                    "ac3" -> AudioFormat.ENCODING_AC3
                    "eac3", "eac3_joc" -> AudioFormat.ENCODING_E_AC3
                    "opus" -> AudioFormat.ENCODING_OPUS
                    "aac" -> AudioFormat.ENCODING_AAC_LC
                    else -> AudioFormat.ENCODING_INVALID
                }

                if (encoding != AudioFormat.ENCODING_INVALID) {
                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                        .build()

                    val isDirect = AudioTrack.isDirectPlaybackSupported(
                        audioFormat,
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    return if (isDirect) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED
                }
            }

            if (codecKey == "ac3" || codecKey == "eac3" || codecKey == "eac3_joc") {
                TriStateCapability.SUPPORTED
            } else {
                TriStateCapability.UNKNOWN
            }
        } catch (e: Exception) {
            TriStateCapability.UNKNOWN
        }
    }

    private fun scanDisplayCapabilities(): DisplayCapability {
        return try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val defaultDisplay = wm?.defaultDisplay

            var width = 1920
            var height = 1080
            var fps = 60
            var hdr10 = TriStateCapability.UNKNOWN
            var hlg = TriStateCapability.UNKNOWN
            var hdr10Plus = TriStateCapability.UNKNOWN
            var dolbyVision = TriStateCapability.UNKNOWN

            if (defaultDisplay != null) {
                val metrics = appContext.resources.displayMetrics
                width = metrics.widthPixels
                height = metrics.heightPixels

                if (Build.VERSION.SDK_INT >= 23) {
                    val mode = defaultDisplay.mode
                    if (mode != null) {
                        width = mode.physicalWidth
                        height = mode.physicalHeight
                        fps = mode.refreshRate.toInt()
                    }
                }

                if (Build.VERSION.SDK_INT >= 24) {
                    val hdrCaps = defaultDisplay.hdrCapabilities
                    if (hdrCaps != null && hdrCaps.supportedHdrTypes != null) {
                        val types = hdrCaps.supportedHdrTypes
                        hdr10 = if (types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10)) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED
                        hlg = if (types.contains(Display.HdrCapabilities.HDR_TYPE_HLG)) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED
                        dolbyVision = if (types.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED
                        if (Build.VERSION.SDK_INT >= 29) {
                            hdr10Plus = if (types.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)) TriStateCapability.SUPPORTED else TriStateCapability.UNSUPPORTED
                        }
                    }
                }
            }

            DisplayCapability(
                maxWidth = width,
                maxHeight = height,
                maxFps = fps,
                hdr10 = hdr10,
                hlg = hlg,
                hdr10Plus = hdr10Plus,
                dolbyVision = dolbyVision
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning display capabilities", e)
            DisplayCapability()
        }
    }

    private fun scanAudioOutputCapabilities(): AudioOutputCapability {
        return try {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            var passthrough = TriStateCapability.UNKNOWN
            var multichannel = TriStateCapability.UNKNOWN

            if (Build.VERSION.SDK_INT >= 23 && audioManager != null) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (device in devices) {
                    val type = device.type
                    if (type == android.media.AudioDeviceInfo.TYPE_HDMI
                        || type == android.media.AudioDeviceInfo.TYPE_HDMI_ARC
                        || (Build.VERSION.SDK_INT >= 31 && type == android.media.AudioDeviceInfo.TYPE_HDMI_EARC)) {
                        passthrough = TriStateCapability.SUPPORTED
                        multichannel = TriStateCapability.SUPPORTED
                        break
                    }
                }
            }

            AudioOutputCapability(
                stereo = TriStateCapability.SUPPORTED,
                multichannel = multichannel,
                passthrough = passthrough
            )
        } catch (e: Exception) {
            AudioOutputCapability()
        }
    }

    private fun getMediaCodecInfos(): List<MediaCodecInfo> {
        return try {
            if (Build.VERSION.SDK_INT >= 21) {
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
            } else {
                val count = MediaCodecList.getCodecCount()
                val list = ArrayList<MediaCodecInfo>(count)
                for (i in 0 until count) {
                    list.add(MediaCodecList.getCodecInfoAt(i))
                }
                list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching codec list", e)
            emptyList()
        }
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated
        }
        val name = info.name?.lowercase() ?: ""
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
    }

    companion object {
        private const val TAG = "AndroidVoxCapability"
    }
}
