package app.aaps.pump.medtronic.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.res.Configuration
import android.os.Binder
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.pump.common.hw.rileylink.ble.data.GattAttributes
import app.aaps.pump.common.hw.rileylink.RileyLinkConst
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkEncodingType
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency
import app.aaps.pump.common.hw.rileylink.data.RLHistoryItemRileyLinkSelection
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringPreferenceKey
import app.aaps.pump.common.hw.rileylink.keys.RileylinkBooleanPreferenceKey
import app.aaps.pump.common.hw.rileylink.service.RileyLinkService
import app.aaps.pump.medtronic.MedtronicPumpPlugin
import app.aaps.pump.medtronic.R
import app.aaps.pump.medtronic.comm.MedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.ui.MedtronicUIComm
import app.aaps.pump.medtronic.defs.BatteryType
import app.aaps.pump.medtronic.defs.MedtronicDeviceType
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import app.aaps.pump.medtronic.keys.MedtronicIntPreferenceKey
import app.aaps.pump.medtronic.keys.MedtronicStringPreferenceKey
import app.aaps.pump.medtronic.util.MedtronicUtil
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RileyLinkMedtronicService is intended to stay running when the gui-app is closed.
 */
@Singleton
class RileyLinkMedtronicService : RileyLinkService() {

    @Inject lateinit var medtronicPumpPlugin: MedtronicPumpPlugin
    @Inject lateinit var medtronicUtil: MedtronicUtil
    @Inject lateinit var medtronicPumpStatus: MedtronicPumpStatus
    @Inject lateinit var medtronicCommunicationManager: MedtronicCommunicationManager
    @Inject lateinit var medtronicUIComm: MedtronicUIComm

    private val mBinder: IBinder = LocalBinder()
    private var serialChanged = false
    private var rileyLinkAddress: String? = null
    private var rileyLinkAddressChanged = false
    private var encodingType: RileyLinkEncodingType? = null
    private var encodingChanged = false
    private var inPreInit = true

    override fun onCreate() {
        super.onCreate()
        aapsLogger.debug(LTag.PUMPCOMM, "RileyLinkMedtronicService newly created")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        aapsLogger.warn(LTag.PUMPCOMM, "onConfigurationChanged")
        super.onConfigurationChanged(newConfig)
    }

    override fun onBind(intent: Intent): IBinder {
        return mBinder
    }

    override val encoding: RileyLinkEncodingType
        get() = RileyLinkEncodingType.FourByteSixByteLocal

    /**
     * If you have customized RileyLinkServiceData you need to override this
     */
    override fun initRileyLinkServiceData() {
        rileyLinkServiceData.targetDevice = RileyLinkTargetDevice.MedtronicPump
        setPumpIDString(preferences.get(MedtronicStringPreferenceKey.Serial))

        // get most recently used RileyLink address and name
        rileyLinkServiceData.rileyLinkAddress = preferences.get(RileyLinkStringPreferenceKey.MacAddress)
        rileyLinkServiceData.rileyLinkName = preferences.get(RileyLinkStringKey.Name)
        rfSpy.startReader()
        aapsLogger.debug(LTag.PUMPCOMM, "RileyLinkMedtronicService newly constructed")
    }

    override val deviceCommunicationManager
        get() = medtronicCommunicationManager

    override fun setPumpDeviceState(pumpDeviceState: PumpDeviceState) {
        medtronicPumpStatus.pumpDeviceState = pumpDeviceState
    }

