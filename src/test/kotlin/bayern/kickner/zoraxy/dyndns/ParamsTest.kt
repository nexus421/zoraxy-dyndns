package bayern.kickner.zoraxy.dyndns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParamsTest {

    private val v4 = "87.176.150.207"
    private val v6 = "2003:d5:df4a:a500::/56"

    private fun parse(vararg p: Pair<String, String>) = addressesFromQuery(p.toMap()).getOrThrow()?.map { it.display }
    private fun rejected(vararg p: Pair<String, String>) = assertTrue(addressesFromQuery(p.toMap()).isFailure, p.toList().toString())

    @Test
    fun myipWithBothFamilies() = assertEquals(listOf(v4, v6), parse("myip" to "$v4,$v6"))

    @Test
    fun ipv6FirstIsSortedAfterIpv4() = assertEquals(listOf(v4, v6), parse("myip" to "$v6,$v4"))

    @Test
    fun myipv4AndMyipv6() = assertEquals(listOf(v4, v6), parse("myipv4" to v4, "myipv6" to v6))

    @Test
    fun ipAndIpv6() = assertEquals(listOf(v4, v6), parse("ip" to v4, "ipv6" to v6))

    @Test
    fun myip6Alias() = assertEquals(listOf(v6), parse("myip6" to v6))

    @Test
    fun myipPlusSeparateIpv6() = assertEquals(listOf(v4, v6), parse("myip" to v4, "myipv6" to v6))

    @Test
    fun sameFamilyTwiceIsRejected() {
        rejected("myip" to v4, "myipv4" to "93.184.215.14")
        rejected("myip" to "$v6,2003:d5::/48")
    }

    @Test
    fun wrongFamilyForAliasIsRejected() {
        rejected("myipv4" to v6)
        rejected("ipv6" to v4)
    }

    @Test
    fun invalidValueIsRejected() = rejected("myip" to "192.168.178.1")

    @Test
    fun emptyValuesAreIgnored() = assertEquals(listOf(v4), parse("myip" to v4, "myipv6" to "", "hostname" to "home"))

    @Test
    fun noKnownParameterGivesNull() {
        assertNull(parse("hostname" to "home"))
        assertNull(parse("myip" to ""))
    }
}
