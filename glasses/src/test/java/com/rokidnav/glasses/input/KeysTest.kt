package com.rokidnav.glasses.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeysTest {
    @Test fun theCameraButtonBroadcastIsNotATap() {
        assertNull(GlassesKey.fromKeyType(KeyType.CLICK))
        assertNull(GlassesKey.fromKeyType(KeyType.BUTTON_DOWN))
        assertNull(GlassesKey.fromKeyType(KeyType.BUTTON_UP))
    }

    @Test fun theTouchpadTapArrivesAsAnEnterKey() {
        assertEquals(GlassesKey.TAP, GlassesKey.fromKeyCode(KeyEvent.KEYCODE_ENTER))
        assertEquals(GlassesKey.SWIPE_FORWARD, GlassesKey.fromKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(GlassesKey.fromKeyCode(83))
    }
}
