package com.cipherlink.vpn

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Parsed VLESS share link. Secrets are kept only in local app storage. */
data class VlessNode(
    val name: String,
    val uri: String,
    val host: String,
    val port: Int,
    val uuid: String,
    val security: String,
    val network: String,
    val sni: String,
    val wsHost: String,
    val path: String,
    val flow: String,
    val fingerprint: String,
    val alpn: List<String>,
    val allowInsecure: Boolean,
    val realityPublicKey: String,
    val realityShortId: String,
    val grpcServiceName: String
)

object VlessParser {
    fun parse(rawInput: String): VlessNode? {
        val raw = rawInput.trim()
        if (!raw.startsWith("vless://", ignoreCase = true)) return null
        return try {
            val parsed = Uri.parse(raw)
            val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
            val uuid = parsed.userInfo?.takeIf { it.isNotBlank() } ?: return null
            val port = if (parsed.port in 1..65535) parsed.port else 443
            val security = (parsed.getQueryParameter("security") ?: "none").lowercase(Locale.ROOT)
            val network = (parsed.getQueryParameter("type") ?: "tcp").lowercase(Locale.ROOT)
            if (security !in setOf("none", "tls", "reality")) return null
            if (network !in setOf("tcp", "ws", "grpc", "httpupgrade", "h2")) return null

            val sni = parsed.getQueryParameter("sni")?.takeIf { it.isNotBlank() }
                ?: parsed.getQueryParameter("servername")?.takeIf { it.isNotBlank() }
                ?: host
            val wsHost = parsed.getQueryParameter("host")?.takeIf { it.isNotBlank() } ?: sni
            val path = parsed.getQueryParameter("path")?.takeIf { it.isNotBlank() } ?: "/"
            val fragment = parsed.fragment?.takeIf { it.isNotBlank() }
            val name = fragment?.let { Uri.decode(it) } ?: "$host:$port"
            val alpn = parsed.getQueryParameter("alpn")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            VlessNode(
                name = name,
                uri = raw,
                host = host,
                port = port,
                uuid = uuid,
                security = security,
                network = network,
                sni = sni,
                wsHost = wsHost,
                path = path,
                flow = parsed.getQueryParameter("flow") ?: "",
                fingerprint = parsed.getQueryParameter("fp")?.takeIf { it.isNotBlank() } ?: "chrome",
                alpn = alpn,
                allowInsecure = parsed.getQueryParameter("allowInsecure") == "1" ||
                    parsed.getQueryParameter("allowInsecure").equals("true", true),
                realityPublicKey = parsed.getQueryParameter("pbk") ?: "",
                realityShortId = parsed.getQueryParameter("sid") ?: "",
                grpcServiceName = parsed.getQueryParameter("serviceName") ?: path.trim('/')
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Build a sing-box config. TUN creation is delegated to the Android platform adapter. */
    fun toSingBox(node: VlessNode): String {
        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", node.host)
            .put("server_port", node.port)
            .put("uuid", node.uuid)

        if (node.flow.isNotBlank()) outbound.put("flow", node.flow)

        if (node.security != "none") {
            val tls = JSONObject()
                .put("enabled", true)
                .put("server_name", node.sni)
                .put("insecure", node.allowInsecure)
                .put("utls", JSONObject().put("enabled", true).put("fingerprint", node.fingerprint))
            if (node.alpn.isNotEmpty()) tls.put("alpn", JSONArray(node.alpn))
            if (node.security == "reality") {
                require(node.realityPublicKey.isNotBlank()) { "clé publique Reality (pbk) absente" }
                tls.put("reality", JSONObject()
                    .put("enabled", true)
                    .put("public_key", node.realityPublicKey)
                    .put("short_id", node.realityShortId))
            }
            outbound.put("tls", tls)
        }

        when (node.network) {
            "ws" -> outbound.put("transport", JSONObject()
                .put("type", "ws")
                .put("path", node.path)
                .put("headers", JSONObject().put("Host", node.wsHost)))
            "grpc" -> outbound.put("transport", JSONObject()
                .put("type", "grpc")
                .put("service_name", node.grpcServiceName))
            "httpupgrade" -> outbound.put("transport", JSONObject()
                .put("type", "httpupgrade")
                .put("host", node.wsHost)
                .put("path", node.path))
            "h2" -> outbound.put("transport", JSONObject()
                .put("type", "http")
                .put("host", JSONArray().put(node.wsHost))
                .put("path", node.path))
        }

        val inbound = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("mtu", 1500)
            .put("auto_route", false)
            .put("stack", "system")

        return JSONObject()
            .put("log", JSONObject().put("level", "info"))
            .put("dns", JSONObject()
                .put("servers", JSONArray()
                    .put(JSONObject()
                        .put("type", "https")
                        .put("tag", "dns-remote")
                        .put("server", "1.1.1.1")
                        .put("server_port", 443)
                        .put("path", "/dns-query")
                        .put("tls", JSONObject().put("enabled", true).put("server_name", "cloudflare-dns.com"))
                        .put("detour", "direct")))
                .put("final", "dns-remote")
                .put("strategy", "prefer_ipv4"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray()
                .put(outbound)
                .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject()
                .put("auto_detect_interface", true)
                .put("final", "proxy"))
            .toString(2)
    }
}
