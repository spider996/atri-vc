package com.atri.vc

import android.util.Log

/**
 * 极简日志。所有关键步骤都打 TAG = AtriVC，便于在模拟器/真机上用
 * `adb logcat -s AtriVC` 观察端到端流程（无 UI 也能确认跑到哪一步）。
 */
object L {
    const val TAG = "AtriVC"

    var enabled: Boolean = true

    fun d(msg: String) {
        if (enabled) Log.d(TAG, msg)
    }

    fun i(msg: String) {
        if (enabled) Log.i(TAG, msg)
    }

    fun w(msg: String) {
        if (enabled) Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (enabled) Log.e(TAG, msg, t)
    }
}
