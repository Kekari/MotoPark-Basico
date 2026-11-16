package cl.motopark

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context



import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

data class PairedDevice(val name: String, val address: String, val remembered: Boolean = false)

/** Sin fallback a sockets inseguros, reflexión, PIN fijo ni búsqueda por nombre. */
@SuppressLint("MissingPermission") // El Activity pide CONNECT antes de listar o conectar.
class BluetoothTransport(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var socket: BluetoothSocket? = null
    private val writeLock = Any()
    private val socketLock = Any()

    fun pairedDevices(): List<PairedDevice> {
        val bluetooth = adapter ?: throw IOException("Este dispositivo no tiene Bluetooth")
        check(bluetooth.isEnabled) { "Activa Bluetooth y empareja el HC-05 en los ajustes del teléfono" }
        return bluetooth.bondedDevices.map { PairedDevice(it.name ?: "Dispositivo Bluetooth", it.address) }
            .sortedWith(compareBy<PairedDevice> { !it.name.contains("HC-05", ignoreCase = true) }.thenBy { it.name })
    }

    suspend fun run(address: String, onConnected: suspend () -> Unit, onFrame: suspend (Frame) -> Unit) =
        withContext(Dispatchers.IO) {
            val bluetooth = adapter ?: throw IOException("Bluetooth no disponible")
            check(bluetooth.isEnabled) { "Bluetooth está desactivado" }
            val device = bluetooth.bondedDevices.firstOrNull { it.address == address }
                ?: throw IOException("Empareja primero el dispositivo desde los ajustes de Android")
            check(device.bondState == BluetoothDevice.BOND_BONDED)
            val local = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            try {
                val jobContext = coroutineContext
                synchronized(socketLock) {
                    // Un disconnect puede cancelar la tarea antes de registrar el socket.
                    jobContext.ensureActive()
                    socket = local
                }
                coroutineScope {
                    val connected = AtomicBoolean(false)
                    val timeout = launch(Dispatchers.Default) {
                        delay(12_000)
                        if (!connected.get()) runCatching { local.close() }
                    }
                    try {
                        local.connect()
                        connected.set(true)
                    } finally { timeout.cancel() }
                }
                coroutineContext.ensureActive()
                // Arranque seguro; la interfaz usará únicamente el estado que confirme el firmware.
                write(local, "M:0\n")
                write(local, "B:0\n")
                write(local, "Q\n")
                withContext(Dispatchers.Main.immediate) { onConnected() }
                val decoder = LineDecoder()
                val bytes = ByteArray(256)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = local.inputStream.read(bytes)
                    if (count < 0) throw IOException("El dispositivo cerró la conexión")
                    for (line in decoder.feed(bytes.copyOf(count))) {
                        Parking.parse(line)?.let { frame ->
                            withContext(Dispatchers.Main.immediate) { onFrame(frame) }
                        }
                    }
                }
            } finally {
                runCatching { local.close() }
                synchronized(socketLock) { if (socket === local) socket = null }
            }
        }

    suspend fun send(command: String) {
        // Capturar antes de cambiar de dispatcher evita enviar un comando viejo a otra sesión.
        val current = socket ?: throw IOException("No hay conexión Bluetooth")
        withContext(Dispatchers.IO) { write(current, command) }
    }

    private fun write(current: BluetoothSocket, command: String) {
        require(command in setOf("Q\n", "M:0\n", "M:1\n", "B:0\n", "B:1\n"))
        synchronized(writeLock) {
            if (socket !== current) throw IOException("La sesión Bluetooth ya cambió")
            current.outputStream.write(command.toByteArray(Charsets.US_ASCII))
            current.outputStream.flush()
        }
    }

    /** Cierra el socket para desbloquear también un connect/read pendiente. */
    fun close() {
        val current = synchronized(socketLock) { socket.also { socket = null } }
        runCatching { current?.close() }
    }
}



sealed interface Frame {
    data class Distance(val cm: Int?) : Frame
    data class Status(val monitoring: Boolean, val buzzer: Boolean) : Frame
}

enum class Proximity { DANGER, CAUTION, CLEAR, UNKNOWN }

object Parking {
    fun parse(line: String): Frame? {
        if (line.length > 48) return null
        if (line == "D:X") return Frame.Distance(null)
        if (line.startsWith("D:")) {
            val value = line.substring(2)
            if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
            val cm = value.toIntOrNull() ?: return null
            return if (cm in 2..400) Frame.Distance(cm) else null
        }
        if (line.length == 5 && line.startsWith("S:") && line[3] == ':' &&
            line[2] in '0'..'1' && line[4] in '0'..'1') {
            return Frame.Status(line[2] == '1', line[4] == '1')
        }
        return null
    }

    fun proximity(cm: Int?): Proximity = when {
        cm == null || cm !in 2..400 -> Proximity.UNKNOWN
        cm <= 15 -> Proximity.DANGER
        cm <= 40 -> Proximity.CAUTION
        else -> Proximity.CLEAR
    }

    fun freshDistance(cm: Int?, receivedAt: Long?, now: Long): Int? =
        cm?.takeIf { receivedAt != null && now >= receivedAt && now - receivedAt < 2000 }

    fun beepInterval(cm: Int?): Long? = when (proximity(cm)) {
        Proximity.DANGER -> 180L
        Proximity.CAUTION -> 350L + ((cm!! - 16) * 20L)
        else -> null
    }
}

class LineDecoder {
    private val buffer = StringBuilder()
    private var dropping = false

    fun feed(bytes: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            when {
                value == 10 -> {
                    if (!dropping && buffer.isNotEmpty()) lines += buffer.toString().removeSuffix("\r")
                    buffer.setLength(0)
                    dropping = false
                }
                dropping -> Unit
                value !in 32..126 && value != 13 -> {
                    dropping = true
                    buffer.setLength(0)
                }
                buffer.length >= 48 -> {
                    dropping = true
                    buffer.setLength(0)
                }
                else -> buffer.append(value.toChar())
            }
        }
        return lines
    }
}

