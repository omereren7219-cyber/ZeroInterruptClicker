package com.zerointerrupt.clicker.shizuku

import android.content.Context
import android.os.Binder
import android.os.Parcel
import android.util.Log
import androidx.annotation.Keep

/**
 * Connectivity-only Shizuku UserService. It does not access input, native code,
 * game state, or any automatic-touch functionality.
 */
@Keep
class NoopShizukuUserService() : Binder() {

    @Suppress("unused")
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    init {
        Log.i(TAG, "No-op Shizuku UserService started; binder ready")
    }

    @Keep
    @Suppress("unused")
    fun destroy() {
        Log.i(TAG, "No-op Shizuku UserService destroyed")
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == 16777114 || code == 16777115) {
            destroy()
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private companion object {
        const val TAG = "ShizukuNoopService"
    }
}
