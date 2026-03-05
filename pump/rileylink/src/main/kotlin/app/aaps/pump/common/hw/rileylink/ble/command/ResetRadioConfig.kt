package app.aaps.pump.common.hw.rileylink.ble.command

import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkCommandType

/**
 * Reset radio configuration command (RileyLink firmware 2.x+).
 * Aligns with iAPS: reset before configureRadio(for:frequency).
 * Caller (RFSpy) must check firmware supports ResetRadioConfig before sending.
 */
class ResetRadioConfig : RileyLinkCommand() {

    override fun getCommandType(): RileyLinkCommandType = RileyLinkCommandType.ResetRadioConfig
    override fun getRaw(): ByteArray = getByteArray(getCommandType().code)
}
