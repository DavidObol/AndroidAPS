package app.aaps.pump.common.hw.rileylink.data

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.common.hw.rileylink.data.RLHistoryItem.RLHistoryItemSource
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import org.joda.time.LocalDateTime

/**
 * History item describing automatic RileyLink selection between multiple devices.
 */
class RLHistoryItemRileyLinkSelection(
    private val message: String,
    targetDevice: RileyLinkTargetDevice
) : RLHistoryItem(LocalDateTime(), RLHistoryItemSource.RileyLink, targetDevice) {

    override fun getDescription(rh: ResourceHelper): String = message
}

