package com.webvisor.app

import android.net.Uri

/**
 * Buscador por defecto de la pantalla de inicio. Se usa Swisscows en vez de
 * Google porque no rastrea al usuario y su SafeSearch (filtro de contenido
 * para adultos) no se puede desactivar desde la propia web, a diferencia de
 * Google — coherente con el resto de los controles de contenido de la app
 * (ver ContentGuard).
 */
object SearchEngine {

    /** Arma la URL de resultados de Swisscows para una búsqueda de texto. */
    fun buildSearchUrl(query: String): String {
        return "https://swisscows.com/en/web?query=" + Uri.encode(query.trim())
    }

    /**
     * true si lo que escribió el usuario parece directamente una dirección
     * web (ej. "wikipedia.org" o "https://wikipedia.org") en vez de una
     * búsqueda de texto libre.
     */
    fun looksLikeUrl(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.contains(" ")) return false
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return true

        // Un dominio simple: sin espacios, con al menos un punto y sin
        // caracteres típicos de una búsqueda ("?", que sí puede aparecer en
        // una URL real, se deja pasar igual porque el WebView la resuelve bien).
        return Regex("^[\\w.-]+\\.[a-zA-Z]{2,}(/.*)?$").matches(trimmed)
    }

    /** Normaliza lo que escribió el usuario a una URL cargable por el WebView. */
    fun resolveInput(input: String): String {
        val trimmed = input.trim()
        return if (looksLikeUrl(trimmed)) {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
        } else {
            buildSearchUrl(trimmed)
        }
    }
}
