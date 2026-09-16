package app.zoocall.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkDoctorTest {
    private val lan = NetworkState(onLocalNetwork = true, localAddresses = listOf("192.168.1.24"))

    private fun inputs(
        network: NetworkState = lan,
        port: Int? = 47474,
        discovered: Int = 0,
        connected: Int = 0,
        probe: ProbeOutcome = ProbeOutcome.NotTried,
        contactHosts: List<String> = emptyList(),
        platform: List<DiagnosticResult> = emptyList(),
    ) = NetworkDoctor.Inputs(network, port, discovered, connected, probe, contactHosts, platform)

    private fun List<DiagnosticResult>.status(check: DoctorCheck) = firstOrNull { it.check == check }?.status

    @Test
    fun healthyNetwork() {
        val results = NetworkDoctor.evaluate(inputs(discovered = 2, connected = 1))
        assertTrue(results.all { it.status == CheckStatus.Ok })
        assertEquals(listOf("2"), results.first { it.check == DoctorCheck.Discovery }.args)
    }

    @Test
    fun offlineStopsEarlyButKeepsBatteryCheck() {
        val battery = DiagnosticResult(DoctorCheck.Battery, CheckStatus.Warning, fixable = true)
        val firewall = DiagnosticResult(DoctorCheck.Firewall, CheckStatus.Problem)
        val results = NetworkDoctor.evaluate(inputs(network = NetworkState(false, emptyList()), platform = listOf(battery, firewall)))
        assertEquals(listOf(DoctorCheck.LocalNetwork, DoctorCheck.Battery), results.map { it.check })
        assertEquals(CheckStatus.Problem, results.status(DoctorCheck.LocalNetwork))
    }

    @Test
    fun multicastBlockedWhenDirectWorks() {
        val results = NetworkDoctor.evaluate(inputs(probe = ProbeOutcome.Reached))
        assertEquals(CheckStatus.Problem, results.status(DoctorCheck.Discovery))
        assertEquals(CheckStatus.Ok, results.status(DoctorCheck.Reachability))
    }

    @Test
    fun clientIsolationWhenContactsTimeOut() {
        val results = NetworkDoctor.evaluate(inputs(probe = ProbeOutcome.Failed, contactHosts = listOf("192.168.1.30")))
        assertEquals(CheckStatus.Warning, results.status(DoctorCheck.Discovery))
        assertEquals(CheckStatus.Problem, results.status(DoctorCheck.Reachability))
        assertNull(results.status(DoctorCheck.Subnet))
    }

    @Test
    fun differentSubnet() {
        val results = NetworkDoctor.evaluate(inputs(probe = ProbeOutcome.Failed, contactHosts = listOf("10.0.0.7")))
        val subnet = results.first { it.check == DoctorCheck.Subnet }
        assertEquals(listOf("192.168.1.24", "10.0.0.7"), subnet.args)
    }

    @Test
    fun nobodyToTestIsOnlyAWarning() {
        val results = NetworkDoctor.evaluate(inputs(network = lan.copy(vpnActive = true), port = null))
        assertEquals(CheckStatus.Warning, results.status(DoctorCheck.Discovery))
        assertEquals(CheckStatus.Warning, results.status(DoctorCheck.Vpn))
        assertEquals(CheckStatus.Problem, results.status(DoctorCheck.Listening))
        assertNull(results.status(DoctorCheck.Reachability))
    }

    @Test
    fun subnetParsing() {
        assertEquals("192.168.1", NetworkDoctor.subnet24("192.168.1.24"))
        assertNull(NetworkDoctor.subnet24("fe80::1"))
        assertNull(NetworkDoctor.subnet24("300.1.1.1"))
    }
}
