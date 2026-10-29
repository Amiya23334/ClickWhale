package io.github.dshmobile

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在局域网里找跑着 dsh-relay 的电脑。
 *
 * 一开始想用 UDP 广播（"DSH_DISCOVER"），本机自测完全通，但手机上发不出去：
 * Android 底层直接 EPERM，`nc -u 255.255.255.255` 报 `connect: Permission denied`。
 * 普通 App 同样没有广播权限，所以改走 TCP 扫网段——慢一点（2-4 秒），但绝对可靠。
 *
 * 扫法：拿到本机所有 /24 网段，对每个地址试连 8090。同网段里绝大多数地址
 * 会立刻 RST（几毫秒），只有真在监听的才握手成功，所以 254 个地址并发扫很快。
 */
object Discovery {

    private const val TAG = "DshDiscovery"
    private const val DEFAULT_PORT = 8090
    private const val CONCURRENCY = 32
    private const val CONNECT_TIMEOUT_MS = 300

    private val pool = Executors.newFixedThreadPool(CONCURRENCY)
    private val main = Handler(Looper.getMainLooper())

    data class Found(val host: String, val port: Int) {
        val url: String get() = "http://$host:$port"
    }

    /**
     * 扫一遍局域网。[cb] 在主线程被调用，每找到一台调一次；最后一定调一次 [onDone]。
     */
    fun scan(port: Int = DEFAULT_PORT, cb: (Found) -> Unit, onDone: () -> Unit) {
        Thread {
            val prefixes = lanPrefixes()
            Log.i(TAG, "开始扫描，网段=$prefixes")
            if (prefixes.isEmpty()) {
                main.post { onDone() }
                return@Thread
            }

            val total = AtomicInteger(prefixes.size * 254)
            val finished = AtomicBoolean(false)
            val seen = HashSet<String>()
            val seenLock = Any()

            for (prefix in prefixes) {
                for (i in 1..254) {
                    val host = "$prefix.$i"
                    pool.execute {
                        try {
                            if (probe(host, port)) {
                                val isNew = synchronized(seenLock) { seen.add(host) }
                                if (isNew) {
                                    Log.i(TAG, "找到 $host:$port")
                                    main.post { cb(Found(host, port)) }
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            if (total.decrementAndGet() == 0 && finished.compareAndSet(false, true)) {
                                Log.i(TAG, "扫描结束，共找到 ${synchronized(seenLock) { seen.size }} 台")
                                main.post { onDone() }
                            }
                        }
                    }
                }
            }
        }.start()
    }

    /** 连上去问一句「你是中转吗」，靠 /relay/ping 的响应确认。 */
    private fun probe(host: String, port: Int): Boolean {
        try {
            Socket().use { s -> s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        } catch (_: Exception) {
            return false
        }
        return try {
            val conn = URL("http://$host:$port/relay/ping").openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = CONNECT_TIMEOUT_MS
            conn.requestMethod = "GET"
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()
            body.contains("dsh-relay")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 本机所有值得扫的 /24 前缀。
     *
     * 注意别只取第一个 IPv4：手机同时有 wlan0（192.168.x）和移动数据（10.x），
     * 移动数据常常排在前面，只扫它等于白扫。
     */
    private fun lanPrefixes(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name ?: ""
                // 跳过移动数据、点对点、VPN 这类不可能是局域网的口
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("p2p") || name.startsWith("tun") ||
                    name.startsWith("dummy")
                ) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    val prefix = ip.substringBeforeLast('.', "")
                    if (prefix.isEmpty()) continue
                    // 局域网常见三类私网地址
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                        (ip.startsWith("172.") && ip.split(".").getOrNull(1)?.toIntOrNull()
                            ?.let { it in 16..31 } == true)
                    ) {
                        // wlan 口排前面
                        if (name.startsWith("wlan")) out.add(prefix) 
                        else out.add(prefix)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }
}
