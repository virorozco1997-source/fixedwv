package com.webvisor.app

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * Control de contenido para adultos (control parental), con dos capas:
 *
 *   1) BLOCKED_HOSTS: bloqueo directo (sin cooldown, sin opción de "seguir
 *      igual") de sitios para adultos conocidos. La lista base vive en
 *      app/src/main/assets/blocked_domains.txt, para poder ampliarla sin
 *      tocar código Kotlin.
 *   2) applySafeSearch(): fuerza el modo seguro/restringido en los
 *      buscadores y en YouTube, para que ni aparezcan miniaturas o
 *      resultados NSFW dentro de una búsqueda normal.
 *
 * Importante ser honestos con las limitaciones: esto bloquea navegar
 * directamente a esos sitios (link, URL escrita a mano, etc.) y limpia
 * los buscadores más comunes, pero no analiza cada imagen o video que
 * carga cualquier página de internet, ni cubre "millones de sitios" como
 * promocionan filtros comerciales (Spin, etc.) que consultan una base de
 * datos en la nube en tiempo real. Para un control parental más estricto
 * y a nivel de red (todos los dispositivos y apps del teléfono, no solo
 * esta app), lo ideal es combinarlo con un DNS familiar como
 * CleanBrowsing (family-filter-dns.cleanbrowsing.org) configurado en
 * Ajustes → Red e Internet → DNS Privado, u OpenDNS FamilyShield a nivel
 * de router.
 */
object ContentGuard {

    private const val TAG = "ContentGuard"
    private const val ASSET_FILE = "blocked_domains.txt"

    /** Lista de respaldo por si el archivo de assets no se pudo leer. */
    private val FALLBACK_HOSTS = setOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
        "redtube.com", "youporn.com", "chaturbate.com", "livejasmin.com"
    )

    private var blockedHosts: Set<String> = FALLBACK_HOSTS
    private var initialized = false

    /**
     * Carga la lista de dominios bloqueados desde assets. Se llama una
     * sola vez, lo antes posible (ver WebVisorApp.onCreate), para que ya
     * esté lista antes de que el usuario navegue a cualquier lado.
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

    fun isBlocked(host: String?): Boolean {
        if (host == null) return false
        val lowerHost = host.lowercase()
        return blockedHosts.any {
            lowerHost == it || lowerHost.endsWith(".$it")
        }
    }

    /**
     * Si la URL es de un buscador o YouTube y todavía no tiene el modo
     * seguro forzado, devuelve la URL corregida. Si no aplica o ya está
     * bien, devuelve null (no hay que reemplazar nada).
     *
     * Siempre REEMPLAZA el parámetro de modo seguro (no lo agrega al
     * final): así una URL con adlt=off, safe=off o kp=-2 no lo apaga.
     */
    fun applySafeSearch(uri: Uri): Uri? {
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path ?: ""

        return when {
            // Google: safe=strict en cualquier búsqueda.
            host.endsWith("google.com") && path.startsWith("/search") ->
                forceParam(uri, "safe", "strict")

            // Bing: adlt=strict en búsquedas web e imágenes.
            (host == "bing.com" || host.endsWith(".bing.com")) &&
                (path.startsWith("/search") || path.startsWith("/images")) ->
                forceParam(uri, "adlt", "strict")

            // DuckDuckGo: kp=1 (safe search estricto).
            host == "duckduckgo.com" || host == "www.duckduckgo.com" ->
                forceParam(uri, "kp", "1")

            // YouTube: redirigir al host "restrict" que Google ofrece para
            // forzar el Modo Restringido a nivel de red/DNS.
            host == "youtube.com" || host == "www.youtube.com" || host == "m.youtube.com" ->
                uri.buildUpon().authority("restrict.youtube.com").build()

            else -> null
        }
    }

    /**
     * Deja el parámetro [name] con el único valor [value], borrando
     * cualquier otra copia (sin importar mayúsculas). Devuelve null si
     * ya estaba bien, para no entrar en un bucle de recargas.
     */
    private fun forceParam(uri: Uri, name: String, value: String): Uri? {
        val copies = uri.queryParameterNames.filter { it.equals(name, ignoreCase = true) }
        if (copies.size == 1 && copies[0] == name && uri.getQueryParameters(name) == listOf(value)) {
            return null
        }

        val rebuilt = uri.buildUpon().clearQuery()
        uri.queryParameterNames
            .filter { !it.equals(name, ignoreCase = true) }
            .forEach { key ->
                uri.getQueryParameters(key).forEach { rebuilt.appendQueryParameter(key, it) }
            }
        return rebuilt.appendQueryParameter(name, value).build()
    }

    /**
     * Detecta específicamente el "Modo IA" (AI Mode) de Google Search, sin
     * importar la variante de URL que use (Google la fue cambiando):
     *   - google.com/ai y google.com/aimode (accesos directos)
     *   - google.com/search con el parámetro udm=50 o aep=11 (se activa
     *     tocando el botón "Modo IA" desde una búsqueda común)
     * Aplica a cualquier dominio de Google (google.com, google.com.ar,
     * google.co.uk, etc.), con o sin "www.".
     */
    private val googleHostRegex = Regex("^(www\\.)?google\\.[a-z.]+$")

    fun isGoogleAiMode(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (!googleHostRegex.matches(host)) return false

        val path = uri.path?.lowercase()?.trimEnd('/') ?: ""
        if (path == "/ai" || path == "/aimode") return true

        if (path == "/search") {
            val udm = uri.getQueryParameter("udm")
            val aep = uri.getQueryParameter("aep")
            if (udm == "50" || aep == "11") return true
        }

        return false
    }

    /**
     * Detecta la IA integrada de Bing (Copilot): bing.com/chat,
     * bing.com/copilotsearch y bing.com/copilot. El sitio copilot.microsoft.com
     * se bloquea aparte desde blocked_domains.txt.
     */
    fun isBingAi(uri: Uri): Boolean {
        val host = uri
