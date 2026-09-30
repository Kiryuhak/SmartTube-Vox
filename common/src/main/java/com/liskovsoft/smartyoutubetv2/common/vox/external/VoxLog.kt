package com.liskovsoft.smartyoutubetv2.common.vox.external

/**
 * Безопасное логирование с прямой записью в android.util.Log
 * и защитой от отсутствия мока android.util.Log в Unit-тестах.
 */
object VoxLog {

    fun d(tag: String, msg: String) {
        try {
            android.util.Log.d(tag, msg)
        } catch (e: Throwable) {
            println("DEBUG: [$tag] $msg")
        }
    }

    fun i(tag: String, msg: String) {
        try {
            android.util.Log.i(tag, msg)
        } catch (e: Throwable) {
            println("INFO: [$tag] $msg")
        }
    }

    fun w(tag: String, msg: String) {
        try {
            android.util.Log.w(tag, msg)
        } catch (e: Throwable) {
            System.err.println("WARN: [$tag] $msg")
        }
    }

    fun e(tag: String, msg: String) {
        try {
            android.util.Log.e(tag, msg)
        } catch (e: Throwable) {
            System.err.println("ERROR: [$tag] $msg")
        }
    }
}

