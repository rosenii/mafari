/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.net

import dev.dsh.miuixbrowser.i18n.tr

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本机 SOCKS5 中转（仅用于「上游 SOCKS5 需要用户名 / 密码」的场景）。
 *
 * Chromium / WebView 的 SOCKS5 不支持认证，直接连需要认证的代理会整体失败（表现为开启后所有网页打不开）。
 * 这里在 127.0.0.1 的随机端口起一个免认证的 SOCKS5 入口给 WebView 用，
 * 再用 RFC 1929 用户名 / 密码向上游认证，并把 CONNECT 请求（含域名，由代理端解析）原样转发。
 * 只支持 CONNECT；UDP ASSOCIATE / BIND 回复「不支持的命令」。
 */
internal class Socks5Relay(
    private val upHost: String,
    private val upPort: Int,
    private val user: String,
    private val pass: String,
) {
    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "mafari-socks5").apply { isDaemon = true } }

    @Volatile private var closed = false

    val port: Int get() = server.localPort
    val isAlive: Boolean get() = !closed && !server.isClosed

    fun start() {
        pool.execute { acceptLoop() }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (e: Exception) {
                closeQuietly(client)
                break
            }
        }
    }

    private fun handle(client: Socket) {
        var up: Socket? = null
        var replied = false
        var streaming = false
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_MS
            val cin = DataInputStream(client.getInputStream())
            val cout = client.getOutputStream()

            // 1) 问候：只接受「免认证」
            if (cin.readUnsignedByte() != 5) return
            val nMethods = cin.readUnsignedByte()
            cin.readFully(ByteArray(nMethods))
            cout.write(byteArrayOf(5, 0))
            cout.flush()

            // 2) 请求
            val head = ByteArray(4)
            cin.readFully(head)
            if (head[0].toInt() != 5) return
            val cmd = head[1].toInt()
            val addr = readAddr(cin, head[3].toInt()) ?: run {
                cout.write(FAIL_ATYP); cout.flush(); replied = true
                return
            }
            val dstPort = ByteArray(2)
            cin.readFully(dstPort)
            if (cmd != 1) {
                cout.write(FAIL_CMD); cout.flush(); replied = true
                return
            }

            // 3) 连上游并认证
            val s = Socket()
            up = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(upHost, upPort), CONNECT_MS)
            s.soTimeout = HANDSHAKE_MS
            val uin = DataInputStream(s.getInputStream())
            val uout = s.getOutputStream()

            uout.write(byteArrayOf(5, 2, 0, 2))
            uout.flush()
            if (uin.readUnsignedByte() != 5) throw IOException(tr("上游不是 SOCKS5"))
            when (uin.readUnsignedByte()) {
                0 -> {}
                2 -> {
                    val ub = user.toByteArray(Charsets.UTF_8)
                    val pb = pass.toByteArray(Charsets.UTF_8)
                    if (ub.size > 255 || pb.size > 255) throw IOException(tr("用户名或密码过长"))
                    uout.write(byteArrayOf(1, ub.size.toByte()))
                    uout.write(ub)
                    uout.write(byteArrayOf(pb.size.toByte()))
                    uout.write(pb)
                    uout.flush()
                    uin.readUnsignedByte()
                    if (uin.readUnsignedByte() != 0) throw IOException(tr("上游认证失败"))
                }
                else -> throw IOException(tr("上游不接受可用的认证方式"))
            }

            // 4) 转发 CONNECT，把上游的回复原样还给 WebView
            uout.write(head)
            uout.write(addr)
            uout.write(dstPort)
            uout.flush()

            val rh = ByteArray(4)
            uin.readFully(rh)
            val rAddr = readAddr(uin, rh[3].toInt()) ?: throw IOException(tr("上游回复异常"))
            val rPort = ByteArray(2)
            uin.readFully(rPort)
            cout.write(rh)
            cout.write(rAddr)
            cout.write(rPort)
            cout.flush()
            replied = true
            if (rh[1].toInt() != 0) return

            // 5) 双向转发
            client.soTimeout = 0
            s.soTimeout = 0
            streaming = true
            val left = AtomicInteger(2)
            val finish = {
                if (left.decrementAndGet() == 0) {
                    closeQuietly(client)
                    closeQuietly(s)
                }
            }
            try {
                pool.execute { pipe(s.getInputStream(), client, finish) }
            } catch (e: Exception) {
                closeQuietly(client)
                closeQuietly(s)
                return
            }
            pipe(client.getInputStream(), s, finish)
        } catch (e: Exception) {
            if (!replied) {
                try {
                    client.getOutputStream().write(FAIL_GENERAL)
                    client.getOutputStream().flush()
                } catch (e: Exception) {
                }
            }
        } finally {
            if (!streaming) {
                closeQuietly(client)
                closeQuietly(up)
            }
        }
    }

    /** 读地址部分（域名带长度字节），不含端口；类型未知返回 null */
    private fun readAddr(input: DataInputStream, atyp: Int): ByteArray? = when (atyp) {
        1 -> ByteArray(4).also { input.readFully(it) }
        4 -> ByteArray(16).also { input.readFully(it) }
        3 -> {
            val len = input.readUnsignedByte()
            ByteArray(len + 1).also {
                it[0] = len.toByte()
                input.readFully(it, 1, len)
            }
        }
        else -> null
    }

    private fun pipe(input: InputStream, dest: Socket, finish: () -> Unit) {
        try {
            val out = dest.getOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
            runCatching { dest.shutdownOutput() }
        } catch (e: Exception) {
            // 另一端已关闭 / 重置
        } finally {
            finish()
        }
    }

    private fun closeQuietly(s: Socket?) {
        try {
            s?.close()
        } catch (e: Exception) {
        }
    }

    private companion object {
        const val HANDSHAKE_MS = 15_000
        const val CONNECT_MS = 10_000
        val FAIL_GENERAL = byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)
        val FAIL_CMD = byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)
        val FAIL_ATYP = byteArrayOf(5, 8, 0, 1, 0, 0, 0, 0, 0, 0)
    }
}
