package com.liskovsoft.smartyoutubetv2.common.vox.external

import android.content.Context
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs
import java.util.UUID

/**
 * Управление идентификатором устройства (UPnP UDN UUID) и именем для DIAL/SSDP.
 */
open class VoxDeviceIdentity(
    private val context: Context,
    private val customUuid: String? = null
) {

    companion object {
        private const val PREF_KEY_DIAL_UUID = "vox_dial_device_uuid"
        private const val DEFAULT_FRIENDLY_NAME = "SmartTube VOX"
        private const val MANUFACTURER = "SmartTube"
        private const val MODEL_NAME = "SmartTube VOX Receiver"
        private const val MODEL_NUMBER = "5.0"
    }

    private val prefs by lazy { AppPrefs.instance(context) }

    /**
     * Возвращает постоянный UUID устройства (сохраняется в SharedPreferences).
     */
    open fun getDeviceUuid(): String {
        if (!customUuid.isNullOrBlank()) {
            return customUuid
        }
        var uuid = prefs.getData(PREF_KEY_DIAL_UUID)
        if (uuid.isNullOrBlank()) {
            uuid = UUID.randomUUID().toString()
            prefs.setData(PREF_KEY_DIAL_UUID, uuid)
        }
        return uuid
    }

    /**
     * UDN в формате UPnP: uuid:<uuid>
     */
    open fun getUdn(): String {
        return "uuid:${getDeviceUuid()}"
    }

    open fun getFriendlyName(): String {
        return DEFAULT_FRIENDLY_NAME
    }

    open fun getManufacturer(): String {
        return MANUFACTURER
    }

    open fun getModelName(): String {
        return MODEL_NAME
    }

    open fun getModelNumber(): String {
        return MODEL_NUMBER
    }
}
