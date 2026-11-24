package com.androx.app.platform

import com.androx.app.core.net.NetException
import com.androx.app.core.net.NetworkProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/** DNS, TCP and Wake-on-LAN with plain JDK sockets. Always called through the gate, never directly. */
class AndroidNetworkProbe : NetworkProbe {
    override suspend fun dns(host: String): List<String> = withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
        } catch (e: UnknownHostException) {
            throw NetException("Couldn't find \"$host\".")
        }
    }

    override suspend fun tcp(host: String, port: Int, timeoutMs: Int): Long? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            System.currentTimeMillis() - started
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun wakeOnLan(mac: String, broadcast: String, port: Int) = withContext(Dispatchers.IO) {
        val bytes = mac.split(':', '-').map { it.toInt(16).toByte() }
        if (bytes.size != 6) throw NetException("A MAC address has six pairs of digits.")
        val packet = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), port))
            }
        } catch (e: Exception) {
            throw NetException("The wake-up packet couldn't be sent: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
