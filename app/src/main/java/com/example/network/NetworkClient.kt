package com.example.network

import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object NetworkClient {
    const val DEFAULT_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()
    private val sharedConnectionPool = ConnectionPool(24, 10, TimeUnit.MINUTES)

    val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val (v4, v6) = all.partition { it is Inet4Address }
            return v4 + v6
        }
    }

    fun seedCookie(urlStr: String, name: String, value: String, domain: String) {
        val httpUrl = urlStr.toHttpUrlOrNull() ?: return
        val cookie = Cookie.Builder()
            .name(name)
            .value(value)
            .domain(domain.trimStart('.'))
            .path("/")
            .expiresAt(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12))
            .build()
        inMemoryCookieJar.saveFromResponse(httpUrl, listOf(cookie))
    }

    fun getCookieHeader(urlStr: String): String? {
        val httpUrl = urlStr.toHttpUrlOrNull() ?: return null
        val cookies = inMemoryCookieJar.loadForRequest(httpUrl)
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    private val inMemoryCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = cookieStore.getOrPut(url.host) { mutableListOf() }
            synchronized(list) {
                for (c in cookies) {
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val matched = mutableListOf<Cookie>()
            val host = url.host.lowercase()
            for ((storedHost, list) in cookieStore) {
                if (host == storedHost.lowercase() || host.endsWith(".${storedHost.lowercase()}")) {
                    synchronized(list) {
                        matched.addAll(list.filter { it.expiresAt > now })
                    }
                }
            }
            return matched.distinctBy { it.name }
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .connectionPool(sharedConnectionPool)
            .cookieJar(inMemoryCookieJar)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                if (original.header("User-Agent") == null) {
                    requestBuilder.header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
                    )
                }
                val host = original.url.host.lowercase()
                val pathLower = original.url.encodedPath.lowercase()
                val isPhnImageHost = host.contains("phncdn.com") && (
                    host.startsWith("pix") ||
                        host.startsWith("ei.") ||
                        host.startsWith("di.") ||
                        host.startsWith("ci.") ||
                        host.startsWith("bi.") ||
                        pathLower.contains("/rs:fit:") ||
                        pathLower.contains("/rs:fill:") ||
                        pathLower.contains("/thumbs_")
                    )
                val isImageRequest = pathLower.endsWith(".jpg") ||
                    pathLower.endsWith(".jpeg") ||
                    pathLower.endsWith(".png") ||
                    pathLower.endsWith(".webp") ||
                    pathLower.endsWith(".gif") ||
                    pathLower.endsWith(".svg") ||
                    pathLower.contains("/contentthumbs/") ||
                    pathLower.contains("/images/") ||
                    isPhnImageHost

                val existingAccept = original.header("Accept")
                if (existingAccept == null) {
                    if (isImageRequest) {
                        requestBuilder.header("Accept", "image/webp,image/apng,image/svg+xml,image/jpeg,image/png,image/*;q=0.8,*/*;q=0.5")
                    } else {
                        requestBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                    }
                } else if (existingAccept.contains("image/avif", ignoreCase = true) || existingAccept.contains("image/heif", ignoreCase = true)) {
                    val sanitizedAccept = existingAccept
                        .replace("image/avif,", "", ignoreCase = true)
                        .replace("image/heif,", "", ignoreCase = true)
                        .replace("image/heic,", "", ignoreCase = true)
                    requestBuilder.header("Accept", sanitizedAccept)
                }
                requestBuilder
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Ch-Ua", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\", \"Google Chrome\";v=\"128\"")
                    .header("Sec-Ch-Ua-Mobile", "?0")
                    .header("Sec-Ch-Ua-Platform", "\"Windows\"")

                if (isImageRequest) {
                    requestBuilder
                        .header("Sec-Fetch-Dest", "image")
                        .header("Sec-Fetch-Mode", "no-cors")
                        .header("Sec-Fetch-Site", "cross-site")
                } else {
                    requestBuilder
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .header("Sec-Fetch-User", "?1")
                        .header("Upgrade-Insecure-Requests", "1")
                }

                if (host.contains("sexmex") || host.contains("sexmex-cdn")) {
                    requestBuilder.header("Referer", "https://sexmex.xxx/")
                } else if (host.contains("phncdn.com") || host.contains("pornhub.com")) {
                    if (original.header("Referer") == null) {
                        requestBuilder.header("Referer", "https://www.pornhub.com/")
                    }
                }

                val request = requestBuilder.build()
                chain.proceed(request)
            }
            .build()
    }

    /**
     * Dedicated clean API client for debrid and REST API requests (no browser headers interceptor).
     */
    val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .connectionPool(sharedConnectionPool)
            .cookieJar(inMemoryCookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent") == null) {
                    builder.header("User-Agent", "Okb/1.0 (Android)")
                }
                if (original.header("Accept") == null) {
                    builder.header("Accept", "application/json")
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    suspend fun getHtml(url: String, headers: Map<String, String> = emptyMap()): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val response: Response = okHttpClient.newCall(reqBuilder.build()).await()
            if (!response.isSuccessful && response.code !in 300..399) {
                val code = response.code
                val msg = response.message
                response.close()
                throw Exception("HTTP $code: $msg")
            }
            val bodyStr = response.body?.string() ?: ""
            response.close()
            bodyStr
        }
    }
}

