package app.zoocall.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaSoundDevicesTest {

    @Test
    fun matchesTheSameDeviceAcrossNamingStyles() {
        assertTrue(JavaSoundDevices.namesMatch("Microphone (Realtek(R) Audio)", "Microphone (Realtek(R) Audio)"))
        // DirectSound truncates long names to 31 characters.
        assertTrue(JavaSoundDevices.namesMatch("Headset Microphone (Jabra Evolv", "Headset Microphone (Jabra Evolve2 65)"))
    }

    @Test
    fun rejectsPortsAndDifferentDevices() {
        assertFalse(JavaSoundDevices.namesMatch("Port Microphone (Realtek(R) Audio)", "Microphone (Realtek(R) Audio)"))
        assertFalse(JavaSoundDevices.namesMatch("Speakers (Realtek(R) Audio)", "Microphone (Realtek(R) Audio)"))
        assertFalse(JavaSoundDevices.namesMatch("Mic", "Mic Array"))
    }
}
