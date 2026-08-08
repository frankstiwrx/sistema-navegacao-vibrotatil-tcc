package com.example.firstorder

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.nio.charset.Charset
import java.util.UUID


class BleClient(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onReadyChanged: (Boolean) -> Unit = {}
) {

    // UUIDs do ESP32 (tem que bater 100%)
    private val serviceUuid = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
    private val charUuid = UUID.fromString("abcdefab-1234-1234-1234-abcdefabcdef")

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private var scanning = false

    var isConnected: Boolean = false
        private set

    var isReady: Boolean = false
        private set(value) {
            field = value
            onReadyChanged(value)
        }

    // Guarda o último comando caso chegue antes de ficar pronto
    private var pendingPayload: String? = null

    @SuppressLint("MissingPermission")
    fun startScan(targetName: String = "ESP32_DIRECAO") {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            onLog("Bluetooth desligado ou indisponível.")
            return
        }

        if (scanning) return
        scanning = true
        isConnected = false
        isReady = false
        pendingPayload = null

        onLog("Scan BLE iniciado... procurando: $targetName")

        val filters = listOf(
            ScanFilter.Builder().setDeviceName(targetName).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        scanner?.stopScan(scanCallback)
        onLog("Scan BLE parado.")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        onLog("Desconectando...")
        isReady = false
        isConnected = false
        pendingPayload = null

        try {
            gatt?.disconnect()
        } catch (_: Throwable) {}
        try {
            gatt?.close()
        } catch (_: Throwable) {}

        gatt = null
        writeChar = null
    }

    @SuppressLint("MissingPermission")
    fun sendCommand(dir: Int, freq: Float) {
        val payload = "DIR=$dir;FREQ=$freq"
        val gg = gatt
        val ch = writeChar

        if (!isReady || gg == null || ch == null) {
            pendingPayload = payload
            onLog("BLE não pronto ainda (aguarde 'Pronto ✅'). Comando guardado: $payload")
            return
        }

        write(payload)
    }

    @SuppressLint("MissingPermission")
    private fun write(payload: String) {
        val gg = gatt ?: run {
            onLog("ERRO: gatt null ao escrever.")
            return
        }
        val ch = writeChar ?: run {
            onLog("ERRO: writeChar null ao escrever.")
            return
        }

        val bytes = payload.toByteArray(Charset.forName("UTF-8"))
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = bytes

        val ok = gg.writeCharacteristic(ch)
        onLog("Enviando: $payload  (writeCharacteristic=$ok)")
    }

    // ---------- Scan Callback ----------
    private val scanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            onLog("Encontrado: ${device.name}  ${device.address}")
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onLog("Scan falhou: $errorCode")
        }
    }

    // ---------- Connect ----------
    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        onLog("Conectando em ${device.name}...")

        // Fecha gatt antigo se existir
        try { gatt?.close() } catch (_: Throwable) {}
        gatt = null
        writeChar = null
        isReady = false
        isConnected = false

        gatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
    }

    // ---------- GATT Callback ----------
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            onLog("onConnectionStateChange: status=$status newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("Falha conexão: status=$status (fechando)")
                isReady = false
                isConnected = false
                try { g.close() } catch (_: Throwable) {}
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true
                isReady = false
                onLog("Conectado! Descobrindo serviços...")
                val ok = g.discoverServices()
                onLog("discoverServices() = $ok")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onLog("Desconectado.")
                isReady = false
                isConnected = false
                writeChar = null
                try { g.close() } catch (_: Throwable) {}
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            onLog("onServicesDiscovered: status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                onLog("Services discover falhou: $status")
                return
            }

            val service = g.getService(serviceUuid)
            if (service == null) {
                onLog("Serviço NÃO encontrado: $serviceUuid")
                // Loga tudo que veio (pra diagnosticar UUID diferente)
                g.services.forEach { s -> onLog("Service encontrado: ${s.uuid}") }
                return
            }

            val ch = service.getCharacteristic(charUuid)
            if (ch == null) {
                onLog("Characteristic NÃO encontrada: $charUuid")
                service.characteristics.forEach { c ->
                    onLog("Char no service: ${c.uuid} props=${c.properties}")
                }
                return
            }

            writeChar = ch
            isReady = true
            onLog("Pronto! Characteristic WRITE encontrada ✅")

            // Se tinha comando pendente, manda agora
            pendingPayload?.let {
                pendingPayload = null
                onLog("Enviando comando pendente agora ✅")
                write(it)
            }
        }
    }
}
