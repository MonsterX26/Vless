package com.cipherlink.vpn

import android.net.VpnService
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.Socket

/** Reflection boundary around gomobile-generated libbox classes.
 * This keeps the UI module independent of generated Java source names while
 * still invoking the official libbox API shipped by the Gradle dependency.
 */
object CoreBridge {
    private const val TAG = "CoreBridge"
    private const val LIBBOX_CLASS = "io.nekohasekai.libbox.Libbox"
    @Volatile private var boxService: Any? = null
    @Volatile private var vpnService: VpnService? = null
    @Volatile private var activeConfig: String? = null

    fun isCoreAvailable(): Boolean = try {
        Class.forName(LIBBOX_CLASS)
        true
    } catch (_: Throwable) { false }

    fun isRunning(): Boolean = boxService != null

    @Synchronized
    fun start(vpn: VpnService, configJson: String): String? {
        if (boxService != null && activeConfig == configJson) return null
        if (boxService != null) stop()
        var createdService: Any? = null
        return try {
            val libbox = Class.forName(LIBBOX_CLASS)
            vpnService = vpn
            val base = File(vpn.filesDir, "sing-box").apply { mkdirs() }
            val work = File(base, "work").apply { mkdirs() }
            val temp = File(cacheDir(vpn), "sing-box").apply { mkdirs() }

            // libbox.Setup(basePath, workingPath, tempPath, isTVOS)
            val setup = libbox.methods.firstOrNull {
                it.name.equals("setup", true) && it.parameterTypes.size == 4
            } ?: error("API libbox.setup(base, work, temp, isTVOS) introuvable")
            setup.invoke(null, base.absolutePath, work.absolutePath, temp.absolutePath, false)

            // Validate configuration with the same core before creating the TUN.
            val checkConfig = libbox.methods.firstOrNull {
                it.name.equals("checkConfig", true) && it.parameterTypes.size == 1
            } ?: error("API libbox.checkConfig(config) introuvable")
            checkConfig.invoke(null, configJson)

            val platformType = Class.forName("io.nekohasekai.libbox.PlatformInterface")
            val platform = Proxy.newProxyInstance(
                platformType.classLoader,
                arrayOf(platformType),
                PlatformHandler(vpn)
            )

            val factory = libbox.methods.firstOrNull {
                it.name.equals("newService", true) && it.parameterTypes.size == 2
            } ?: error("API libbox.newService(config, PlatformInterface) introuvable")
            val service = factory.invoke(null, configJson, platform)
                ?: error("libbox.newService a retourné null")
            createdService = service
            val start = service.javaClass.methods.firstOrNull {
                it.name.equals("start", true) && it.parameterTypes.isEmpty()
            } ?: error("BoxService.start() introuvable")
            start.invoke(service)
            val version = try {
                libbox.methods.firstOrNull {
                    it.name.equals("version", true) && it.parameterTypes.isEmpty()
                }?.invoke(null)?.toString() ?: "version inconnue"
            } catch (_: Throwable) { "version inconnue" }
            boxService = service
            activeConfig = configJson
            Log.i(TAG, "sing-box démarré ($version)")
            null
        } catch (error: Throwable) {
            val cause = error.cause ?: error
            try {
                createdService?.javaClass?.methods?.firstOrNull {
                    it.name.equals("close", true) && it.parameterTypes.isEmpty()
                }?.invoke(createdService)
            } catch (closeError: Throwable) {
                Log.w(TAG, "nettoyage après échec", closeError.cause ?: closeError)
            }
            Log.e(TAG, "démarrage sing-box impossible", cause)
            vpnService = null
            "${cause.javaClass.simpleName}: ${cause.message ?: "erreur libbox"}"
        }
    }

    @Synchronized
    fun stop() {
        val current = boxService
        try {
            current?.javaClass?.methods?.firstOrNull {
                it.name.equals("close", true) && it.parameterTypes.isEmpty()
            }?.invoke(current)
        } catch (error: Throwable) {
            Log.w(TAG, "fermeture libbox", error.cause ?: error)
        } finally {
            boxService = null
            vpnService = null
            activeConfig = null
        }
    }

    /** Keep diagnostic TCP probes outside the VPN to avoid testing through the tunnel itself. */
    fun protect(socket: Socket): Boolean = vpnService?.protect(socket) ?: true

    /** Notify sing-box that Android changed the active network (Wi-Fi/mobile handover). */
    fun networkChanged() {
        val current = boxService ?: return
        try {
            current.javaClass.methods.firstOrNull {
                it.name.equals("resetNetwork", true) && it.parameterTypes.isEmpty()
            }?.invoke(current)
            current.javaClass.methods.firstOrNull {
                it.name.equals("wake", true) && it.parameterTypes.isEmpty()
            }?.invoke(current)
        } catch (error: Throwable) {
            Log.w(TAG, "mise à jour du réseau", error.cause ?: error)
        }
    }

    private fun cacheDir(vpn: VpnService): File = vpn.cacheDir

    private class PlatformHandler(private val vpn: VpnService) : InvocationHandler {
        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? {
            val values = args ?: emptyArray()
            return when (method.name.lowercase()) {
                "opentun" -> {
                    val descriptor = vpn.Builder()
                        .setSession("CipherLink")
                        .setMtu(1500)
                        .addAddress("172.19.0.1", 30)
                        .addAddress("fdfe:dcba:9876::1", 126)
                        .addRoute("0.0.0.0", 0)
                        .addRoute("::", 0)
                        .addDnsServer("1.1.1.1")
                        .addDnsServer("2606:4700:4700::1111")
                        .setBlocking(true)
                        .establish() ?: throw IllegalStateException("Android a refusé la création du TUN")
                    descriptor.detachFd()
                }
                "useplatformautodetectinterfacecontrol" -> true
                "autodetectinterfacecontrol" -> {
                    val fd = (values.firstOrNull() as? Number)?.toInt()
                        ?: throw IllegalArgumentException("descripteur socket manquant")
                    if (!vpn.protect(fd)) throw IllegalStateException("VpnService.protect($fd) a échoué")
                    null
                }
                "getinterfaces" -> emptyIteratorProxy(method.returnType)
                "systemcertificates" -> emptyIteratorProxy(method.returnType)
                "undernetworkextension", "includeallnetworks" -> false
                "readwifistate", "localdnstransport", "findconnectionowner" -> null
                "startdefaultinterfacemonitor", "closedefaultinterfacemonitor",
                "cleardnscache", "sendnotification", "cancelnotification",
                "startneighbormonitor", "closeneighbormonitor" -> null
                "tostring" -> "CipherLinkPlatformInterface"
                "hashcode" -> System.identityHashCode(proxy)
                "equals" -> proxy === values.firstOrNull()
                else -> primitiveDefault(method.returnType)
            }
        }

        private fun emptyIteratorProxy(type: Class<*>): Any? {
            if (!type.isInterface) return null
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                when (method.name.lowercase()) {
                    "hasnext" -> false
                    "next" -> null
                    "tostring" -> "EmptyIterator"
                    else -> primitiveDefault(method.returnType)
                }
            }
        }

        private fun primitiveDefault(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Void.TYPE -> null
            else -> null
        }
    }
}
