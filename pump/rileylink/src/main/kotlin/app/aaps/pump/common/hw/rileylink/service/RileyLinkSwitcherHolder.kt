package app.aaps.pump.common.hw.rileylink.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current [RileyLinkConnectionSwitcher] implementation (set by the RileyLink service when bound).
 * Allows [MedtronicCommunicationManager] and similar components to request a connection switch
 * without depending on the Android Service directly.
 */
@Singleton
class RileyLinkSwitcherHolder @Inject constructor() {

    var switcher: RileyLinkConnectionSwitcher? = null
        set(value) {
            field = value
        }
}
