package app.zoocall.core.app

import app.zoocall.core.model.PeerAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContactCodeTest {

    @Test
    fun roundTrip() {
        val code = ContactCode(ByteArray(32) { it.toByte() }, listOf(PeerAddress("192.168.1.24", 47474), PeerAddress("fe80::1", 5000)), "Anika & Co ✓")
        val parsed = ContactCode.parse(code.toUri())!!
        assertContentEquals(code.publicKey, parsed.publicKey)
        assertEquals(code.addresses, parsed.addresses)
        assertEquals("Anika & Co ✓", parsed.name)
    }

    @Test
    fun rejectsMalformed() {
        assertNull(ContactCode.parse("https://example.com"))
        assertNull(ContactCode.parse("zoocall://add?v=2&k=AAAA"))
        assertNull(ContactCode.parse("zoocall://add?v=1&k=short"))
    }
}
