package app.zoocall.core.discovery

import app.zoocall.core.model.DeviceClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TxtRecordTest {

    @Test
    fun roundTrip() {
        val record = ServiceRecord("abc", 47474, "abcdefghijklmnopqrst", "Rakib", "Front desk", DeviceClass.Desktop)
        val parsed = TxtRecord.parse(TxtRecord.from(record).toAttributes())
        assertEquals(listOf(1), parsed.versions)
        assertEquals("abcdefghijklmnopqrst", parsed.fingerprintShortId)
        assertEquals("Rakib", parsed.displayName)
        assertEquals("Front desk", parsed.role)
        assertEquals(DeviceClass.Desktop, parsed.deviceClass)
    }

    @Test
    fun privateRecordCarriesOnlyTags() {
        val record = ServiceRecord("abc", 47474, null, null, null, DeviceClass.Phone, tags = listOf("0a1b2c3d", "deadbeef"))
        val attributes = TxtRecord.from(record).toAttributes()
        assertNull(attributes[TxtRecord.KEY_FP])
        assertNull(attributes[TxtRecord.KEY_NAME])
        val parsed = TxtRecord.parse(attributes)
        assertEquals(listOf("0a1b2c3d", "deadbeef"), parsed.tags)

        val hostile = TxtRecord.parse(mapOf("t" to "zzzzzzzz,DEADBEEF,0a1b2c3d," + List(40) { "00000000" }.joinToString(",")))
        assertEquals("0a1b2c3d", hostile.tags.first())
        assertEquals(16, hostile.tags.size)
    }

    @Test
    fun hostileValuesAreSanitized() {
        val parsed = TxtRecord.parse(
            mapOf(
                "v" to "1,x,2",
                "fp" to "NOT-BASE32",
                "n" to "‮evil" + "a".repeat(200),
                "d" to "toaster",
            ),
        )
        assertEquals(listOf(1, 2), parsed.versions)
        assertNull(parsed.fingerprintShortId)
        assertEquals(64, parsed.displayName!!.length)
        assertEquals('e', parsed.displayName.first())
        assertEquals(DeviceClass.Phone, parsed.deviceClass)
    }
}
