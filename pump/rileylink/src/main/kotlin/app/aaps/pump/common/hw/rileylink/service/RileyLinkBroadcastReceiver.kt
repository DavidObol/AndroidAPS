package app.aaps.pump.common.hw.rileylink.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.RileyLinkConst
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.ble.data.GattAttributes
import app.aaps.pump.common.hw.rileylink.data.RLHistoryItemRileyLinkSelection
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkError
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkPumpDevice
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkServiceState
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringPreferenceKey
import app.aaps.pump.common.hw.rileylink.service.tasks.DiscoverGattServicesTask
import app.aaps.pump.common.hw.rileylink.service.tasks.InitializePumpManagerTask
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTask
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTaskExecutor
import app.aaps.pump.common.hw.rileylink.service.tasks.WakeAndTuneTask
import dagger.android.DaggerBroadcastReceiver
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider

class RileyLinkBroadcastReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rileyLinkUtil: RileyLinkUtil
    @Inject lateinit var rileyLinkServiceData: RileyLinkServiceData
    @Inject lateinit var serviceTaskExecutor: ServiceTaskExecutor
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var wakeAndTuneTaskProvider: Provider<WakeAndTuneTask>
    @Inject lateinit var initializePumpManagerTaskProvider: Provider<InitializePumpManagerTask>
    @Inject lateinit var discoverGattServicesTaskProvider: Provider<DiscoverGattServicesTask>
    @Inject lateinit var interactionLogger: RileyLinkInteractionLogger

    private val broadcastIdentifiers: MutableMap<String, List<String>> = HashMap()

    companion object {
        private val selectionInProgress = AtomicBoolean(false)
    }

    init {
        createBroadcastIdentifiers()
    }

    private val rileyLinkService: RileyLinkService?
        get() = (activePlugin.activePump as RileyLinkPumpDevice).rileyLinkService

    private fun createBroadcastIdentifiers() {

        // Bluetooth
        broadcastIdentifiers["Bluetooth"] = listOf(
            RileyLinkConst.Intents.BluetoothConnected,
            RileyLinkConst.Intents.BluetoothReconnected
        )

        // TuneUp
        broadcastIdentifiers["TuneUp"] = listOf(
            RileyLinkConst.IPC.MSG_PUMP_tunePump,
            RileyLinkConst.IPC.MSG_PUMP_quickTune
        )

        // RileyLink
        broadcastIdentifiers["RileyLink"] = listOf(
            RileyLinkConst.Intents.RileyLinkDisconnected,
            RileyLinkConst.Intents.RileyLinkReady,
            RileyLinkConst.Intents.RileyLinkDisconnected,
            RileyLinkConst.Intents.RileyLinkNewAddressSet,
            RileyLinkConst.Intents.RileyLinkDisconnect
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        Thread {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Received Broadcast: $action")
            if (!processBluetoothBroadcasts(action) && !processRileyLinkBroadcasts(action, context) && !processTuneUpBroadcasts(action))
                aapsLogger.error(LTag.PUMPBTCOMM, "Unhandled broadcast: action=$action")
        }.start()
    }

    fun registerBroadcasts(context: Context) {
        val intentFilter = IntentFilter()
        for ((_, value) in broadcastIdentifiers)
            for (intentKey in value)
                intentFilter.addAction(intentKey)
        LocalBroadcastManager.getInstance(context).registerReceiver(this, intentFilter)
    }

    fun unregisterBroadcasts(context: Context) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(this)
    }

    private fun processRileyLinkBroadcasts(action: String, context: Context): Boolean =
        when (action) {
            RileyLinkConst.Intents.RileyLinkDisconnected  -> {
                if ((context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.isEnabled)
                    rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.RileyLinkUnreachable)
                else
                    rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.BluetoothDisabled)
                true
            }

            RileyLinkConst.Intents.RileyLinkReady         -> {
                aapsLogger.warn(LTag.PUMPBTCOMM, "RileyLinkConst.Intents.RileyLinkReady")
                // sendIPCNotification(RT2Const.IPC.MSG_note_WakingPump);
                rileyLinkService?.rileyLinkBLE?.enableNotifications()
                rileyLinkService?.rfSpy?.startReader() // call startReader from outside?
                rileyLinkService?.rfSpy?.initializeRileyLink()
                val bleVersion = rileyLinkService?.rfSpy?.getBLEVersionCached()
                val rlVersion = rileyLinkServiceData.firmwareVersion
                aapsLogger.debug(LTag.PUMPBTCOMM, "RfSpy version (BLE113): $bleVersion")
                rileyLinkService?.rileyLinkServiceData?.versionBLE113 = bleVersion

                aapsLogger.debug(LTag.PUMPBTCOMM, "RfSpy Radio version (CC110): ${rlVersion?.name}")
                rileyLinkServiceData.firmwareVersion = rlVersion
                val task: ServiceTask = initializePumpManagerTaskProvider.get()
                serviceTaskExecutor.startTask(task)
                aapsLogger.info(LTag.PUMPBTCOMM, "Announcing RileyLink open For business")
                true
            }

            RileyLinkConst.Intents.RileyLinkNewAddressSet -> {
                interactionLogger.log("BROADCAST_RECEIVED", "RileyLinkNewAddressSet")
                selectBestRileyLinkAndConnect(context)
                true
            }

            RileyLinkConst.Intents.RileyLinkDisconnect    -> {
                interactionLogger.log("DISCONNECT_REQUESTED", "by user/broadcast")
                rileyLinkService?.disconnectRileyLink()
                true
            }

            else                                          -> false
        }

    private fun selectBestRileyLinkAndConnect(context: Context) {
        if (!selectionInProgress.compareAndSet(false, true)) {
            interactionLogger.log("SELECT_SKIP", "selection already in progress, skipping to avoid freeze")
            aapsLogger.debug(LTag.PUMPBTCOMM, "RileyLink selection already in progress, skip")
            return
        }
        try {
            selectBestRileyLinkAndConnectInternal(context)
        } finally {
            selectionInProgress.set(false)
            interactionLogger.log("SELECT_END", "selection phase finished")
        }
    }

    private fun selectBestRileyLinkAndConnectInternal(context: Context) {
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

        if (knownDevices.isEmpty()) {
            val rileylinkBLEAddress = preferences.get(RileyLinkStringPreferenceKey.MacAddress)
            if (rileylinkBLEAddress == "") {
                interactionLogger.log("SINGLE_RL_ERROR", "No RileyLink BLE Address saved")
                aapsLogger.error("No Rileylink BLE Address saved in app")
                return
            }
            interactionLogger.log("SINGLE_RL_USE", "address=$rileylinkBLEAddress")
            aapsLogger.debug(LTag.PUMPBTCOMM, "Using single configured RileyLink address: $rileylinkBLEAddress")
            rileyLinkService?.reconfigureRileyLink(rileylinkBLEAddress)
            return
        }

        interactionLogger.log("MULTI_RL_START", "knownDevices=${knownDevices.keys.toList()}")

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            interactionLogger.log("MULTI_RL_ERROR", "Bluetooth adapter not available or disabled")
            aapsLogger.error(LTag.PUMPBTCOMM, "Bluetooth adapter not available or disabled, cannot auto-select RileyLink")
            return
        }

        val hasScanPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasScanPermission) {
            interactionLogger.log("MULTI_RL_ERROR", "BLUETOOTH_SCAN permission not granted")
            aapsLogger.error(LTag.PUMPBTCOMM, "BLUETOOTH_SCAN permission not granted, cannot auto-select RileyLink")
            return
        }

        val scanner: BluetoothLeScanner? = adapter.bluetoothLeScanner
        if (scanner == null) {
            interactionLogger.log("MULTI_RL_ERROR", "BluetoothLeScanner is null")
            aapsLogger.error(LTag.PUMPBTCOMM, "BluetoothLeScanner is null, cannot auto-select RileyLink")
            return
        }

        val rssiByAddress: MutableMap<String, Int> = HashMap()
        val scanDurationMs = 3_000L
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(
                android.os.ParcelUuid.fromString(GattAttributes.SERVICE_RADIO)
            ).build()
        )

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleResult(result, knownDevices, rssiByAddress)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) {
                    handleResult(result, knownDevices, rssiByAddress)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                interactionLogger.log("SCAN_FAILED", "errorCode=$errorCode")
                aapsLogger.error(LTag.PUMPBTCOMM, "Auto-select RileyLink scan failed, errorCode=$errorCode")
            }

            private fun handleResult(
                result: ScanResult,
                knownDevices: Map<String, String>,
                rssiByAddress: MutableMap<String, Int>
            ) {
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
            scanner.startScan(filters, settings, callback)
            interactionLogger.log("SCAN_START", "durationMs=$scanDurationMs")
            aapsLogger.debug(LTag.PUMPBTCOMM, "Started auto-select RileyLink scan")
            Thread.sleep(scanDurationMs)
        } catch (e: Exception) {
            interactionLogger.log("SCAN_EXCEPTION", e.message ?: e.toString())
            aapsLogger.error(LTag.PUMPBTCOMM, "Exception during auto-select RileyLink scan", e)
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
            interactionLogger.log("SCAN_STOP", "rssiByAddress=$rssiByAddress")
        }

        val selectedAddress: String?
        val selectedRssi: Int?
        if (rssiByAddress.isNotEmpty()) {
            val best = rssiByAddress.entries.maxByOrNull { it.value }!!
            selectedAddress = best.key
            selectedRssi = best.value
        } else {
            val current = preferences.get(RileyLinkStringPreferenceKey.MacAddress)
            selectedAddress = if (!current.isNullOrBlank() && knownDevices.containsKey(current)) {
                current
            } else {
                knownDevices.keys.firstOrNull()
            }
            selectedRssi = null
        }

        if (selectedAddress == null) {
            interactionLogger.log("SELECT_ERROR", "No RileyLink address could be selected")
            aapsLogger.error(LTag.PUMPBTCOMM, "No RileyLink address could be selected")
            return
        }

        val selectedName = knownDevices[selectedAddress] ?: ""
        val currentAddress = preferences.get(RileyLinkStringPreferenceKey.MacAddress)
        if (selectedAddress == currentAddress) {
            interactionLogger.log("RECONFIGURE_SAME", "address=$selectedAddress (no change)")
            rileyLinkService?.reconfigureRileyLink(selectedAddress)
            return
        }

        preferences.put(RileyLinkStringPreferenceKey.MacAddress, selectedAddress)
        if (selectedName.isNotBlank()) {
            preferences.put(app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey.Name, selectedName)
        }

        val targetDevice: RileyLinkTargetDevice = rileyLinkServiceData.targetDevice
        val candidateCount = knownDevices.size
        val msg = if (selectedRssi != null) {
            "Selected RileyLink $selectedName ($selectedAddress), RSSI=$selectedRssi, among $candidateCount device(s)"
        } else {
            "Selected RileyLink $selectedName ($selectedAddress) among $candidateCount configured device(s)"
        }
        rileyLinkUtil.rileyLinkHistory.add(RLHistoryItemRileyLinkSelection(msg, targetDevice))
        aapsLogger.info(LTag.PUMPBTCOMM, msg)
        interactionLogger.log("SELECT_RESULT", "address=$selectedAddress name=$selectedName rssi=$selectedRssi candidates=$candidateCount")

        interactionLogger.log("RECONFIGURE_START", "disconnect current, connect to $selectedAddress")
        rileyLinkService?.reconfigureRileyLink(selectedAddress)
        interactionLogger.log("RECONFIGURE_CALLED", "reconfigureRileyLink($selectedAddress) returned")
    }

    private fun processBluetoothBroadcasts(action: String): Boolean =
        when (action) {
            RileyLinkConst.Intents.BluetoothConnected   -> {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Bluetooth - Connected")
                serviceTaskExecutor.startTask(discoverGattServicesTaskProvider.get())
                true
            }

            RileyLinkConst.Intents.BluetoothReconnected -> {
                aapsLogger.debug(LTag.PUMPBTCOMM, "Bluetooth - Reconnecting")
                rileyLinkService?.bluetoothInit()
                serviceTaskExecutor.startTask(discoverGattServicesTaskProvider.get().with(true))
                true
            }

            else                                        -> false
        }

    private fun processTuneUpBroadcasts(action: String): Boolean =
        if (broadcastIdentifiers["TuneUp"]?.contains(action) == true) {
            if (rileyLinkServiceData.targetDevice.tuneUpEnabled) serviceTaskExecutor.startTask(wakeAndTuneTaskProvider.get())
            true
        } else false
}