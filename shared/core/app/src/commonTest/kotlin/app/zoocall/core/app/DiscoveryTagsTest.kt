package app.zoocall.core.app

import app.zoocall.core.crypto.Identity
import app.zoocall.core.crypto.Sodium
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiscoveryTagsTest {

    @Test
    fun onlyThePairSharesATagAndItRotates() = runTest {
        Sodium.ensureInitialized()
        val alice = Identity.generate()
        val bob = Identity.generate()
        val eve = Identity.generate()

        val ab = DiscoveryTags.pairSecret(alice, bob.publicKey)
        assertContentEquals(ab, DiscoveryTags.pairSecret(bob, alice.publicKey))

        val epoch = DiscoveryTags.epoch(1_800_000_000_000)
        val tag = DiscoveryTags.tag(ab, epoch)
        assertTrue(Regex("[0-9a-f]{8}").matches(tag))
        assertEquals(tag, DiscoveryTags.tag(DiscoveryTags.pairSecret(bob, alice.publicKey), epoch))
        assertNotEquals(tag, DiscoveryTags.tag(ab, epoch + 1), "tags change every epoch")
        assertNotEquals(tag, DiscoveryTags.tag(DiscoveryTags.pairSecret(alice, eve.publicKey), epoch), "another contact sees a different tag")
        assertNotEquals(DiscoveryTags.rotatingInstanceName("abc", epoch), DiscoveryTags.rotatingInstanceName("abc", epoch + 1))
    }
}
