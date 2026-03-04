package app.aaps.pump.common.hw.rileylink.service

import app.aaps.core.interfaces.logging.AapsDirectoryLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringPreferenceKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Returns an ordered list of RileyLink BLE addresses to try for pump communication.
 * Order: last successfully used address first (if still in the list); otherwise by BLE quality (RSSI, higher first).
 * When only one address is configured, returns a single-element list.
 */
@Singleton
class RileyLinkSelector @Inject constructor(
    private val preferences: Preferences,
    private val rileyLinkServiceData: RileyLinkServiceData,
    private val aapsDirectoryLogger: AapsDirectoryLogger
) {

    companion object {
        private const val LOG_TAG = "DualRL"
    }

    /**
     * @return List of RileyLink MAC addresses in the order they should be tried (first = preferred).
     */
    fun getOrderedAddresses(): List<String> {
        val primary = preferences.get(RileyLinkStringPreferenceKey.MacAddress).trim()
        val secondary = preferences.get(RileyLinkStringPreferenceKey.MacAddressSecondary).trim()
        val lastSuccessful = preferences.get(RileyLinkStringPreferenceKey.LastSuccessfulRileyLinkAddress).trim()

        val addresses = mutableListOf<String>()
        if (primary.isNotEmpty()) addresses.add(primary)
        if (secondary.isNotEmpty() && secondary != primary) addresses.add(secondary)
        if (addresses.isEmpty()) {
            aapsDirectoryLogger.log(LOG_TAG, "getOrderedAddresses: no addresses configured (primary=$primary, secondary=$secondary)")
            return emptyList()
        }
        if (addresses.size == 1) {
            aapsDirectoryLogger.log(LOG_TAG, "getOrderedAddresses: single RL -> [$primary]")
            return addresses
        }

        val order: List<String>
        val reason: String
        if (lastSuccessful.isNotEmpty() && addresses.contains(lastSuccessful)) {
            order = listOf(lastSuccessful) + addresses.filter { it != lastSuccessful }
            reason = "lastSuccessful=$lastSuccessful first"
        } else {
            order = addresses.sortedByDescending { addr -> rileyLinkServiceData.lastRssiByAddress[addr] ?: Int.MIN_VALUE }
            val rssiStr = order.joinToString { "$it=${rileyLinkServiceData.lastRssiByAddress[it] ?: "n/a"}" }
            reason = "by RSSI: $rssiStr"
        }
        aapsDirectoryLogger.log(LOG_TAG, "getOrderedAddresses: primary=$primary secondary=$secondary -> order=${order.joinToString(",")} ($reason)")
        return order
    }

    fun rememberLastSuccessfulAddress(address: String) {
        if (address.isNotEmpty()) {
            preferences.put(RileyLinkStringPreferenceKey.LastSuccessfulRileyLinkAddress, address)
            aapsDirectoryLogger.log(LOG_TAG, "rememberLastSuccessfulAddress: $address")
        }
    }
}