    private fun setPumpIDString(pumpID: String) {
        if (pumpID.length != 6) {
            aapsLogger.error("setPumpIDString: invalid pump id string: $pumpID")
            return
        }
        val pumpIDBytes = ByteUtil.fromHexString(pumpID)
        if (pumpIDBytes == null) {
            aapsLogger.error("Invalid pump ID? - PumpID is null.")
            rileyLinkServiceData.setPumpID("000000", byteArrayOf(0, 0, 0))
        } else if (pumpIDBytes.size != 3) {
            aapsLogger.error("Invalid pump ID? " + ByteUtil.shortHexString(pumpIDBytes))
            rileyLinkServiceData.setPumpID("000000", byteArrayOf(0, 0, 0))
        } else if (pumpID == "000000") {
            aapsLogger.error("Using pump ID $pumpID")
            rileyLinkServiceData.setPumpID(pumpID, byteArrayOf(0, 0, 0))
        } else {
            aapsLogger.info(LTag.PUMPBTCOMM, "Using pump ID $pumpID")
            val oldId = rileyLinkServiceData.pumpID
            rileyLinkServiceData.setPumpID(pumpID, pumpIDBytes)
            if (oldId != null && oldId != pumpID) {
                medtronicUtil.medtronicPumpModel = MedtronicDeviceType.Medtronic_522 // if we change pumpId, model probably changed too
                medtronicUtil.isModelSet = false
            }
            return
        }
        medtronicPumpStatus.pumpDeviceState = PumpDeviceState.InvalidConfiguration

        // LOG.info("setPumpIDString: saved pumpID " + idString);
    }

    inner class LocalBinder : Binder() {

        val serviceInstance: RileyLinkMedtronicService
            get() = this@RileyLinkMedtronicService
    }

    /* private functions */ // PumpInterface - REMOVE
    val isInitialized: Boolean
        get() = rileyLinkServiceData.rileyLinkServiceState.isReady()

    private fun autoSelectBestRileyLinkIfConfigured() {
        val knownDevicesRaw = preferences.get(RileyLinkStringPreferenceKey.MacAddressList)
        val knownDevices: MutableMap<String, String> = LinkedHashMap()
        if (knownDevicesRaw.isNotBlank()) {
            knownDevicesRaw.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { entry ->
                    val parts = entry.split("|", limit = 2)
                    if (parts.isNotEmpty()) {
                        val addr = parts[0].trim()
                        if (addr.isNotEmpty()) {
                            val name = if (parts.size > 1) parts[1].trim() else ""
                            knownDevices[addr] = name
                        }
                    }
                }
        }

        if (knownDevices.size <= 1) {
            // Нечего выбирать – либо одно устройство, либо ни одного.
            return
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "autoSelectBestRileyLinkIfConfigured: Bluetooth adapter not available or disabled")
            return
        }

