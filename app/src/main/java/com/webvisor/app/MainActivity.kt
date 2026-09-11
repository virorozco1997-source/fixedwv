package com.webvisor.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.webvisor.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Host del primer sitio cargado; referencia para DomainPolicy en modo estricto. */
    private var originalHost: String? = null

    /** Callback que la web deja pendiente mientras el usuario elige un archivo. */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * Vista que la propia web pide mostrar en pantalla completa (el
     * <video> nativo del reproductor), y el callback para avisarle a la
     * web cuando el usuario salió de ese modo. Ver
     * onShowCustomView/onHideCustomView en setupWebView().
     */
    private var fullscreenVideoView: View? = null
    private var fullscreenVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var isFreshNavigation = false

    /**
     * Bloquea SOLO el buscador de Google en sí (google.com, www.google.com,
     * y sus variantes por país como google.com.ar, google.co.uk, etc.),
     * dejando pasar automáticamente cualquier otro servicio de Google
     * (Drive, Gmail, Maps, login/accounts, Meet, Translate, etc.) sin
     * necesidad de armar una lista de excepciones: esos hosts tienen un
     * subdominio antes de "google" (drive.google.com, accounts.google.com)
     * y por eso no matchean este patrón, que solo admite "google." o
     * "www.google." al principio.
     *
     * OJO: además, esto solo aplica a la navegación PRINCIPAL (ver
     * "request.isForMainFrame" más abajo) — un iframe de reCAPTCHA dentro
     * de otra página tampoco se bloquea.
     */
    private val googleSearchHostRegex = Regex("^(www\\.)?google\\.[a-z.]+$")

    private fun isManuallyBlockedHost(host: String?): Boolean {
        if (host == null) return false
        return googleSearchHostRegex.matches(host.lowercase())
    }

    /** Lanza el selector de archivos del sistema (galería, Drive, "Archivos", etc.). */
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val results = if (result.resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            } else {
                null
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupStatusBar()
        setupWebView()
        // setupFullScreenOnScroll() desactivado: ocultaba la barra de
        // navegación del sistema al scrollear, y el usuario prefiere que
        // quede siempre visible. Se mantiene solo el ajuste del
        // pull-to-refresh (isEnabled según scrollY) para que siga
        // funcionando bien al recargar.
        binding.webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.swipeRefresh.isEnabled = scrollY == 0
        }
        setupDownloads()
        setupBlockOverlay()
        setupHomeScreen()

        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }

        // Además de las zonas/estados que ya controlan CUÁNDO puede
        // arrancar un pull-to-refresh (ver setupWebView y
        // setupFullScreenOnScroll), esto controla CUÁNTO hay que tirar
        // para que dispare: con el valor por defecto alcanza un pull corto,
        // fácil de hacer sin querer. Pidiendo una distancia bastante mayor,
        // solo un gesto largo y deliberado hacia abajo termina recargando
        // la página.
        binding.swipeRefresh.setDistanceToTriggerSync(
            (220 * resources.displayMetrics.density).toInt()
        )

        applyDarkModePreferences()
        handleIncomingIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // La Activity está marcada con android:configChanges="...|uiMode" para
        // no reiniciarse (perdería la página cargada) cuando el sistema pasa
        // de claro a oscuro o viceversa. Por eso el ajuste se hace a mano acá.
        applyDarkModePreferences(newConfig)
    }

    private fun isSystemInDarkMode(config: Configuration = resources.configuration): Boolean {
        val nightModeFlags = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Sincroniza la app con el modo claro/oscuro del sistema:
     * - Color de los íconos de la barra de estado (oscuros sobre fondo claro,
     *   claros sobre fondo oscuro).
     * - Le pide al WebView que oscurezca automáticamente las páginas que no
     *   tengan su propio modo oscuro (si el dispositivo lo soporta).
     */
    private fun applyDarkModePreferences(config: Configuration = resources.configuration) {
        val isDark = isSystemInDarkMode(config)

        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = !isDark

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.webView.settings, isDark)
        }

        // El cambio de modo claro/oscuro puede alterar cómo se ve la misma
        // página (por el oscurecido forzado del WebView), así que
        // recalculamos el color de las barras.
        syncStatusBarColorWithPage()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * La barra de estado y la de navegación reservan su propio espacio
     * (nada se dibuja detrás): la web se ve como el contenido normal de una
     * app, no "por debajo" de la hora/batería. El color de ambas barras se
     * sincroniza con el fondo de cada página (ver syncStatusBarColorWithPage).
     */
    private fun setupStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    /**
     * true si el WebView está forzando actualmente un renderizado oscuro
     * (algorithmic darkening) porque el sistema está en modo oscuro. En ese
     * caso, el color de fondo "real" de la página (el que devuelve JS) no
     * coincide con lo que realmente se ve en pantalla, así que hay que
     * corregirlo al calcular el color de las barras.
     */
    private fun isForcedDarkRenderingActive(): Boolean {
        return isSystemInDarkMode() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
    }

    /**
     * Lee el color de fondo real de la página cargada (el de <body>, o si
     * ese es transparente, el de <html>) y lo aplica a la barra de estado y
     * a la de navegación, para que se vean como una continuación de la web
     * en vez de una franja de color fijo ajena a la página.
     */
    private fun syncStatusBarColorWithPage() {
        val script = """
            (function() {
                var bodyColor = window.getComputedStyle(document.body).backgroundColor;
                if (!bodyColor || bodyColor === 'rgba(0, 0, 0, 0)' || bodyColor === 'transparent') {
                    return window.getComputedStyle(document.documentElement).backgroundColor;
                }
                return bodyColor;
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { rawResult ->
            var color = parseCssColor(rawResult) ?: return@evaluateJavascript

            // El WebView está mostrando la página oscurecida artificialmente,
            // pero el color que acabamos de leer es el original (claro) de
            // la web: usamos el fondo oscuro de la app en su lugar para que
            // las barras coincidan con lo que se ve en pantalla.
            if (isForcedDarkRenderingActive() && isColorLight(color)) {
                color = ContextCompat.getColor(this, R.color.window_background)
            }

            applyBarsColor(color)
        }
    }

    /** Aplica un color a ambas barras del sistema y ajusta el color de sus íconos. */
    private fun applyBarsColor(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color

        val lightIcons = isColorLight(color)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
    }

    /**
     * Convierte el resultado de evaluateJavascript (un string con formato
     * CSS, ej. "rgb(18, 18, 18)" o "rgba(18, 18, 18, 1)", entre comillas
     * dobles por venir de JSON) a un color de Android. Devuelve null si no
     * se pudo interpretar o si el fondo es completamente transparente.
     */
    private fun parseCssColor(rawResult: String?): Int? {
        if (rawResult.isNullOrBlank() || rawResult == "null") return null

        val unquoted = rawResult.trim().removeSurrounding("\"")
        val numbers = Regex("[\\d.]+").findAll(unquoted).map { it.value }.toList()
        if (numbers.size < 3) return null

        return try {
            val r = numbers[0].toFloat().toInt().coerceIn(0, 255)
            val g = numbers[1].toFloat().toInt().coerceIn(0, 255)
            val b = numbers[2].toFloat().toInt().coerceIn(0, 255)
            val alpha = if (numbers.size >= 4) numbers[3].toFloat() else 1f
            if (alpha <= 0f) return null
            Color.rgb(r, g, b)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** true si el color es "claro" (conviene usar íconos oscuros encima). */
    private fun isColorLight(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return luminance > 0.5
    }

    /**
     * Oculta la barra de navegación inferior (modo inmersivo) para que la
     * web ocupe toda la pantalla como una app. La barra de estado (reloj,
     * batería, señal) NO se toca: queda siempre visible.
     */
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    /** Vuelve a mostrar la barra de navegación inferior. */
    private fun showSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }

    /**
     * A diferencia de hideSystemBars() (que deja siempre visible la barra
     * de estado con el reloj/batería), esta oculta AMBAS barras: se usa
     * solo mientras un video está en modo pantalla completa, para que no
     * quede ninguna franja del sistema tapando el video.
     */
    private fun hideSystemBarsCompletely() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * Al empezar a scrollear la web, la barra de navegación inferior
     * desaparece para dejar la página en pantalla completa, como si fuera
     * una app nativa. El reloj y la batería (barra de estado) siguen
     * visibles todo el tiempo. Si el usuario vuelve al principio de la
     * página, la barra de navegación reaparece.
     *
     * De paso, acá se soluciona que un scroll hacia arriba dentro de la
     * página termine recargándola: SwipeRefreshLayout, si queda habilitado
     * todo el tiempo, confía en WebView.canScrollVertically() para saber
     * si un gesto hacia abajo es "pull to refresh" o scroll normal, y esa
     * detección no siempre es exacta con WebView (en gestos rápidos, o en
     * páginas con su propio scroll interno, a veces reporta que ya está
     * en el tope cuando en realidad no lo está). La solución confiable es
     * habilitar el pull-to-refresh SOLO cuando el scroll listener confirma
     * que scrollY es exactamente 0: en cualquier otro punto de la página,
     * un gesto hacia abajo es scroll común y no debe disparar un reload.
     */
    private fun setupFullScreenOnScroll() {
        binding.webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.swipeRefresh.isEnabled = scrollY == 0

            if (scrollY > 0) {
                hideSystemBars()
            } else {
                showSystemBars()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

        // Google bloquea el login OAuth cuando detecta, por el user-agent,
        // que la página corre dentro de un WebView "genérico" (el token
        // ";wv)" que Android agrega automáticamente) y muestra el error
        // "This browser or app may not be secure". Sacando ese token el
        // user-agent queda idéntico al de Chrome para Android normal, así
        // que Google (y cualquier otro sitio con la misma protección) deja
        // de tratarlo como un WebView embebido.
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        settings.userAgentString = settings.userAgentString + " WebVisor/1.0"

        // Puente para recibir imágenes que la propia web genera como blob:
        // (ej. el botón "Descargar" de Pinterest) y que no pueden bajarse
        // como una URL de red normal. Ver downloadBlobUrl().
        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidBlobDownloader")

        // Mantener presionada una imagen la descarga (para imágenes normales;
        // las que usan blob: se resuelven en downloadBlobUrl()).
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val imageUrl = when (result.type) {
                WebView.HitTestResult.IMAGE_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> result.extra
                else -> null
            }

            // Un link de texto normal (no una imagen): SRC_ANCHOR_TYPE.
            // result.extra es directamente la URL del href.
            val linkUrl = if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                result.extra
            } else {
                null
            }

            when {
                imageUrl != null -> {
                    webView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    downloadImage(imageUrl)
                    true
                }
                linkUrl != null -> {
                    webView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    copyLinkToClipboard(linkUrl)
                    true
                }
                else -> false
            }
        }

        // Compartir discreto: mantener presionado la franja inferior de la
        // pantalla (últimos ~56dp) comparte el link actual. Se limita a esa
        // franja a propósito para no interferir con la selección de texto
        // nativa del WebView en el resto del contenido.
        val shareStripHeightPx = (56 * resources.displayMetrics.density).toInt()
        val shareGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                shareCurrentUrl()
            }
        })

        // Zona donde puede ARRANCAR un gesto de pull-to-refresh (el ~30%
        // superior de la pantalla). Existen páginas (ej. Family Link) que
        // manejan su propio scroll interno con un <div> en vez de dejar
        // que se mueva el documento completo: para el WebView, esa página
        // "nunca se va de scrollY = 0", así que el chequeo de
        // "¿está arriba del todo?" que usa SwipeRefreshLayout por sí solo
        // no alcanza (queda habilitado todo el tiempo y cualquier gesto
        // hacia abajo, en cualquier parte de la pantalla, se malinterpreta
        // como pull-to-refresh). Restringir el punto de partida del gesto
        // resuelve ese caso: si el dedo arranca en la parte de abajo o del
        // medio de la pantalla —que es donde normalmente se apoya para
        // scrollear un feed—, el refresh queda desactivado para ese gesto
        // en particular, sin importar lo que reporte scrollY.
        val pullToRefreshZoneHeightPx = (resources.displayMetrics.heightPixels * 0.3f).toInt()

        webView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.y > pullToRefreshZoneHeightPx) {
                        binding.swipeRefresh.isEnabled = false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Al soltar, se recalcula según la posición real de scroll
                    // (ver setupFullScreenOnScroll), para no dejar el
                    // pull-to-refresh apagado para siempre tras un gesto que
                    // arrancó fuera de la zona permitida.
                    binding.swipeRefresh.isEnabled = binding.webView.scrollY == 0
                }
            }

            if (event.y >= view.height - shareStripHeightPx) {
                shareGestureDetector.onTouchEvent(event)
            }
            // Devolvemos false siempre para que el WebView reciba el evento
            // con normalidad (scroll, zoom, selección de texto, etc.).
            false
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.swipeRefresh.isRefreshing = true

                // "about:blank" es la URL interna que usa showHomeScreen() para
                // vaciar el WebView antes de mostrar la pantalla de inicio. Como
                // loadUrl() es asíncrono, este callback llega DESPUÉS de que
                // showHomeScreen() ya puso emptyState en VISIBLE — si acá lo
                // ocultáramos igual, la pantalla de inicio se ve un instante y
                // desaparece, dejando todo en blanco (swipeRefresh invisible +
                // emptyState oculto al mismo tiempo). Por eso el "about:blank"
                // no debe tocar emptyState; cualquier otra URL sí es una
                // navegación real y oculta la pantalla de inicio como antes.
                if (url != "about:blank") {
                    binding.emptyState.visibility = View.GONE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefresh.isRefreshing = false
                syncStatusBarColorWithPage()
                if (isFreshNavigation) {
                    view?.clearHistory()
                }


            /**
             * A diferencia de onPageStarted/onPageFinished (que solo
             * disparan con una carga de página completa), esto también se
             * entera cuando la URL cambia por History API (pushState /
             * replaceState) sin recargar nada — que es justamente cómo
             * Google Search, al ser una sola página, pasa al Modo IA
             * cuando el usuario toca ese botón sin salir de google.com.
             * Como esa transición no pasa por shouldOverrideUrlLoading (ya
             * "está" en la página, no es una navegación nueva), es la única
             * forma de agarrarla y cortarla.
             */
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val uri = url?.let { Uri.parse(it) } ?: return
                if (ContentGuard.isGoogleAiMode(uri)) {
                    binding.webView.stopLoading()
                    showBlockScreen(
                        getString(R.string.ai_mode_block_title),
                        getString(R.string.ai_mode_block_message),
                        R.color.manual_block_gray
                    )
                    if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        // OJO: no cargar aquí una URL de google.com "segura"
                        // como alternativa — loadUrl() directo tiene el
                        // mismo problema que el link inicial (no pasa por
                        // shouldOverrideUrlLoading), así que volvería a
                        // colarse sin control. Mejor una página neutra.
                        binding.webView.loadUrl("about:blank")
                    }
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                // No se ignoran errores SSL silenciosamente: se cancela la carga.
                handler.cancel()
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                // AdBlocker: corta banners, scripts y píxeles de tracking de
                // redes de publicidad/analítica conocidas antes de que
                // lleguen a descargarse. Nunca se aplica al frame principal
                // (ver AdBlocker.shouldBlock), solo a subrecursos.
                return AdBlocker.shouldBlock(request.url?.host, request.isForMainFrame)
                    ?: super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()

                // Esquemas que no son web (intent://, tel:, mailto:, whatsapp:, market://, etc.)
                // se delegan a la app nativa correspondiente del sistema.
                if (scheme != "http" && scheme != "https") {
                    return openWithExternalApp(uri)
                }

                val targetHost = uri.host

                // Bloqueo manual del buscador de Google (y, con mensaje
                // propio, del Modo IA específicamente): solo aplica a la
                // navegación PRINCIPAL, no a iframes de otras páginas (ej.
                // el widget de reCAPTCHA que corre bajo google.com/recaptcha/...
                // dentro de otro sitio) — por eso "request.isForMainFrame".
                if (request.isForMainFrame &&
                    (ContentGuard.isGoogleAiMode(uri) || isManuallyBlockedHost(targetHost))
                ) {
                    val isAiMode = ContentGuard.isGoogleAiMode(uri)
                    showBlockScreen(
                        getString(if (isAiMode) R.string.ai_mode_block_title else R.string.manual_block_title),
                        getString(if (isAiMode) R.string.ai_mode_block_message else R.string.manual_block_message),
                        R.color.manual_block_gray
                    )
                    return true
                }

                // ContentGuard: bloqueo duro de sitios para adultos.
                if (ContentGuard.isBlocked(targetHost)) {
                    showBlockScreen(
                        getString(R.string.content_block_title),
                        getString(R.string.content_block_message),
                        R.color.content_block_red
                    )
                    return true
                }

                // ContentGuard: si es un buscador o YouTube sin modo seguro
                // forzado, se reescribe la URL y se recarga con ese modo.
                val safeUri = ContentGuard.applySafeSearch(uri)
                if (safeUri != null) {
                    binding.webView.loadUrl(safeUri.toString())
                    return true
                }

                // Regla de Dominio Flexible: por defecto se permite navegar
                // libremente (YouTube, pagos, login, etc. incluidos). Ver DomainPolicy.
                return if (DomainPolicy.isNavigationAllowed(uri.toString(), originalHost)) {
                    false // false = "no lo intercepto", que lo cargue el propio WebView
                } else {
                    openWithExternalApp(uri)
                }
            }
        }

        // Sin esto, un <input type="file"> en la web (ej. subir el CV en un
        // Google Forms) no abre ningún selector: el WebView, a diferencia de
        // Chrome, no sabe manejarlo solo. onShowFileChooser delega la
        // elección al selector nativo de Android (galería, Drive, Archivos).
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            // Se dispara cuando la página pide pantalla completa para un
            // <video> (o para un reproductor tipo YouTube embed). El
            // WebView por sí solo no reserva pantalla para esto: hay que
            // sacar la vista que nos entrega y montarla a mano por encima
            // de todo (ver fullscreenContainer en activity_main.xml).
            override fun onShowCustomView(
                view: View?,
                callback: CustomViewCallback?
            ) {
                if (fullscreenVideoView != null || view == null) {
                    callback?.onCustomViewHidden()
                    return
                }

                fullscreenVideoView = view
                fullscreenVideoCallback = callback

                binding.fullscreenContainer.addView(
                    view,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.swipeRefresh.visibility = View.INVISIBLE

                // Pantalla completa real: se ocultan también el reloj/batería
                // (a diferencia del modo scroll normal, que deja la barra de
                // estado siempre visible) y se evita que la pantalla se
                // apague sola mientras se reproduce el video.
                hideSystemBarsCompletely()
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onHideCustomView() {
                val view = fullscreenVideoView ?: return

                binding.fullscreenContainer.removeView(view)
                binding.fullscreenContainer.visibility = View.GONE
                binding.swipeRefresh.visibility = View.VISIBLE

                fullscreenVideoView = null
                fullscreenVideoCallback = null

                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                // Al salir, se vuelve al esquema normal de barras: la de
                // estado siempre visible (a diferencia de durante el video,
                // que se ocultaba por completo) y la de navegación según la
                // posición actual del scroll.
                WindowInsetsControllerCompat(window, binding.root)
                    .show(WindowInsetsCompat.Type.statusBars())
                if (binding.webView.scrollY > 0) hideSystemBars() else showSystemBars()
            }
        }
    }

    /**
     * Pantalla de inicio (buscador Swisscows + marcadores). Se muestra al
     * abrir la app sin ningún link (ver handleIncomingIntent) y al volver
     * atrás desde la primera página de una navegación (ver onBackPressed),
     * en vez de cerrar la app de golpe.
     */
    private fun setupHomeScreen() {
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performHomeSearch()
                true
            } else {
                false
            }
        }

        binding.addBookmarkButton.setOnClickListener { showAddBookmarkDialog() }

        renderBookmarks()
    }

    private fun performHomeSearch() {
        val input = binding.searchInput.text?.toString().orEmpty()
        if (input.isBlank()) return

        val url = SearchEngine.resolveInput(input)
        binding.searchInput.text?.clear()
        hideKeyboard()
        loadUrlAndShowBrowser(url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    /**
     * Carga una URL (desde el buscador o un marcador) y pasa de la
     * pantalla de inicio al WebView, con los mismos controles de contenido
     * que cualquier otra navegación (ver handleIncomingIntent, que sigue el
     * mismo patrón para links externos).
     */
    private fun loadUrlAndShowBrowser(url: String) {
        val uri = Uri.parse(url)
        originalHost = uri.host
        binding.emptyState.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE

        when {
            ContentGuard.isGoogleAiMode(uri) -> showBlockScreen(
                getString(R.string.ai_mode_block_title),
                getString(R.string.ai_mode_block_message),
                R.color.manual_block_gray
            )
            isManuallyBlockedHost(uri.host) -> showBlockScreen(
                getString(R.string.manual_block_title),
                getString(R.string.manual_block_message),
                R.color.manual_block_gray
            )
            ContentGuard.isBlocked(uri.host) -> showBlockScreen(
                getString(R.string.content_block_title),
                getString(R.string.content_block_message),
                R.color.content_block_red
            )
            else -> {
                val safeUri = ContentGuard.applySafeSearch(uri)
                binding.webView.loadUrl((safeUri ?: uri).toString())
            }
        }
    }

    /**
     * Vuelve a la pantalla de inicio sin cerrar la app (ver onBackPressed).
     * A propósito no se intenta "conservar" la página anterior: sin barra
     * de direcciones ni pestañas, volver a inicio es un punto de partida
     * limpio, no un estado más para arrastrar.
     */
    private fun showHomeScreen() {
        binding.webView.loadUrl("about:blank")
        binding.swipeRefresh.visibility = View.INVISIBLE
        binding.emptyState.visibility = View.VISIBLE
        applyBarsColor(ContextCompat.getColor(this, R.color.empty_state_background))
    }

    /** Redibuja la lista de marcadores dentro de bookmarksContainer. */
    private fun renderBookmarks() {
        val container = binding.bookmarksContainer
        container.removeAllViews()

        val bookmarks = BookmarksStore.getAll(this)
        binding.bookmarksEmptyMessage.visibility =
            if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
        container.visibility = if (bookmarks.isEmpty()) View.GONE else View.VISIBLE

        val density = resources.displayMetrics.density
        val rowPadding = (16 * density).toInt()
        val iconSize = (22 * density).toInt()

        bookmarks.forEachIndexed { index, bookmark ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(rowPadding, rowPadding, rowPadding, rowPadding)
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(context, R.drawable.bg_bookmark_row)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val icon = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_link_24)
                layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize)
            }

            val textContainer = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = (12 * density).toInt() }
            }

            val titleView = android.widget.TextView(this).apply {
                text = bookmark.title
                setTextColor(ContextCompat.getColor(context, R.color.empty_state_text))
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val urlView = android.widget.TextView(this).apply {
                text = bookmark.url
                setTextColor(ContextCompat.getColor(context, R.color.home_text_secondary))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            textContainer.addView(titleView)
            textContainer.addView(urlView)
            row.addView(icon)
            row.addView(textContainer)

            row.setOnClickListener { loadUrlAndShowBrowser(bookmark.url) }
            row.setOnLongClickListener {
                confirmDeleteBookmark(bookmark)
                true
            }

            container.addView(row)

            if (index < bookmarks.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt()
                    ).apply {
                        marginStart = rowPadding
                        marginEnd = rowPadding
                    }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.home_text_secondary))
                    alpha = 0.15f
                }
                container.addView(divider)
            }
        }
    }

    /** Diálogo para cargar un nuevo marcador a mano (nombre + dirección web). */
    private fun showAddBookmarkDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_bookmark, null)
        val nameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.bookmarkNameInput
        )
        val urlInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.bookmarkUrlInput
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.home_add_bookmark_title)
            .setView(dialogView)
            .setPositiveButton(R.string.home_add_bookmark_save) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val rawUrl = urlInput.text?.toString()?.trim().orEmpty()
                if (rawUrl.isEmpty()) return@setPositiveButton

                val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                    rawUrl
                } else {
                    "https://$rawUrl"
                }
                val title = name.ifEmpty { Uri.parse(url).host ?: url }

                BookmarksStore.add(this, title, url)
                renderBookmarks()
            }
            .setNegativeButton(R.string.home_add_bookmark_cancel, null)
            .show()
    }

    private fun confirmDeleteBookmark(bookmark: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle(R.string.home_delete_bookmark_title)
            .setMessage(getString(R.string.home_delete_bookmark_message, bookmark.title))
            .setPositiveButton(R.string.home_delete_bookmark_confirm) { _, _ ->
                BookmarksStore.remove(this, bookmark)
                renderBookmarks()
            }
            .setNegativeButton(R.string.home_add_bookmark_cancel, null)
            .show()
    }

    /** Conecta el botón "Volver" del overlay de bloqueo. */
    private fun setupBlockOverlay() {
        binding.blockCancelButton.setOnClickListener {
            hideBlockOverlay()
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            }
        }
    }

    /**
     * Muestra la pantalla de bloqueo con un título, mensaje y color de
     * fondo dados (rojo para NSFW, gris discreto para el bloqueo manual
     * de Google, etc.).
     */
    private fun showBlockScreen(title: String, message: String, backgroundColorRes: Int) {
        binding.blockOverlay.setBackgroundColor(ContextCompat.getColor(this, backgroundColorRes))
        binding.blockTitle.text = title
        binding.blockMessage.text = message
        binding.blockOverlay.visibility = View.VISIBLE
    }

    private fun hideBlockOverlay() {
        binding.blockOverlay.visibility = View.GONE
    }

    /**
     * Registra un DownloadListener en el WebView: cualquier archivo que la
     * página quiera descargar (PDF, imágenes, adjuntos, etc.) se manda al
     * DownloadManager del sistema, que se encarga de bajarlo a la carpeta
     * "Descargas" del dispositivo y mostrar el progreso/notificación.
     */
    private fun setupDownloads() {
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

                // Un blob: no es una URL de red real (es un archivo generado
                // en memoria por JS, típico de botones "Descargar" propios
                // de la web como el de Pinterest): no lo puede bajar el
                // DownloadManager, hay que resolverlo dentro del WebView.
                if (url.startsWith("blob:")) {
                    downloadBlobUrl(url, fileName)
                    return@setDownloadListener
                }
                val cookies = CookieManager.getInstance().getCookie(url)

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(
                        mimeType.takeIf { it.isNotBlank() }
                            ?: MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(
                                    MimeTypeMap.getFileExtensionFromUrl(url)
                                )
                            ?: "application/octet-stream"
                    )
                    if (!cookies.isNullOrEmpty()) {
                        addRequestHeader("cookie", cookies)
                    }
                    addRequestHeader("User-Agent", userAgent)
                    setDescription(getString(R.string.app_name))
                    setTitle(fileName)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                val downloadManager =
                    getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)

                Toast.makeText(
                    this,
                    getString(R.string.downloading_toast, fileName),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.download_error),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Descarga una imagen detectada por long-press (ver setupWebView). */
    /**
     * Copia al portapapeles la URL de un link mantenido presionado (ver
     * setupWebView). A diferencia de shareCurrentUrl() (que comparte la
     * página actual, con el gesto en la franja inferior), esto copia el
     * destino de un link puntual dentro del contenido, para pegarlo donde
     * el usuario quiera sin necesariamente abrirlo.
     */
    private fun copyLinkToClipboard(linkUrl: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), linkUrl))
        Toast.makeText(this, getString(R.string.link_copied_toast), Toast.LENGTH_SHORT).show()
    }

    /** Descarga una imagen detectada por long-press (ver setupWebView). */
    private fun downloadImage(imageUrl: String) {
        try {
            val mimeType =
                MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(imageUrl))
                    ?: "image/jpeg"

            val fileName = URLUtil.guessFileName(imageUrl, null, mimeType)
            val cookies = CookieManager.getInstance().getCookie(imageUrl)

            val request = DownloadManager.Request(Uri.parse(imageUrl)).apply {
                setMimeType(mimeType)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("cookie", cookies)
                }
                setDescription(getString(R.string.app_name))
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(this, getString(R.string.downloading_toast, fileName), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_error), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Le pide al propio WebView (en JS) que resuelva el blob: en base64 y
     * nos lo devuelva por BlobDownloadBridge, ya que un blob: solo existe
     * en memoria dentro de esa página y Kotlin no puede leerlo directo.
     */
    private fun downloadBlobUrl(blobUrl: String, suggestedFileName: String) {
        val safeFileName = suggestedFileName.replace("\"", "").replace("\\", "")
        val script = """
            (function() {
                fetch("$blobUrl")
                    .then(function(res) { return res.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            var base64 = reader.result.split(',')[1];
                            AndroidBlobDownloader.receiveBlob(base64, "$safeFileName");
                        };
                        reader.readAsDataURL(blob);
                    });
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script, null)
    }

    /** Decodifica el base64 recibido desde JS y lo guarda en Descargas. */
    private fun saveBase64File(base64Data: String, fileName: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val mimeType =
                MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(fileName))
                    ?: "image/jpeg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                java.io.File(downloadsDir, fileName).outputStream().use { it.write(bytes) }
            }

            Toast.makeText(
                this,
                getString(R.string.downloading_toast, fileName),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.download_error), Toast.LENGTH_SHORT).show()
        }
    }

    /** Puente expuesto al JavaScript de la página (ver downloadBlobUrl). */
    private inner class BlobDownloadBridge {
        @JavascriptInterface
        fun receiveBlob(base64Data: String, fileName: String) {
            runOnUiThread { saveBase64File(base64Data, fileName) }
        }
    }

    private fun openWithExternalApp(uri: Uri): Boolean {
        return try {
            val externalIntent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(externalIntent)
            true
        } catch (e: ActivityNotFoundException) {
            // No hay app instalada que maneje ese esquema; evitamos crash.
            true
        }
    }

    /**
     * Comparte la URL actualmente cargada usando el selector nativo de
     * Android (WhatsApp, Email, copiar al portapapeles, etc.). Se activa
     * con un long-press en la franja inferior de la pantalla; como única
     * confirmación visible se usa una vibración breve, sin ícono ni botón.
     */
    private fun shareCurrentUrl() {
        val url = binding.webView.url
        if (url.isNullOrEmpty()) return

        binding.webView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, null))
    }

    /**
     * Extrae la URL de un Deep Link (QR escaneado, link compartido desde
     * WhatsApp/Email/Galería, etc.) y la carga en el WebView.
     */
    private fun handleIncomingIntent(intent: Intent) {
        val data: Uri? = intent.data

        if (data != null && (data.scheme == "http" || data.scheme == "https")) {
            originalHost = data.host
            binding.emptyState.visibility = View.GONE

            // Antes, este link se cargaba directo con loadUrl() sin pasar
            // por ninguno de los controles de contenido: esos viven en
            // shouldOverrideUrlLoading, que el WebView NO dispara para la
            // primera URL que carga la app (solo para navegaciones
            // posteriores hechas dentro de la página). Un link o QR que
            // apuntara directo a un sitio bloqueado, o al Modo IA de
            // Google, se colaba entero. Por eso acá se repiten los mismos
            // chequeos antes de cargar cualquier cosa.
            when {
                ContentGuard.isGoogleAiMode(data) -> showBlockScreen(
                    getString(R.string.ai_mode_block_title),
                    getString(R.string.ai_mode_block_message),
                    R.color.manual_block_gray
                )
                isManuallyBlockedHost(data.host) -> showBlockScreen(
                    getString(R.string.manual_block_title),
                    getString(R.string.manual_block_message),
                    R.color.manual_block_gray
                )
                ContentGuard.isBlocked(data.host) -> showBlockScreen(
                    getString(R.string.content_block_title),
                    getString(R.string.content_block_message),
                    R.color.content_block_red
                )
                else -> {
                    val safeUri = ContentGuard.applySafeSearch(data)
                    binding.webView.loadUrl((safeUri ?: data).toString())
                }
            }
        } else if (binding.webView.url.isNullOrEmpty()) {
            // La app se abrió desde el ícono, sin ningún enlace: mostramos
            // la pantalla de inicio (buscador + marcadores) en lugar de una
            // web en blanco.
            showHomeScreen()
        }
    }

    override fun onBackPressed() {
        if (fullscreenVideoView != null) {
            // Le avisamos a la web que salió del modo pantalla completa;
            // eso dispara onHideCustomView() y hace la limpieza de la UI.
            fullscreenVideoCallback?.onCustomViewHidden()
        } else if (binding.blockOverlay.visibility == View.VISIBLE) {
            hideBlockOverlay()
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else if (binding.emptyState.visibility != View.VISIBLE) {
            // Se llegó al principio de la navegación (sin más historial):
            // en vez de cerrar la app de una, se vuelve a la pantalla de
            // inicio con el buscador y los marcadores. Solo un segundo
            // "atrás" desde ahí cierra la app de verdad.
            showHomeScreen()
        } else {
            super.onBackPressed()
        }
    }
}
