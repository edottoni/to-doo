package com.familia.core.net

import com.familia.core.data.LoginResp
import com.familia.core.data.OpDto
import com.familia.core.data.Ping
import com.familia.core.data.PushReq
import com.familia.core.data.PushResp
import com.familia.core.data.RefreshResp
import com.familia.core.data.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(val status: Int, val code: String, message: String) : Exception(message)
class SessionExpired : Exception("Sessão expirada. Entre novamente.")

/** Ignora BOM/espaços/avisos antes do JSON e lixo depois dele. */
fun cleanJson(text: String): String {
    val i = text.indexOf('{'); val j = text.lastIndexOf('}')
    return if (i in 0 until j) text.substring(i, j + 1) else text
}

class ApiClient(private val session: SessionStore, private val appKind: String) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }
    private val refreshLock = Mutex()
    private val mt = "application/json".toMediaType()

    /** Aceita "meusite.com", "https://meusite.com/casa/" etc. Mantém a subpasta. Retorna o endereço normalizado ou null. */
    fun normalizeUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
        val u = s.toHttpUrlOrNull() ?: return null
        return u.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
    }

    // Hospedagens gratuitas (InfinityFree e similares) respondem a clientes sem navegador com uma página de
    // "teste de JavaScript" que grava o cookie __test. O app resolve esse desafio sozinho (AES) e repete o pedido.
    private val challengeCookies = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun solveChallenge(html: String): String? = runCatching {
        val h = Regex("toNumbers\\(\"([0-9a-fA-F]+)\"\\)").findAll(html).map { it.groupValues[1] }.toList()
        if (h.size < 3) return@runCatching null
        fun bytes(x: String) = ByteArray(x.length / 2) { x.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val c = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(bytes(h[0]), "AES"), javax.crypto.spec.IvParameterSpec(bytes(h[1])))
        c.doFinal(bytes(h[2])).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun raw(method: String, base: String, path: String, body: String?, token: String?): String {
        // Rota por query string (api.php?r=...): funciona em qualquer hospedagem PHP, sem mod_rewrite.
        val route = path.substringBefore('?').trimStart('/')
        val extra = path.substringAfter('?', "")
        val url = "$base/api.php?i=1&r=$route" + if (extra.isNotEmpty()) "&$extra" else ""
        val host = base.toHttpUrlOrNull()?.host ?: base
        for (attempt in 0..1) {
            val b = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Mozilla/5.0 (Linux; Android 14) CasaEmDia/1.0").header("x-device-id", session.deviceId)
            challengeCookies[host]?.let { b.header("Cookie", "__test=$it") }
            if (token != null) b.header("Authorization", "Bearer $token").header("X-Auth-Token", token) // algumas hospedagens removem Authorization
            if (method == "POST") b.post((body ?: "{}").toRequestBody(mt)) else b.get()
            var retry = false
            http.newCall(b.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (attempt == 0 && text.contains("slowAES")) {
                    val v = solveChallenge(text)
                    if (v != null) { challengeCookies[host] = v; retry = true }
                }
                if (!retry) {
                    if (r.isSuccessful) return cleanJson(text)
                    val obj = runCatching { json.parseToJsonElement(cleanJson(text)) as JsonObject }.getOrNull()
                    val code = (obj?.get("error") as? JsonPrimitive)?.content ?: "http_${r.code}"
                    val msg = (obj?.get("message") as? JsonPrimitive)?.content ?: "Falha na comunicação (${r.code})"
                    throw ApiError(r.code, code, msg)
                }
            }
        }
        throw IOException("Não foi possível passar pela proteção do site")
    }

    private suspend fun authed(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val base = session.siteUrl ?: throw IOException("Site não configurado")
        try {
            raw(method, base, path, body, session.accessToken)
        } catch (e: ApiError) {
            if (e.status != 401) throw e
            if (!refresh(base)) { session.clearTokens(); throw SessionExpired() }
            try { raw(method, base, path, body, session.accessToken) }
            catch (e2: ApiError) { if (e2.status == 401) { session.clearTokens(); throw SessionExpired() } else throw e2 }
        }
    }

    private suspend fun refresh(base: String): Boolean = refreshLock.withLock {
        val rt = session.refreshToken ?: return false
        try {
            val body = JsonObject(mapOf("refreshToken" to JsonPrimitive(rt))).toString()
            val r = json.decodeFromString<RefreshResp>(raw("POST", base, "/auth/refresh", body, null))
            session.saveTokens(r.accessToken, r.refreshToken); true
        } catch (e: ApiError) { false }
        // IOException (sem rede) propaga: não é motivo para derrubar a sessão
    }

    suspend fun ping(base: String): Ping = withContext(Dispatchers.IO) {
        val text = raw("GET", base, "/ping", null, null)
        try { json.decodeFromString<Ping>(text) }
        catch (e: Exception) { throw ApiError(200, "bad_body", "O endereço respondeu, mas não parece o site do Casa em dia (ou há uma página de proteção na frente). Começo da resposta: " + text.trim().take(140)) }
    }

    suspend fun login(email: String, password: String): LoginResp = withContext(Dispatchers.IO) {
        val base = session.siteUrl ?: throw IOException("Site não configurado")
        val body = JsonObject(mapOf(
            "email" to JsonPrimitive(email.trim()), "password" to JsonPrimitive(password), "app" to JsonPrimitive(appKind),
            "deviceId" to JsonPrimitive(session.deviceId), "deviceName" to JsonPrimitive(android.os.Build.MODEL ?: "Android"),
            "platform" to JsonPrimitive("android-$appKind"),
        )).toString()
        json.decodeFromString<LoginResp>(raw("POST", base, "/auth/login", body, null))
    }

    suspend fun pull(since: Long): Snapshot = json.decodeFromString(authed("GET", "/sync/pull?since=$since"))

    suspend fun push(ops: List<OpDto>): PushResp = json.decodeFromString(authed("POST", "/sync/push", json.encodeToString(PushReq.serializer(), PushReq(ops))))
}
