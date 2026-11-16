package dev.dsh.miuixbrowser.script

import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** 局域网里的一台 DLNA 渲染设备（电视 / 盒子 / 音箱） */
class DlnaDevice(
    val name: String,
    /** 设备描述文档地址，同时用来去重 */
    val location: String,
    val controlUrl: String,
    /** 设备声明的 AVTransport 服务类型，如 urn:schemas-upnp-org:service:AVTransport:1 */
    val serviceType: String,
)

/** 一次轮询的结果：时间单位秒；state 是 UPnP 的 CurrentTransportState */
class DlnaPos(val rel: Int, val dur: Int, val state: String)

/**
 * 最小可用的 DLNA 投屏客户端：SSDP 搜索 → 读设备描述 → AVTransport SOAP（SetAVTransportURI / Play / Pause / Seek / Stop / 查询进度）。
 *
 * 只依赖 JDK，网络接口由调用方注入：
 *  - [bind] 给 SSDP 的 UDP 套接字选网络（Android 上绑到 Wi-Fi，避免开着 VPN / 移动数据时组播发不出去）；
 *  - [open] 打开 HTTP 连接（Android 上经 Network.openConnection，同样绑 Wi-Fi，并且直连不走应用的代理）。
 *
 * 所有方法都是阻塞的，必须在后台线程调用。
 */
