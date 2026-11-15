package com.example.prog7314_poepart23.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.sync.SyncScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.utils.LocaleHelper

/**
 * Android BLE GATT manager for connecting to the ESP32-2432S028R nurse touchscreen (`PulseLink-XXXXXX`),
 * reading encrypted hardware status (`c3_uart`, `sd`, `wifi`, `api`, `staff_transfer`), revoking bonds,
 * and executing authorised background transfer of completed ECG recordings into SQLite.
 *
 * Live ECG waveforms and live BPM are never streamed over BLE to Android.
 */
@SuppressLint("MissingPermission")
class PulseLinkBleManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val operationMutex = Mutex()

    private val _connectionStage = MutableStateFlow(BleConnectionStage.DISCONNECTED)
    val connectionStage: StateFlow<BleConnectionStage> = _connectionStage.asStateFlow()

    private val _touchscreenStatus = MutableStateFlow<TouchscreenBleStatus?>(null)
    val touchscreenStatus: StateFlow<TouchscreenBleStatus?> = _touchscreenStatus.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var chunkChar: BluetoothGattCharacteristic? = null

    private var pendingRead: CompletableDeferred<String?>? = null
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var pendingReady: CompletableDeferred<Boolean>? = null

    fun findBondedTouchscreen(deviceNameOrId: String? = null): BluetoothDevice? {
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        return bonded.firstOrNull { device ->
            val name = runCatching { device.name }.getOrNull()
            PulseLinkBleContract.isValidTouchscreenName(name) &&
                (deviceNameOrId.isNullOrBlank() || name.equals(deviceNameOrId.trim(), ignoreCase = true))
        }
    }

    fun revokeBond(deviceNameOrId: String? = null): Boolean {
        disconnect()
        val target = findBondedTouchscreen(deviceNameOrId) ?: return false
        val removed = runCatching {
            val method = target.javaClass.getMethod("removeBond")
            method.invoke(target) as? Boolean ?: false
        }.getOrDefault(false)
        if (removed) {
            _connectionStage.value = BleConnectionStage.REVOKED
            _touchscreenStatus.value = null
        }
        return removed
    }

    fun disconnect() {
        runCatching {
            activeGatt?.disconnect()
            activeGatt?.close()
        }
        activeGatt = null
        statusChar = null
        controlChar = null
        chunkChar = null
        pendingRead?.complete(null)
        pendingWrite?.complete(false)
        pendingReady?.complete(false)
        if (_connectionStage.value != BleConnectionStage.REVOKED) {
            _connectionStage.value = BleConnectionStage.DISCONNECTED
        }
    }

    suspend fun connectAndSyncAuthorisedRecordings(
        device: BluetoothDevice,
        grantId: String,
        nurseUid: String
    ): BleTransferResult = withContext(Dispatchers.IO) {
        val name = runCatching { device.name }.getOrNull()
        if (!PulseLinkBleContract.isValidTouchscreenName(name)) {
            return@withContext BleTransferResult.Failure(
                BleConnectionStage.FAILED,
                LocaleHelper.stringOr(R.string.rt_ble_invalid_device, "Device is not a valid PulseLink touchscreen.")
            )
        }
        val readyDeferred = CompletableDeferred<Boolean>()
        pendingReady = readyDeferred
        _connectionStage.value = if (device.bondState == BluetoothDevice.BOND_BONDED) {
            BleConnectionStage.BONDED_RECONNECTING
        } else {
            BleConnectionStage.PAIRING
        }

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
        activeGatt = gatt

        val connected = withTimeoutOrNull(15_000L) { readyDeferred.await() } == true
        if (!connected) {
            disconnect()
            _connectionStage.value = BleConnectionStage.TIMEOUT
            return@withContext BleTransferResult.Failure(
                BleConnectionStage.TIMEOUT,
                LocaleHelper.stringOr(R.string.rt_ble_connect_timeout, "Timed out establishing encrypted BLE connection with touchscreen.")
            )
        }

        val transport = object : BleGattTransport {
            override suspend fun readStatus(): String? = operationMutex.withLock {
                val chr = statusChar ?: return@withLock null
                readCharacteristicValue(chr)
            }

            override suspend fun writeControlAndReadChunk(commandJson: String): String? = operationMutex.withLock {
                val ctrl = controlChar ?: return@withLock null
                val chnk = chunkChar ?: return@withLock null
                if (!writeCharacteristicValue(ctrl, commandJson)) return@withLock null
                readCharacteristicValue(chnk)
            }
        }

        transport.readStatus()?.let { raw ->
            _touchscreenStatus.value = TouchscreenBleStatus.parse(raw)
        }

        _connectionStage.value = BleConnectionStage.TRANSFERRING
        val coordinator = BleBackgroundTransferCoordinator(
            queueStore = OfflineEcgDatabase.getInstance(appContext)
        ) {
            SyncScheduler.triggerImmediateSync(appContext)
        }
        val result = coordinator.syncCompletedRecordingsOverBle(transport, grantId, nurseUid)
        when (result) {
            is BleTransferResult.Success -> _connectionStage.value = BleConnectionStage.SECURED_AUTHORISED
            is BleTransferResult.Failure -> _connectionStage.value = result.stage
        }
        result
    }

    private suspend fun readCharacteristicValue(characteristic: BluetoothGattCharacteristic): String? {
        val gatt = activeGatt ?: return null
        val deferred = CompletableDeferred<String?>()
        pendingRead = deferred
        if (!gatt.readCharacteristic(characteristic)) {
            pendingRead = null
            return null
        }
        return withTimeoutOrNull(8_000L) { deferred.await() }
    }

    private suspend fun writeCharacteristicValue(characteristic: BluetoothGattCharacteristic, value: String): Boolean {
        val gatt = activeGatt ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pendingWrite = deferred
        val bytes = value.toByteArray(Charsets.UTF_8)
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            pendingWrite = null
            return false
        }
        return withTimeoutOrNull(8_000L) { deferred.await() } == true
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                pendingReady?.complete(false)
                pendingRead?.complete(null)
                pendingWrite?.complete(false)
                _connectionStage.value = BleConnectionStage.DISCONNECTED
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(512)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                pendingReady?.complete(false)
                return
            }
            val service = gatt.getService(UUID.fromString(PulseLinkBleContract.SERVICE_UUID))
            statusChar = service?.getCharacteristic(UUID.fromString(PulseLinkBleContract.STATUS_CHAR_UUID))
            controlChar = service?.getCharacteristic(UUID.fromString(PulseLinkBleContract.CONTROL_CHAR_UUID))
            chunkChar = service?.getCharacteristic(UUID.fromString(PulseLinkBleContract.CHUNK_CHAR_UUID))
            val ready = statusChar != null && controlChar != null && chunkChar != null
            if (ready) _connectionStage.value = BleConnectionStage.SECURED_LOCKED
            pendingReady?.complete(ready)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            val text = if (status == BluetoothGatt.GATT_SUCCESS) {
                characteristic.value?.toString(Charsets.UTF_8)
            } else null
            pendingRead?.complete(text)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            val text = if (status == BluetoothGatt.GATT_SUCCESS) {
                value.toString(Charsets.UTF_8)
            } else null
            pendingRead?.complete(text)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }
}
