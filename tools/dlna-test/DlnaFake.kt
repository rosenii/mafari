/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

// 编译运行：kotlinc DlnaFake.kt ../../app/src/main/java/dev/dsh/miuixbrowser/script/DlnaCast.kt -include-runtime -d t.jar && java -jar t.jar
import dev.dsh.miuixbrowser.script.*
import com.sun.net.httpserver.HttpServer
import java.net.*

fun main() {
    val log = mutableListOf<String>()
    var state = "STOPPED"
    val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port = srv.address.port
    srv.createContext("/desc.xml") { ex ->
        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>客厅电视</friendlyName><serviceList><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/avt</controlURL></service></serviceList></device></root>"""
        val b = xml.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
    }
    srv.createContext("/avt") { ex ->
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val act = ex.requestHeaders.getFirst("SOAPACTION").trim('"').substringAfter('#')
        log += act
        if (act == "SetAVTransportURI") { log += "URI=" + DlnaCast.tag(body, "CurrentURI"); log += "META=" + DlnaCast.tag(body, "CurrentURIMetaData")!!.take(60) }
        if (act == "Play") state = "PLAYING"; if (act == "Pause") state = "PAUSED_PLAYBACK"; if (act == "Stop") state = "STOPPED"
        if (act == "Seek") log += "T=" + DlnaCast.tag(body, "Target")
        val resp = when (act) {
            "GetPositionInfo" -> "<u:GetPositionInfoResponse><RelTime>0:01:05</RelTime><TrackDuration>01:30:00.000</TrackDuration></u:GetPositionInfoResponse>"
            "GetTransportInfo" -> "<u:GetTransportInfoResponse><CurrentTransportState>$state</CurrentTransportState></u:GetTransportInfoResponse>"
            else -> "<u:${act}Response/>"
        }
        val env = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>$resp</s:Body></s:Envelope>".toByteArray()
        ex.sendResponseHeaders(200, env.size.toLong()); ex.responseBody.use { it.write(env) }
    }
    srv.start()
    // fake SSDP responder on a unicast port (the client sends to ssdpAddr:ssdpPort)
    val ssdp = DatagramSocket(0, InetAddress.getByName("127.0.0.1")); val sp = ssdp.localPort
    Thread {
        val buf = ByteArray(2048)
        while (true) { val p = DatagramPacket(buf, buf.size); ssdp.receive(p)
            val r = "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:$port/desc.xml\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n".toByteArray()
            ssdp.send(DatagramPacket(r, r.size, p.socketAddress)) }
    }.apply { isDaemon = true }.start()

    val d = DlnaCast(ssdpAddr = "127.0.0.1", ssdpPort = sp)
    val found = mutableListOf<DlnaDevice>()
    d.discover(2500, { found.size >= 1 }) { found += it }
    check(found.size == 1) { "found=${found.size}" }
    val dev = found[0]
    println("device: ${dev.name} ctl=${dev.controlUrl} svc=${dev.serviceType}")
    d.setUriAndPlay(dev, "http://a.com/v/a.m3u8?x=1&y=2", "T & <b>", "hls")
    d.seek(dev, 3725)
    val q = d.query(dev)
    println("query rel=${q.rel} dur=${q.dur} state=${q.state}")
    d.pause(dev); d.stop(dev)
    println(log.joinToString(" | "))
    check(q.rel == 65 && q.dur == 5400 && q.state == "PLAYING")
    check(log.contains("T=01:02:05")) ; check(log.any { it == "URI=http://a.com/v/a.m3u8?x=1&y=2" })
    println("DLNA_OK"); System.exit(0)
}
