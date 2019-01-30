/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2019 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2019 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.net

import com.github.shadowsocks.utils.parseNumericAddress
import net.sourceforge.jsocks.Socks4Message
import net.sourceforge.jsocks.Socks5Message
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.nio.ByteBuffer
import kotlin.math.max

class Socks5Endpoint(host: String, port: Int) {
    private val dest = host.parseNumericAddress().let { numeric ->
        val bytes = numeric?.address ?: host.toByteArray().apply { check(size < 256) { "Hostname too long" } }
        val type = when (numeric) {
            null -> Socks5Message.SOCKS_ATYP_DOMAINNAME
            is Inet4Address -> Socks5Message.SOCKS_ATYP_IPV4
            is Inet6Address -> Socks5Message.SOCKS_ATYP_IPV6
            else -> throw IllegalStateException("Unsupported address type")
        }
        ByteBuffer.allocate(bytes.size + (if (numeric == null) 1 else 0) + 3).apply {
            put(type.toByte())
            if (numeric == null) put(bytes.size.toByte())
            put(bytes)
            putShort(port.toShort())
        }
    }.array()
    private val headerReserved = max(3 + 3 + 16, 3 + dest.size)

    fun tcpWrap(message: ByteBuffer): ByteBuffer {
        check(message.remaining() < 65536) { "TCP message too large" }
        return ByteBuffer.allocate(8 + dest.size + message.remaining()).apply {
            put(Socks5Message.SOCKS_VERSION.toByte())
            put(1)  // nmethods
            put(0)  // no authentication required
            // header
            put(Socks5Message.SOCKS_VERSION.toByte())
            put(Socks4Message.REQUEST_CONNECT.toByte())
            put(0)  // reserved
            put(dest)
            // data
            putShort(message.remaining().toShort())
            put(message)
            flip()
        }
    }
    fun tcpReceiveBuffer(size: Int) = ByteBuffer.allocate(headerReserved + 4 + size)
    suspend fun tcpUnwrap(size: Int, reader: (ByteBuffer) -> Int, wait: suspend () -> Unit): ByteBuffer {
        suspend fun ByteBuffer.readBytes(till: Int) {
            if (position() >= till) return
            while (reader(this) >= 0 && position() < till) wait()
            if (position() < till) throw IOException("EOF")
        }
        suspend fun ByteBuffer.read(index: Int): Byte {
            readBytes(index + 1)
            return get(index)
        }
        val buffer = tcpReceiveBuffer(size)
        check(buffer.read(0) == Socks5Message.SOCKS_VERSION.toByte()) { "Unsupported SOCKS version" }
        if (buffer.read(1) != 0.toByte()) throw IOException("Unsupported authentication ${buffer[1]}")
        check(buffer.read(2) == Socks5Message.SOCKS_VERSION.toByte()) { "Unsupported SOCKS version" }
        if (buffer.read(3) != 0.toByte()) throw IOException("SOCKS5 server returned error ${buffer[3]}")
        val dataOffset = when (buffer.read(5)) {
            Socks5Message.SOCKS_ATYP_IPV4.toByte() -> 4
            Socks5Message.SOCKS_ATYP_DOMAINNAME.toByte() -> {
                buffer.readBytes(4)
                1 + buffer[3]
            }
            Socks5Message.SOCKS_ATYP_IPV6.toByte() -> 16
            else -> throw IllegalStateException("Unsupported address type ${buffer[5]}")
        } + 8
        buffer.readBytes(dataOffset + 2)
        buffer.limit(buffer.position()) // store old position to update mark
        buffer.position(dataOffset)
        val dataLength = buffer.short.toUShort().toInt()
        check(dataLength <= size) { "Buffer too small to contain the message" }
        buffer.mark()
        val end = buffer.position() + dataLength
        buffer.position(buffer.limit()) // restore old position
        buffer.limit(end)
        buffer.readBytes(buffer.limit())
        buffer.reset()
        return buffer
    }

    fun udpWrap(packet: ByteBuffer) = ByteBuffer.allocate(3 + dest.size + packet.remaining()).apply {
        // header
        putShort(0) // reserved
        put(0)      // fragment number
        put(dest)
        // data
        put(packet)
        flip()
    }
    fun udpReceiveBuffer(size: Int) = ByteBuffer.allocate(headerReserved + size)
    fun udpUnwrap(packet: ByteBuffer) {
        packet.position(3)
        packet.position(6 + when (packet.get()) {
            Socks5Message.SOCKS_ATYP_IPV4.toByte() -> 4
            Socks5Message.SOCKS_ATYP_DOMAINNAME.toByte() -> 1 + packet.get()
            Socks5Message.SOCKS_ATYP_IPV6.toByte() -> 16
            else -> throw IllegalStateException("Unsupported address type")
        })
        packet.mark()
    }
}
