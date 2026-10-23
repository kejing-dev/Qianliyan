package io.embodit.jieyan.platform.net

import android.content.Context
import io.embodit.jieyan.platform.JieyanLog
import io.ktor.network.tls.certificates.KeyType
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.security.auth.x500.X500Principal

/**
 * The LAN https listener (design v3 §2.2 route B): browsers only open the microphone on https or
 * localhost, so the headset also answers `https://<ip>:[PORT]` with a self-signed certificate.
 *
 * Ktor CIO has no TLS server connector, so this is a small TLS terminator: an `SSLServerSocket` on
 * [PORT] whose decrypted bytes are piped to the server's loopback-only plain connector
 * ([AssistServer.TLS_BACKEND_PORT]). The server tells the two apart by that local port (link "Wi-Fi").
 *
 * The certificate (CN `qianliyan.local`, SAN = every LAN IPv4 of the moment + 127.0.0.1, RSA 2048,
 * SHA256withRSA, 825 days) is generated once with Ktor's pure-Kotlin DER builder and stored (BKS, the
 * platform's default key store type) in the app's private files; it is re-signed only when a new LAN address is not covered (then the
 * browser asks once more).
 */
class TlsGateway(private val context: Context) {
    @Volatile private var server: SSLServerSocket? = null
    private var acceptThread: Thread? = null

    val running: Boolean
        get() = server != null

    fun start(): Boolean {
        if (server != null) return true
        return try {
            val ks = keyStore()
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, PASS) }
            val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            val s = (ssl.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT), 16)
            }
            server = s
            acceptThread = Thread({ acceptLoop(s) }, "jieyan-tls-accept").apply { isDaemon = true; start() }
            JieyanLog.i("TLS started port=$PORT")
            true
        } catch (t: Throwable) {
            JieyanLog.e("TLS start failed: $t", t)
            server = null
            false
        }
    }

    fun stop() {
        val s = server ?: return
        server = null
        runCatching { s.close() }
        acceptThread = null
        JieyanLog.i("TLS stopped")
    }

    private fun acceptLoop(s: SSLServerSocket) {
        while (server === s) {
            val client = try { s.accept() } catch (_: Throwable) { break }
            Thread({ bridge(client) }, "jieyan-tls-conn").apply { isDaemon = true; start() }
        }
    }

    private fun bridge(client: Socket) {
        val backend =
            try {
                client.tcpNoDelay = true
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), AssistServer.TLS_BACKEND_PORT), 2_000) // the backend binds IPv4 loopback only
                }
            } catch (t: Throwable) {
                JieyanLog.w("TLS backend connect failed: $t")
                runCatching { client.close() }
                return
            }
        val up = Thread({ pipe(client.getInputStream(), backend.getOutputStream(), client, backend) }, "jieyan-tls-up").apply { isDaemon = true }
        up.start()
        pipe(backend.getInputStream(), client.getOutputStream(), client, backend)
    }

    private fun pipe(input: InputStream, output: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }

    /** The stored key store if it covers today's addresses, else a newly signed one. */
    private fun keyStore(): KeyStore {
        val ips = (NetworkAddress.allLanIps() + "127.0.0.1").toSortedSet()
        val file = File(context.filesDir, FILE)
        val ipsFile = File(context.filesDir, "$FILE.ips")
        val stored = runCatching { ipsFile.readText().split(',').filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        if (file.exists() && stored.containsAll(ips)) {
            runCatching {
                return KeyStore.getInstance(KeyStore.getDefaultType()).apply { file.inputStream().use { load(it, PASS) } }
            }.onFailure { JieyanLog.w("TLS stored key store unreadable: $it") }
        }
        val all = (ips + stored).toSortedSet()
        val t0 = System.currentTimeMillis()
        // Ktor's builder: RSA 2048, SHA256withRSA, server key usage; the store is KeyStore.getDefaultType() (BKS on Android)
        val ks =
            buildKeyStore {
                certificate(ALIAS) {
                    password = String(PASS)
                    subject = X500Principal("CN=$CN, O=Embodit, C=CN")
                    daysValid = 825
                    keyType = KeyType.Server
                    hash = HashAlgorithm.SHA256
                    sign = SignatureAlgorithm.RSA
                    keySizeInBits = 2048
                    domains = listOf(CN, "localhost")
                    ipAddresses = all.map { InetAddress.getByName(it) }
                }
            }
        runCatching {
            file.outputStream().use { ks.store(it, PASS) }
            ipsFile.writeText(all.joinToString(","))
        }.onFailure { JieyanLog.w("TLS key store not saved: $it") }
        JieyanLog.i("TLS certificate signed in ${System.currentTimeMillis() - t0} ms san=$all")
        return ks
    }

    companion object {
        const val PORT = 8766
        private const val CN = "qianliyan.local"
        private const val FILE = "tls.bks"
        private const val ALIAS = "qianliyan"

        /** Not a secret: the key store never leaves the app's private files. */
        private val PASS = "qianliyan".toCharArray()
    }
}
