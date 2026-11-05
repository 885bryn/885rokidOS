package com.rokidos885.phone.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** The call volume of a Grok Bot chat. Grok Bot runs as a call, so the glasses' Bluetooth call volume applies. */
interface CallVolume {
    /** Sets the call volume to half. True when the call audio is on Bluetooth, so the setting reached the glasses. */
    fun half(): Boolean
    /** One step up or down; the new volume in percent. */
    fun step(up: Boolean): Int
    /** The volume in percent. */
    fun percent(): Int
}

/**
 * STREAM_VOICE_CALL. Android keeps one volume for each output, and Bluetooth call volume starts at full
 * (too loud, user report 2026-11-05), so a chat starts at half once its audio is on the glasses.
 */
class AndroidCallVolume(context: Context) : CallVolume {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val min get() = audio.getStreamMinVolume(STREAM)
    private val max get() = audio.getStreamMaxVolume(STREAM)

    override fun half(): Boolean {
        audio.setStreamVolume(STREAM, min + (max - min + 1) / 2, 0)
        return onBluetooth()
    }

    override fun step(up: Boolean): Int {
        audio.adjustStreamVolume(STREAM, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        return percent()
    }

    override fun percent(): Int = if (max <= min) -1 else (audio.getStreamVolume(STREAM) - min) * 100 / (max - min)

    @Suppress("DEPRECATION")
    private fun onBluetooth() = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        else audio.isBluetoothScoOn

    private companion object { const val STREAM = AudioManager.STREAM_VOICE_CALL }
}
