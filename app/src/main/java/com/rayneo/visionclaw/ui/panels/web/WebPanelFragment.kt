package com.rayneo.visionclaw.ui.panels.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.rayneo.visionclaw.MainActivity
import com.rayneo.visionclaw.R
import com.rayneo.visionclaw.ui.MainViewModel
import com.rayneo.visionclaw.ui.panels.PanelContracts
import com.rayneo.visionclaw.ui.panels.TrackpadPanel
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Edge-to-edge browser panel with no tabs/settings overlays.
 * Maintains a lightweight 3-URL recent stack for fast return.
 */
class WebPanelFragment : Fragment(), PanelContracts.WebPanel, TrackpadPanel {

    companion object {
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_RECENT_URLS = "recent_urls"
        private const val SCROLL_DEADZONE = 1.0f
        private const val SCROLL_PIXELS_PER_DELTA = 26f
        private const val AUTO_RETURN_TO_CHAT_MS = 1400L
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val viewModel: MainViewModel by activityViewModels()

    private var webContainer: FrameLayout? = null
    private var webLoadingSpinner: ProgressBar? = null
    private var speakPrompt: TextView? = null
    private var webView: WebView? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val recentUrlStack = ArrayDeque<String>()
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private val autoReturnRunnable = Runnable {
        if (!isAdded) return@Runnable
        if (isBlankUrl(currentUrl)) {
            viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_web_panel, container, false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webContainer = view.findViewById(R.id.webContainer)
        webLoadingSpinner = view.findViewById(R.id.webLoadingSpinner)
        speakPrompt = view.findViewById(R.id.speakPrompt)

        setupWebView()
        observeViewModel()

        restoreRecentStack(savedInstanceState)
        currentUrl = savedInstanceState?.getString(KEY_LAST_URL).orEmpty().trim()
        if (!isBlankUrl(currentUrl)) {
            loadUrl(currentUrl)
        } else {
            ensureContentForEntry()
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveSystemUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_LAST_URL, currentUrl)
        outState.putStringArrayList(KEY_RECENT_URLS, ArrayList(recentUrlStack))
    }

    override fun onDestroyView() {
        uiHandler.removeCallbacks(autoReturnRunnable)
        webView?.stopLoading()
        webView?.webChromeClient = WebChromeClient()
        webView?.webViewClient = WebViewClient()
        webView?.destroy()
        webView = null
        webContainer?.removeAllViews()
        webContainer = null
        webLoadingSpinner = null
        speakPrompt = null
        super.onDestroyView()
    }

    private fun applyImmersiveSystemUi() {
        val decor = activity?.window?.decorView ?: return
        decor.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val container = webContainer ?: return
        val created = WebView(requireContext())
        val settings = created.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = MOBILE_UA
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.textZoom = 100

        created.setBackgroundColor(0xFF000000.toInt())
        created.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        created.overScrollMode = View.OVER_SCROLL_NEVER
        created.isVerticalScrollBarEnabled = false
        created.isHorizontalScrollBarEnabled = false

        created.webChromeClient =
            object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    currentTitle = title.orEmpty()
                }
            }

        created.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val requested = request.url?.toString().orEmpty()
                    val normalized = normalizeNavigableUrl(requested) ?: return true
                    view.loadUrl(normalized)
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val normalized = normalizeNavigableUrl(url) ?: return true
                    view.loadUrl(normalized)
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    currentUrl = url
                    rememberRecentUrl(url)
                    setLoadingState(true)
                    hideSpeakPrompt()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url
                    rememberRecentUrl(url)
                    setLoadingState(false)
                    lockViewportAndStability()
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame) {
                        setLoadingState(false)
                        (activity as? MainActivity)?.showMirroredNotice("Page load failed")
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        setLoadingState(false)
                        (activity as? MainActivity)?.showMirroredNotice("Page load failed")
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    setLoadingState(false)
                    (activity as? MainActivity)?.showMirroredNotice("Browser restarted")
                    container.removeAllViews()
                    webView?.destroy()
                    webView = null
                    setupWebView()
                    val resumeUrl = recentUrlStack.lastOrNull()
                    if (!resumeUrl.isNullOrBlank()) {
                        loadUrl(resumeUrl)
                    } else {
                        currentUrl = ""
                        showSpeakPrompt()
                    }
                    return true
                }
            }

        container.removeAllViews()
        container.addView(
            created,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView = created
    }

    private fun observeViewModel() {
        viewModel.webNavigationUrl.observe(viewLifecycleOwner) { url ->
            if (url.isNullOrBlank()) return@observe
            loadUrl(url)
            viewModel.clearWebNavigation()
        }
    }

    private fun setLoadingState(loading: Boolean) {
        webLoadingSpinner?.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun lockViewportAndStability() {
        webView?.evaluateJavascript(
            """
            (function() {
              try {
                var d = document;
                var h = d.head || d.getElementsByTagName('head')[0];
                if (h) {
                  var m = d.querySelector('meta[name=viewport]');
                  if (!m) {
                    m = d.createElement('meta');
                    m.setAttribute('name', 'viewport');
                    h.appendChild(m);
                  }
                  m.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no');
                }
                if (d.documentElement) {
                  d.documentElement.style.overflowX = 'hidden';
                  d.documentElement.style.backgroundColor = '#000000';
                }
                if (d.body) {
                  d.body.style.overflowX = 'hidden';
                  d.body.style.backgroundColor = '#000000';
                }
                if (d.activeElement && d.activeElement.blur) {
                  d.activeElement.blur();
                }
              } catch (e) {}
            })();
            """.trimIndent(),
            null
        )
    }

    fun ensureContentForEntry() {
        val pendingNavigation = viewModel.webNavigationUrl.value?.trim().orEmpty()
        if (pendingNavigation.isNotBlank()) {
            hideSpeakPrompt()
            return
        }

        if (!isBlankUrl(currentUrl)) {
            hideSpeakPrompt()
            return
        }

        val recent = recentUrlStack.lastOrNull()
        if (!recent.isNullOrBlank()) {
            loadUrl(recent)
            return
        }

        showSpeakPrompt()
        uiHandler.removeCallbacks(autoReturnRunnable)
        uiHandler.postDelayed(autoReturnRunnable, AUTO_RETURN_TO_CHAT_MS)
    }

    override fun onTrackpadPan(deltaX: Float, deltaY: Float): Boolean {
        if (abs(deltaY) <= SCROLL_DEADZONE && abs(deltaX) <= SCROLL_DEADZONE) return true
        val scrollY = (-deltaY * SCROLL_PIXELS_PER_DELTA).toInt()
        val scrollX = (-deltaX * (SCROLL_PIXELS_PER_DELTA * 0.35f)).toInt()
        webView?.scrollBy(scrollX, scrollY)
        return true
    }

    override fun onTrackpadScroll(deltaY: Float): Boolean = onTrackpadPan(0f, deltaY)

    override fun onTrackpadSelect(): Boolean = false

    override fun onTrackpadDoubleTap(): Boolean = false

    override fun onTextInputFromHold(text: String): Boolean = false

    override fun onHeadYaw(yawDegrees: Float) {
        view?.translationX = 0f
    }

    override fun getReadableText(): String {
        val title = currentTitle.ifBlank { "Web page" }
        val url = currentUrl.ifBlank { "No URL" }
        return "Web content. Title: $title. URL: $url"
    }

    override fun loadUrl(url: String) {
        val normalized = normalizeNavigableUrl(url)
        // URLPipe diagnostic: see what the WebPanel was handed and what it
        // normalized to. If `normalized == null` we drop it (rare, but
        // important to know — usually means URL had an unsupported scheme).
        // If normalized differs from the input the normalizer rescued it
        // (added scheme, stripped trailing punct, etc.), which is a strong
        // signal the upstream extractor / launcher didn't deliver a clean URL.
        if (normalized == null) {
            android.util.Log.w("WebPanelFragment", "URLPipe/webview DROPPED url='$url' (normalize returned null)")
            return
        }
        if (normalized != url) {
            android.util.Log.d(
                "WebPanelFragment",
                "URLPipe/webview RESCUED input='$url' → normalized='$normalized'"
            )
        } else {
            android.util.Log.d("WebPanelFragment", "URLPipe/webview url='$normalized'")
        }
        uiHandler.removeCallbacks(autoReturnRunnable)
        currentUrl = normalized
        rememberRecentUrl(normalized)
        setLoadingState(true)
        hideSpeakPrompt()
        webView?.loadUrl(normalized)
    }

    override fun goBack(): Boolean {
        val web = webView ?: return false
        if (!web.canGoBack()) return false
        web.goBack()
        return true
    }

    override fun goForward(): Boolean {
        val web = webView ?: return false
        if (!web.canGoForward()) return false
        web.goForward()
        return true
    }

    override fun reload() {
        if (isBlankUrl(currentUrl)) {
            ensureContentForEntry()
            return
        }
        webView?.reload()
    }

    override fun getCurrentUrl(): String? = currentUrl.ifBlank { null }

    override fun getTitle(): String? = currentTitle

    private fun restoreRecentStack(savedInstanceState: Bundle?) {
        recentUrlStack.clear()
        val restored = savedInstanceState?.getStringArrayList(KEY_RECENT_URLS).orEmpty()
        restored.forEach { url ->
            normalizeNavigableUrl(url)?.let { rememberRecentUrl(it) }
        }
    }

    private fun rememberRecentUrl(url: String) {
        val normalized = normalizeNavigableUrl(url) ?: return
        recentUrlStack.remove(normalized)
        recentUrlStack.addLast(normalized)
        while (recentUrlStack.size > 3) {
            recentUrlStack.removeFirst()
        }
    }

    private fun showSpeakPrompt() {
        speakPrompt?.visibility = View.VISIBLE
    }

    private fun hideSpeakPrompt() {
        speakPrompt?.visibility = View.GONE
    }

    private fun isBlankUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        return value.isBlank() || value.equals("about:blank", ignoreCase = true)
    }

    private fun normalizeNavigableUrl(input: String): String? {
        var candidate = stripTrailingUrlPunctuation(input.trim())
        if (candidate.isBlank()) return null

        if (candidate.startsWith("intent://", ignoreCase = true)) {
            val fallback = extractIntentFallbackUrl(candidate) ?: return null
            candidate = stripTrailingUrlPunctuation(fallback)
        }

        if (!candidate.contains("://")) {
            candidate =
                if (URLUtil.isValidUrl("https://$candidate") && candidate.contains(".")) {
                    "https://$candidate"
                } else {
                    "https://www.google.com/search?q=${Uri.encode(candidate)}"
                }
        }

        val parsed = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return null
        return parsed.toString()
    }

    private fun extractIntentFallbackUrl(intentUrl: String): String? {
        return runCatching {
            val intent = android.content.Intent.parseUri(intentUrl, android.content.Intent.URI_INTENT_SCHEME)
            intent.getStringExtra("browser_fallback_url")
                ?: intent.getStringExtra("S.browser_fallback_url")
                ?: intent.dataString
        }.getOrNull()
    }

    private fun stripTrailingUrlPunctuation(value: String): String {
        return value.trimEnd { ch ->
            ch == '.' || ch == ',' || ch == ';' || ch == ':' ||
                ch == '!' || ch == '?' || ch == ')' || ch == ']' ||
                ch == '}' || ch == '>' || ch == '"' || ch == '\''
        }
    }
}
