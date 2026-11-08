package work.stdpi.smolkey

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

internal class MidiOutput(
    context: Context,
    private val status: (String) -> Unit,
    private val receive: (MidiReadback) -> Unit,
) : MidiPort {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val midi = app.getSystemService(MidiManager::class.java)
    private val bt = app.getSystemService(BluetoothManager::class.java)
    private var device: MidiDevice? = null
    private var port: MidiInputPort? = null
    private var receivePort: MidiOutputPort? = null
    private var receiveDecoder: MidiReadbackDecoder? = null
    private var usbListener: MidiManager.DeviceCallback? = null
    private var openingUsbId: Int? = null
    private var usbOpenGeneration = 0
    private var server: BluetoothGattServer? = null
    private var gattCallback: BluetoothGattServerCallback? = null
    private var bluetoothConnected: BluetoothDevice? = null
    private var subscribed = false
    private val pendingHitPulses = mutableMapOf<Int, Runnable>()
    private val usbConsumer = MidiUsbConsumer(::sendUsb)
    private val bluetoothConsumer = MidiBluetoothConsumer(::enqueueBle)
    private var activeConsumer: MidiMessageConsumer = MidiNullConsumer
    private val characteristic = BluetoothGattCharacteristic(
        MIDI_CHARACTERISTIC,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
    ).apply {
        addDescriptor(BluetoothGattDescriptor(
            CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        ))
    }

    override fun select(output: Output) {
        stop {
            when (output) {
                Output.OFF -> { activeConsumer = MidiNullConsumer; status(app.getString(R.string.midi_output_off)) }
                Output.USB -> { activeConsumer = usbConsumer; connectUsb() }
                Output.BLUETOOTH -> { activeConsumer = bluetoothConsumer; status(app.getString(R.string.bluetooth_start_hint)) }
            }
        }
    }

    private fun isPeripheral(info: MidiDeviceInfo) = info.type == MidiDeviceInfo.TYPE_USB &&
        info.inputPortCount > 0 && !info.properties.containsKey(MidiDeviceInfo.PROPERTY_USB_DEVICE)

    private fun connectUsb() {
        if (midi == null) { status(app.getString(R.string.midi_unavailable)); return }
        status(app.getString(R.string.usb_connect_hint))
        val listener = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                if (usbListener === this && !closing && isPeripheral(info)) open(info)
            }

            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                if (usbListener !== this || closing) return
                if (openingUsbId == info.id || device?.info?.id == info.id) {
                    closeUsb()
                    status(app.getString(R.string.usb_disconnected))
                }
            }
        }
        usbListener = listener
        midi.registerDeviceCallback(listener, main)
        midi.devices.firstOrNull(::isPeripheral)?.let(::open)
    }

    private fun open(info: MidiDeviceInfo) {
        if (device != null || openingUsbId != null) return
        openingUsbId = info.id
        val generation = ++usbOpenGeneration
        midi.openDevice(info, { opened ->
            if (generation != usbOpenGeneration || closing) {
                runCatching { opened?.close() }
                return@openDevice
            }
            openingUsbId = null
            if (opened == null) { status(app.getString(R.string.usb_open_failed)); return@openDevice }
            device = opened
            port = try { opened.openInputPort(0) } catch (_: Exception) { null }
            receivePort = if (info.outputPortCount > 0) runCatching { opened.openOutputPort(0) }.getOrNull() else null
            receivePort?.let { output ->
                val decoder = MidiReadbackDecoder(receive)
                receiveDecoder = decoder
                output.connect(object : MidiReceiver() {
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        val bytes = data.copyOfRange(offset, offset + count)
                        main.post { if (receiveDecoder === decoder) decoder.usb(bytes) }
                    }

                    override fun onFlush() {
                        main.post {
                            if (receiveDecoder === decoder) {
                                decoder.reset()
                                receive(MidiReadback.Disconnected)
                            }
                        }
                    }
                })
            }
            status(when {
                port == null && receivePort == null -> app.getString(R.string.midi_port_open_failed)
                port == null -> app.getString(R.string.usb_receive_only)
                receivePort == null -> app.getString(R.string.usb_send_only)
                else -> app.getString(R.string.usb_ready)
            })
        }, main)
    }

    private fun clearReceive() {
        if (receiveDecoder == null) return
        receiveDecoder = null
        receive(MidiReadback.Disconnected)
    }

    private fun closeUsb() {
        usbOpenGeneration++
        openingUsbId = null
        clearReceive()
        runCatching { receivePort?.close() }
        runCatching { port?.close() }
        runCatching { device?.close() }
        receivePort = null
        port = null
        device = null
    }

    override fun note(note: Int, down: Boolean, channel: Int, velocity: Int) = send(
        channelMessage(if (down) 0x90 else 0x80, channel, note, if (down) velocity else 0),
    )

    override fun consume(event: InstrumentEvent) {
        when (event) {
            is InstrumentEvent.Note -> note(event.note, event.down, event.channel, event.velocity)
            is InstrumentEvent.Hit -> {
                val key = event.channel * 128 + event.sound.note
                pendingHitPulses.remove(key)?.let(main::removeCallbacks)
                note(event.sound.note, true, event.channel, event.velocity)
                val release = Runnable {
                    pendingHitPulses.remove(key)
                    note(event.sound.note, false, event.channel, 0)
                }
                pendingHitPulses[key] = release
                main.postDelayed(release, 25)
            }
            is InstrumentEvent.Roll -> Unit
        }
    }

    override fun send(message: ByteArray) {
        activeConsumer.send(message)
    }

    private fun sendUsb(message: ByteArray) {
        try { port?.send(message, 0, message.size, System.nanoTime()) } catch (_: Exception) {
            status(app.getString(R.string.usb_send_failed))
        }
    }

    private val bleQueue = ArrayDeque<ByteArray>()
    private val bleMessages = mutableListOf<TimedMidiMessage>()
    private var bleSending = false
    private var bleFlushScheduled = false
    private var closing = false
    private var afterClose: () -> Unit = {}
    private val flushBle = Runnable {
        bleFlushScheduled = false
        bleQueue.addAll(bleMidiPackets(bleMessages))
        bleMessages.clear()
        sendNextBle()
    }

    private fun enqueueBle(message: ByteArray) {
        bleMessages += TimedMidiMessage(message.copyOf(), System.nanoTime() / 1_000_000L)
        if (!bleFlushScheduled) {
            bleFlushScheduled = true
            main.postDelayed(flushBle, 1)
        }
    }

    private fun sendNextBle() {
        val target = bluetoothConnected ?: return
        val gatt = server ?: return
        if (bleSending || !subscribed || bleQueue.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            status(app.getString(R.string.bluetooth_send_permission))
            stop()
            return
        }
        try {
        bleSending = true
        val result = if (Build.VERSION.SDK_INT >= 33) {
            gatt.notifyCharacteristicChanged(target, characteristic, false, bleQueue.first())
        } else {
            @Suppress("DEPRECATION")
            run { characteristic.value = bleQueue.first(); gatt.notifyCharacteristicChanged(target, characteristic, false) }
        }
        if (result != BluetoothGatt.GATT_SUCCESS && result != true) {
            bleSending = false; status(app.getString(R.string.bluetooth_send_failed))
        }
        } catch (_: SecurityException) {
            bleSending = false; status(app.getString(R.string.bluetooth_send_permission))
        }
    }

    private fun createGattCallback() = object : BluetoothGattServerCallback() {
        private fun onMain(block: () -> Unit) {
            main.post { if (gattCallback === this) block() }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, statusCode: Int, newState: Int) {
            onMain {
                if (closing) return@onMain
                if (statusCode == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (bluetoothConnected != device) {
                        if (bluetoothConnected != null) {
                            clearReceive()
                            bleQueue.clear()
                            bleSending = false
                        }
                        bluetoothConnected = device
                        receiveDecoder = MidiReadbackDecoder(receive)
                        subscribed = false
                        status(app.getString(R.string.bluetooth_waiting_for_host))
                    }
                } else if (bluetoothConnected == device) {
                    clearReceive()
                    bluetoothConnected = null
                    subscribed = false
                    bleSending = false
                    bleQueue.clear()
                    bleMessages.clear()
                    status(app.getString(R.string.bluetooth_disconnected))
                }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            onMain { respond(device, requestId, BluetoothGatt.GATT_SUCCESS, byteArrayOf()) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val packet = value.copyOf()
            onMain {
                val accepted = !closing && bluetoothConnected == device &&
                    characteristic.uuid == MIDI_CHARACTERISTIC && !preparedWrite && offset == 0
                if (accepted) receiveDecoder?.ble(packet)
                if (responseNeeded) respond(
                    device, requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    byteArrayOf(),
                )
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            onMain {
                if (bluetoothConnected != device) return@onMain
                subscribed = enable
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, value)
                status(if (enable) app.getString(R.string.bluetooth_ready) else app.getString(R.string.bluetooth_midi_paused))
                sendNextBle()
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, statusCode: Int) {
            onMain {
                if (bluetoothConnected != device) return@onMain
                if (bleQueue.isNotEmpty()) bleQueue.removeFirst()
                bleSending = false
                if (statusCode != BluetoothGatt.GATT_SUCCESS) status(app.getString(R.string.bluetooth_send_failed)) else sendNextBle()
            }
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, result: Int, value: ByteArray) {
        try {
            server?.sendResponse(device, requestId, result, 0, value)
        } catch (_: SecurityException) {
            status(app.getString(R.string.bluetooth_permission))
        }
    }

    override fun startBluetooth() {
        if (closing || server != null) return
        activeConsumer = bluetoothConsumer
        if (Build.VERSION.SDK_INT >= 31 && listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                .any { ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED }) {
            status(app.getString(R.string.bluetooth_start_permission)); return
        }
        val manager = bt ?: run { status(app.getString(R.string.bluetooth_unavailable)); return }
        val adapter = manager.adapter
        if (!adapter.isEnabled) { status(app.getString(R.string.bluetooth_enable_hint)); return }
        val advertiser = adapter.bluetoothLeAdvertiser ?: run { status(app.getString(R.string.bluetooth_advertising_unavailable)); return }
        val callback = createGattCallback()
        val gatt = manager.openGattServer(app, callback) ?: run { status(app.getString(R.string.bluetooth_start_failed)); return }
        gattCallback = callback
        server = gatt
        val service = BluetoothGattService(MIDI_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        if (!gatt.addService(service)) { status(app.getString(R.string.midi_service_add_failed)); stop(); return }
        val advertisingCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                main.post {
                    if (advertiseCallback === this && !closing && bluetoothConnected == null) status(app.getString(R.string.bluetooth_advertising))
                }
            }

            override fun onStartFailure(errorCode: Int) {
                main.post {
                    if (advertiseCallback !== this || closing) return@post
                    status(app.getString(R.string.bluetooth_advertising_failed, errorCode))
                    stop()
                }
            }
        }
        advertiseCallback = advertisingCallback
        advertiser.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).setTimeout(0).build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(MIDI_SERVICE)).setIncludeDeviceName(false).build(), advertisingCallback,
        )
    }

    private var advertiseCallback: AdvertiseCallback? = null

    override fun retry(output: Output) {
        if (output == Output.BLUETOOTH) stop { startBluetooth() } else select(output)
    }

    override fun stop() = stop { }

    private fun stop(onClosed: () -> Unit) {
        afterClose = onClosed
        if (closing) return
        closing = true
        clearReceive()
        releasePendingHitPulses()
        val deadline = android.os.SystemClock.uptimeMillis() + 800
        fun drain() {
            val pending = subscribed && (bleFlushScheduled || bleMessages.isNotEmpty() || bleSending || bleQueue.isNotEmpty())
            if (pending && android.os.SystemClock.uptimeMillis() < deadline) {
                main.postDelayed({ drain() }, 20)
                return
            }
            closeNow()
            closing = false
            val callback = afterClose
            afterClose = {}
            callback()
        }
        drain()
    }

    private fun releasePendingHitPulses() {
        pendingHitPulses.toMap().forEach { (key, callback) ->
            main.removeCallbacks(callback)
            pendingHitPulses.remove(key)
            if (port != null || subscribed) {
                val channel = key / 128
                val note = key % 128
                note(note, false, channel, 0)
            }
        }
    }

    private fun closeNow() {
        usbListener?.let { runCatching { midi?.unregisterDeviceCallback(it) } }
        usbListener = null
        gattCallback = null
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try { bluetoothConnected?.let { server?.cancelConnection(it) } } catch (_: Exception) { }
            try { server?.close() } catch (_: Exception) { }
            advertiseCallback?.let { runCatching { bt?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        } else {
            try { server?.close() } catch (_: Exception) { }
        }
        advertiseCallback = null
        subscribed = false; bluetoothConnected = null; server = null
        activeConsumer = MidiNullConsumer
        main.removeCallbacks(flushBle)
        bleQueue.clear(); bleMessages.clear(); bleSending = false; bleFlushScheduled = false
        closeUsb()
    }

    companion object {
        val MIDI_SERVICE = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
        private val MIDI_CHARACTERISTIC = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
