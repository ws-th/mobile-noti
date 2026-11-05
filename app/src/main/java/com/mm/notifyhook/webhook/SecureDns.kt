package com.mm.notifyhook.webhook

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * หา IP ผ่าน DNS-over-HTTPS (Cloudflare → Google) ก่อน เพราะ DNS ของผู้ให้บริการ/เครือข่าย
 * บางแห่งตอบ IP ปลอม (หน้าบล็อก หรือ fe80::) ทำให้ส่ง webhook ไม่ถึง
 * ถ้า DoH ใช้ไม่ได้ (เช่นโดเมนภายใน LAN) ค่อยถอยไปใช้ DNS ของระบบ
 */
object SecureDns : Dns {

    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun doh(url: String, vararg ips: String) = DnsOverHttps.Builder()
        .client(bootstrap)
        .url(url.toHttpUrl())
        // ระบุ IP ของ resolver ตรง ๆ เพื่อไม่ต้องพึ่ง DNS ของระบบในการหา resolver เอง
        .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
        .includeIPv6(true)
        .build()

    private val resolvers = listOf(
        "Cloudflare DoH" to doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
        "Google DoH" to doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
        "DNS ของระบบ" to Dns.SYSTEM,
    )

    /** resolver ล่าสุดที่ตอบได้ของแต่ละ host — ใช้แสดงในผลทดสอบ */
    private val lastSource = mutableMapOf<String, String>()

    fun describe(hostname: String): String? = synchronized(lastSource) { lastSource[hostname] }

    private const val CACHE_MS = 5 * 60 * 1000L
    private val cache = mutableMapOf<String, Pair<Long, List<InetAddress>>>()

    override fun lookup(hostname: String): List<InetAddress> {
        // จำผลไว้ชั่วครู่ ไม่ต้องถาม DoH ใหม่ทุกครั้งที่ส่ง (ช่วยลดเวลาและโอกาส timeout)
        synchronized(cache) {
            cache[hostname]?.let { (at, addrs) -> if (System.currentTimeMillis() - at < CACHE_MS) return addrs }
        }
        var lastError: Exception? = null
        for ((name, dns) in resolvers) {
            try {
                // ตัดที่อยู่ link-local (fe80::) ที่ DNS ปลอมชอบตอบมา — ใช้เชื่อมต่อจริงไม่ได้
                val addrs = dns.lookup(hostname).filterNot { it.isLinkLocalAddress || it.isAnyLocalAddress }
                if (addrs.isNotEmpty()) {
                    // ให้ IPv4 มาก่อน เครือข่ายมือถือไทยหลายเจ้า IPv6 ไม่เสถียร
                    val sorted = addrs.sortedBy { it is Inet6Address }
                    synchronized(lastSource) {
                        lastSource[hostname] = "$name → ${sorted.joinToString { it.hostAddress.orEmpty() }}"
                    }
                    synchronized(cache) { cache[hostname] = System.currentTimeMillis() to sorted }
                    return sorted
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw UnknownHostException("หา IP ของ $hostname ไม่ได้: ${lastError?.message ?: "ไม่มีผลลัพธ์"}")
    }
}
