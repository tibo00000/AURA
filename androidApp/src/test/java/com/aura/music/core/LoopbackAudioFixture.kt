package com.aura.music.core

import okhttp3.OkHttpClient
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real loopback sockets/TLS. Temporary synthetic keys only, no global TLS bypass. */
internal class LoopbackAudioFixture : Closeable {
    data class Received(val path: String, val authorization: String?, val range: String?)
    data class Reply(val status: Int = 200, val body: ByteArray = "synthetic-audio".toByteArray(),
                     val headers: Map<String, String> = emptyMap())
    private val directory = Files.createTempDirectory("aura-sec004-test-")
    val tls: SSLContext
    val client: OkHttpClient
    private val endpoints = mutableListOf<Endpoint>()
    private val serverFailures = ConcurrentLinkedQueue<String>()
    init {
        val password = "synthetic-fixture-only".toCharArray()
        val keytool = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool").toString()
        val storePath = directory.resolve("synthetic-tls.p12")
        val command = ProcessBuilder(keytool, "-genkeypair", "-alias", "loopback", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "2", "-startdate", "-1d", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", storePath.toString(),
            "-storepass", String(password), "-noprompt").redirectErrorStream(true).start()
        val output = command.inputStream.readBytes()
        check(command.waitFor(30, TimeUnit.SECONDS) && command.exitValue() == 0) { "Synthetic TLS fixture unavailable" }
        check(output.isNotEmpty())
        val store = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(storePath).use { load(it, password) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        tls = SSLContext.getInstance("TLS", "SunJSSE").apply { init(keys.keyManagers, trust.trustManagers, null) }
        client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust.trustManagers.single() as X509TrustManager)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    }

    inner class Endpoint(secure: Boolean, handler: (Received) -> Reply) : Closeable {
        val received = ConcurrentLinkedQueue<Received>()
        private val executor = Executors.newCachedThreadPool { Thread(it, "aura-test-http").apply { isDaemon = true } }
        private val server: ServerSocket = if (secure) tls.serverSocketFactory.createServerSocket(
            0, 50, InetAddress.getByName("127.0.0.1")) else ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val base = "${if (secure) "https" else "http"}://localhost:${server.localPort}"
        init {
            executor.submit {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                    executor.submit {
                        socket.use { connection ->
                            try { respond(connection, handler) }
                            catch (e: Exception) { serverFailures.add(e.javaClass.simpleName + ": " + e.message) }
                        }
                    }
                }
            }
        }
        private fun respond(socket: Socket, handler: (Received) -> Reply) {
            socket.soTimeout = 5000
            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
            val requestLine = input.readLine()?.split(' ') ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                check(line.length <= 8192 && headers.size < 100)
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val request = Received(requestLine[1], headers["authorization"], headers["range"])
            received.add(request)
            val reply = handler(request)
            val header = buildString {
                append("HTTP/1.1 ${reply.status} Test\r\nContent-Length: ${reply.body.size}\r\nConnection: close\r\n")
                reply.headers.forEach { (key, value) -> append("$key: $value\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(header.toByteArray(Charsets.US_ASCII))
                if (requestLine[0] != "HEAD") write(reply.body)
                flush()
            }
        }
        override fun close() { server.close(); executor.shutdownNow() }
    }

    fun endpoint(secure: Boolean = true, handler: (Received) -> Reply = { Reply() }): Endpoint =
        Endpoint(secure, handler).also { endpoints.add(it) }
    override fun close() {
        endpoints.forEach { it.close() }
        client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        // Synthetic fixture diagnostics only: no headers, URI or bearer values.
        if (serverFailures.isNotEmpty()) System.err.println("Loopback fixture: " + serverFailures.joinToString())
    }
}
