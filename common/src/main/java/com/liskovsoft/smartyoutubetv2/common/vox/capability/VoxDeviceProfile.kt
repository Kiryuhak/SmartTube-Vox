package com.liskovsoft.smartyoutubetv2.common.vox.capability

import org.json.JSONObject

/**
 * Характеристики видеокодека.
 */
data class VideoCodecCapability(
    val codec: String, // "avc", "hevc", "vp9", "av1"
    val capability: TriStateCapability = TriStateCapability.UNKNOWN,
    val hardwareAccelerated: Boolean = false,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFps: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("codec", codec)
        put("capability", capability.value)
        put("hardwareAccelerated", hardwareAccelerated)
        put("maxWidth", maxWidth)
        put("maxHeight", maxHeight)
        put("maxFps", maxFps)
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): VideoCodecCapability {
            return VideoCodecCapability(
                codec = json.optString("codec", "unknown"),
                capability = TriStateCapability.fromValue(json.optString("capability")),
                hardwareAccelerated = json.optBoolean("hardwareAccelerated", false),
                maxWidth = json.optInt("maxWidth", 0),
                maxHeight = json.optInt("maxHeight", 0),
                maxFps = json.optInt("maxFps", 0)
            )
        }
    }
}

/**
 * Характеристики аудиокодека с разделением декодирования и passthrough.
 */
data class AudioCodecCapability(
    val codec: String, // "aac", "opus", "ac3", "eac3", "eac3_joc"
    val decodeCapability: TriStateCapability = TriStateCapability.UNKNOWN,
    val passthroughCapability: TriStateCapability = TriStateCapability.UNKNOWN
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("codec", codec)
        put("decodeCapability", decodeCapability.value)
        put("passthroughCapability", passthroughCapability.value)
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): AudioCodecCapability {
            return AudioCodecCapability(
                codec = json.optString("codec", "unknown"),
                decodeCapability = TriStateCapability.fromValue(json.optString("decodeCapability")),
                passthroughCapability = TriStateCapability.fromValue(json.optString("passthroughCapability"))
            )
        }
    }
}

/**
 * Характеристики экрана и форматов HDR.
 */
data class DisplayCapability(
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    val maxFps: Int = 60,
    val hdr10: TriStateCapability = TriStateCapability.UNKNOWN,
    val hlg: TriStateCapability = TriStateCapability.UNKNOWN,
    val hdr10Plus: TriStateCapability = TriStateCapability.UNKNOWN,
    val dolbyVision: TriStateCapability = TriStateCapability.UNKNOWN
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("maxWidth", maxWidth)
        put("maxHeight", maxHeight)
        put("maxFps", maxFps)
        put("hdr10", hdr10.value)
        put("hlg", hlg.value)
        put("hdr10Plus", hdr10Plus.value)
        put("dolbyVision", dolbyVision.value)
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): DisplayCapability {
            return DisplayCapability(
                maxWidth = json.optInt("maxWidth", 1920),
                maxHeight = json.optInt("maxHeight", 1080),
                maxFps = json.optInt("maxFps", 60),
                hdr10 = TriStateCapability.fromValue(json.optString("hdr10")),
                hlg = TriStateCapability.fromValue(json.optString("hlg")),
                hdr10Plus = TriStateCapability.fromValue(json.optString("hdr10Plus")),
                dolbyVision = TriStateCapability.fromValue(json.optString("dolbyVision"))
            )
        }
    }
}

/**
 * Характеристики аудиовыхода (стерео, многоканальный, сквозной passthrough).
 */
data class AudioOutputCapability(
    val stereo: TriStateCapability = TriStateCapability.SUPPORTED,
    val multichannel: TriStateCapability = TriStateCapability.UNKNOWN,
    val passthrough: TriStateCapability = TriStateCapability.UNKNOWN
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stereo", stereo.value)
        put("multichannel", multichannel.value)
        put("passthrough", passthrough.value)
    }

    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): AudioOutputCapability {
            return AudioOutputCapability(
                stereo = TriStateCapability.fromValue(json.optString("stereo", TriStateCapability.SUPPORTED.value)),
                multichannel = TriStateCapability.fromValue(json.optString("multichannel")),
                passthrough = TriStateCapability.fromValue(json.optString("passthrough"))
            )
        }
    }
}

/**
 * Профиль устройства SmartTube VOX (схема vox-device-profile-v1).
 */
