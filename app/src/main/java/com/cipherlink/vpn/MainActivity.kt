package com.cipherlink.vpn

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private val nodes = mutableListOf<VlessNode>()
    private val latency = mutableMapOf<String, Int?>()
    private val testing = AtomicBoolean(false)
    private lateinit var nodesBox: LinearLayout
    private lateinit var statusView: TextView
    private var pending: VlessNode? = null
    private var selectedUri: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadNodes()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 21)
        setContentView(buildUi())
        renderNodes()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(28))
            setBackgroundColor(Color.rgb(5, 7, 10))
        }
        root.addView(caption("CONSOLE DE ROUTAGE", "#4DFF9F", 11f))
        root.addView(caption("CipherLink", "#E8FFF4", 27f))
        statusView = caption("prêt · ${nodes.size} profil(s)", "#8B9C93", 11f)
        statusView.setPadding(0, dp(10), 0, dp(4))
        root.addView(statusView)
        root.addView(action("TESTER LES NŒUDS") { testAll() })
        nodesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(nodesBox)
        root.addView(action("CONNECTER LE NŒUD SÉLECTIONNÉ") { connect() })
        root.addView(action("DÉCONNECTER") { stopTunnel() })
        root.addView(action("+ AJOUTER UNE CONFIG VLESS") { addDialog() })
        root.addView(action("COLLER DEPUIS LE PRESSE-PAPIER") { pasteFromClipboard() })
        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun caption(value: String, color: String, size: Float): TextView = TextView(this).apply {
        text = value
        setTextColor(Color.parseColor(color))
        textSize = size
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun action(value: String, onClick: () -> Unit): Button = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun renderNodes() {
        nodesBox.removeAllViews()
        if (nodes.isEmpty()) {
            nodesBox.addView(caption("aucune configuration — ajoute une URI vless://", "#8B9C93", 11f))
            return
        }
        nodes.forEach { node ->
            val selected = node.uri == selectedUri
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(5), dp(7), dp(5), dp(7))
                setBackgroundColor(if (selected) Color.rgb(16, 42, 31) else Color.TRANSPARENT)
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            details.addView(caption((if (selected) "● " else "○ ") + node.name, "#E8FFF4", 12f))
            details.addView(caption("${node.network.uppercase()} · ${node.security.uppercase()} · ${node.host}:${node.port}", "#8B9C93", 10f))
            details.addView(caption(latency[node.uri]?.let { "TCP $it ms" } ?: "latence —", "#4DFF9F", 10f))
            details.setOnClickListener {
                selectedUri = node.uri
                prefs().edit().putString("selected_uri", selectedUri).apply()
                renderNodes()
            }
            row.addView(details)
            val remove = Button(this).apply {
                text = "×"
                isAllCaps = false
                setOnClickListener { confirmDelete(node) }
            }
            row.addView(remove)
            nodesBox.addView(row)
        }
    }

    private fun testAll() {
        if (nodes.isEmpty()) {
            statusView.text = "ajoute d'abord une configuration"
            return
        }
        if (!testing.compareAndSet(false, true)) return
        val snapshot = nodes.toList()
        statusView.text = "test TCP en cours…"
        Thread {
            snapshot.forEach { node -> latency[node.uri] = ping(node) }
            runOnUiThread {
                testing.set(false)
                statusView.text = "test terminé · ${snapshot.size} nœud(s) · mesure TCP, pas ping du tunnel"
                renderNodes()
            }
        }.start()
    }

    /** TCP connect latency to the configured endpoint; this is not an ICMP ping. */
    private fun ping(node: VlessNode): Int? {
        val socket = Socket()
        return try {
            if (!CoreBridge.protect(socket)) return null
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(node.host, node.port), 4000)
            ((System.nanoTime() - started) / 1_000_000L).toInt()
        } catch (_: Exception) {
            null
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun connect() {
        val node = nodes.firstOrNull { it.uri == selectedUri } ?: nodes.firstOrNull()
        if (node == null) {
            statusView.text = "ajoute d'abord une configuration"
            return
        }
        selectedUri = node.uri
        prefs().edit().putString("selected_uri", selectedUri).apply()
        val request = VpnService.prepare(this)
        if (request != null) {
            pending = node
            startActivityForResult(request, VPN_REQUEST)
        } else startTunnel(node)
    }

    private fun startTunnel(node: VlessNode) {
        val intent = Intent(this, TunnelService::class.java).apply {
            action = TunnelService.ACTION_START
            putExtra(TunnelService.EXTRA_CONFIG, node.uri)
        }
        ContextCompat.startForegroundService(this, intent)
        statusView.text = "démarrage demandé · ${node.name}"
    }

    private fun stopTunnel() {
        val intent = Intent(this, TunnelService::class.java).apply { action = TunnelService.ACTION_STOP }
        startService(intent)
        statusView.text = "arrêt demandé"
    }

    @Deprecated("Kept for compatibility with the project's current Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST) return
        if (resultCode == Activity.RESULT_OK) pending?.let { startTunnel(it) }
        else statusView.text = "autorisation VPN refusée"
        pending = null
    }

    private fun addDialog(initial: String = "") {
        val input = EditText(this).apply {
            hint = "vless://uuid@host:443?security=tls&type=ws&host=…&path=/…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            setText(initial)
            minLines = 4
        }
        AlertDialog.Builder(this)
            .setTitle("Nouvelle configuration")
            .setView(input)
            .setPositiveButton("Ajouter") { _, _ ->
                val node = VlessParser.parse(input.text.toString())
                if (node == null) statusView.text = "URI VLESS invalide ou paramètres non pris en charge"
                else if (nodes.any { it.uri == node.uri }) statusView.text = "ce profil existe déjà"
                else {
                    nodes.add(node)
                    if (selectedUri.isBlank()) selectedUri = node.uri
                    saveNodes()
                    renderNodes()
                    statusView.text = "ajouté · ${node.name}"
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (value.isNullOrBlank()) statusView.text = "presse-papier vide"
        else addDialog(value)
    }

    private fun confirmDelete(node: VlessNode) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer le profil ?")
            .setMessage(node.name)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Supprimer") { _, _ ->
                nodes.removeAll { it.uri == node.uri }
                latency.remove(node.uri)
                if (selectedUri == node.uri) selectedUri = nodes.firstOrNull()?.uri ?: ""
                saveNodes()
                renderNodes()
                statusView.text = "profil supprimé"
            }
            .show()
    }

    private fun prefs() = getSharedPreferences("cipherlink", Context.MODE_PRIVATE)

    private fun loadNodes() {
        nodes.clear()
        selectedUri = prefs().getString("selected_uri", "") ?: ""
        val raw = prefs().getString("nodes", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val uri = array.getJSONObject(index).optString("uri")
                VlessParser.parse(uri)?.let { parsed ->
                    if (nodes.none { it.uri == parsed.uri }) nodes.add(parsed)
                }
            }
        } catch (_: Exception) {
            prefs().edit().putString("nodes", "[]").apply()
        }
        if (nodes.none { it.uri == selectedUri }) selectedUri = nodes.firstOrNull()?.uri ?: ""
    }

    private fun saveNodes() {
        val array = JSONArray()
        nodes.forEach { array.put(JSONObject().put("uri", it.uri)) }
        prefs().edit().putString("nodes", array.toString()).putString("selected_uri", selectedUri).apply()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { private const val VPN_REQUEST = 7 }
}
