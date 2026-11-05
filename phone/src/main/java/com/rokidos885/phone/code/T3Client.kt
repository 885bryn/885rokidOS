package com.rokidos885.phone.code

import android.content.Context
import android.util.Base64
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class T3Error(message: String) : Exception(message)

/** One thread as the T3 page needs it. [items] counts every timeline item; a finished run adds some. */
data class T3Thread(val title: String, val model: String, val status: String, val pending: Int, val items: Int) {
    val running get() = status in RUNNING
    private companion object { val RUNNING = setOf("preparing", "queued", "starting", "running", "waiting") }
}

/**
 * T3 Code on the PC, reached over Tailscale HTTPS (docs/T3-MINIAPP-PLAN.md). Sign-in is T3's OAuth for outside clients:
 * PKCE in the browser, back to a loopback port on this phone (T3 refuses custom schemes, 2026-11-05). The token lives in
 * app-private preferences. Every call blocks: run it off the main thread. Never log the token, prompts or replies.
 */
class T3Client(context: Context) {
    private val prefs = context.getSharedPreferences("t3", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private var session: String? = null
    private var version = PROTOCOL_VERSION
    private var nextId = 0

    /** e.g. https://desktop-xxxx.tailnet.ts.net */
    var origin: String
        get() = prefs.getString("origin", "").orEmpty()
        set(value) { prefs.edit().putString("origin", value.trim().trimEnd('/')).apply() }
    val signedIn get() = prefs.getString("token", null) != null && prefs.getLong("expiresAt", 0) > System.currentTimeMillis()
    val threadId get() = prefs.getString("threadId", "").orEmpty()
    val threadTitle get() = prefs.getString("threadTitle", "").orEmpty()
    fun pickThread(id: String, title: String) = prefs.edit().putString("threadId", id).putString("threadTitle", title).apply()

    /** Registers, opens T3's approval page with [openBrowser], waits for the loopback redirect, then keeps the token. */
    fun signIn(openBrowser: (String) -> Unit) {
        val base = origin.ifEmpty { throw T3Error("Enter the T3 address first") }
        val meta = getJson("$base/.well-known/oauth-authorization-server")
        val client = postJson(meta.str("registration_endpoint"), buildJsonObject {
            put("client_name", "885rokidOS")
            put("redirect_uris", JsonArray(listOf(JsonPrimitive(REDIRECT))))
            put("token_endpoint_auth_method", "none")
            put("grant_types", JsonArray(listOf(JsonPrimitive("authorization_code"))))
            put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
        }).str("client_id")
        val verifier = random(32)
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = random(16)
        val url = meta.str("authorization_endpoint").toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code").addQueryParameter("client_id", client)
            .addQueryParameter("redirect_uri", REDIRECT).addQueryParameter("scope", SCOPES).addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge).addQueryParameter("code_challenge_method", "S256").build().toString()
        val code = ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = SIGN_IN_MS
            openBrowser(url)
            awaitCode(server, state)
        }
        val token = http.newCall(Request.Builder().url(meta.str("token_endpoint")).post(FormBody.Builder()
            .add("grant_type", "authorization_code").add("client_id", client).add("code", code)
            .add("redirect_uri", REDIRECT).add("code_verifier", verifier).build()).build()).execute().use {
            if (!it.isSuccessful) throw T3Error("T3 refused the sign-in (HTTP ${it.code})")
            Json.parseToJsonElement(it.body!!.string()).jsonObject
        }
        val lifetime = token["expires_in"]?.jsonPrimitive?.longOrNull ?: 3_600L
        prefs.edit().putString("token", token.str("access_token"))
            .putLong("expiresAt", System.currentTimeMillis() + lifetime * 1000).apply()
        session = null
    }

    /** The browser may ask for other paths (favicon) first; only /callback with our state counts. */
    private fun awaitCode(server: ServerSocket, state: String): String {
        while (true) {
            server.accept().use { socket ->
                val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                val target = line.split(' ').getOrNull(1).orEmpty()
                val query = ("http://127.0.0.1$target").toHttpUrl()
                val ok = query.encodedPath == "/callback" && query.queryParameter("state") == state
                val body = if (ok) "Signed in to T3. Go back to 885rokidOS." else "Not found"
                socket.getOutputStream().write(("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                if (ok) return query.queryParameter("code") ?: throw T3Error(query.queryParameter("error") ?: "T3 sent no code")
            }
        }
    }

    /** Newest first, in every project. */
    fun listThreads(): List<Pair<String, String>> =
        tool("t3_thread_list", buildJsonObject { put("allProjects", true); put("limit", 30) }).jsonObject["threads"]!!.jsonArray
            .map { it.jsonObject.str("threadId") to it.jsonObject["title"]?.jsonPrimitive?.contentOrNull.orEmpty() }

    fun readThread(id: String): T3Thread {
        val t = tool("t3_thread_read", buildJsonObject { put("threadId", id); put("limit", 1); put("maxCharsPerItem", 1) })
            .jsonObject["thread"]!!.jsonObject
        return T3Thread(t["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), t["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            t["status"]?.jsonPrimitive?.contentOrNull.orEmpty(), t["pendingRequestCount"]?.jsonPrimitive?.intOrNull ?: 0,
            t["itemCount"]?.jsonPrimitive?.intOrNull ?: 0)
    }

    /** The last assistant message of thread [id] with [items] timeline items, or "" when there is none near the end. */
    fun lastReply(id: String, items: Int): String =
        tool("t3_thread_read", buildJsonObject {
            put("threadId", id); put("afterPosition", (items - 40).coerceAtLeast(0)); put("limit", 40); put("maxCharsPerItem", 1500)
        }).jsonObject["items"]?.jsonArray.orEmpty().map { it.jsonObject }
            .lastOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "assistant_message" }
            ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()

    fun send(id: String, text: String) {
        tool("t3_thread_send", buildJsonObject { put("threadId", id); put("message", text); put("mode", "auto") })
    }

    /** MCP over Streamable HTTP, as the t3 CLI does it: initialize without a version header, then session + version on each call. */
    @Synchronized private fun tool(name: String, args: JsonObject): JsonElement {
        if (session == null) initialize()
        val result = try { call(name, args) } catch (_: SessionLost) { session = null; initialize(); call(name, args) }
        if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) throw T3Error(text(result).take(80).ifEmpty { "T3 refused $name" })
        result["structuredContent"]?.let { return it }
        return runCatching { Json.parseToJsonElement(text(result)) }.getOrElse { throw T3Error("T3 answered in an unknown form") }
    }

    private fun call(name: String, args: JsonObject) =
        rpc("tools/call", buildJsonObject { put("name", name); put("arguments", args) })!!

    private fun text(result: JsonObject) = result["content"]?.jsonArray.orEmpty()
        .mapNotNull { it.jsonObject.takeIf { c -> c["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.contentOrNull }
        .joinToString("")

    private fun initialize() {
        version = PROTOCOL_VERSION
        val init = rpc("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION); put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "885rokidOS"); put("version", "0.2") })
        }, initializing = true)
        version = init?.get("protocolVersion")?.jsonPrimitive?.contentOrNull ?: PROTOCOL_VERSION
        rpc("notifications/initialized", buildJsonObject {}, notification = true)
    }

    private fun rpc(method: String, params: JsonObject, initializing: Boolean = false, notification: Boolean = false): JsonObject? {
        val token = prefs.getString("token", null)?.takeIf { signedIn } ?: throw T3Error("Sign in to T3 in phone Settings")
        val base = origin.ifEmpty { throw T3Error("Enter the T3 address in phone Settings") }
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("method", method); put("params", params)
            if (!notification) put("id", ++nextId)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url("$base/mcp").post(body).header("Authorization", "Bearer $token")
            .header("Accept", "application/json, text/event-stream")
            .apply { if (!initializing) { session?.let { header("mcp-session-id", it) }; header("mcp-protocol-version", version) } }
            .build()
        http.newCall(request).execute().use { r ->
            if (initializing) session = r.header("mcp-session-id")
            if (r.code == 401) { prefs.edit().remove("token").apply(); throw T3Error("Sign in to T3 again in phone Settings") }
            if (r.code == 404 && !initializing) throw SessionLost()
            if (!r.isSuccessful) throw T3Error("T3 error (HTTP ${r.code})")
            val raw = r.body?.string().orEmpty()
            if (notification || raw.isBlank()) return null
            val message = if (r.header("Content-Type").orEmpty().contains("text/event-stream"))
                raw.split(Regex("\r?\n\r?\n")).mapNotNull { block ->
                    block.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
                        .takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it).jsonObject }
                }.lastOrNull { "result" in it || "error" in it } ?: throw T3Error("T3 sent no answer")
            else Json.parseToJsonElement(raw).jsonObject
            message["error"]?.let { throw T3Error(it.jsonObject["message"]?.jsonPrimitive?.contentOrNull?.take(80) ?: "T3 error") }
            return message["result"]?.jsonObject
        }
    }

    private fun getJson(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw T3Error("T3 not reachable (HTTP ${it.code})")
        Json.parseToJsonElement(it.body!!.string()).jsonObject
    }

    private fun postJson(url: String, body: JsonObject) =
        http.newCall(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()).execute().use {
            if (!it.isSuccessful) throw T3Error("T3 refused the sign-in (HTTP ${it.code})")
            Json.parseToJsonElement(it.body!!.string()).jsonObject
        }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull ?: throw T3Error("T3 answer has no $key")
    private fun random(bytes: Int) = b64(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    private fun b64(data: ByteArray) = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private class SessionLost : RuntimeException()

    private companion object {
        const val PORT = 43821
        /** T3 accepted this loopback redirect over Tailscale HTTPS (spike, 2026-11-05). */
        const val REDIRECT = "http://127.0.0.1:$PORT/callback"
        const val SCOPES = "orchestration:read orchestration:operate"
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SIGN_IN_MS = 5 * 60_000
        val JSON = "application/json".toMediaType()
    }
}
