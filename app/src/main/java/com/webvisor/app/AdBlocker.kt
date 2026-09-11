package com.webvisor.app

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Bloqueador básico de publicidad y rastreadores (trackers), a nivel de
 * dominio.
 *
 * Cómo funciona: en WebViewClient.shouldInterceptRequest() se consulta
 * isAdOrTracker(host) por cada request (imagen, script, iframe, XHR,
 * etc.) que hace la página. Si el host pertenece a una red de
 * publicidad/analítica conocida, se le devuelve una respuesta vacía en
 * vez de dejar que la red la cargue: el recurso "no existe" para la
 * página, así que el banner/script simplemente no aparece.
 *
 * La lista base vive en app/src/main/assets/ad_tracker_hosts.txt, para
 * poder ampliarla sin tocar código Kotlin.
 *
 * Importante ser honestos con las limitaciones (igual que ContentGuard):
 * esto bloquea por dominio, no analiza ni oculta elementos por CSS como
 * hacen uBlock Origin o AdGuard. Cubre las redes de ads/trackers más
 * comunes, pero no es infalible: algún sitio con su propio ad-server
 * puede seguir mostrando publicidad.
 */
object AdBlocker {

    private const val TAG = "AdBlocker"
    private const val ASSET_FILE = "ad_tracker_hosts.txt"

    /** Lista de respaldo por si el archivo de assets no se pudo leer. */
    private val FALLBACK_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "adnxs.com",
        "criteo.com", "outbrain.com", "taboola.com", "scorecardresearch.com"
    )

    private var blockedHosts: Set<String> = FALLBACK_HOSTS
    private var initialized = false

    /** Respuesta vacía que se le devuelve al WebView en vez del recurso real. */
    private val emptyResponse: WebResourceResponse
        get() = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )

    /**
     * Carga la lista de dominios desde assets. Se llama una sola vez, lo
     * antes posible (ver WebVisorApp.onCreate), para que ya esté lista
     * antes de la primera navegación.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val hosts = context.assets.open(ASSET_FILE)
                .bufferedReader()
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.lowercase() }
                .toSet()

            if (hosts.isNotEmpty()) {
                blockedHosts = hosts
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer $ASSET_FILE, se usa la lista de respaldo", e)
        }
    }

    private fun isAdOrTrackerHost(host: String?): Boolean {
        if (host == null) return false
        val lowerHost = host.lowercase()
        return blockedHosts.any {
            lowerHost == it || lowerHost.endsWith(".$it")
        }
    }

    /**
     * Decide qué hacer con un request del WebView:
     * - null: dejarlo pasar normalmente (no es un recurso de ads/trackers).
     * - WebResourceResponse vacío: bloquearlo.
     *
     * isMainFrame se recibe aparte para nunca bloquear la navegación
     * principal (si el usuario entra directo a un dominio que además
     * figura en la lista, no tiene sentido dejarlo con la pantalla en
     * blanco; el bloqueo tiene sentido para subrecursos como banners,
     * píxeles de tracking o scripts).
     */
    fun shouldBlock(host: String?, isMainFrame: Boolean): WebResourceResponse? {
        if (isMainFrame) return null
        return if (isAdOrTrackerHost(host)) emptyResponse else null
    }
}