        val hasScanPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasScanPermission) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "autoSelectBestRileyLinkIfConfigured: BLUETOOTH_SCAN permission not granted")
            return
        }

        val scanner: BluetoothLeScanner? = adapter.bluetoothLeScanner
        if (scanner == null) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "autoSelectBestRileyLinkIfConfigured: BluetoothLeScanner is null")
            return
        }

        val rssiByAddress: MutableMap<String, Int> = HashMap()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(
                android.os.ParcelUuid.fromString(GattAttributes.SERVICE_RADIO)
            ).build()
        )

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) {
                    handleResult(result)
                }
            }

            private fun handleResult(result: ScanResult) {
                val addr = result.device.address
                if (knownDevices.containsKey(addr)) {
                    val current = rssiByAddress[addr]
                    val rssi = result.rssi
                    if (current == null || rssi > current) {
                        rssiByAddress[addr] = rssi
                    }
                }
            }
        }

        try {
            scanner.startScan(filters, scanSettings, callback)
            aapsLogger.debug(LTag.PUMPBTCOMM, "autoSelectBestRileyLinkIfConfigured: started scan")
            Thread.sleep(3_000L)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "autoSelectBestRileyLinkIfConfigured: exception during scan", e)
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }

        val selectedAddress: String?
        val selectedRssi: Int?
        if (rssiByAddress.isNotEmpty()) {
            val best = rssiByAddress.entries.maxByOrNull { it.value }!!
            selectedAddress = best.key
            selectedRssi = best.value
        } else {
            // Ни одно из сохранённых устройств не увидели, выходим, не меняя текущий MacAddress.
            aapsLogger.debug(LTag.PUMPBTCOMM, "autoSelectBestRileyLinkIfConfigured: no known RL seen during scan")
            return
        }

        val currentAddress = preferences.get(RileyLinkStringPreferenceKey.MacAddress)
        if (selectedAddress == null || selectedAddress == currentAddress) {
            // Уже выбрано оптимальное устройство.
            return
        }

        val selectedName = knownDevices[selectedAddress] ?: ""
        preferences.put(RileyLinkStringPreferenceKey.MacAddress, selectedAddress)
        if (selectedName.isNotBlank()) {
            preferences.put(RileyLinkStringKey.Name, selectedName)
        }

        val msg = "Medtronic auto-selected RileyLink $selectedName ($selectedAddress), RSSI=$selectedRssi among ${knownDevices.size} device(s)"
        rileyLinkUtil.rileyLinkHistory.add(RLHistoryItemRileyLinkSelection(msg, RileyLinkTargetDevice.MedtronicPump))
        aapsLogger.info(LTag.PUMPBTCOMM, msg)

        // Отметим, что адрес изменился, чтобы reconfigureService инициировал реконнект.
        rileyLinkAddress = selectedAddress
        rileyLinkAddressChanged = true
    }

    override fun verifyConfiguration(forceRileyLinkAddressRenewal: Boolean): Boolean {
        return try {
            val regexSN = "[0-9]{6}"
            val regexMac = "([\\da-fA-F]{1,2}(?::|$)){6}"
            medtronicPumpStatus.errorDescription = "-"
            val serialNr = preferences.get(MedtronicStringPreferenceKey.Serial)
            if (!serialNr.matches(regexSN.toRegex())) {
                medtronicPumpStatus.errorDescription = rh.gs(R.string.medtronic_error_serial_invalid)
                return false
            }
            if (serialNr != medtronicPumpStatus.serialNumber) {
                medtronicPumpStatus.serialNumber = serialNr
                serialChanged = true
            }
            val pumpTypePref = preferences.get(MedtronicStringPreferenceKey.PumpType)
            if (pumpTypePref.isEmpty()) {
                medtronicPumpStatus.errorDescription = rh.gs(R.string.medtronic_error_pump_type_not_set)
                return false
            } else {
                val pumpTypePart = pumpTypePref.substring(0, 3)
                if (!pumpTypePart.matches("[0-9]{3}".toRegex())) {
                    medtronicPumpStatus.errorDescription = rh.gs(R.string.medtronic_error_pump_type_invalid)
                    return false
                } else {
                    val pumpType = medtronicPumpStatus.medtronicPumpMap[pumpTypePart] ?: return false
                    medtronicPumpStatus.medtronicDeviceType = medtronicPumpStatus.medtronicDeviceTypeMap[pumpTypePart] ?: return false
                    medtronicPumpStatus.pumpType = pumpType
                    medtronicPumpPlugin.pumpType = pumpType
                    if (pumpTypePart.startsWith("7")) medtronicPumpStatus.reservoirFullUnits = 300 else medtronicPumpStatus.reservoirFullUnits = 176
                }
            }

            // If multiple RileyLinks are configured, try to auto-select the best one by RSSI
            autoSelectBestRileyLinkIfConfigured()

            rileyLinkServiceData.rileyLinkTargetFrequency = RileyLinkTargetFrequency.getByKey(preferences.get(MedtronicStringPreferenceKey.PumpFrequency))
            val rileyLinkAddress = preferences.get(RileyLinkStringPreferenceKey.MacAddress)
            if (rileyLinkAddress.isEmpty()) {
                aapsLogger.debug(LTag.PUMP, "RileyLink address invalid: null")
                medtronicPumpStatus.errorDescription = rh.gs(R.string.medtronic_error_rileylink_address_invalid)
                return false
            } else {
                if (!rileyLinkAddress.matches(regexMac.toRegex())) {
                    medtronicPumpStatus.errorDescription = rh.gs(R.string.medtronic_error_rileylink_address_invalid)
                    aapsLogger.debug(LTag.PUMP, "RileyLink address invalid: %s", rileyLinkAddress)
                    return false
                } else {
                    if (rileyLinkAddress != this.rileyLinkAddress) {
                        this.rileyLinkAddress = rileyLinkAddress
                        rileyLinkAddressChanged = true
                    }
                }
            }
            val maxBolusLcl = preferences.get(MedtronicIntPreferenceKey.MaxBolus).toDouble()
            if (medtronicPumpStatus.maxBolus == null || medtronicPumpStatus.maxBolus != maxBolusLcl) {
                medtronicPumpStatus.maxBolus = maxBolusLcl

                //LOG.debug("Max Bolus from AAPS settings is " + maxBolus);
            }
            val maxBasalLcl = preferences.get(MedtronicIntPreferenceKey.MaxBasal).toDouble()
            if (medtronicPumpStatus.maxBasal == null || medtronicPumpStatus.maxBasal != maxBasalLcl) {
                medtronicPumpStatus.maxBasal = maxBasalLcl

                //LOG.debug("Max Basal from AAPS settings is " + maxBasal);
            }
            val encodingTypeStr = preferences.get(RileyLinkStringPreferenceKey.Encoding)
            val newEncodingType = RileyLinkEncodingType.getByKey(encodingTypeStr)
            if (encodingType == null) {
                encodingType = newEncodingType
            } else if (encodingType != newEncodingType) {
                encodingType = newEncodingType
                encodingChanged = true
            }
            medtronicPumpStatus.batteryType = BatteryType.getByKey(preferences.get(MedtronicStringPreferenceKey.BatteryType))

            //String bolusDebugEnabled = sp.getStringOrNull(MedtronicConst.Prefs.BolusDebugEnabled, null);
            //boolean bolusDebug = bolusDebugEnabled != null && bolusDebugEnabled.equals(rh.gs(R.string.common_on));
            //MedtronicHistoryData.doubleBolusDebug = bolusDebug;
            rileyLinkServiceData.showBatteryLevel = preferences.get(RileylinkBooleanPreferenceKey.ShowReportedBatteryLevel)
            reconfigureService(forceRileyLinkAddressRenewal)
            true
        } catch (ex: Exception) {
            medtronicPumpStatus.errorDescription = ex.message
            aapsLogger.error(LTag.PUMP, "Error on Verification: " + ex.message, ex)
            false
        }
    }

    private fun reconfigureService(forceRileyLinkAddressRenewal: Boolean): Boolean {
        if (!inPreInit) {
            if (serialChanged) {
                setPumpIDString(medtronicPumpStatus.serialNumber) // short operation
                serialChanged = false
            }
            if (rileyLinkAddressChanged || forceRileyLinkAddressRenewal) {
                rileyLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.RileyLinkNewAddressSet)
                rileyLinkAddressChanged = false
            }
            if (encodingChanged) {
                changeRileyLinkEncoding(encodingType!!)
                encodingChanged = false
            }
        }

        // if (targetFrequencyChanged && !inPreInit && MedtronicUtil.getMedtronicService() != null) {
        // RileyLinkUtil.setRileyLinkTargetFrequency(targetFrequency);
        // // RileyLinkUtil.getRileyLinkCommunicationManager().refreshRileyLinkTargetFrequency();
        // targetFrequencyChanged = false;
        // }
        return !rileyLinkAddressChanged && !serialChanged && !encodingChanged // && !targetFrequencyChanged);
    }

    fun setNotInPreInit(): Boolean {
        inPreInit = false
        return reconfigureService(false)
    }
}