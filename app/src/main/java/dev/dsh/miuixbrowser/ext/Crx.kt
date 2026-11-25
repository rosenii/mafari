/*
 * Copyright © 2026 rosenii. All rights reserved.
 * 未经版权所有者书面许可，不得复制、修改或分发。
 */

package dev.dsh.miuixbrowser.ext

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class CrxInfo(
    val version: Int,
    val extensionId: String,
    val publicKeyDer: ByteArray,
    /** 内嵌 zip 在文件里的起始偏移 */
    val zipOffset: Long,
    val signatureVerified: Boolean,
)

/**
 * CRX2 / CRX3 头解析、签名校验与拓展 ID 推导。
 * CRX3 布局：`Cr24` | version=3 (u32 LE) | headerSize (u32 LE) | CrxFileHeader (protobuf) | zip。
 * CrxFileHeader：field 2 / 3 = RSA / ECDSA 的 AsymmetricKeyProof{1:public_key, 2:signature}；
 * field 10000 = signed_header_data（SignedData{1:crx_id 16 字节}）。
 * 签名覆盖：`"CRX3 SignedData\0"` + u32 LE 长度 + signed_header_data + zip 全部字节。
 */
object Crx {
    private const val MAX_HEADER = 256 * 1024
    private val SIGNED_PREFIX = "CRX3 SignedData\u0000".toByteArray(Charsets.ISO_8859_1)