class DlnaCast(
    private val bind: (DatagramSocket) -> Unit = {},
    private val open: (URL) -> HttpURLConnection = { it.openConnection(Proxy.NO_PROXY) as HttpURLConnection },
    private val ssdpAddr: String = "239.255.255.250",
    private val ssdpPort: Int = 1900,
) {

    // ------------------------------------------------------------------ 发现

    /**
     * 搜索 [durationMs] 毫秒，每发现一台新设备回调一次 [onFound]（在调用线程里回调）。
     * [cancelled] 返回 true 时提前结束。
     */
    fun discover(durationMs: Long, cancelled: () -> Boolean, onFound: (DlnaDevice) -> Unit) {
        val socket = DatagramSocket()
        try {
            bind(socket)
            socket.soTimeout = 400
            val targets = listOf(
                "urn:schemas-upnp-org:service:AVTransport:1",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
            )
            val group = InetAddress.getByName(ssdpAddr)
            fun sendAll() {
                for (st in targets) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: $ssdpAddr:$ssdpPort\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val b = msg.toByteArray(Charsets.US_ASCII)
                    runCatching { socket.send(DatagramPacket(b, b.size, group, ssdpPort)) }
                }
            }
            val seen = HashSet<String>()
            val start = System.currentTimeMillis()
            var nextSend = 0L
            val buf = ByteArray(4096)
            while (!cancelled() && System.currentTimeMillis() - start < durationMs) {
                val elapsed = System.currentTimeMillis() - start
                if (elapsed >= nextSend) { sendAll(); nextSend = if (nextSend == 0L) 1000L else nextSend + 1500L }
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                val loc = header(text, "LOCATION") ?: continue
                if (!loc.startsWith("http://", true) || !seen.add(loc)) continue
                val dev = runCatching { describe(loc) }.getOrNull() ?: continue
                onFound(dev)
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun header(resp: String, name: String): String? {
        for (line in resp.split("\r\n", "\n")) {
            val i = line.indexOf(':')
            if (i > 0 && line.substring(0, i).trim().equals(name, ignoreCase = true)) return line.substring(i + 1).trim()
        }
        return null
    }

    /** 读设备描述文档，找出 AVTransport 服务；不是渲染设备（没有 AVTransport）返回 null */
    fun describe(location: String): DlnaDevice? {
        val xml = httpGet(location)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = doc.documentElement
        val name = childText(firstTag(root, "device"), "friendlyName")?.takeIf { it.isNotBlank() } ?: return null
        val base = firstTag(root, "URLBase")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val sv = services.item(i) as? Element ?: continue
            val type = childText(sv, "serviceType") ?: continue
            if (!type.contains(":AVTransport:")) continue
            val ctl = childText(sv, "controlURL") ?: continue
            return DlnaDevice(name, location, URL(URL(base), ctl).toString(), type)
        }
        return null
    }

    private fun firstTag(e: Element?, tag: String): Element? {
        val l = e?.getElementsByTagName(tag) ?: return null
        return if (l.length > 0) l.item(0) as? Element else null
    }

    private fun childText(e: Element?, tag: String): String? {
        var n = e?.firstChild
        while (n != null) {
            if (n is Element && (n.tagName == tag || n.localName == tag)) return n.textContent?.trim()
            n = n.nextSibling
        }
        return null
    }

    private fun httpGet(url: String): String {
        val c = open(URL(url))
        try {
            c.connectTimeout = 2500
            c.readTimeout = 3000
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ 控制

    /** 设置播放地址并开始播放。失败抛 [DlnaException]，消息里带 UPnP 错误码 */
    fun setUriAndPlay(dev: DlnaDevice, url: String, title: String, kind: String) {
        val mime = mimeOf(url, kind)
        val didl = didl(url, title, mime)
        soap(
            dev, "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${esc(url)}</CurrentURI><CurrentURIMetaData>${esc(didl)}</CurrentURIMetaData>",
        )
        play(dev)
    }

    fun play(dev: DlnaDevice) { soap(dev, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") }
    fun pause(dev: DlnaDevice) { soap(dev, "Pause", "<InstanceID>0</InstanceID>") }
    fun stop(dev: DlnaDevice) { soap(dev, "Stop", "<InstanceID>0</InstanceID>") }

    fun seek(dev: DlnaDevice, sec: Int) {
        soap(dev, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${hms(sec)}</Target>")
    }

    /** 当前进度 + 传输状态（两次 SOAP） */
    fun query(dev: DlnaDevice): DlnaPos {
        val pos = soap(dev, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        val info = soap(dev, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        return DlnaPos(
            rel = parseHms(tag(pos, "RelTime")),
            dur = parseHms(tag(pos, "TrackDuration")),
            state = tag(info, "CurrentTransportState") ?: "",
        )
    }

    private fun soap(dev: DlnaDevice, action: String, args: String): String {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${dev.serviceType}\">$args</u:$action></s:Body></s:Envelope>"
        val c = open(URL(dev.controlUrl))
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 3000
            c.readTimeout = 6000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPACTION", "\"${dev.serviceType}#$action\"")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
            if (code !in 200..299) {
                val upnp = tag(text, "errorCode")
                throw DlnaException("$action 失败：HTTP $code" + (if (upnp != null) "，UPnP 错误 $upnp" else ""), upnp?.toIntOrNull() ?: code)
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ 小工具

    class DlnaException(msg: String, val code: Int) : Exception(msg)

    companion object {
        fun esc(s: String): String = buildString(s.length + 16) {
            for (ch in s) when (ch) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
                '"' -> append("&quot;"); '\'' -> append("&apos;")
                else -> append(ch)
            }
        }

        fun mimeOf(url: String, kind: String): String {
            if (kind == "hls") return "application/vnd.apple.mpegurl"
            val path = runCatching { URL(url).path.lowercase() }.getOrDefault(url.lowercase())
            return when {
                path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".mov") -> "video/quicktime"
                else -> "video/mp4"
            }
        }

        fun didl(url: String, title: String, mime: String): String =
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
                "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${esc(title)}</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"

        fun hms(sec: Int): String {
            val s = sec.coerceAtLeast(0)
            return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        }

        /** "0:01:23" / "00:01:23.500" → 83；NOT_IMPLEMENTED / 空 → 0 */
        fun parseHms(t: String?): Int {
            if (t.isNullOrBlank()) return 0
            val p = t.trim().substringBefore('.').split(':')
            if (p.size != 3) return 0
            val h = p[0].toIntOrNull() ?: return 0
            val m = p[1].toIntOrNull() ?: return 0
            val s = p[2].toIntOrNull() ?: return 0
            return h * 3600 + m * 60 + s
        }

        /** 取响应里第一个 <tag>…</tag> 的文本（SOAP 响应结构固定，不必走完整 XML 解析）。去掉命名空间前缀，反转义常见实体 */
        fun tag(xml: String, name: String): String? {
            val m = Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>([\\s\\S]*?)</(?:[A-Za-z0-9_]+:)?$name>").find(xml) ?: return null
            return m.groupValues[1].trim()
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        }
    }
}
