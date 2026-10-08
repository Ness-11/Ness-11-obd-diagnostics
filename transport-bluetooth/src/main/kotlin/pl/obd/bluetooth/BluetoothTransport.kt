package pl.obd.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.obd.core.ByteTransport

data class PairedAdapter(val name: String, val address: String)
@SuppressLint("MissingPermission")
fun pairedAdapters(adapter: BluetoothAdapter?): List<PairedAdapter> {
    requireNotNull(adapter) { "Telefon nie ma Bluetooth" }
    check(adapter.isEnabled) { "Włącz Bluetooth w ustawieniach telefonu" }
    return adapter.bondedDevices.map { PairedAdapter(it.name ?: "Urządzenie Bluetooth", it.address) }.sortedBy { it.name }
}

/** Classic RFCOMM/SPP, secure connection to a device already paired in Android Settings. */
@SuppressLint("MissingPermission")
class BluetoothTransport(private val adapter: BluetoothAdapter, private val address: String) : ByteTransport {
    private val lock = Any()
    private var socket: BluetoothSocket? = null
    private var closed = false
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "obd-spp").apply { isDaemon = true } }
    companion object { val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb") }
    override suspend fun open() = io {
        val s = synchronized(lock) {
            check(!closed)
            check(socket == null)
            check(adapter.isEnabled) { "Bluetooth jest wyłączony" }
            adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SPP).also { socket = it }
        }
        s.connect()
    }
    override suspend fun read(buffer: ByteArray): Int = io { connectedSocket().inputStream.read(buffer) }
    override suspend fun write(bytes: ByteArray) = io {
        connectedSocket().outputStream.run { write(bytes); flush() }
    }
    private fun connectedSocket(): BluetoothSocket = synchronized(lock) { socket?.takeUnless { closed } ?: throw IOException("SPP zamknięte") }
    private suspend fun <T> io(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { close() }
        try {
            workers.execute {
                try { val result = block(); if (continuation.isActive) continuation.resume(result) }
                catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    }
    override fun close() {
        val s = synchronized(lock) { closed = true; socket.also { socket = null } }
        try { s?.close() } catch (_: IOException) { } finally { workers.shutdownNow() }
    }
}