    fun isCrx(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(4)
            raf.readFully(b)
            String(b, Charsets.ISO_8859_1) == "Cr24"
        }
    } catch (_: IOException) {
        false
    }

    /** [verify] 为 true 时签名不对就抛 [PackageException]（默认，安装路径不要关） */
    fun parse(file: File, verify: Boolean = true): CrxInfo {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 16) throw PackageException("CRX 文件过短")
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (String(magic, Charsets.ISO_8859_1) != "Cr24") throw PackageException("不是 CRX 文件")
                val v = readLe32(raf)
                when (v) {
                    3L -> parseV3(raf, verify)
                    2L -> parseV2(raf, verify)
                    else -> throw PackageException("不支持的 CRX 版本：$v")
                }
            }
        } catch (e: IOException) {
            throw PackageException("CRX 读取失败：${e.message}")
        }
    }

    /** 把 CRX 里内嵌的 zip 原样拷到 [out]，再交给 [PackageUnpacker] */
    fun extractZip(file: File, info: CrxInfo, out: File) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.zipOffset)
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = raf.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                }
            }
        }
    }

    // ───────────────────────── CRX3 ─────────────────────────

    private class Proof(val publicKey: ByteArray, val signature: ByteArray, val rsa: Boolean)

    private fun parseV3(raf: RandomAccessFile, verify: Boolean): CrxInfo {
        val headerSize = readLe32(raf)
        if (headerSize <= 0 || headerSize > MAX_HEADER || 12L + headerSize > raf.length()) {
            throw PackageException("CRX3 头部大小不合法")
        }
        val header = ByteArray(headerSize.toInt())
        raf.readFully(header)
        val zipOffset = 12L + headerSize

        val proofs = mutableListOf<Proof>()
        var signedHeaderData: ByteArray? = null
        val r = ProtoReader(header)
        while (r.hasMore()) {
            val key = r.varint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                wire == 2 && field == 2 -> proofs.add(parseProof(r.bytes(), true))
                wire == 2 && field == 3 -> proofs.add(parseProof(r.bytes(), false))
                wire == 2 && field == 10000 -> signedHeaderData = r.bytes()
                else -> r.skip(wire)
            }
        }
        val signed = signedHeaderData ?: throw PackageException("CRX3 缺少 signed_header_data")
        if (proofs.isEmpty()) throw PackageException("CRX3 没有签名")

        var crxId: ByteArray? = null
        val sr = ProtoReader(signed)
        while (sr.hasMore()) {
            val key = sr.varint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (wire == 2 && field == 1) crxId = sr.bytes() else sr.skip(wire)
        }
        if (crxId == null || crxId.size != 16) throw PackageException("CRX3 的 crx_id 缺失或长度不对")
        val declared = idFromBytes(crxId)
        val owner = proofs.firstOrNull { ExtensionIds.fromPublicKey(it.publicKey) == declared }
            ?: throw PackageException("CRX3 声明的 ID 与任何签名公钥都不匹配")

        if (verify) verifyV3(raf, zipOffset, signed, proofs)
        return CrxInfo(3, declared, owner.publicKey, zipOffset, verify)
    }

    private fun parseProof(b: ByteArray, rsa: Boolean): Proof {
        var pub: ByteArray? = null
        var sig: ByteArray? = null
        val r = ProtoReader(b)
        while (r.hasMore()) {
            val key = r.varint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            when {
                wire == 2 && field == 1 -> pub = r.bytes()
                wire == 2 && field == 2 -> sig = r.bytes()
                else -> r.skip(wire)
            }
        }
        return Proof(
            pub ?: throw PackageException("CRX3 签名缺少公钥"),
            sig ?: throw PackageException("CRX3 签名缺少签名值"),
            rsa,
        )
    }

    private fun verifyV3(raf: RandomAccessFile, zipOffset: Long, signed: ByteArray, proofs: List<Proof>) {
        try {
            val sigs = proofs.map { p ->
                val key = KeyFactory.getInstance(if (p.rsa) "RSA" else "EC")
                    .generatePublic(X509EncodedKeySpec(p.publicKey))
                Signature.getInstance(if (p.rsa) "SHA256withRSA" else "SHA256withECDSA").also {
                    it.initVerify(key)
                    it.update(SIGNED_PREFIX)
                    it.update(le32(signed.size))
                    it.update(signed)
                }
            }
            raf.seek(zipOffset)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = raf.read(buf)
                if (n < 0) break
                for (s in sigs) s.update(buf, 0, n)
            }
            // 每一个签名都必须通过，而不是任意一个
            proofs.forEachIndexed { i, p ->
                if (!sigs[i].verify(p.signature)) throw PackageException("CRX3 签名校验失败（包被改动或损坏）")
            }
        } catch (e: GeneralSecurityException) {
            throw PackageException("CRX3 签名无法校验：${e.message}")
        }
    }

    // ───────────────────────── CRX2（旧格式） ─────────────────────────

    private fun parseV2(raf: RandomAccessFile, verify: Boolean): CrxInfo {
        val keyLen = readLe32(raf)
        val sigLen = readLe32(raf)
        if (keyLen <= 0 || sigLen <= 0 || keyLen > MAX_HEADER || sigLen > MAX_HEADER) {
            throw PackageException("CRX2 头部大小不合法")
        }
        val zipOffset = 16L + keyLen + sigLen
        if (zipOffset > raf.length()) throw PackageException("CRX2 文件被截断")
        val pub = ByteArray(keyLen.toInt())
        raf.readFully(pub)
        val sig = ByteArray(sigLen.toInt())
        raf.readFully(sig)
        if (verify) {
            try {
                val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pub))
                val s = Signature.getInstance("SHA1withRSA")
                s.initVerify(key)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = raf.read(buf)
                    if (n < 0) break
                    s.update(buf, 0, n)
                }
                if (!s.verify(sig)) throw PackageException("CRX2 签名校验失败")
            } catch (e: GeneralSecurityException) {
                throw PackageException("CRX2 签名无法校验：${e.message}")
            }
        }
        return CrxInfo(2, ExtensionIds.fromPublicKey(pub), pub, zipOffset, verify)
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun idFromBytes(b: ByteArray): String {
        val sb = StringBuilder(32)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append('a' + (v shr 4))
            sb.append('a' + (v and 0x0f))
        }
        return sb.toString()
    }

    private fun readLe32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toLong() and 0xff) or
            ((b[1].toLong() and 0xff) shl 8) or
            ((b[2].toLong() and 0xff) shl 16) or
            ((b[3].toLong() and 0xff) shl 24)
    }

    private fun le32(n: Int): ByteArray =
        byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

    /** 只够读 CRX 头用的 protobuf 读取器：varint / 定长 / length-delimited */
    private class ProtoReader(private val b: ByteArray) {
        private var pos = 0

        fun hasMore() = pos < b.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= b.size || shift > 63) throw PackageException("CRX 头部损坏")
                val x = b[pos++].toInt() and 0xff
                result = result or ((x and 0x7f).toLong() shl shift)
                if ((x and 0x80) == 0) return result
                shift += 7
            }
        }

        fun bytes(): ByteArray {
            val n = varint()
            if (n < 0 || n > (b.size - pos).toLong()) throw PackageException("CRX 头部损坏")
            val out = b.copyOfRange(pos, pos + n.toInt())
            pos += n.toInt()
            return out
        }

        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> advance(8)
                2 -> bytes()
                5 -> advance(4)
                else -> throw PackageException("CRX 头部含未知字段类型")
            }
        }

        private fun advance(n: Int) {
            if (pos + n > b.size) throw PackageException("CRX 头部损坏")
            pos += n
        }
    }
}