data class VoxDeviceProfile(
    val schemaVersion: Int = SCHEMA_VERSION,
    val platform: VoxPlatform = VoxPlatform.UNKNOWN,
    val manufacturer: String = "Unknown",
    val model: String = "Unknown",
    val osName: String = "Unknown",
    val osVersion: String = "Unknown",
    val videoCodecs: Map<String, VideoCodecCapability> = emptyMap(),
    val audioCodecs: Map<String, AudioCodecCapability> = emptyMap(),
    val display: DisplayCapability = DisplayCapability(),
    val audioOutput: AudioOutputCapability = AudioOutputCapability(),
    val scannedAtTimestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", schemaVersion)
        put("schemaId", SCHEMA_ID)
        put("platform", platform.id)
        put("manufacturer", manufacturer)
        put("model", model)
        put("osName", osName)
        put("osVersion", osVersion)
        put("scannedAtTimestampMs", scannedAtTimestampMs)

        val videoObj = JSONObject()
        videoCodecs.forEach { (k, v) -> videoObj.put(k, v.toJson()) }
        put("video", videoObj)

        val audioObj = JSONObject()
        audioCodecs.forEach { (k, v) -> audioObj.put(k, v.toJson()) }
        put("audio", audioObj)

        put("display", display.toJson())
        put("audioOutput", audioOutput.toJson())
    }

    fun isVideoCodecSupported(codec: String): Boolean {
        val key = normalizeCodecKey(codec)
        return videoCodecs[key]?.capability == TriStateCapability.SUPPORTED
    }

    fun isAudioDecodeSupported(codec: String): Boolean {
        val key = normalizeCodecKey(codec)
        return audioCodecs[key]?.decodeCapability == TriStateCapability.SUPPORTED
    }

    fun isAudioPassthroughSupported(codec: String): Boolean {
        val key = normalizeCodecKey(codec)
        return audioCodecs[key]?.passthroughCapability == TriStateCapability.SUPPORTED
    }

    fun has4kHardwareDecode(): Boolean {
        val av1Cap = videoCodecs["av1"]
        val vp9Cap = videoCodecs["vp9"]
        val avcCap = videoCodecs["avc"]
        val hevcCap = videoCodecs["hevc"]
        return (av1Cap?.hardwareAccelerated == true && av1Cap.maxHeight >= 2160) ||
               (vp9Cap?.hardwareAccelerated == true && vp9Cap.maxHeight >= 2160) ||
               (avcCap?.hardwareAccelerated == true && avcCap.maxHeight >= 2160) ||
               (hevcCap?.hardwareAccelerated == true && hevcCap.maxHeight >= 2160)
    }

    private fun normalizeCodecKey(codec: String): String {
        val lower = codec.lowercase()
        return when {
            lower.contains("av01") || lower.contains("av1") -> "av1"
            lower.contains("vp9") || lower.contains("vp09") -> "vp9"
            lower.contains("avc") || lower.contains("h264") -> "avc"
            lower.contains("hevc") || lower.contains("h265") -> "hevc"
            lower.contains("eac3") || lower.contains("ec-3") -> "eac3"
            lower.contains("ac3") || lower.contains("ac-3") -> "ac3"
            lower.contains("opus") -> "opus"
            lower.contains("mp4a") || lower.contains("aac") -> "aac"
            else -> lower
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val SCHEMA_ID = "vox-device-profile-v1"

        @JvmStatic
        fun fromJson(jsonStr: String?): VoxDeviceProfile? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val json = JSONObject(jsonStr)
                val platform = VoxPlatform.fromId(json.optString("platform"))
                val manufacturer = json.optString("manufacturer", "Unknown")
                val model = json.optString("model", "Unknown")
                val osName = json.optString("osName", "Unknown")
                val osVersion = json.optString("osVersion", "Unknown")
                val scannedAt = json.optLong("scannedAtTimestampMs", System.currentTimeMillis())

                val videoMap = mutableMapOf<String, VideoCodecCapability>()
                val videoObj = json.optJSONObject("video")
                videoObj?.keys()?.forEach { key ->
                    val cObj = videoObj.optJSONObject(key)
                    if (cObj != null) {
                        videoMap[key] = VideoCodecCapability.fromJson(cObj)
                    }
                }

                val audioMap = mutableMapOf<String, AudioCodecCapability>()
                val audioObj = json.optJSONObject("audio")
                audioObj?.keys()?.forEach { key ->
                    val cObj = audioObj.optJSONObject(key)
                    if (cObj != null) {
                        audioMap[key] = AudioCodecCapability.fromJson(cObj)
                    }
                }

                val display = json.optJSONObject("display")?.let { DisplayCapability.fromJson(it) } ?: DisplayCapability()
                val audioOutput = json.optJSONObject("audioOutput")?.let { AudioOutputCapability.fromJson(it) } ?: AudioOutputCapability()

                VoxDeviceProfile(
                    schemaVersion = json.optInt("schema", SCHEMA_VERSION),
                    platform = platform,
                    manufacturer = manufacturer,
                    model = model,
                    osName = osName,
                    osVersion = osVersion,
                    videoCodecs = videoMap,
                    audioCodecs = audioMap,
                    display = display,
                    audioOutput = audioOutput,
                    scannedAtTimestampMs = scannedAt
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
