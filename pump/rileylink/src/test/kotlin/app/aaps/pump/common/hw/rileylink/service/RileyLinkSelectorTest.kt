package app.aaps.pump.common.hw.rileylink.service

import app.aaps.core.interfaces.logging.AapsDirectoryLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringPreferenceKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class RileyLinkSelectorTest {

    lateinit var preferences: Preferences
    lateinit var rileyLinkServiceData: RileyLinkServiceData
    lateinit var aapsDirectoryLogger: AapsDirectoryLogger
    lateinit var selector: RileyLinkSelector

    @BeforeEach
    fun setup() {
        preferences = mock()
        rileyLinkServiceData = mock()
        aapsDirectoryLogger = mock()
        selector = RileyLinkSelector(preferences, rileyLinkServiceData, aapsDirectoryLogger)
    }

    @Test
    fun `getOrderedAddresses returns single address when only primary is set`() {
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("AA:BB:CC:DD:EE:01")
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddressSecondary)).thenReturn("")
        whenever(preferences.get(RileyLinkStringPreferenceKey.LastSuccessfulRileyLinkAddress)).thenReturn("")

        val result = selector.getOrderedAddresses()

        assertThat(result).containsExactly("AA:BB:CC:DD:EE:01")
    }

    @Test
    fun `getOrderedAddresses returns last successful first when two addresses configured`() {
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("AA:BB:CC:DD:EE:01")
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddressSecondary)).thenReturn("AA:BB:CC:DD:EE:02")
        whenever(preferences.get(RileyLinkStringPreferenceKey.LastSuccessfulRileyLinkAddress)).thenReturn("AA:BB:CC:DD:EE:02")

        val result = selector.getOrderedAddresses()

        assertThat(result).containsExactly("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:01").inOrder()
    }

    @Test
    fun `getOrderedAddresses returns both addresses ordered by RSSI when two configured and no last successful`() {
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("AA:BB:CC:DD:EE:01")
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddressSecondary)).thenReturn("AA:BB:CC:DD:EE:02")
        whenever(preferences.get(RileyLinkStringPreferenceKey.LastSuccessfulRileyLinkAddress)).thenReturn("")
        whenever(rileyLinkServiceData.lastRssiByAddress).thenReturn(
            mutableMapOf(
                "AA:BB:CC:DD:EE:01" to -70,
                "AA:BB:CC:DD:EE:02" to -65
            )
        )

        val result = selector.getOrderedAddresses()

        assertThat(result).hasSize(2)
        assertThat(result[0]).isEqualTo("AA:BB:CC:DD:EE:02")
        assertThat(result[1]).isEqualTo("AA:BB:CC:DD:EE:01")
    }

    @Test
    fun `getOrderedAddresses returns empty when no primary`() {
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddress)).thenReturn("")
        whenever(preferences.get(RileyLinkStringPreferenceKey.MacAddressSecondary)).thenReturn("")

        val result = selector.getOrderedAddresses()

        assertThat(result).isEmpty()
    }
}
