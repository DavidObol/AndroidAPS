package app.aaps.pump.common.hw.rileylink.service

/**
 * Allows switching the active RileyLink BLE connection to a different device address.
 * Used when two RileyLinks are configured: try primary first, on failure switch to secondary and retry.
 */
interface RileyLinkConnectionSwitcher {

    /**
     * Switch the active connection to the given address. May block until connection is established or timeout.
     * @param address BLE MAC address of the RileyLink to connect to
     * @return true if the switch was initiated (and connection may still be in progress)
     */
    fun switchTo(address: String): Boolean
}
