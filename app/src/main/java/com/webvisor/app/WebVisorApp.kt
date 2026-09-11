package com.webvisor.app

import android.app.Application

/**
 * Clase Application de Web Visor.
 * Se deja preparada por si en el futuro se necesita inicializar
 * librerías globales (analytics, crash reporting, etc.).
 */
class WebVisorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Carga la lista de dominios bloqueados (ContentGuard) lo antes
        // posible, para que ya esté lista antes de la primera navegación.
        ContentGuard.init(this)
        // Idem para la lista de dominios de ads/trackers (AdBlocker).
        AdBlocker.init(this)
    }
}
