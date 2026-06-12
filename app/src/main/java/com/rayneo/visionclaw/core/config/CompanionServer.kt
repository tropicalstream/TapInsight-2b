package com.rayneo.visionclaw.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rayneo.visionclaw.BuildConfig
import com.rayneo.visionclaw.core.network.ActiveNetworkHttp
import com.rayneo.visionclaw.core.network.GoogleAirQualityClient
import com.rayneo.visionclaw.core.network.GoogleCalendarClient
import com.rayneo.visionclaw.core.network.GoogleDirectionsClient
import com.rayneo.visionclaw.core.network.GoogleOAuthManager
import com.rayneo.visionclaw.core.network.OpenClawPairingClient
import com.rayneo.visionclaw.core.network.GooglePlacesClient
import com.rayneo.visionclaw.core.network.GoogleTasksClient
import com.rayneo.visionclaw.core.network.RelayUrlHelper
import com.rayneo.visionclaw.core.network.ResearchRouter
import com.TapLink.app.media.MediaLibraryService
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.notifications.NotificationCenter
import com.rayneo.visionclaw.core.storage.AppPreferences
import com.rayneo.visionclaw.core.storage.OrbImageStore
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Lightweight HTTPS server that serves the TapInsight companion configuration pages.
 * Uses a self-signed TLS certificate generated via Android KeyStore to enable
 * secure context in browsers (required for Geolocation API / Phone GPS Bridge).
 *
 * Open from any phone/computer on the same WiFi:
 *   https://<glasses-ip>:19110
 * (Accept the self-signed certificate warning on first visit.)
 *
 * Pages:
 *   GET  /                → Setup page (API keys, model, personality)
 *   GET  /browser          → Browser settings + login sync
 *   GET  /dashboard        → Dashboard editor (categories & links)
 *   GET  /radio            → TapRadio station manager
 *
 * API:
 *   GET  /api/config      → all config as JSON
 *   POST /api/config      → save config from JSON body
 *   GET  /api/dashboard   → dashboard links/groups as JSON
 *   POST /api/dashboard   → save dashboard links/groups
 *   GET  /api/radio       → TapRadio stations as JSON array
 *   POST /api/radio       → save TapRadio stations
 */
class CompanionServer(
    private val context: Context,
    port: Int = 19110,
    var oauthManager: GoogleOAuthManager? = null,
    /** Provides the latest device GPS location for the Location test button. */
    var locationProvider: (() -> com.rayneo.visionclaw.core.model.DeviceLocationContext?)? = null,
    var calendarSummaryProvider: (() -> String)? = null,
    var tasksSummaryProvider: (() -> String)? = null,
    var newsSummaryProvider: (() -> String)? = null,
    var airQualityTextProvider: (() -> String?)? = null,
    var airQualityValueProvider: (() -> Int?)? = null,
    var phoneLocationConsumer: ((DeviceLocationContext?) -> Unit)? = null,
    /** Provides the latest camera frame as raw JPEG bytes for the /api/camera/frame endpoint. */
    var cameraFrameProvider: (() -> ByteArray?)? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "CompanionServer"
        private const val PREFS_NAME = "visionclaw_prefs"
        private const val DASHBOARD_PREFS_KEY = "dashboard_data"
        private const val SESSION_TOKEN_KEY = "companion_session_token"

        /** JS bridge for the Setup page (index.html). */
        private const val SETUP_BRIDGE_JS = """
// REST API bridge (replaces Android JavascriptInterface for phone/computer access)
const AiTapBridge = {
  _cache: {},
  _dirty: {},
  _isRestShim: true,
  _token: '__SESSION_TOKEN__',
  _headers() { return {'Content-Type': 'application/json', 'X-Session-Token': this._token}; },
  async _loadAll() {
    try {
      const r = await fetch('/api/config', {headers: {'X-Session-Token': this._token}});
      if (r.ok) this._cache = await r.json();
    } catch(e) { console.error('Load failed:', e); }
  },
  getString(key) { return this._cache[key] == null ? '' : String(this._cache[key]); },
  putString(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putFloat(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putBoolean(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putInt(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  async applyConfig() {
    try {
      const payload = {};
      for (const k of Object.keys(this._dirty)) { payload[k] = this._cache[k]; }
      const r = await fetch('/api/config', {
        method: 'POST',
        headers: this._headers(),
        body: JSON.stringify(payload)
      });
      if (r.ok) this._dirty = {};
      return r.ok;
    } catch(e) { console.error('Save failed:', e); return false; }
  }
};

"""

        /** JS bridge for the Browser page (browser.html). */
        private const val BROWSER_BRIDGE_JS = """
// REST API bridge for browser settings page
const AiTapBridge = {
  _cache: {},
  _dirty: {},
  _isRestShim: true,
  _token: '__SESSION_TOKEN__',
  _headers() { return {'Content-Type': 'application/json', 'X-Session-Token': this._token}; },
  async _loadAll() {
    try {
      const r = await fetch('/api/config', {headers: {'X-Session-Token': this._token}});
      if (r.ok) this._cache = await r.json();
    } catch(e) { console.error('Load failed:', e); }
  },
  getString(key) { return this._cache[key] == null ? '' : String(this._cache[key]); },
  putString(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putFloat(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putBoolean(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putInt(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  async applyConfig() {
    try {
      const payload = {};
      for (const k of Object.keys(this._dirty)) { payload[k] = this._cache[k]; }
      const r = await fetch('/api/config', {
        method: 'POST',
        headers: this._headers(),
        body: JSON.stringify(payload)
      });
      if (r.ok) this._dirty = {};
      return r.ok;
    } catch(e) { console.error('Save failed:', e); return false; }
  }
};

function hasBridge() { return true; }

async function loadAll() {
  await AiTapBridge._loadAll();
  for (const [id, cfg] of Object.entries(BROWSER_FIELDS)) {
    const el = document.getElementById(id);
    try { el.value = AiTapBridge.getString(cfg.key) || ''; } catch(e) {}
  }
  try {
    const raw = AiTapBridge.getString('browser_cookies');
    if (raw) {
      const parsed = JSON.parse(raw);
      cookieEntries = parsed.map((e, i) => ({ ...e, id: i }));
      nextCookieId = cookieEntries.length;
    }
  } catch(e) { console.error('Cookie load error:', e); }
  renderCookieList();
  showStatus('Settings loaded.', 'ok');
}

async function saveAll() {
  for (const [id, cfg] of Object.entries(BROWSER_FIELDS)) {
    const el = document.getElementById(id);
    const v = el.value.trim();
    if (cfg.type === 'float') AiTapBridge.putFloat(cfg.key, parseFloat(v) || 1.0);
    else if (cfg.type === 'bool') AiTapBridge.putBoolean(cfg.key, v === 'true');
    else AiTapBridge.putString(cfg.key, v);
  }
  document.querySelectorAll('.cookie-entry').forEach(el => {
    const id = parseInt(el.dataset.id);
    const entry = cookieEntries.find(e => e.id === id);
    if (entry) {
      entry.domain = el.querySelector('input[type="text"]').value.trim();
      entry.cookies = el.querySelector('textarea').value.trim();
    }
  });
  const cookieData = cookieEntries
    .filter(e => e.domain)
    .map(e => ({ domain: e.domain, cookies: e.cookies, label: e.label || '' }));
  AiTapBridge.putString('browser_cookies', JSON.stringify(cookieData));
  const ok = await AiTapBridge.applyConfig();
  showStatus(ok ? 'Settings saved! Cookies will sync on next browser launch.' : 'Failed to save.', ok ? 'ok' : 'err');
}

document.addEventListener('DOMContentLoaded', loadAll);
"""
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appPreferences = AppPreferences(context)

    /** Whether HTTPS was successfully configured. When true, the server serves
     *  HTTPS on port 19110 and the Geolocation API works (secure context). */
    var httpsEnabled: Boolean = false
        private set

    init {
        setupHttps()
    }

    /**
     * Generates a self-signed TLS certificate and configures NanoHTTPD to serve HTTPS.
     * Must be called before start().
     *
     * Uses a standard PKCS12 keystore (NOT Android KeyStore) so the private key is
     * accessible to NanoHTTPD's SSLServerSocketFactory for TLS handshakes.
     * The keystore is persisted in the app's private files dir so the certificate
     * stays stable across restarts (users only accept the cert warning once).
     *
     * On success: port 19110 serves HTTPS, `window.isSecureContext === true` in browsers,
     *   enabling the Geolocation API for the Phone GPS Bridge.
     * On failure: server falls back to plain HTTP (GPS bridge won't work but everything else does).
     */
    private fun setupHttps() {
        try {
            val ksFile = File(context.filesDir, "companion_tls.p12")
            val password = "tapinsight-tls".toCharArray()

            // Migration: delete old keystore if cert uses RSA (too slow for TLS on
            // the X3 Pro — causes audio stutters) or has validity > 398 days.
            if (ksFile.exists()) {
                try {
                    val tmpKs = KeyStore.getInstance("PKCS12")
                    ksFile.inputStream().use { tmpKs.load(it, password) }
                    val cert = tmpKs.getCertificate("companion") as? X509Certificate
                    if (cert != null) {
                        val validityDays = (cert.notAfter.time - cert.notBefore.time) / (24 * 3600 * 1000L)
                        val isRsa = cert.publicKey.algorithm == "RSA"
                        if (validityDays > 398 || isRsa) {
                            ksFile.delete()
                            Log.i(TAG, "Deleted old TLS keystore (RSA=$isRsa, validity=${validityDays}d)")
                        }
                    }
                } catch (e: Exception) {
                    ksFile.delete()
                    Log.w(TAG, "Deleted unreadable TLS keystore, will regenerate", e)
                }
            }

            val ks: KeyStore
            if (ksFile.exists()) {
                // Load existing keystore (validity already verified above)
                ks = KeyStore.getInstance("PKCS12")
                ksFile.inputStream().use { ks.load(it, password) }
                Log.d(TAG, "Loaded existing TLS keystore from ${ksFile.name}")
            } else {
                // Generate new EC key pair (P-256) — ECDSA TLS handshakes are 10-20x
                // faster than RSA 2048, critical for avoiding audio stutters when the
                // phone companion page sends frequent GPS HTTPS updates.
                val kpg = KeyPairGenerator.getInstance("EC")
                kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
                val keyPair = kpg.generateKeyPair()

                // Build self-signed X.509 certificate via DER encoding
                val cert = buildSelfSignedCertificate(keyPair)

                // Store in PKCS12 keystore
                ks = KeyStore.getInstance("PKCS12")
                ks.load(null, password)
                ks.setKeyEntry("companion", keyPair.private, password, arrayOf(cert))

                // Persist to disk so cert is stable across restarts
                ksFile.outputStream().use { ks.store(it, password) }
                Log.i(TAG, "Generated new self-signed TLS certificate for companion HTTPS")
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, password)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            makeSecure(sslContext.serverSocketFactory, null)
            httpsEnabled = true
            Log.i(TAG, "HTTPS enabled on companion server (port 19110)")
        } catch (e: Exception) {
            Log.e(TAG, "HTTPS setup failed — falling back to HTTP. GPS bridge will not work.", e)
            httpsEnabled = false
        }
    }

    // ── Self-signed certificate generation via raw DER encoding ──────────

    /**
     * Builds a minimal self-signed X.509v3 certificate using only standard Java APIs
     * (no BouncyCastle, no Android KeyStore). The certificate is valid for 397 days
     * (under the 398-day browser maximum) with CN=TapInsight Companion, signed with SHA256withECDSA.
     * Uses ECDSA P-256 instead of RSA for ~10-20x faster TLS handshakes.
     */
    private fun buildSelfSignedCertificate(keyPair: java.security.KeyPair): X509Certificate {
        // SHA256withECDSA OID: 1.2.840.10045.4.3.2
        val sha256WithEcdsaOid = byteArrayOf(
            0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02
        )
        // ECDSA AlgorithmIdentifier has no parameters (unlike RSA which has NULL)
        val signAlgId = derSequence(derOid(sha256WithEcdsaOid))

        // Subject/Issuer: CN=TapInsight Companion
        val cnOid = byteArrayOf(0x55, 0x04, 0x03) // OID 2.5.4.3
        val cnAttr = derSequence(derOid(cnOid), derUtf8String("TapInsight Companion"))
        val rdnSet = derSet(cnAttr)
        val name = derSequence(rdnSet)

        // Validity: now → +397 days (browsers reject certs valid > 398 days)
        val now = Date()
        val expiry = Date(System.currentTimeMillis() + 397L * 24 * 3600 * 1000)
        val validity = derSequence(derUtcTime(now), derUtcTime(expiry))

        // Version: v3 (integer value 2)
        val version = derExplicit(0, derInteger(BigInteger.valueOf(2)))

        // Serial number: current timestamp
        val serial = derInteger(BigInteger.valueOf(System.currentTimeMillis()))

        // SubjectPublicKeyInfo: already DER-encoded by Java
        val spki = keyPair.public.encoded

        // Assemble TBSCertificate
        val tbsCert = derSequence(version, serial, signAlgId, name, validity, name, spki)

        // Sign the TBS certificate
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(tbsCert)
        val signatureBytes = signer.sign()

        // Assemble full Certificate
        val certDer = derSequence(tbsCert, signAlgId, derBitString(signatureBytes))

        // Parse DER → X509Certificate
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    // ── DER encoding primitives ──────────────────────────────────────────

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derSequence(vararg elements: ByteArray): ByteArray {
        val body = elements.fold(ByteArray(0)) { acc, e -> acc + e }
        return derTag(0x30, body)
    }

    private fun derSet(vararg elements: ByteArray): ByteArray {
        val body = elements.fold(ByteArray(0)) { acc, e -> acc + e }
        return derTag(0x31, body)
    }

    private fun derInteger(value: BigInteger): ByteArray =
        derTag(0x02, value.toByteArray())

    private fun derBitString(bytes: ByteArray): ByteArray =
        derTag(0x03, byteArrayOf(0x00) + bytes) // 0 unused bits

    private fun derOid(oid: ByteArray): ByteArray =
        derTag(0x06, oid)

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derUtf8String(s: String): ByteArray =
        derTag(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun derExplicit(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf((0xA0 or tag).toByte()) + derLength(content.size) + content

    private fun derUtcTime(date: Date): ByteArray {
        val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return derTag(0x17, sdf.format(date).toByteArray(Charsets.US_ASCII))
    }

    /**
     * Media library service — serves the in-browser playlist player.
     * Created lazily; bootstrap (default folders + README) runs on first access.
     */
    private val mediaLibrary: MediaLibraryService by lazy {
        MediaLibraryService(context).also { it.ensureBootstrap() }
    }

    /** Session token for authenticating companion page API requests. */
    val sessionToken: String
        get() {
            val existing = prefs.getString(SESSION_TOKEN_KEY, null)
            if (!existing.isNullOrBlank()) return existing
            val token = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(SESSION_TOKEN_KEY, token).commit()
            return token
        }

    /** Validates the session token on API requests. HTML pages are served without auth
     *  (they embed the token as a cookie/header for subsequent API calls). */
    private fun isAuthorizedApiRequest(session: IHTTPSession): Boolean {
        // Check Authorization header first: "Bearer <token>"
        val authHeader = session.headers?.get("authorization") ?: ""
        if (authHeader.equals("Bearer $sessionToken", ignoreCase = true)) return true
        // Check X-Session-Token header
        val tokenHeader = session.headers?.get("x-session-token") ?: ""
        if (tokenHeader == sessionToken) return true
        // Check cookie for same-origin companion requests after page reloads
        val cookieHeader = session.headers?.get("cookie") ?: ""
        val cookieToken = cookieHeader
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("companion_session_token=") }
            ?.substringAfter('=', "")
            ?.trim()
            .orEmpty()
        if (cookieToken == sessionToken) return true
        // Check query parameter for simple GET requests
        val queryToken = session.parms?.get("token") ?: ""
        if (queryToken == sessionToken) return true
        return false
    }

    /** Add CORS and security headers to a response. */
    private fun addSecurityHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Set-Cookie", "companion_session_token=$sessionToken; Path=/; SameSite=Lax")
        return response
    }

    /** Known boolean config keys and their defaults (prevents returning "" for unset booleans). */
    private val booleanKeyDefaults = mapOf(
        "tts_muted" to false,
        "web_desktop_mode" to false,
        "web_force_dark_mode" to true,
        "browser_show_system_info" to false,
        "hud_show_calendar" to true,
        "hud_show_traffic" to true,
        "hud_show_notifications" to true,
        "hud_show_event_time" to true,
        "hud_show_tasks" to true,
        "hud_show_news" to true,
        // AQI on the HUD strip — default OFF (user R6 declutter); the
        // value plumbing stays warm so flipping this shows live data.
        "hud_show_aqi" to false,
        "phone_location_bridge_enabled" to false,
        // Live AI
        "live_session_resumption" to true,
        "live_context_compression" to false,
        "live_proactive_audio" to false,
        "live_disable_interrupt" to false,
        // Accessibility
        "hud_high_contrast" to false,
        "tts_auto_read" to true,
        // OpenClaw
        "openclaw_enabled" to false,
        // Hermes Agent (NousResearch hermes-agent OpenAI-compatible API).
        // Master enable toggle — gates HermesTool registration in
        // ToolDispatcher.
        "hermes_enabled" to false,
        // Hermes auto-follow-up — keep Gemini Live listening after a
        // Hermes turn so the user can ask a natural follow-up.
        "hermes_auto_followup_enabled" to false,
        // Assistant
        "assistant_default_camera" to false,
        // Translation
        "translate_auto_mode" to false,
        // Fish.audio cloud TTS — defaults match Fish's documented defaults so
        // a freshly-set engine produces sensible audio without the user
        // having to flip every toggle. `fish_normalize` defaults true because
        // unnormalized cloned voices vary wildly in loudness; `fish_dramatize`
        // defaults false so dialogue tags don't fire on plain readouts.
        // `fish_clean_text` defaults true because Fish's realistic voices
        // read decorative ASCII (***, _underline_, [Illustration]) literally
        // — which is almost never what users want for prose readout.
        "fish_normalize" to true,
        "fish_dramatize" to false,
        "fish_clean_text" to true,
        // URL-strip default ON: reading raw URLs aloud on glasses ("h-t-t-p-s
        // colon slash slash...") is jarring, and the chat card already shows
        // the URLs as bold tappable headers. Toggle in companion app under
        // Readout Cleanup if you want URLs spoken.
        "strip_urls_readout" to true,
        // Swipe-unlock boot screen. Default OFF — most users want the
        // glasses to boot straight into the unipanel. When on, the launcher
        // shows an animated swipe-sequence lock at cold launch. Purely a
        // visual deterrent ("dissuade common folk"); no encryption.
        "security_enabled" to false,
        // Dim-mode TapRadio song lyrics ("Dim-Mode Captions" card in the
        // companion radio page). When ON, the dim screen's caption line
        // shows LRCLIB synced lyrics for the playing radio track. Default
        // ON — it only ever activates when a song is identified anyway.
        "dim_captions_radio" to true
    )

    /** Known integer config keys and their defaults. */
    private val intKeyDefaults = mapOf(
        "hud_refresh_interval_seconds" to 60,
        "tasks_item_count" to 5,
        "news_item_count" to 3,
        "news_refresh_interval_seconds" to 600,
        // Timeouts
        "timeout_live_idle_seconds" to 0,
        "timeout_gemini_seconds" to 0,
        "timeout_research_seconds" to 0,
        "timeout_learnlm_seconds" to 0,
        // Live AI
        "live_compression_tokens" to 0,
        "live_silence_threshold" to 600,
        // OpenClaw
        "openclaw_timeout_seconds" to 0,
        "openclaw_heartbeat_interval_seconds" to 20,
        // HUD agent-status ticker poll interval (seconds, default 30).
        "agent_status_poll_seconds" to 30,
        // Days of chat history retained for the H / O badge overlay. 1–5,
        // default 3. Older records are pruned on next per-agent write.
        "hud_chat_history_days" to 3,
        // Hermes per-request timeout (seconds). 0 = default 30s.
        "hermes_timeout_seconds" to 0,
        // Battery Saver
        "battery_saver_auto_threshold" to 20,
        // Swipe-unlock: number of wrong sequences before a brief cooldown.
        "security_attempt_limit" to 3
    )

    /** Known float config keys and their defaults. */
    private val floatKeyDefaults = mapOf(
        "tts_volume" to 0.8f,
        "web_pointer_sensitivity" to 1.0f,
        // Live AI
        "live_temperature" to -1f,
        "live_barge_in_sensitivity" to 1.0f,
        // Accessibility
        "hud_font_scale" to 0f,
        "tts_speech_rate" to 0f,
        "screen_brightness" to 1.0f,
        // Fish.audio: 1.0 = neutral pace / unity gain. The Fish API treats
        // these as multipliers, so 0 would be "silent / paused" — keep
        // defaults at unity rather than 0 to avoid a confusing silent first
        // playback when the engine is first flipped to Fish.
        "fish_speed" to 1.0f,
        "fish_volume" to 0.0f
    )

    /** Config keys the companion pages can read/write. */
    private val allowedKeys = setOf(
        // Setup page keys
        "gemini_api_key",
        "gemini_model_override",
        "research_provider",
        "research_api_key",
        "research_model",
        "research_prompt",
        "research_tts_model",
        "research_tts_voice_name",
        "research_tts_language",
        "research_tts_director_notes",
        "learnlm_model",
        "calendar_api_key",
        "calendar_id",
        "google_maps_api_key",
        "phone_location_bridge_enabled",
        "google_oauth_client_id",
        "google_oauth_client_secret",
        "spotify_client_id",
        "spotify_client_secret",
        "audd_api_token",
        "personality",
        "custom_system_prompt",
        "tts_volume",
        "tts_muted",
        // Browser page keys
        "web_desktop_mode",
        "web_force_dark_mode",
        "web_pointer_sensitivity",
        "browser_show_system_info",
        "browser_cookies",
        // HUD display keys
        "hud_show_calendar",
        "hud_show_traffic",
        "hud_show_notifications",
        "hud_refresh_interval_seconds",
        "hud_show_event_time",
        "enabled_calendar_ids",
        "hud_show_tasks",
        "hud_show_news",
        "hud_show_aqi",
        "tasks_item_count",
        "news_item_count",
        "news_refresh_interval_seconds",
        "calendar_item_counts",
        "hud_display_order",
        "prompt_identity",
        "prompt_routing_rules",
        "prompt_behavior",
        "prompt_url_rules",
        // Gemini Live voice & AI settings
        "live_voice_name",
        "tts_voice_name",
        // (removed: cloud_tts_api_key, cloud_tts_voice_name, cloud_tts_language
        // — legacy Google Cloud TTS keys, no longer settable from any UI.
        // The Kotlin pipeline that consumed them in tapbrowser/MainActivity.kt
        // is unreachable from current builds; only defensive
        // stopCloudTts() lifecycle calls remain. A focused future cleanup
        // can delete the dead playback block + those keys' SharedPreferences
        // reads — touching neither risks a regression today.)
        "live_thinking_level",
        "live_temperature",
        "live_session_resumption",
        "live_context_compression",
        "live_compression_tokens",
        "live_proactive_audio",
        "live_language_code",
        "live_barge_in_sensitivity",
        "live_silence_threshold",
        "live_disable_interrupt",
        // Timeout settings
        "timeout_live_idle_seconds",
        "timeout_research_seconds",
        "timeout_learnlm_seconds",
        "timeout_gemini_seconds",
        // Accessibility settings
        "hud_font_scale",
        "hud_high_contrast",
        "tts_speech_rate",
        "tts_auto_read",
        "screen_brightness",
        // Translation
        "translate_default_language",
        "translate_auto_mode",
        // Battery Saver
        "battery_saver_auto_threshold",
        // Dim-mode TapRadio lyrics toggle (companion radio page). The
        // podcast-captions twin was removed with the rest of podcast CC —
        // radio lyrics is the single surviving dim-caption toggle.
        "dim_captions_radio",
        // Quick Actions
        "home_address",
        "work_address",
        "quick_actions_json",
        // OpenClaw integration
        "openclaw_endpoint",
        "openclaw_token",
        "openclaw_session_id",
        "openclaw_timeout_seconds",
        "openclaw_enabled",
        "openclaw_heartbeat_interval_seconds",
        "agent_status_poll_seconds",
        "hud_chat_history_days",
        // OpenClaw mode brackets (per-turn slash-command prefix +
        // after-turn restore). See AppPreferences.openClawFastMode etc.
        "openclaw_fast_mode",
        "openclaw_think_level",
        "openclaw_after_fast_mode",
        "openclaw_after_think_level",
        // Hermes Agent connection settings (Hermes section of companion app).
        // The endpoint + API key feed HermesClient; session ID supports
        // isolating conversations; timeout overrides the default 30s.
        "hermes_enabled",
        "hermes_endpoint",
        "hermes_api_key",
        "hermes_session_id",
        "hermes_timeout_seconds",
        // Hermes follow-up toggle (Hermes section of companion app).
        "hermes_auto_followup_enabled",
        // OpenClaw web-dashboard credentials. The companion app stores
        // these so the user can manage them in one place — they are
        // surfaced verbatim on the dashboard tile so the user can
        // copy/paste them into the dashboard's own login form. We
        // never auto-submit credentials on the user's behalf.
        "openclaw_dashboard_url",
        "openclaw_dashboard_username",
        "openclaw_dashboard_password",
        "tapinsight_companion_url",
        // Assistant
        "assistant_default_camera",
        // ── Readout TTS engine selection ──
        // `readout_engine` selects which cloud TTS service powers the
        // research/chat-card readouts AND the Media Library text-file
        // reader. Valid values: "gemini" (default), "fish". If "fish" is
        // selected but no fish_api_key is set, the glasses transparently
        // fall back to Gemini so the reader never goes silent.
        "readout_engine",
        // ── Fish.audio (Cloud TTS) ──
        // The companion app collects an API key, lets the user manage a
        // small list of saved voices (premade picks, voice-cloned from
        // an uploaded reference, or generated from a text description),
        // and the glasses render readouts through that voice. Settings
        // mirror the Fish v1 /tts request shape so the relay/glasses can
        // pass them straight through as headers/body fields.
        "fish_api_key",
        "fish_active_voice_id",
        "fish_active_voice_name",
        // JSON array of {"id":"…","name":"…","source":"library|clone|description"}.
        // Stored as a string blob so SharedPreferences can persist it; the
        // companion HTML and the glasses parse it with JSON.parse / JSONArray.
        "fish_saved_voices_json",
        "fish_model",
        "fish_speed",
        "fish_volume",
        "fish_format",
        "fish_latency",
        "fish_normalize",
        "fish_dramatize",
        "fish_clean_text",
        "strip_urls_readout",
        // Swipe-unlock boot screen (Security card in companion app).
        // security_enabled (bool) gates it; security_sequence is the
        // comma-separated 3–6 direction code (e.g. "U,R,D,L");
        // security_attempt_limit (int) sets wrong-tries-before-cooldown.
        "security_enabled",
        "security_sequence",
        "security_attempt_limit"
    )

    /** Keys readable via /api/oauth/status (no secrets exposed). */
    private val oauthStatusKeys = setOf(
        "google_oauth_client_id",
        "google_oauth_token_expiry_ms"
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        // Handle CORS preflight
        if (method == Method.OPTIONS) {
            return addSecurityHeaders(
                newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            )
        }

        // API endpoints require authentication (HTML pages do not — they embed the token)
        val isApiRequest = uri.startsWith("/api/")
        if (isApiRequest && !isAuthorizedApiRequest(session)) {
            Log.w(TAG, "Unauthorized API request to $uri from ${session.remoteIpAddress}")
            return addSecurityHeaders(
                newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json",
                    """{"error":"Unauthorized. Include header 'X-Session-Token: <token>' or query param '?token=<token>'."}"""
                )
            )
        }

        return try {
            val response = when {
                uri == "/" || uri == "/index.html" -> serveAssetPage("companion/index.html", SETUP_BRIDGE_JS)
                uri == "/browser" || uri == "/browser.html" -> serveAssetPage("companion/browser.html", BROWSER_BRIDGE_JS)
                uri == "/dashboard" || uri == "/dashboard.html" -> serveRawAsset("companion/dashboard.html")
                uri == "/radio" || uri == "/radio.html" -> serveRawAsset("companion/radio.html")
                uri == "/openclaw" || uri == "/openclaw.html" -> serveRawAsset("companion/openclaw.html")
                uri == "/api/radio" && method == Method.GET -> serveRadioStations()
                uri == "/api/radio" && method == Method.POST -> saveRadioStations(session)
                uri == "/api/config" && method == Method.GET -> serveConfig()
                uri == "/api/config" && method == Method.POST -> saveConfig(session)
                uri == "/api/dashboard" && method == Method.GET -> serveDashboard()
                uri == "/api/dashboard" && method == Method.POST -> saveDashboard(session)
                uri == "/api/phone-location/status" && method == Method.GET -> servePhoneLocationBridgeStatus()
                uri == "/api/phone-location" && method == Method.POST -> savePhoneLocationBridge(session)
                // HUD bell push endpoint. The Mac relay (tools/image_relay.py)
                // forwards POST /notify payloads here when the glasses are
                // reachable; RelayNotifyInbox pulls the queued leftovers.
                uri == "/api/notify" && method == Method.POST -> handlePushNotification(session)
                uri == "/oauth/callback" && method == Method.GET -> handleOAuthCallback(session)
                uri == "/api/oauth/exchange" && method == Method.POST -> handleOAuthExchange(session)
                uri == "/api/oauth/status" && method == Method.GET -> serveOAuthStatus()
                uri == "/spotify/auth/start" && method == Method.GET -> handleSpotifyAuthStart(session)
                uri == "/spotify/callback" && method == Method.GET -> handleSpotifyCallback(session)
                uri == "/api/spotify/status" && method == Method.GET -> serveSpotifyStatus()
                uri == "/api/spotify/disconnect" && method == Method.POST -> handleSpotifyDisconnect()
                uri == "/api/spotify/refresh" && method == Method.POST -> handleSpotifyRefresh()
                uri == "/api/calendars" && method == Method.GET -> fetchCalendarList()
                uri == "/api/verify/calendar" && method == Method.GET -> verifyCalendar()
                uri == "/api/verify/directions" && method == Method.GET -> verifyDirections()
                uri == "/api/verify/tasks" && method == Method.GET -> verifyTasks()
                uri == "/api/verify/places" && method == Method.GET -> verifyPlaces()
                uri == "/api/verify/location" && method == Method.GET -> verifyLocation()
                uri == "/api/verify/traffic" && method == Method.GET -> verifyTraffic()
                uri == "/api/verify/air_quality" && method == Method.GET -> verifyAirQuality()
                uri == "/api/verify/research" && method == Method.GET -> verifyResearch()
                uri == "/api/verify/openclaw" && method == Method.POST -> verifyOpenClaw(session)
                uri == "/api/openclaw/pair" && method == Method.POST -> pairOpenClaw(session)
                uri == "/api/camera/frame" && method == Method.GET -> serveCameraFrame()
                uri == "/api/relay/status" && method == Method.GET -> proxyRelayRequest("/status", "application/json")
                uri == "/api/relay/latest" && method == Method.GET -> proxyRelayRequest("/latest", "image/jpeg")
                uri.startsWith("/api/relay/media/") && method == Method.GET -> proxyRelayMedia(uri)
                uri == "/api/hud_state" && method == Method.GET -> serveHudState()
                uri == "/api/server-info" && method == Method.GET -> serveServerInfo(session)
                // ── Media Library (M3U playlists + in-browser player) ─────────
                uri == "/library" || uri == "/library.html" -> serveRawAsset("companion/library.html")
                uri == "/api/library/list" && method == Method.GET -> serveLibraryList(session)
                uri == "/api/library/playlist" && method == Method.GET -> serveLibraryPlaylist(session)
                uri == "/api/library/playlist" && method == Method.POST -> saveLibraryPlaylist(session)
                uri == "/api/library/write" && method == Method.POST -> writeLibraryFile(session)
                uri == "/api/library/generate" && method == Method.POST -> generateLibraryPlaylist(session)
                uri == "/api/library/upload" && method == Method.POST -> uploadLibraryMedia(session)
                uri == "/api/library/mkdir" && method == Method.POST -> mkdirLibraryFolder(session)
                uri == "/api/library/delete" && method == Method.POST -> deleteLibraryEntry(session)
                // Custom chat-panel orb image (Personalization).
                // The companion's orb.html cropper produces a square PNG and
                // POSTs both the cropped square and the user's original
                // upload (so they can re-open the cropper later without
                // re-uploading). The chat panel reads the cropped file at
                // display time and applies a circular outline clip.
                uri == "/api/orb/state" && method == Method.GET -> serveOrbState()
                uri == "/api/orb/visibility" && method == Method.POST -> saveOrbVisibility(session)
                uri == "/api/orb/upload" && method == Method.POST -> uploadOrbImage(session, original = false)
                uri == "/api/orb/upload-original" && method == Method.POST -> uploadOrbImage(session, original = true)
                uri == "/api/orb/preview" && method == Method.GET -> serveOrbPreview(session)
                uri == "/api/orb/default" && method == Method.GET -> serveOrbDefault()
                uri == "/api/orb" && method == Method.DELETE -> resetOrbImage()
                // TapBrowser-side state — bookmarks live in BookmarkPrefs and
                // the Groq API key in TapLinkPrefs (both separate from the
                // main app's visionclaw_prefs, which AiTapBridge talks to).
                // Direct endpoints expose them so the companion app's
                // browser tab can edit without bouncing through the bridge.
                uri == "/api/browser/bookmarks" && method == Method.GET -> serveBrowserBookmarks()
                uri == "/api/browser/bookmarks" && method == Method.POST -> saveBrowserBookmarks(session)
                uri == "/api/browser/groq_key" && method == Method.GET -> serveGroqKeyStatus()
                uri == "/api/browser/groq_key" && method == Method.POST -> saveGroqKey(session)
                uri == "/api/browser/groq_key" && method == Method.DELETE -> clearGroqKey()
                uri == "/orb" || uri == "/orb.html" -> serveRawAsset("companion/orb.html")
                uri == "/api/library/root" && method == Method.GET -> serveLibraryRootInfo()
                uri == "/api/library/ensure-dashboard-icon" && method == Method.POST -> ensureLibraryDashboardIcon(session)
                uri == "/media/file" && method == Method.GET -> serveMediaFile(session)
                uri == "/media/dcim-video" && method == Method.GET -> serveDcimVideoFile(session)
                // ── Fish.audio TTS proxy (engine = "fish") ─────────────────────
                // The companion HTML never talks to api.fish.audio directly; the
                // glasses proxy every call so the API key stays inside the
                // glasses' SharedPreferences. Same-origin from the companion
                // page (no CORS dance), and the relay (image_relay.py) doesn't
                // need to participate at all — Fish synthesis runs on-glasses.
                uri == "/api/fish/voices" && method == Method.GET -> serveFishVoices()
                uri == "/api/fish/voices/clone" && method == Method.POST -> cloneFishVoice(session)
                uri == "/api/fish/voices/describe" && method == Method.POST -> describeFishVoice(session)
                uri == "/api/fish/voices/library" && method == Method.GET -> searchFishVoiceLibrary(session)
                uri == "/api/fish/voices/delete" && method == Method.POST -> deleteFishVoice(session)
                uri == "/api/fish/voices/save" && method == Method.POST -> saveFishVoice(session)
                uri == "/api/fish/preview" && method == Method.POST -> previewFishVoice(session)
                // Engine-aware end-to-end TTS test. Synthesizes a short
                // canned phrase through the engine selected in
                // `readout_engine`, returns the audio bytes (MP3 for
                // Fish, WAV for Gemini) so the companion page can
                // play it directly. Lets users validate that the entire
                // path works without triggering a TapClaw turn on the
                // glasses — and returns precise HTTP status / body on
                // failure so transient Gemini 5xx events are obvious.
                uri == "/api/tts/test" && method == Method.POST -> testReadoutVoice(session)
                // Live model reachability probe. The Live conversational
                // API itself is WebSocket-based and harder to test in
                // one shot, but every Live model also exposes the
                // standard generateContent REST endpoint, so a tiny
                // text prompt to that endpoint tells us "is this model
                // reachable / not 5xx-ing right now?" which is the
                // question users actually have when their Live session
                // breaks.
                uri == "/api/live/test" && method == Method.POST -> testLiveModel(session)
                // List the user's available Live-capable models. Calls
                // Google's ListModels endpoint and filters for those
                // whose supportedGenerationMethods contain
                // bidiGenerateContent. Used by the companion UI's
                // "List Live Models" button.
                uri == "/api/live/list" && method == Method.GET -> listLiveModels(session)
                // List the user's available TTS-capable models. Same
                // ListModels response as Live, filtered for entries
                // whose name contains "tts". Backs the "List TTS
                // Models" button next to the Reader Voice dropdown.
                uri == "/api/tts/list" && method == Method.GET -> listTtsModels(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            addSecurityHeaders(response)
        } catch (t: Throwable) {
            Log.e(TAG, "Server error: ${t.message}", t)
            addSecurityHeaders(
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${t.message ?: t.javaClass.simpleName}")
            )
        }
    }

    private fun serveAssetPage(assetPath: String, bridgeJs: String): Response {
        val html = context.assets.open(assetPath)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        // Inject session token into bridge JS
        val tokenizedBridgeJs = bridgeJs.replace("__SESSION_TOKEN__", sessionToken)

        // The setup page contains its own verified save/load UX. Only append the
        // REST shim there so we do not overwrite page-local handlers again.
        val patchedHtml = when {
            assetPath == "companion/index.html" && html.contains("</body>") ->
                html.replace("</body>", "<script>\n$tokenizedBridgeJs\n</script>\n</body>")
            assetPath == "companion/index.html" ->
                html + "\n<script>\n$tokenizedBridgeJs\n</script>\n"
            else -> {
                val browserMarker =
                    "// Auto-load on page ready\n" +
                        "document.addEventListener('DOMContentLoaded', loadAll);"
                when {
                    html.contains(browserMarker) -> html.replace(browserMarker, tokenizedBridgeJs)
                    html.contains("</body>") -> html.replace("</body>", "<script>\n$tokenizedBridgeJs\n</script>\n</body>")
                    else -> html + "\n<script>\n$tokenizedBridgeJs\n</script>\n"
                }
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html", patchedHtml)
    }

    private fun serveConfig(): Response {
        val json = JSONObject()
        for (key in allowedKeys) {
            when (val value = prefs.all[key]) {
                is String -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                else -> {
                    // Type-aware defaults for unset keys
                    when {
                        booleanKeyDefaults.containsKey(key) -> json.put(key, booleanKeyDefaults[key]!!)
                        intKeyDefaults.containsKey(key) -> json.put(key, intKeyDefaults[key]!!)
                        floatKeyDefaults.containsKey(key) -> json.put(key, floatKeyDefaults[key]!!.toDouble())
                        else -> json.put(key, prefs.getString(key, "") ?: "")
                    }
                }
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun saveConfig(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"Empty body"}"""
            )
        }

        val beforeSnapshot = HashMap(prefs.all)
        val json = JSONObject(postData)
        val requested = JSONObject()
        val editor = prefs.edit()
        for (key in allowedKeys) {
            if (!json.has(key)) continue
            when {
                floatKeyDefaults.containsKey(key) -> {
                    val value = json.optDouble(key, floatKeyDefaults[key]!!.toDouble()).toFloat()
                    editor.putFloat(key, value)
                    requested.put(key, value.toDouble())
                }
                booleanKeyDefaults.containsKey(key) -> {
                    val value = json.optBoolean(key, booleanKeyDefaults[key]!!)
                    editor.putBoolean(key, value)
                    requested.put(key, value)
                }
                key == "hud_refresh_interval_seconds" -> {
                    val value = json.optInt(key, 60).coerceIn(5, 300)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                key == "tasks_item_count" -> {
                    val value = json.optInt(key, 5).coerceIn(1, 10)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                key == "news_item_count" -> {
                    val value = json.optInt(key, 3).coerceIn(1, 10)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                key == "news_refresh_interval_seconds" -> {
                    val value = json.optInt(key, 600).coerceIn(60, 3600)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                intKeyDefaults.containsKey(key) -> {
                    val value = json.optInt(key, intKeyDefaults[key]!!)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                else -> {
                    val value = json.optString(key, "")
                    editor.putString(key, value)
                    requested.put(key, value)
                }
            }
        }

        val committed = editor.commit()
        if (!committed) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"Settings could not be persisted"}"""
            )
        }

        val saved = JSONObject()
        val mismatches = JSONArray()
        var changedCount = 0
        for (key in allowedKeys) {
            if (!requested.has(key)) continue
            val storedValue: Any? = when (val value = prefs.all[key]) {
                is String -> value
                is Float -> value.toDouble()
                is Boolean -> value
                is Int -> value
                is Long -> value
                else -> when {
                    booleanKeyDefaults.containsKey(key) -> booleanKeyDefaults[key]
                    intKeyDefaults.containsKey(key) -> intKeyDefaults[key]
                    floatKeyDefaults.containsKey(key) -> floatKeyDefaults[key]?.toDouble()
                    else -> prefs.getString(key, "") ?: ""
                }
            }
            if (storedValue == null) saved.put(key, JSONObject.NULL) else saved.put(key, storedValue)

            val requestedValue = requested.get(key)
            val matches = when {
                requestedValue is Number && storedValue is Number ->
                    kotlin.math.abs(requestedValue.toDouble() - storedValue.toDouble()) < 0.0001
                requestedValue == JSONObject.NULL && storedValue == null -> true
                else -> requestedValue.toString() == (storedValue?.toString() ?: "")
            }
            if (!matches) {
                mismatches.put(
                    JSONObject()
                        .put("key", key)
                        .put("requested", requestedValue)
                        .put("saved", storedValue ?: JSONObject.NULL)
                )
            }

            val beforeValue = beforeSnapshot[key]
            val changed = when {
                beforeValue is Number && storedValue is Number ->
                    kotlin.math.abs(beforeValue.toDouble() - storedValue.toDouble()) >= 0.0001
                else -> (beforeValue?.toString() ?: "") != (storedValue?.toString() ?: "")
            }
            if (changed) changedCount++
        }

        val response = JSONObject()
            .put("status", if (mismatches.length() == 0) "saved" else "verification_failed")
            .put("verified", mismatches.length() == 0)
            .put("restartRecommended", true)
            .put("changedCount", changedCount)
            .put("saved", saved)
            .put("mismatches", mismatches)
        Log.d(TAG, "Config saved from companion app; verified=${mismatches.length() == 0}; changed=$changedCount")
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    /** Serve an asset page with minimal auth token injection (for dashboard, radio, etc.).
     *  These pages handle their own bridge logic but need the session token for API calls. */
    private fun serveRawAsset(assetPath: String): Response {
        var html = context.assets.open(assetPath)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        // Inject a minimal script that patches fetch to include the session token.
        // Also expose the token on window.__companionToken so pages can use it
        // for non-fetch flows (e.g. constructing signed media URLs for
        // cross-origin players).
        val tokenScript = """<script>
window.__companionToken = '${sessionToken}';
(function(){
  var _token = window.__companionToken;
  var _origFetch = window.fetch;
  window.fetch = function(url, opts) {
    opts = opts || {};
    if (typeof url === 'string' && url.indexOf('/api/') !== -1) {
      opts.headers = opts.headers || {};
      if (opts.headers instanceof Headers) {
        opts.headers.set('X-Session-Token', _token);
      } else {
        opts.headers['X-Session-Token'] = _token;
      }
    }
    return _origFetch.call(this, url, opts);
  };
})();
</script>"""
        html = if (html.contains("<head>")) {
            html.replace("<head>", "<head>\n$tokenScript")
        } else if (html.contains("<body>")) {
            html.replace("<body>", "$tokenScript\n<body>")
        } else {
            tokenScript + "\n" + html
        }
        val response = newFixedLengthResponse(Response.Status.OK, "text/html", html)
        // Companion HTML pages change frequently between debug builds and the
        // user often reloads expecting to see the latest layout / JS. Without
        // these headers, desktop browsers cache the page heuristically and the
        // user ends up running old JS against a new server, producing
        // confusing errors like "renderBookmarks is not defined" because the
        // cached HTML pre-dates a function we just added. no-store on the page
        // is cheap (these are tiny KB-sized assets) and matches the no-cache
        // policy already used on /api/* responses.
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        return response
    }

    /** Return the saved dashboard JSON (apps + groups). */
    private fun serveDashboard(): Response {
        val raw = prefs.getString(DASHBOARD_PREFS_KEY, null) ?: "{}"
        return newFixedLengthResponse(Response.Status.OK, "application/json", raw)
    }

    /** Save the full dashboard JSON (apps + groups). */
    private fun saveDashboard(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"Empty body"}"""
            )
        }
        // Validate it's valid JSON
        try {
            JSONObject(postData)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{\"error\":\"Invalid JSON\"}"""
            )
        }
        prefs.edit().putString(DASHBOARD_PREFS_KEY, postData).apply()
        Log.d(TAG, "Dashboard saved from companion app (${postData.length} chars)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"saved"}""")
    }

    // ── TapRadio ───────────────────────────────────────────────────────

    private val RADIO_PREFS_KEY = "tapradio_stations"

    /** Return saved TapRadio stations JSON array. */
    private fun serveRadioStations(): Response {
        val raw = prefs.getString(RADIO_PREFS_KEY, null) ?: "[]"
        return newFixedLengthResponse(Response.Status.OK, "application/json", raw)
    }

    /** Save TapRadio stations JSON array from the companion editor. */
    private fun saveRadioStations(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{\"error\":\"Empty body\"}"""
            )
        }
        prefs.edit().putString(RADIO_PREFS_KEY, postData).apply()
        Log.d(TAG, "TapRadio stations saved (${postData.length} chars)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"saved"}""")
    }

    private fun servePhoneLocationBridgeStatus(): Response {
        val loc = appPreferences.getPhoneLocationBridgeContext()
        val ageSeconds = loc?.let { ((System.currentTimeMillis() - it.timestampMs).coerceAtLeast(0L) / 1000L) }
        return jsonResponse(JSONObject().apply {
            put("enabled", appPreferences.phoneLocationBridgeEnabled)
            put("has_location", loc != null)
            put("provider", loc?.provider ?: JSONObject.NULL)
            put("latitude", loc?.latitude ?: JSONObject.NULL)
            put("longitude", loc?.longitude ?: JSONObject.NULL)
            put("accuracy_meters", loc?.accuracyMeters ?: JSONObject.NULL)
            put("timestamp_ms", loc?.timestampMs ?: JSONObject.NULL)
            put("age_seconds", ageSeconds ?: JSONObject.NULL)
        })
    }

    /**
     * POST /api/notify — ring the HUD bell from outside the glasses.
     *
     * This is the push half of the relay notification bridge: the Mac's
     * image_relay.py forwards its POST /notify payloads here (JSON
     * `{title, message, source, id?}`, X-Session-Token auth like every
     * other /api/ route). Payloads the relay can't deliver queue on its
     * disk and arrive later via RelayNotifyInbox's pull. The fallback id
     * is derived from the content exactly like the pull path's, so a
     * notification that travels BOTH paths is deduped by
     * NotificationCenter rather than ringing twice.
     */
    private fun handlePushNotification(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return badRequest("Empty body")
        }
        val json = try {
            JSONObject(postData)
        } catch (e: Exception) {
            return badRequest("Invalid JSON")
        }
        val message = json.optString("message").trim()
        if (message.isBlank()) {
            return badRequest("message is required")
        }
        val title = json.optString("title").trim().ifBlank { "Assistant update" }
        val source = when (json.optString("source").trim().uppercase(Locale.US)) {
            "CALENDAR" -> NotificationCenter.Source.CALENDAR
            "TASK" -> NotificationCenter.Source.TASK
            "HERMES" -> NotificationCenter.Source.HERMES
            "OPENCLAW" -> NotificationCenter.Source.OPENCLAW
            else -> NotificationCenter.Source.SYSTEM
        }
        val id = json.optString("id").trim()
            .ifBlank { "relay_" + ("$title|$message").hashCode() }
        val posted = NotificationCenter.post(
            NotificationCenter.HudNotification(id, source, title, message)
        )
        Log.d(TAG, "Push notification '$title' (source=$source, posted=$posted)")
        return jsonResponse(JSONObject().apply {
            put("status", "ok")
            put("posted", posted)
            put("id", id)
        })
    }

    private fun savePhoneLocationBridge(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"Empty body\"}"
            )
        }
        val json = try {
            JSONObject(postData)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"Invalid JSON\"}"
            )
        }

        if (json.optBoolean("clear", false)) {
            appPreferences.setPhoneLocationBridgeContext(null)
            phoneLocationConsumer?.invoke(null)
            return jsonResponse(JSONObject().apply {
                put("status", "cleared")
                put("enabled", appPreferences.phoneLocationBridgeEnabled)
            })
        }

        if (!appPreferences.phoneLocationBridgeEnabled) {
            return jsonResponse(JSONObject().apply {
                put("status", "disabled")
                put("message", "Phone GPS bridge is off in companion settings.")
            })
        }

        if (!json.has("latitude") || !json.has("longitude")) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"latitude and longitude are required\"}"
            )
        }

        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"invalid latitude or longitude\"}"
            )
        }

        val accuracyMeters =
            if (json.has("accuracy_meters") && !json.isNull("accuracy_meters")) {
                json.optDouble("accuracy_meters", Double.NaN)
                    .takeIf { it.isFinite() && it >= 0.0 && it <= 100_000.0 }
                    ?.toFloat()
            } else {
                null
            }
        val altitudeMeters =
            if (json.has("altitude_meters") && !json.isNull("altitude_meters")) {
                json.optDouble("altitude_meters", Double.NaN)
                    .takeIf { it.isFinite() && it in -20_000.0..100_000.0 }
            } else {
                null
            }
        val speedMps =
            if (json.has("speed_mps") && !json.isNull("speed_mps")) {
                json.optDouble("speed_mps", Double.NaN)
                    .takeIf { it.isFinite() && it >= 0.0 && it <= 500.0 }
                    ?.toFloat()
            } else {
                null
            }
        val bearingDeg =
            if (json.has("bearing_deg") && !json.isNull("bearing_deg")) {
                json.optDouble("bearing_deg", Double.NaN)
                    .takeIf { it.isFinite() }
                    ?.let { (((it % 360.0) + 360.0) % 360.0).toFloat() }
            } else {
                null
            }
        val rawTimestampMs = json.optLong("timestamp_ms", System.currentTimeMillis())
        val nowMs = System.currentTimeMillis()
        val timestampMs =
            rawTimestampMs.takeIf { it in (nowMs - 24L * 60L * 60L * 1000L)..(nowMs + 5L * 60L * 1000L) }
                ?: nowMs

        val context = DeviceLocationContext(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            speedMps = speedMps,
            bearingDeg = bearingDeg,
            provider = "companion_phone",
            timestampMs = timestampMs
        )
        appPreferences.setPhoneLocationBridgeContext(context)
        Log.d(
            TAG,
            "Stored phone bridge fix lat=${context.latitude} lon=${context.longitude} acc=${context.accuracyMeters} ts=${context.timestampMs}"
        )
        return jsonResponse(JSONObject().apply {
            put("status", "ok")
            put("enabled", true)
            put("stored_only", true)
            put("provider", context.provider)
            put("accuracy_meters", context.accuracyMeters ?: JSONObject.NULL)
            put("timestamp_ms", context.timestampMs)
        })
    }

    // ── OAuth ─────────────────────────────────────────────────────────

    /**
     * Handle Google OAuth redirect: /oauth/callback?code=...
     * Exchanges the authorization code for tokens and stores them.
     */
    private fun handleOAuthCallback(session: IHTTPSession): Response {
        val params = session.parms ?: emptyMap()
        val code = params["code"]
        val error = params["error"]

        if (error != null) {
            Log.w(TAG, "OAuth error: $error")
            return newFixedLengthResponse(
                Response.Status.OK, "text/html",
                oauthResultPage(false, "Authorization denied: $error")
            )
        }

        if (code.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html",
                oauthResultPage(false, "No authorization code received.")
            )
        }

        val mgr = oauthManager
        if (mgr == null) {
            Log.e(TAG, "OAuth manager not initialized")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/html",
                oauthResultPage(false, "OAuth manager not ready. Restart TapInsight and try again.")
            )
        }

        // Reconstruct the redirect URI from the incoming request
        val host = session.headers["host"] ?: "localhost:19110"
        val scheme = if (httpsEnabled) "https" else "http"
        val redirectUri = "$scheme://$host/oauth/callback"

        // Exchange code for tokens (blocking in NanoHTTPD thread)
        val result = runBlocking { mgr.exchangeCodeForTokensDetailed(code, redirectUri) }

        return newFixedLengthResponse(
            Response.Status.OK, "text/html",
            oauthResultPage(
                result.success,
                if (result.success) "Google account authorized! You can close this tab."
                else "Token exchange failed: ${result.errorDetail}<br><br><small>Redirect URI used: $redirectUri</small>"
            )
        )
    }

    /**
     * Handle manual OAuth code submission: POST /api/oauth/exchange
     * Body: {"code": "...", "redirect_uri": "http://<glasses-ip>:19110/oauth/callback"}
     */
    private fun handleOAuthExchange(session: IHTTPSession): Response {
        return try {
            // Read POST body
            val body = HashMap<String, String>()
            session.parseBody(body)
            val postData = body["postData"] ?: ""

            if (postData.isBlank()) {
                Log.e(TAG, "OAuth exchange: empty body")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{\"error\":\"Empty body\"}"""
                )
            }

            Log.d(TAG, "OAuth exchange: received body (${postData.length} chars)")

            val json = try { JSONObject(postData) } catch (e: Exception) {
                Log.e(TAG, "OAuth exchange: invalid JSON", e)
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{\"error\":\"Invalid JSON\"}"""
                )
            }

            val code = json.optString("code", "").trim()
            val host = session.headers["host"] ?: "localhost:19110"
            val scheme = if (httpsEnabled) "https" else "http"
            val defaultRedirectUri = "$scheme://$host/oauth/callback"
            val redirectUri = json.optString("redirect_uri", defaultRedirectUri).trim()

            if (code.isBlank()) {
                Log.e(TAG, "OAuth exchange: no code in body")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{"error":"No code provided"}"""
                )
            }

            Log.d(TAG, "OAuth exchange: code=${code.take(10)}..., redirect=$redirectUri")

            val mgr = oauthManager
            if (mgr == null) {
                Log.e(TAG, "OAuth manager not initialized")
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":"OAuth manager not ready. Restart TapInsight and try again."}"""
                )
            }

            val success = runBlocking { mgr.exchangeCodeForTokens(code, redirectUri) }

            if (success) {
                Log.i(TAG, "OAuth exchange: success!")
                newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"status":"authorized"}"""
                )
            } else {
                Log.e(TAG, "OAuth exchange: token exchange failed")
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error":"Token exchange failed. Check Client ID and Secret."}"""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "OAuth exchange: unexpected error", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"error":"Server error: ${e.message?.replace("\"", "'")}"}"""
            )
        }
    }

    /** Returns OAuth authorization status as JSON. */
    private fun serveOAuthStatus(): Response {
        val hasToken = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true
        val expiryMs = prefs.getLong("google_oauth_token_expiry_ms", 0L)
        val isValid = hasToken && System.currentTimeMillis() < expiryMs
        val json = JSONObject().apply {
            put("authorized", hasToken)
            put("token_valid", isValid)
            put("expiry_ms", expiryMs)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * GET /api/camera/frame — serves the latest camera frame as a JPEG image.
     * OpenClaw's agent fetches this URL to analyze what the AR glasses camera sees.
     * Returns 503 if no frame is available (camera not active).
     */
    private fun serveCameraFrame(): Response {
        val frameBytes = cameraFrameProvider?.invoke()
        if (frameBytes == null || frameBytes.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                """{"error":"No camera frame available","hint":"Camera may not be active"}"""
            )
        }

        val inputStream = java.io.ByteArrayInputStream(frameBytes)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            inputStream,
            frameBytes.size.toLong()
        )
        response.addHeader("Cache-Control", "no-cache, no-store")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    /**
     * Proxy a request to the image relay, avoiding mixed-content browser blocks.
     * The companion app is served over HTTPS, so direct fetch to http://<relay>:18790
     * fails silently in browsers. This endpoint proxies the request server-side.
     *
     * @param relayPath the relay path to fetch, e.g. "/status" or "/latest"
     * @param expectedMime the expected MIME type of the response
     */
    private fun proxyRelayRequest(relayPath: String, expectedMime: String): Response {
        val relayBase = buildRelayBaseUrl()
        if (relayBase == null) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                """{"error":"No OpenClaw gateway URL configured — cannot determine relay address"}"""
            )
        }
        return try {
            val url = java.net.URL(relayBase + relayPath)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return newFixedLengthResponse(
                    Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"Relay returned HTTP $code"}"""
                )
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val resp = newFixedLengthResponse(
                Response.Status.OK,
                expectedMime,
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
            resp.addHeader("Cache-Control", "no-cache, no-store")
            resp
        } catch (e: Exception) {
            Log.w(TAG, "Relay proxy failed for $relayBase$relayPath: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"Cannot reach image relay at $relayBase","detail":"${e.message?.replace("\"", "'")}"}"""
            )
        }
    }

    /**
     * Proxy media file requests to the relay's /media/ endpoint.
     * Detects MIME type from the file extension so TapBrowser can identify audio/video.
     */
    private fun proxyRelayMedia(uri: String): Response {
        val filename = uri.removePrefix("/api/relay/media/")
        if (filename.isBlank() || filename.contains("..")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
        }
        val ext = filename.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
        return proxyRelayRequest("/media/$filename", mime)
    }

    /** Build the relay base URL from the configured OpenClaw gateway
     *  endpoint (shared RelayUrlHelper logic; prefers the public tunnel
     *  for LAN endpoints so proxied URLs work from any network). */
    private fun buildRelayBaseUrl(): String? {
        return RelayUrlHelper.baseFromEndpoint(
            appPreferences.openClawEndpoint.trim(),
            preferTapInsightPublicForLocal = true
        )
    }

    private fun serveHudState(): Response {
        return jsonResponse(JSONObject().apply {
            put("hud_show_calendar", prefs.getBoolean("hud_show_calendar", true))
            put("hud_show_tasks", prefs.getBoolean("hud_show_tasks", true))
            put("hud_show_news", prefs.getBoolean("hud_show_news", true))
            put("calendar_summary", calendarSummaryProvider?.invoke().orEmpty())
            put("tasks_summary", tasksSummaryProvider?.invoke().orEmpty())
            put("news_summary", newsSummaryProvider?.invoke().orEmpty())
            put("aqi_text", airQualityTextProvider?.invoke() ?: JSONObject.NULL)
            put("aqi_value", airQualityValueProvider?.invoke() ?: JSONObject.NULL)
            put("location_provider", locationProvider?.invoke()?.provider ?: JSONObject.NULL)
        })
    }

    /** Returns server connection info (protocol, HTTPS status, URL hint). */
    private fun serveServerInfo(session: IHTTPSession): Response {
        val host = session.headers["host"] ?: "localhost:19110"
        val hostName = host.substringBefore(':').ifBlank { "localhost" }
        val serverPort = runCatching { listeningPort }.getOrDefault(19110)
        val lanIps = detectLanIpv4Addresses()
        val primaryLanIp = lanIps.firstOrNull() ?: hostName.takeIf { it != "localhost" && it != "127.0.0.1" }
        val scheme = if (httpsEnabled) "https" else "http"
        return jsonResponse(JSONObject().apply {
            put("https_enabled", httpsEnabled)
            put("scheme", scheme)
            put("url", "$scheme://$host")
            put("secure_context", httpsEnabled)
            put("gps_bridge_supported", httpsEnabled)
            put("host_header", host)
            put("host_name", hostName)
            put("primary_lan_ip", primaryLanIp ?: JSONObject.NULL)
            put("recommended_phone_url", if (primaryLanIp != null && httpsEnabled) "https://$primaryLanIp:${serverPort}" else JSONObject.NULL)
            put("recommended_wifi_url", if (primaryLanIp != null) "$scheme://$primaryLanIp:${serverPort}" else JSONObject.NULL)
            put("recommended_usb_url", if (httpsEnabled) "https://localhost:${serverPort}" else "http://localhost:${serverPort}")
            put("lan_ips", org.json.JSONArray().apply {
                lanIps.forEach { put(it) }
            })
        })
    }

    private fun detectLanIpv4Addresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .asSequence()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { iface ->
                    iface.inetAddresses.toList().asSequence()
                }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { ip ->
                    ip.isNotBlank() &&
                        ip != "127.0.0.1" &&
                        !ip.startsWith("169.254.")
                }
                .distinct()
                .sorted()
                .toList()
        }.getOrDefault(emptyList())
    }

    // ── Calendar List ────────────────────────────────────────────────────

    /** Fetch all calendars visible to the OAuth-authenticated user. */
    private fun fetchCalendarList(): Response {
        val hasOAuth = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true
        if (!hasOAuth) {
            return jsonResponse(JSONObject().apply {
                put("status", "auth_required")
                put("message", "OAuth authorization required to list calendars")
                put("calendars", org.json.JSONArray())
            })
        }

        return try {
            val mgr = oauthManager ?: return jsonResponse(JSONObject().apply {
                put("status", "error")
                put("message", "OAuth manager not initialized")
                put("calendars", org.json.JSONArray())
            })

            val calendarApiKey = prefs.getString("calendar_api_key", "") ?: ""
            val client = GoogleCalendarClient(
                apiKeyProvider = { calendarApiKey.takeIf { it.isNotBlank() } },
                accessTokenProvider = { runBlocking { mgr.getValidAccessToken() } },
                context = context
            )
            val result = runBlocking { client.fetchCalendarList() }

            when (result) {
                is GoogleCalendarClient.CalendarListResult.Success -> {
                    val arr = org.json.JSONArray()
                    result.calendars.forEach { cal ->
                        arr.put(JSONObject().apply {
                            put("id", cal.id)
                            put("summary", cal.summary)
                            put("primary", cal.primary)
                            if (cal.backgroundColor != null) put("backgroundColor", cal.backgroundColor)
                            if (cal.accessRole != null) put("accessRole", cal.accessRole)
                        })
                    }
                    jsonResponse(JSONObject().apply {
                        put("status", "success")
                        put("calendars", arr)
                    })
                }
                is GoogleCalendarClient.CalendarListResult.AuthRequired ->
                    jsonResponse(JSONObject().apply {
                        put("status", "auth_required")
                        put("message", "OAuth authorization required")
                        put("calendars", org.json.JSONArray())
                    })
                is GoogleCalendarClient.CalendarListResult.Error ->
                    jsonResponse(JSONObject().apply {
                        put("status", "error")
                        put("message", result.message)
                        put("calendars", org.json.JSONArray())
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calendar list error", e)
            jsonResponse(JSONObject().apply {
                put("status", "error")
                put("message", "Exception: ${e.message}")
                put("calendars", org.json.JSONArray())
            })
        }
    }

    // ── API Verification ──────────────────────────────────────────────────

    /** Test Google Calendar API with current credentials. */
    private fun verifyCalendar(): Response {
        val calendarApiKey = prefs.getString("calendar_api_key", "") ?: ""
        val calendarId = (prefs.getString("calendar_id", "") ?: "").ifBlank { "primary" }
        val hasOAuth = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true
        Log.d(TAG, "verifyCalendar start hasOAuth=$hasOAuth hasApiKey=${calendarApiKey.isNotBlank()} calendarId=$calendarId")

        if (calendarApiKey.isBlank() && !hasOAuth) {
            return jsonResponse(JSONObject().apply {
                put("service", "calendar")
                put("status", "not_configured")
                put("message", "No OAuth token or Calendar API key configured")
                put("has_oauth", false)
                put("has_api_key", false)
                put("calendar_id", calendarId)
            })
        }

        return try {
            val mgr = oauthManager
            val client = GoogleCalendarClient(
                apiKeyProvider = { calendarApiKey.takeIf { it.isNotBlank() } },
                accessTokenProvider = {
                    if (mgr != null && hasOAuth) runBlocking { mgr.getValidAccessToken() } else null
                },
                context = context
            )
            val result = runBlocking { client.fetchUpcomingEvents(calendarId, maxResults = 5) }

            when (result) {
                is GoogleCalendarClient.CalendarResult.Success -> {
                    Log.d(TAG, "verifyCalendar success events=${result.events.size}")
                    val eventSummary = if (result.events.isEmpty()) {
                        "No events in next 24h"
                    } else {
                        result.events.take(3).joinToString(", ") { it.summary }
                    }
                    jsonResponse(JSONObject().apply {
                        put("service", "calendar")
                        put("status", "success")
                        put("message", "Connected! ${result.events.size} event(s): $eventSummary")
                        put("has_oauth", hasOAuth)
                        put("has_api_key", calendarApiKey.isNotBlank())
                        put("calendar_id", calendarId)
                        put("event_count", result.events.size)
                    })
                }
                is GoogleCalendarClient.CalendarResult.ApiKeyMissing -> jsonResponse(JSONObject().apply {
                    Log.w(TAG, "verifyCalendar api key missing/invalid")
                    put("service", "calendar")
                    put("status", "failed")
                    put("message", "API key is missing or invalid. Enable Calendar API in GCP and check your key.")
                    put("has_oauth", hasOAuth)
                    put("has_api_key", calendarApiKey.isNotBlank())
                    put("calendar_id", calendarId)
                })
                is GoogleCalendarClient.CalendarResult.Error -> jsonResponse(JSONObject().apply {
                    Log.e(TAG, "verifyCalendar error message=${result.message} code=${result.code}")
                    put("service", "calendar")
                    put("status", "failed")
                    put("message", result.message)
                    put("has_oauth", hasOAuth)
                    put("has_api_key", calendarApiKey.isNotBlank())
                    put("calendar_id", calendarId)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calendar verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "calendar")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_oauth", hasOAuth)
                put("has_api_key", calendarApiKey.isNotBlank())
                put("calendar_id", calendarId)
            })
        }
    }

    /** Test Google Directions API with current credentials. */
    private fun verifyDirections(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "directions")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured")
                put("has_api_key", false)
            })
        }

        return try {
            val client = GoogleDirectionsClient(apiKeyProvider = { mapsApiKey }, context = context)
            val result = runBlocking {
                client.getDirections("Times Square, NYC", "Central Park, NYC", "driving")
            }

            when (result) {
                is GoogleDirectionsClient.DirectionsResult.Success -> jsonResponse(JSONObject().apply {
                    put("service", "directions")
                    put("status", "success")
                    put("message", "Connected! Test route: ${result.distance}, ${result.duration}")
                    put("has_api_key", true)
                })
                is GoogleDirectionsClient.DirectionsResult.ApiKeyMissing -> jsonResponse(JSONObject().apply {
                    put("service", "directions")
                    put("status", "failed")
                    put("message", "API key is invalid. Enable Directions API in GCP.")
                    put("has_api_key", true)
                })
                is GoogleDirectionsClient.DirectionsResult.Error -> jsonResponse(JSONObject().apply {
                    put("service", "directions")
                    put("status", "failed")
                    put("message", result.message)
                    put("has_api_key", true)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Directions verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "directions")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
            })
        }
    }

    /** Test Google Tasks API with current OAuth credentials. */
    private fun verifyTasks(): Response {
        val hasOAuth = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true

        if (!hasOAuth) {
            return jsonResponse(JSONObject().apply {
                put("service", "tasks")
                put("status", "not_configured")
                put("message", "OAuth authorization required for Tasks API. Complete Step 6 and re-authorize with Tasks scope.")
                put("has_oauth", false)
            })
        }

        return try {
            val mgr = oauthManager ?: return jsonResponse(JSONObject().apply {
                put("service", "tasks")
                put("status", "failed")
                put("message", "OAuth manager not initialized")
                put("has_oauth", true)
            })

            val client = GoogleTasksClient(
                accessTokenProvider = { runBlocking { mgr.getValidAccessToken() } },
                context = context
            )
            val result = runBlocking { client.fetchTasks(maxResults = 3) }

            when (result) {
                is GoogleTasksClient.TasksResult.Success -> {
                    val taskSummary = if (result.tasks.isEmpty()) {
                        "No pending tasks"
                    } else {
                        result.tasks.take(3).joinToString(", ") { it.title }
                    }
                    jsonResponse(JSONObject().apply {
                        put("service", "tasks")
                        put("status", "success")
                        put("message", "Connected! ${result.tasks.size} task(s): $taskSummary")
                        put("has_oauth", true)
                        put("task_count", result.tasks.size)
                    })
                }
                is GoogleTasksClient.TasksResult.AuthRequired ->
                    jsonResponse(JSONObject().apply {
                        put("service", "tasks")
                        put("status", "failed")
                        put("message", "OAuth token missing or expired. Re-authorize with Tasks scope.")
                        put("has_oauth", true)
                    })
                is GoogleTasksClient.TasksResult.Error ->
                    jsonResponse(JSONObject().apply {
                        put("service", "tasks")
                        put("status", "failed")
                        put("message", result.message)
                        put("has_oauth", true)
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tasks verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "tasks")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_oauth", true)
            })
        }
    }

    /** Test Google Places API (Nearby Search) with current Maps API key. */
    private fun verifyPlaces(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "places")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured. Places API uses the same key as Directions.")
                put("has_api_key", false)
            })
        }

        return try {
            val client = GooglePlacesClient(apiKeyProvider = { mapsApiKey }, context = context)
            // Test with a search for restaurants near a known location (Houston, TX)
            val result = runBlocking {
                client.searchNearby(
                    latitude = 29.7604,
                    longitude = -95.3698,
                    types = listOf("restaurant"),
                    radiusMeters = 1000.0,
                    maxResults = 3
                )
            }

            when (result) {
                is GooglePlacesClient.PlacesResult.Success -> {
                    val placeSummary = if (result.places.isEmpty()) {
                        "No restaurants found (API works but no results at test location)"
                    } else {
                        result.places.take(3).joinToString(", ") { place ->
                            val open = when (place.isOpen) {
                                true -> "Open"
                                false -> "Closed"
                                null -> ""
                            }
                            "${place.name}${if (open.isNotBlank()) " ($open)" else ""}"
                        }
                    }
                    jsonResponse(JSONObject().apply {
                        put("service", "places")
                        put("status", "success")
                        put("message", "Connected! ${result.places.size} place(s): $placeSummary")
                        put("has_api_key", true)
                        put("place_count", result.places.size)
                    })
                }
                is GooglePlacesClient.PlacesResult.ApiKeyMissing -> jsonResponse(JSONObject().apply {
                    put("service", "places")
                    put("status", "failed")
                    put("message", "API key is missing or invalid.")
                    put("has_api_key", mapsApiKey.isNotBlank())
                })
                is GooglePlacesClient.PlacesResult.Error -> jsonResponse(JSONObject().apply {
                    put("service", "places")
                    put("status", "failed")
                    put("message", result.message)
                    put("has_api_key", true)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Places verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "places")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
            })
        }
    }

    /** Test GPS location availability on the device. */
    private fun verifyLocation(): Response {
        val loc = locationProvider?.invoke()

        if (loc == null) {
            return jsonResponse(JSONObject().apply {
                put("service", "location")
                put("status", "failed")
                put("message", "Device location not available. Make sure Location Services are enabled " +
                    "on the glasses (Settings → Location → On) and the app has location permission.")
                put("has_gps", false)
            })
        }

        val ageSeconds = (System.currentTimeMillis() - loc.timestampMs) / 1000
        val fresh = if (ageSeconds < 300) "current" else "${ageSeconds / 60}min old"
        val sourceLabel =
            when (loc.provider) {
                "ip_geolocation" -> "Approximate network location"
                else -> "Location active"
            }
        return jsonResponse(JSONObject().apply {
            put("service", "location")
            put("status", "success")
            put("message", "$sourceLabel: Lat: ${"%.6f".format(loc.latitude)}, " +
                "Lng: ${"%.6f".format(loc.longitude)} " +
                "(accuracy: ${loc.accuracyMeters?.toInt() ?: "?"}m, $fresh)" +
                (loc.altitudeMeters?.let { alt: Double -> ", alt: ${alt.toInt()}m" } ?: "") +
                (loc.speedMps?.let { spd: Float -> if (spd > 0.5f) ", speed: ${"%.1f".format(spd * 2.237)}mph" else "" } ?: ""))
            put("has_gps", loc.provider != "ip_geolocation")
            put("provider", loc.provider ?: JSONObject.NULL)
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("accuracy_meters", loc.accuracyMeters ?: -1)
            put("age_seconds", ageSeconds)
        })
    }

    /** Test Google Directions API with live GPS (origin = device) to a known destination. */
    private fun verifyTraffic(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""
        val loc = locationProvider?.invoke()

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "traffic")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured. Set it above and save first.")
                put("has_api_key", false)
                put("has_gps", loc != null)
            })
        }

        if (loc == null) {
            return jsonResponse(JSONObject().apply {
                put("service", "traffic")
                put("status", "failed")
                put("message", "GPS not available — cannot test traffic from your current location. " +
                    "Enable Location Services on the glasses.")
                put("has_api_key", true)
                put("has_gps", false)
            })
        }

        return try {
            val origin = "${loc.latitude},${loc.longitude}"
            // Use a well-known nearby city as the destination test
            val destination = "Houston, TX"
            val client = GoogleDirectionsClient(apiKeyProvider = { mapsApiKey }, context = context)
            val result = runBlocking {
                client.getDirections(origin = origin, destination = destination)
            }

            when (result) {
                is GoogleDirectionsClient.DirectionsResult.Success -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "traffic")
                        put("status", "success")
                        put("message", "Routes API working! From your location to $destination: " +
                            "${result.duration} (${result.distance})" +
                            (result.durationInTraffic?.let { " — with traffic: $it" } ?: ""))
                        put("has_api_key", true)
                        put("has_gps", true)
                        put("origin", origin)
                        put("destination", destination)
                    })
                }
                is GoogleDirectionsClient.DirectionsResult.Error -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "traffic")
                        put("status", "failed")
                        put("message", "Routes API error: ${result.message}")
                        put("has_api_key", true)
                        put("has_gps", true)
                    })
                }
                is GoogleDirectionsClient.DirectionsResult.ApiKeyMissing -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "traffic")
                        put("status", "failed")
                        put("message", "API key missing or invalid.")
                        put("has_api_key", false)
                        put("has_gps", true)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Traffic verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "traffic")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
                put("has_gps", true)
            })
        }
    }

    /** Test Google Air Quality API with live GPS location. */
    private fun verifyAirQuality(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""
        val loc = locationProvider?.invoke()

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "air_quality")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured. Air Quality uses the same key.")
                put("has_api_key", false)
                put("has_gps", loc != null)
            })
        }

        if (loc == null) {
            return jsonResponse(JSONObject().apply {
                put("service", "air_quality")
                put("status", "failed")
                put("message", "GPS not available — cannot test air quality from the glasses.")
                put("has_api_key", true)
                put("has_gps", false)
            })
        }

        return try {
            val client = GoogleAirQualityClient(apiKeyProvider = { mapsApiKey }, context = context)
            when (
                val result = runBlocking {
                    client.fetchCurrentConditions(loc.latitude, loc.longitude)
                }
            ) {
                is GoogleAirQualityClient.AirQualityResult.Success -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "air_quality")
                        put("status", "success")
                        put(
                            "message",
                            "Connected! ${result.index.label}" +
                                (result.index.dominantPollutant?.let { " — dominant pollutant: $it" } ?: "")
                        )
                        put("has_api_key", true)
                        put("has_gps", true)
                        put("aqi", result.index.aqi ?: JSONObject.NULL)
                    })
                }
                is GoogleAirQualityClient.AirQualityResult.ApiKeyMissing -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "air_quality")
                        put("status", "failed")
                        put("message", "Air Quality API key missing or invalid.")
                        put("has_api_key", false)
                        put("has_gps", true)
                    })
                }
                is GoogleAirQualityClient.AirQualityResult.Error -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "air_quality")
                        put("status", "failed")
                        put("message", result.message)
                        put("has_api_key", true)
                        put("has_gps", true)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Air quality verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "air_quality")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
                put("has_gps", loc != null)
            })
        }
    }

    /** Test the configured research provider with a short sample prompt. */
    private fun verifyResearch(): Response {
        val provider = (prefs.getString("research_provider", "") ?: "").trim().ifBlank { "gemini" }
        val router = ResearchRouter(
            providerProvider = { provider },
            apiKeyProvider = { prefs.getString("research_api_key", "")?.trim() },
            modelProvider = { prefs.getString("research_model", "")?.trim() },
            geminiFallbackApiKeyProvider = {
                (prefs.getString("gemini_api_key", "") ?: "").trim().ifBlank {
                    BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
                }
            },
            context = context,
            timeoutSecondsProvider = { prefs.getInt("timeout_research_seconds", 0) },
            customResearchPromptProvider = {
                prefs.getString("research_prompt", "")?.trim()?.takeIf { it.isNotBlank() }
            }
        )

        return try {
            when (val result = runBlocking { router.research("current capabilities of TapInsight") }) {
                is ResearchRouter.ResearchResult.Success -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "research")
                        put("status", "success")
                        put("message", "Connected! ${result.provider} / ${result.model}")
                        put("provider", result.provider)
                        put("model", result.model)
                        put("preview", result.text.take(240))
                    })
                }
                is ResearchRouter.ResearchResult.ApiKeyMissing -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "research")
                        put("status", "not_configured")
                        put("message", "Research provider API key missing. Configure it in the companion app.")
                        put("provider", provider)
                    })
                }
                is ResearchRouter.ResearchResult.Error -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "research")
                        put("status", "failed")
                        put("message", result.message)
                        put("provider", provider)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Research verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "research")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("provider", provider)
            })
        }
    }

    private fun verifyOpenClaw(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("message", "Empty request body.")
            )
        }

        val json = try {
            JSONObject(postData)
        } catch (e: Exception) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("message", "Invalid JSON: ${e.message}")
            )
        }

        val endpoint = json.optString("endpoint", "").trim()
        val token = json.optString("token", "").trim()
        val sessionId = json.optString("session_id", "main").ifBlank { "main" }
        val timeoutSeconds = json.optInt("timeout_seconds", 0).coerceIn(0, 120)
        val timeoutMs = (if (timeoutSeconds > 0) timeoutSeconds else 30) * 1000L

        if (endpoint.isBlank()) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("message", "OpenClaw gateway URL is missing or invalid.")
            )
        }

        // Normalize to WebSocket URL — gateway is WebSocket-only
        var wsUrl = endpoint.trim().trimEnd('/')
        wsUrl = when {
            wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://") -> wsUrl
            wsUrl.startsWith("https://") -> wsUrl.replace("https://", "wss://")
            wsUrl.startsWith("http://") -> wsUrl.replace("http://", "ws://")
            else -> "ws://$wsUrl"
        }
        val schemeEnd = wsUrl.indexOf("://") + 3
        val hostPart = wsUrl.substring(schemeEnd)
        if (!hostPart.contains(':')) {
            wsUrl = wsUrl.substring(0, schemeEnd) + hostPart + ":18789"
        }

        // Use WebSocket to call the health method
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultJson: JSONObject? = null
        var errorMsg: String? = null

        val connectId = "verify-" + System.currentTimeMillis()
        val callId = "health-" + System.currentTimeMillis()
        var connected = false

        val request = okhttp3.Request.Builder().url(wsUrl).build()
        val ws = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                Log.d(TAG, "Verify WS opened, waiting for challenge...")
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val type = msg.optString("type", "")
                    val event = msg.optString("event", "")

                    when {
                        // Challenge — respond with signed device identity auth
                        type == "event" && event == "connect.challenge" -> {
                            if (token.isNotBlank()) {
                                val nonce = msg.optJSONObject("payload")?.optString("nonce", "") ?: ""
                                val deviceId = prefs.getString("openclaw_pair_device_id", null)?.takeIf { it.isNotBlank() }
                                val publicKey = prefs.getString("openclaw_pair_public_key", null)?.takeIf { it.isNotBlank() }
                                val privateKey = prefs.getString("openclaw_pair_private_key", null)?.takeIf { it.isNotBlank() }
                                val signedAtMs = System.currentTimeMillis()

                                val connectFrame = JSONObject().apply {
                                    put("type", "req")
                                    put("id", connectId)
                                    put("method", "connect")
                                    put("params", JSONObject().apply {
                                        put("minProtocol", 3)
                                        put("maxProtocol", 3)
                                        put("client", JSONObject().apply {
                                            put("id", "openclaw-android")
                                            put("version", "1.1.2")
                                            put("platform", "android")
                                            put("mode", "node")
                                        })
                                        put("role", "operator")
                                        put("scopes", org.json.JSONArray().apply {
                                            put("operator.read")
                                            put("operator.write")
                                        })
                                        put("auth", JSONObject().apply { put("token", token) })
                                        put("caps", org.json.JSONArray())
                                        // Include device identity if available (required by gateway)
                                        if (deviceId != null && publicKey != null && privateKey != null && nonce.isNotBlank()) {
                                            val authPayload = listOf(
                                                "v3", deviceId, "openclaw-android", "node", "operator",
                                                "operator.read,operator.write", signedAtMs.toString(), token, nonce, "android", "glasses"
                                            ).joinToString("|")
                                            try {
                                                val pkBytes = android.util.Base64.decode(
                                                    privateKey.replace('-', '+').replace('_', '/'),
                                                    android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                                )
                                                val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
                                                signer.init(true, org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(pkBytes, 0))
                                                val payloadBytes = authPayload.toByteArray(Charsets.UTF_8)
                                                signer.update(payloadBytes, 0, payloadBytes.size)
                                                val sigBytes = signer.generateSignature()
                                                val signature = android.util.Base64.encodeToString(
                                                    sigBytes,
                                                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                                )
                                                put("device", JSONObject().apply {
                                                    put("id", deviceId)
                                                    put("publicKey", publicKey)
                                                    put("signature", signature)
                                                    put("signedAt", signedAtMs)
                                                    put("nonce", nonce)
                                                })
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Device identity signing failed: ${e.message}")
                                            }
                                        }
                                    })
                                }
                                webSocket.send(connectFrame.toString())
                            }
                        }

                        // Connect response — send health call
                        type == "res" && msg.optString("id", "") == connectId -> {
                            if (msg.optBoolean("ok", false)) {
                                connected = true
                                val callMsg = JSONObject().apply {
                                    put("type", "req")
                                    put("id", callId)
                                    put("method", "health")
                                    put("params", JSONObject())
                                }
                                webSocket.send(callMsg.toString())
                            } else {
                                errorMsg = "Authentication failed"
                                webSocket.close(1000, "auth failed")
                                latch.countDown()
                            }
                        }

                        // Health response
                        type == "res" && msg.optString("id", "") == callId -> {
                            resultJson = msg
                            webSocket.close(1000, "done")
                            latch.countDown()
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                errorMsg = t.localizedMessage ?: "Connection failed"
                latch.countDown()
            }

            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                if (code == 1008) {
                    errorMsg = "Authentication failed: $reason"
                }
                webSocket.close(1000, null)
                latch.countDown()
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })

        val completed = latch.await(timeoutMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS)

        return when {
            !completed -> jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("endpoint", wsUrl)
                    .put("session_id", sessionId)
                    .put("message", "TapClaw connection timed out.")
            )
            errorMsg != null -> {
                val authFailed = errorMsg!!.contains("unauthorized", ignoreCase = true) ||
                        errorMsg!!.contains("auth", ignoreCase = true)
                jsonResponse(
                    JSONObject()
                        .put("service", "openclaw")
                        .put("status", if (authFailed) "auth_failed" else "failed")
                        .put("endpoint", wsUrl)
                        .put("session_id", sessionId)
                        .put("message", errorMsg)
                )
            }
            resultJson?.has("error") == true -> jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("endpoint", wsUrl)
                    .put("session_id", sessionId)
                    .put("message", resultJson!!.optJSONObject("error")?.optString("message", "Gateway error") ?: "Gateway error")
            )
            resultJson != null -> jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "ok")
                    .put("endpoint", wsUrl)
                    .put("session_id", sessionId)
                    .put("message", "TapClaw is reachable from the glasses.")
            )
            else -> jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("endpoint", wsUrl)
                    .put("session_id", sessionId)
                    .put("message", "No response from TapClaw gateway.")
            )
        }
    }


    private fun openClawDebugJson(debug: OpenClawPairingClient.DebugInfo?): JSONObject? {
        if (debug == null) return null
        return JSONObject()
            .put("endpoint", debug.endpoint)
            .put("gatewayWsUrl", debug.gatewayWsUrl)
            .put("deviceId", debug.deviceId)
            .put("clientId", debug.clientId)
            .put("clientMode", debug.clientMode)
            .put("platform", debug.platform)
            .put("deviceFamily", debug.deviceFamily)
            .put("role", debug.role)
            .put("scopes", JSONArray(debug.scopes))
            .put("bootstrapTokenSuffix", debug.bootstrapTokenSuffix)
            .put("payloadVersion", debug.payloadVersion)
            .put("gatewayErrorCode", debug.gatewayErrorCode ?: JSONObject.NULL)
            .put("gatewayErrorDetails", debug.gatewayErrorDetails ?: JSONObject.NULL)
            .put("closeCode", debug.closeCode ?: JSONObject.NULL)
            .put("closeReason", debug.closeReason ?: JSONObject.NULL)
            .put("lastServerFrame", debug.lastServerFrame ?: JSONObject.NULL)
    }

    private fun pairOpenClaw(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw_pairing")
                    .put("status", "failed")
                    .put("message", "Empty request body.")
            )
        }

        val json = try {
            JSONObject(postData)
        } catch (e: Exception) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw_pairing")
                    .put("status", "failed")
                    .put("message", "Invalid JSON: ${e.message}")
            )
        }

        val setupCode = json.optString("setup_code", "").trim()
        val endpoint = json.optString("endpoint", "").trim()
        if (setupCode.isBlank()) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw_pairing")
                    .put("status", "failed")
                    .put("message", "No OpenClaw setup code was provided.")
            )
        }

        return try {
            when (val result = runBlocking { OpenClawPairingClient(context, prefs).startOrCheckPairing(setupCode, endpoint) }) {
                is OpenClawPairingClient.PairingResult.PendingApproval -> {
                    // Pre-save the endpoint so it's ready when pairing is approved
                    if (result.endpoint.isNotBlank()) {
                        appPreferences.openClawEndpoint = result.endpoint
                    }
                    jsonResponse(
                        JSONObject()
                            .put("service", "openclaw_pairing")
                            .put("status", "pending_approval")
                            .put("endpoint", result.endpoint)
                            .put("requestId", result.requestId)
                            .put("deviceId", result.deviceId)
                            .put("debug", openClawDebugJson(result.debug) ?: JSONObject.NULL)
                            .put("message", "Pairing request created. Approve it on your OpenClaw server, then check again.")
                    )
                }
                is OpenClawPairingClient.PairingResult.Approved -> {
                    // Auto-configure: save the endpoint and enable TapClaw so the
                    // Gemini tool registers on next app restart without manual setup.
                    if (result.endpoint.isNotBlank()) {
                        appPreferences.openClawEndpoint = result.endpoint
                        Log.d(TAG, "TapClaw auto-configured endpoint: ${result.endpoint}")
                    }
                    appPreferences.openClawEnabled = true
                    Log.d(TAG, "TapClaw auto-enabled after successful pairing")

                    jsonResponse(
                        JSONObject()
                            .put("service", "openclaw_pairing")
                            .put("status", "approved")
                            .put("endpoint", result.endpoint)
                            .put("deviceId", result.deviceId)
                            .put("hasDeviceToken", result.hasDeviceToken)
                            .put("debug", openClawDebugJson(result.debug) ?: JSONObject.NULL)
                            .put("message", "TapClaw pairing completed. Integration has been enabled automatically.")
                    )
                }
                is OpenClawPairingClient.PairingResult.Failed -> jsonResponse(
                    JSONObject()
                        .put("service", "openclaw_pairing")
                        .put("status", "failed")
                        .put("endpoint", result.endpoint ?: JSONObject.NULL)
                        .put("debug", openClawDebugJson(result.debug) ?: JSONObject.NULL)
                        .put("message", result.message)
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "OpenClaw pairing error", t)
            jsonResponse(
                JSONObject()
                    .put("service", "openclaw_pairing")
                    .put("status", "failed")
                    .put("message", t.localizedMessage ?: t.javaClass.simpleName ?: "OpenClaw pairing failed.")
            )
        }
    }

    private fun normalizeOpenClawBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty().trimEnd('/')
        if (trimmed.isBlank()) return null

        var normalized = trimmed
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")
            .replace(Regex("/ws/?$"), "")

        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        if (!normalized.matches(Regex("https?://[^/]+:\\d+(?:/.*)?"))) {
            val schemeEnd = normalized.indexOf("://") + 3
            val hostAndPath = normalized.substring(schemeEnd)
            val host = hostAndPath.substringBefore('/')
            if (!host.contains(':')) {
                normalized = normalized.substring(0, schemeEnd) + host + ":18789" + hostAndPath.removePrefix(host)
            }
        }
        return normalized
    }

    private fun jsonResponse(json: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())

    // ── Spotify OAuth (Authorization Code + PKCE) ────────────────────────
    //
    // Spotify's Web Playback SDK and Connect API require a user-authorized
    // access token (Client Credentials alone only allows metadata + 30 s
    // preview clips). We run the full PKCE flow through the on-device
    // companion server so users never have to paste a code manually.
    //
    // Flow:
    //   1. User clicks Connect → browser navigates to /spotify/auth/start
    //   2. Server generates PKCE verifier + state, 302-redirects to
    //      https://accounts.spotify.com/authorize
    //   3. User logs in / approves → Spotify redirects back to
    //      /spotify/callback?code=...&state=...
    //   4. Server exchanges code for access + refresh tokens, fetches
    //      /v1/me for profile (display name, product tier), and shows a
    //      success page.

    /** Scopes required for full playback + library modify + playlist read. */
    private val spotifyScopes = listOf(
        "streaming",
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "user-read-private",
        "user-read-email",
        "user-library-read",
        "user-library-modify",
        "playlist-read-private",
        "playlist-modify-public",
        "playlist-modify-private"
    ).joinToString(" ")

    /** Generate a URL-safe base64 string of [numBytes] random bytes (no padding). */
    private fun generateRandomUrlSafe(numBytes: Int): String {
        val bytes = ByteArray(numBytes)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    /** Compute the S256 code challenge for a PKCE verifier. */
    private fun pkceChallengeFor(verifier: String): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(
            sha256,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
    }

    /**
     * Return the Spotify redirect URI to use for this server.
     *
     * Spotify is STRICT: the redirect_uri sent to /authorize and the one
     * sent later to /api/token must be byte-for-byte identical to one of
     * the URIs registered on the Spotify developer dashboard, or the user
     * sees a generic "redirect_uri: Not matching configuration" error
     * before the consent page even loads.
     *
     * As of Spotify's April-2025 developer policy update, `localhost` is
     * no longer an accepted HTTP redirect host — only the literal loopback
     * IP (`127.0.0.1` or `[::1]`) is allowed for plain `http://`, and
     * everything else must be `https://` with an FQDN.
     *
     * The companion server listens on all interfaces, so the request's
     * Host header could be anything: `localhost:19110`, `127.0.0.1:19110`,
     * the glasses' LAN IP, or `[::1]:19110`. We **always** normalise to
     * the canonical loopback form `http://127.0.0.1:19110/spotify/callback`
     * (or the https equivalent when TLS is enabled) so the value the user
     * pastes into their Spotify app dashboard matches what the server
     * actually sends, regardless of which hostname they used to open the
     * companion UI.
     */
    private fun spotifyRedirectUri(session: IHTTPSession): String =
        spotifyCanonicalRedirectUri()

    /** GET /spotify/auth/start — redirects user to Spotify's consent page. */
    private fun handleSpotifyAuthStart(session: IHTTPSession): Response {
        val clientId = appPreferences.spotifyClientId.trim()
        if (clientId.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html",
                oauthResultPage(false,
                    "No Spotify Client ID configured.<br><br>" +
                        "Open Setup → Spotify and paste your Client ID from " +
                        "<a href='https://developer.spotify.com/dashboard'>developer.spotify.com/dashboard</a>, " +
                        "then try Connect again.")
            )
        }

        val verifier = generateRandomUrlSafe(64)   // 64 bytes → 86-char base64url (Spotify requires 43-128)
        val challenge = pkceChallengeFor(verifier)
        val state = generateRandomUrlSafe(24)

        // Persist verifier + state so /spotify/callback can validate + exchange
        appPreferences.spotifyPkceVerifier = verifier
        appPreferences.spotifyAuthState = state

        val redirectUri = spotifyRedirectUri(session)
        // Remember the redirect URI we used, so the callback exchange posts the
        // exact same value back to Spotify (Spotify requires the two to match).
        appPreferences.spotifyRedirectUri = redirectUri

        val authUrl = buildString {
            append("https://accounts.spotify.com/authorize")
            append("?client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            append("&response_type=code")
            append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
            append("&code_challenge_method=S256")
            append("&code_challenge=").append(URLEncoder.encode(challenge, "UTF-8"))
            append("&state=").append(URLEncoder.encode(state, "UTF-8"))
            append("&scope=").append(URLEncoder.encode(spotifyScopes, "UTF-8"))
            append("&show_dialog=false")
        }

        Log.d(TAG, "Spotify auth start: redirect=$redirectUri, scopes=$spotifyScopes")

        // 302 redirect to Spotify's consent page.
        val resp = newFixedLengthResponse(Response.Status.REDIRECT, "text/html",
            "<html><body>Redirecting to Spotify…<br><a href=\"$authUrl\">Click here if not redirected.</a></body></html>")
        resp.addHeader("Location", authUrl)
        return resp
    }

    /** GET /spotify/callback?code=…&state=… — exchanges code for tokens. */
    private fun handleSpotifyCallback(session: IHTTPSession): Response {
        val params = session.parms ?: emptyMap()
        val code = params["code"]?.trim()
        val state = params["state"]?.trim()
        val error = params["error"]?.trim()

        if (!error.isNullOrBlank()) {
            Log.w(TAG, "Spotify callback error: $error")
            return newFixedLengthResponse(
                Response.Status.OK, "text/html",
                oauthResultPage(false, "Spotify authorization denied: $error")
            )
        }

        if (code.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html",
                oauthResultPage(false, "No authorization code received from Spotify.")
            )
        }

        val expectedState = appPreferences.spotifyAuthState
        if (expectedState.isBlank() || state != expectedState) {
            Log.w(TAG, "Spotify callback state mismatch (expected=$expectedState, got=$state)")
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html",
                oauthResultPage(false,
                    "Authorization state mismatch (possible CSRF). Start over from the Connect button.")
            )
        }

        val verifier = appPreferences.spotifyPkceVerifier
        if (verifier.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html",
                oauthResultPage(false,
                    "Missing PKCE verifier. Start over from the Connect button.")
            )
        }

        val clientId = appPreferences.spotifyClientId.trim()
        val clientSecret = appPreferences.spotifyClientSecret.trim()
        if (clientId.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html",
                oauthResultPage(false, "Spotify Client ID is not configured.")
            )
        }

        val redirectUri = appPreferences.spotifyRedirectUri.ifBlank { spotifyRedirectUri(session) }

        val tokenResult = runBlocking {
            spotifyExchangeCodeForTokens(
                code = code,
                verifier = verifier,
                redirectUri = redirectUri,
                clientId = clientId,
                clientSecret = clientSecret
            )
        }

        if (!tokenResult.success) {
            Log.e(TAG, "Spotify token exchange failed: ${tokenResult.errorDetail}")
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html",
                oauthResultPage(false,
                    "Spotify token exchange failed: ${tokenResult.errorDetail}" +
                        "<br><br><small>Redirect URI used: $redirectUri</small>")
            )
        }

        // Clear the one-shot PKCE material
        appPreferences.spotifyPkceVerifier = ""
        appPreferences.spotifyAuthState = ""

        val displayName = appPreferences.spotifyUserDisplayName.ifBlank { "your Spotify account" }
        val product = appPreferences.spotifyUserProduct
        val premiumNote = if (product.equals("premium", ignoreCase = true)) {
            "Premium detected — full-track playback is unlocked."
        } else {
            "Account tier: $product. Full-track Web Playback requires a Spotify Premium subscription; " +
                "free accounts fall back to 30-second previews."
        }

        return newFixedLengthResponse(
            Response.Status.OK, "text/html",
            oauthResultPage(true,
                "Connected $displayName to TapInsight.<br><br>$premiumNote<br><br>You can close this tab.")
        )
    }

    /** Result of a Spotify token request. */
    private data class SpotifyTokenResult(
        val success: Boolean,
        val errorDetail: String = ""
    )

    /**
     * POST to https://accounts.spotify.com/api/token exchanging an auth code
     * (with PKCE verifier) for access + refresh tokens. Writes the resulting
     * tokens + user profile into [appPreferences].
     */
    private suspend fun spotifyExchangeCodeForTokens(
        code: String,
        verifier: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String
    ): SpotifyTokenResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val form = buildString {
                append("grant_type=authorization_code")
                append("&code=").append(URLEncoder.encode(code, "UTF-8"))
                append("&redirect_uri=").append(URLEncoder.encode(redirectUri, "UTF-8"))
                append("&code_verifier=").append(URLEncoder.encode(verifier, "UTF-8"))
                append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            }

            val url = java.net.URL("https://accounts.spotify.com/api/token")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (clientSecret.isNotBlank()) {
                // Confidential client — include Basic auth. PKCE-only public
                // clients would omit this header.
                val basic = android.util.Base64.encodeToString(
                    "$clientId:$clientSecret".toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                conn.setRequestProperty("Authorization", "Basic $basic")
            }
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }

            val code2 = conn.responseCode
            val body = try {
                val stream = if (code2 in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (_: Exception) { "" }
            conn.disconnect()

            if (code2 !in 200..299) {
                return@withContext SpotifyTokenResult(
                    success = false,
                    errorDetail = "HTTP $code2 — ${body.take(300)}"
                )
            }

            val json = JSONObject(body)
            val accessToken = json.optString("access_token", "").trim()
            val refreshToken = json.optString("refresh_token", "").trim()
            val expiresIn = json.optLong("expires_in", 3600L)
            val scope = json.optString("scope", "").trim()

            if (accessToken.isBlank()) {
                return@withContext SpotifyTokenResult(
                    success = false,
                    errorDetail = "Token endpoint returned empty access_token"
                )
            }

            val now = System.currentTimeMillis()
            appPreferences.spotifyAccessToken = accessToken
            if (refreshToken.isNotBlank()) {
                appPreferences.spotifyRefreshToken = refreshToken
            }
            // Subtract a 60 s safety margin so we refresh before the server
            // rejects us for a stale token.
            appPreferences.spotifyAccessTokenExpiryMs = now + (expiresIn - 60L) * 1000L
            if (scope.isNotBlank()) appPreferences.spotifyScopes = scope

            // Best-effort profile fetch. Failures here do not invalidate the token.
            try {
                spotifyFetchUserProfile(accessToken)
            } catch (e: Exception) {
                Log.w(TAG, "Spotify profile fetch after token exchange failed: ${e.message}")
            }

            SpotifyTokenResult(success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Spotify token exchange exception", e)
            SpotifyTokenResult(
                success = false,
                errorDetail = e.message?.replace("\"", "'") ?: e.javaClass.simpleName
            )
        }
    }

    /** GET /v1/me to learn display_name + product tier, writes both to prefs. */
    private fun spotifyFetchUserProfile(accessToken: String) {
        val url = java.net.URL("https://api.spotify.com/v1/me")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        val code = conn.responseCode
        val body = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (_: Exception) { "" }
        conn.disconnect()
        if (code !in 200..299) {
            Log.w(TAG, "Spotify /v1/me returned HTTP $code: ${body.take(200)}")
            return
        }
        val json = JSONObject(body)
        appPreferences.spotifyUserDisplayName = json.optString("display_name", "").trim()
        appPreferences.spotifyUserId = json.optString("id", "").trim()
        appPreferences.spotifyUserProduct = json.optString("product", "").trim()
    }

    /** Exchange a stored refresh_token for a fresh access_token. */
    private suspend fun spotifyRefreshAccessToken(): SpotifyTokenResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val refresh = appPreferences.spotifyRefreshToken
            val clientId = appPreferences.spotifyClientId.trim()
            val clientSecret = appPreferences.spotifyClientSecret.trim()
            if (refresh.isBlank() || clientId.isBlank()) {
                return@withContext SpotifyTokenResult(false, "No refresh_token or Client ID on file")
            }
            try {
                val form = buildString {
                    append("grant_type=refresh_token")
                    append("&refresh_token=").append(URLEncoder.encode(refresh, "UTF-8"))
                    append("&client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
                }
                val url = java.net.URL("https://accounts.spotify.com/api/token")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                if (clientSecret.isNotBlank()) {
                    val basic = android.util.Base64.encodeToString(
                        "$clientId:$clientSecret".toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    conn.setRequestProperty("Authorization", "Basic $basic")
                }
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = try {
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
                conn.disconnect()
                if (code !in 200..299) {
                    return@withContext SpotifyTokenResult(false, "HTTP $code — ${body.take(200)}")
                }
                val json = JSONObject(body)
                val accessToken = json.optString("access_token", "").trim()
                val expiresIn = json.optLong("expires_in", 3600L)
                val newRefresh = json.optString("refresh_token", "").trim()
                if (accessToken.isBlank()) {
                    return@withContext SpotifyTokenResult(false, "Refresh returned empty access_token")
                }
                val now = System.currentTimeMillis()
                appPreferences.spotifyAccessToken = accessToken
                appPreferences.spotifyAccessTokenExpiryMs = now + (expiresIn - 60L) * 1000L
                if (newRefresh.isNotBlank()) {
                    appPreferences.spotifyRefreshToken = newRefresh
                }
                SpotifyTokenResult(true)
            } catch (e: Exception) {
                Log.e(TAG, "Spotify refresh exception", e)
                SpotifyTokenResult(false, e.message?.replace("\"", "'") ?: e.javaClass.simpleName)
            }
        }

    /** GET /api/spotify/status — JSON view of Spotify OAuth state for the companion UI. */
    private fun serveSpotifyStatus(): Response {
        val hasToken = appPreferences.hasSpotifyUserTokens()
        val isValid = appPreferences.isSpotifyUserTokenValid()
        // Always report the canonical redirect URI the server will
        // actually send to Spotify — i.e., the value produced by
        // [spotifyRedirectUri].  The stored pref can be blank (before
        // the user ever clicks Connect) or stale (if `httpsEnabled`
        // changed between launches), and a mismatch here is exactly
        // what produces Spotify's "redirect_uri: Not matching
        // configuration" error.  Publishing the authoritative value
        // lets the companion UI populate the "paste-this-in-Spotify"
        // hint with a string that is guaranteed to match.
        val canonicalRedirect = spotifyCanonicalRedirectUri()
        val json = JSONObject().apply {
            put("authorized", hasToken)
            put("token_valid", isValid)
            put("expiry_ms", appPreferences.spotifyAccessTokenExpiryMs)
            put("display_name", appPreferences.spotifyUserDisplayName)
            put("user_id", appPreferences.spotifyUserId)
            put("product", appPreferences.spotifyUserProduct)
            put("scopes", appPreferences.spotifyScopes)
            put("client_id_set", appPreferences.spotifyClientId.isNotBlank())
            put("client_secret_set", appPreferences.spotifyClientSecret.isNotBlank())
            put("redirect_uri", canonicalRedirect)
            put("https_enabled", httpsEnabled)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * Session-free version of [spotifyRedirectUri].  Used by
     * [serveSpotifyStatus] (which is hit before any /authorize call)
     * and by logging so the value stays consistent regardless of
     * caller.
     */
    private fun spotifyCanonicalRedirectUri(): String {
        val scheme = if (httpsEnabled) "https" else "http"
        val port = runCatching { listeningPort }.getOrNull()?.takeIf { it > 0 } ?: 19110
        return "$scheme://127.0.0.1:$port/spotify/callback"
    }

    /** POST /api/spotify/disconnect — wipe all stored Spotify user tokens. */
    private fun handleSpotifyDisconnect(): Response {
        appPreferences.clearSpotifyUserTokens()
        return newFixedLengthResponse(
            Response.Status.OK, "application/json",
            """{"status":"disconnected"}"""
        )
    }

    /** POST /api/spotify/refresh — force a refresh_token exchange. */
    private fun handleSpotifyRefresh(): Response {
        val result = runBlocking { spotifyRefreshAccessToken() }
        return if (result.success) {
            newFixedLengthResponse(
                Response.Status.OK, "application/json",
                JSONObject()
                    .put("status", "refreshed")
                    .put("expiry_ms", appPreferences.spotifyAccessTokenExpiryMs)
                    .toString()
            )
        } else {
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                JSONObject()
                    .put("status", "error")
                    .put("error", result.errorDetail)
                    .toString()
            )
        }
    }

    /** Simple HTML result page shown after OAuth redirect. */
    private fun oauthResultPage(success: Boolean, message: String): String {
        val color = if (success) "#4caf50" else "#f44336"
        val icon = if (success) "&#10003;" else "&#10007;"
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>TapInsight OAuth</title>
            <style>
              body { font-family: -apple-system, sans-serif; display: flex; justify-content: center;
                     align-items: center; height: 100vh; margin: 0; background: #111; color: #eee; }
              .card { text-align: center; padding: 40px; border-radius: 12px; background: #1a1a1a;
                      border: 1px solid #333; max-width: 400px; }
              .icon { font-size: 48px; color: $color; margin-bottom: 16px; }
              .msg { font-size: 16px; line-height: 1.5; }
              a { color: #6ea8fe; }
            </style></head>
            <body><div class="card">
              <div class="icon">$icon</div>
              <div class="msg">$message</div>
              ${if (success) "" else "<p><a href='/'>Back to Setup</a></p>"}
            </div></body></html>
        """.trimIndent()
    }

    // ── Media Library endpoints ────────────────────────────────────────────

    /** Return info about the on-device media library root path. */
    private fun serveLibraryRootInfo(): Response {
        val root = mediaLibrary.mediaRoot
        val free = try { root.freeSpace } catch (e: Exception) { 0L }
        val total = try { root.totalSpace } catch (e: Exception) { 0L }
        val json = JSONObject()
            .put("rootAbsolute", root.absolutePath)
            .put("rootShortHint", "Android/data/${context.packageName}/files/Media")
            .put("freeBytes", free)
            .put("totalBytes", total)
            .put("bootstrapped", true)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * POST /api/library/ensure-dashboard-icon
     *
     * Idempotently inserts the Media Library app into the Navigation / Entertainment
     * sub-section of the persisted TapLink Dashboard.  The same dashboard JSON
     * feeds `/api/dashboard`, the companion editor, AND (via AndroidInterface's
     * getDashboardData) the on-glasses `dashboardLinksV1` localStorage key.
     *
     * Response:
     *   { "added": true,  "message": "...", "dashboard": {...} }   // inserted
     *   { "added": false, "message": "...", "dashboard": {...} }   // already present
     */
    private fun ensureLibraryDashboardIcon(session: IHTTPSession): Response {
        val raw = prefs.getString(DASHBOARD_PREFS_KEY, null)
        val dashboard = try {
            if (raw.isNullOrBlank() || raw == "{}") JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt dashboard JSON on ensure-icon — rebuilding fresh", e)
            JSONObject()
        }

        val apps = dashboard.optJSONObject("apps") ?: JSONObject().also {
            dashboard.put("apps", it)
        }
        val groups = dashboard.optJSONArray("groups") ?: JSONArray().also {
            dashboard.put("groups", it)
        }

        var changed = false
        var wasAlreadyComplete = true

        // 1. Ensure the `medialibrary` app entry exists with the correct URL.
        //    Any pre-existing entry pointing at the retired launcher or the
        //    old server-backed /library endpoint is auto-healed forward to
        //    the glasses-local bridge page. Users who explicitly customized
        //    the URL keep their override.
        val CORRECT_LIBRARY_URL = "file:///android_asset/library_local.html"
        val OWNED_LIBRARY_URLS = setOf(
            "file:///android_asset/library_launcher.html",
            "file:///android_asset/library_local.html",
            "https://127.0.0.1:19110/library",
            "http://127.0.0.1:19110/library"
        )
        val existingApp = apps.optJSONObject("medialibrary")
        if (existingApp == null) {
            apps.put("medialibrary", JSONObject()
                .put("name", "Media Library")
                .put("url", CORRECT_LIBRARY_URL))
            changed = true
            wasAlreadyComplete = false
        } else {
            val curUrl = existingApp.optString("url", "")
            if (OWNED_LIBRARY_URLS.contains(curUrl) && curUrl != CORRECT_LIBRARY_URL) {
                existingApp.put("url", CORRECT_LIBRARY_URL)
                changed = true
                wasAlreadyComplete = false
            }
        }

        // 2. Locate (or create) the Navigation / Entertainment group and ensure
        //    'medialibrary' is in its keys list.
        var navGroup: JSONObject? = null
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val title = g.optString("title", "").trim().lowercase()
            if (title == "navigation / entertainment") { navGroup = g; break }
        }
        if (navGroup == null) {
            navGroup = JSONObject()
                .put("title", "Navigation / Entertainment")
                .put("cls", "sec-nav")
                .put("keys", JSONArray().put("medialibrary"))
            // Insert as the first group so the Media Library lives at the top.
            val rebuilt = JSONArray().put(navGroup)
            for (i in 0 until groups.length()) rebuilt.put(groups.get(i))
            dashboard.put("groups", rebuilt)
            changed = true
            wasAlreadyComplete = false
        } else {
            val keys = navGroup.optJSONArray("keys") ?: JSONArray().also {
                navGroup.put("keys", it)
            }
            var alreadyIn = false
            for (i in 0 until keys.length()) {
                if (keys.optString(i) == "medialibrary") { alreadyIn = true; break }
            }
            if (!alreadyIn) {
                // Unshift: rebuild array with medialibrary first.
                val rebuilt = JSONArray().put("medialibrary")
                for (i in 0 until keys.length()) rebuilt.put(keys.get(i))
                navGroup.put("keys", rebuilt)
                changed = true
                wasAlreadyComplete = false
            }
        }

        if (changed) {
            prefs.edit().putString(DASHBOARD_PREFS_KEY, dashboard.toString()).apply()
            Log.i(TAG, "Added Media Library icon to TapLink Dashboard (persisted)")
        }

        val resp = JSONObject()
            .put("added", !wasAlreadyComplete)
            .put("message", if (wasAlreadyComplete)
                "Media Library icon already in Navigation / Entertainment."
            else
                "Media Library icon added to Navigation / Entertainment. Re-launch TapBrowser on the glasses to see it.")
            .put("dashboard", dashboard)
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /** GET /api/library/list?path=<relative>  → FolderListing as JSON. */
    private fun serveLibraryList(session: IHTTPSession): Response {
        val path = session.parms?.get("path") ?: ""
        val listing = mediaLibrary.listFolder(path)
            ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"Folder not found or escapes media root"}"""
            )
        val arr = JSONArray()
        for (entry in listing.entries) {
            arr.put(mediaEntryToJson(entry))
        }
        val json = JSONObject()
            .put("relativePath", listing.relativePath)
            .put("breadcrumbs", buildBreadcrumbs(listing.relativePath))
            .put("entries", arr)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /** GET /api/library/playlist?path=<relative>  → ParsedPlaylist with playable URLs. */
    private fun serveLibraryPlaylist(session: IHTTPSession): Response {
        val path = session.parms?.get("path")
            ?: return badRequest("Missing ?path= parameter")
        val file = mediaLibrary.resolveSafe(path)
            ?: return badRequest("Invalid playlist path")
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "application/json",
                """{"error":"Playlist not found"}"""
            )
        }
        val parsed = mediaLibrary.parsePlaylist(file)
        val token = sessionToken
        val arr = JSONArray()
        for (entry in parsed.entries) {
            arr.put(playlistEntryToJson(entry, token))
        }
        val warnings = JSONArray()
        for (w in parsed.warnings) warnings.put(w)
        val json = JSONObject()
            .put("name", parsed.name)
            .put("relativePath", mediaLibrary.relativize(file))
            .put("entries", arr)
            .put("warnings", warnings)
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * POST /api/library/playlist  body: { path, entries: [{ targetPathOrUrl, title, durationSeconds? }] }
     * Writes the playlist to disk.
     */
    private fun saveLibraryPlaylist(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: return badRequest("Empty body")

        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON")
        }
        val path = json.optString("path")
        if (path.isBlank()) return badRequest("Missing path")
        val target = mediaLibrary.resolveSafe(path) ?: return badRequest("Invalid path")

        val entriesJson = json.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<MediaLibraryService.PlaylistWriteEntry>()
        for (i in 0 until entriesJson.length()) {
            val e = entriesJson.optJSONObject(i) ?: continue
            val pathOrUrl = e.optString("targetPathOrUrl").trim()
            if (pathOrUrl.isEmpty()) continue
            val title = e.optString("title").ifBlank {
                pathOrUrl.substringAfterLast('/').substringBeforeLast('.')
            }
            val dur = if (e.has("durationSeconds") && !e.isNull("durationSeconds"))
                e.optInt("durationSeconds") else null
            entries.add(
                MediaLibraryService.PlaylistWriteEntry(
                    targetPathOrUrl = pathOrUrl,
                    title = title,
                    durationSeconds = dur
                )
            )
        }

        val ok = mediaLibrary.writePlaylist(target, entries)
        val resp = JSONObject()
            .put("status", if (ok) "saved" else "error")
            .put("path", mediaLibrary.relativize(target))
            .put("entryCount", entries.size)
        return newFixedLengthResponse(
            if (ok) Response.Status.OK else Response.Status.INTERNAL_ERROR,
            "application/json",
            resp.toString()
        )
    }

    /**
     * POST /api/library/write
     *
     * JSON body:
     *   {
     *     "filename": "stations.m3u",       // or "name"; optional if path includes a filename
     *     "folder": "Playlists",           // optional; defaults by extension/kind
     *     "path": "Playlists/stations.m3u",// optional exact relative path
     *     "kind": "playlist|text|audio|video",
     *     "content": "#EXTM3U\n...",
     *     "overwrite": false
     *   }
     *
     * This gives TapClaw/OpenClaw a deterministic way to create text and M3U
     * assets on the glasses without guessing the app-private filesystem path.
     */
    private fun writeLibraryFile(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: return badRequest("Empty body")
        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON")
        }

        val content = json.optString("content", "")
        if (content.isEmpty()) return badRequest("Missing content")

        val kind = json.optString("kind", "")
        val explicitPath = json.optString("path", "").trim().trimStart('/', '\\')
        val folderHint = json.optString("folder", "").trim().trim('/', '\\')
        val requestedName = json.optString("filename", "")
            .ifBlank { json.optString("name", "") }
            .trim()
        val overwrite = json.optBoolean("overwrite", false)

        val relativePath = when {
            explicitPath.isNotBlank() && !explicitPath.endsWith("/") && !explicitPath.endsWith("\\") ->
                explicitPath
            else -> {
                val rawName = requestedName.ifBlank { defaultLibraryFilename(kind) }
                val safeName = ensureLibraryExtension(sanitizeFilename(rawName), kind)
                    .ifBlank { defaultLibraryFilename(kind) }
                val folder = if (explicitPath.isNotBlank()) {
                    explicitPath.trimEnd('/', '\\')
                } else {
                    folderHint.ifBlank { mediaLibrary.defaultFolderForFilename(safeName, kind) }
                }
                if (folder.isBlank()) safeName else "$folder/$safeName"
            }
        }

        val target = mediaLibrary.resolveSafe(relativePath)
            ?: return badRequest("Invalid path")
        target.parentFile?.mkdirs()
        val destination = if (target.exists() && !overwrite) {
            uniqueFile(target.parentFile ?: mediaLibrary.mediaRoot, target.name)
        } else {
            target
        }

        // ─── Encoding: text vs base64 (binary) ───────────────────────────
        // Historically this endpoint only handled text files (.txt, .md,
        // .m3u, etc.) and just `writeText`'d the body. JSON can't carry
        // binary, so when OpenClaw saved an MP3/MP4 here it had to
        // base64-encode the bytes to fit them in `content`. The handler
        // wrote that base64 STRING straight to disk, which is what
        // produced the "MP3 file is actually 14MB of ASCII text" bug.
        //
        // Two paths now:
        //   • Explicit:   {"encoding": "base64"}  → caller is telling us
        //                 the content is base64 and we MUST decode before
        //                 writing.
        //   • Defensive:  no encoding field, but the destination filename
        //                 is a known-binary extension AND the content
        //                 looks like base64 → auto-decode and log a
        //                 warning so we can chase the upstream caller
        //                 that didn't set the field.
        // Plain text writes are unchanged.
        val explicitEncoding = json.optString("encoding", "").trim().lowercase()
        val isBinaryExtension = isBinaryLibraryExtension(destination.name)
        val shouldDecodeBase64 = when (explicitEncoding) {
            "base64" -> true
            "" -> isBinaryExtension && contentLooksLikeBase64(content)
            else -> false
        }

        return try {
            if (shouldDecodeBase64) {
                val decoded = try {
                    java.util.Base64.getMimeDecoder().decode(content)
                } catch (e: IllegalArgumentException) {
                    return badRequest("Content is not valid base64: ${e.message}")
                }
                destination.writeBytes(decoded)
                if (explicitEncoding != "base64") {
                    Log.w(
                        TAG,
                        "writeLibraryFile: ${destination.name} arrived with " +
                            "no encoding= field but content looks base64 and the " +
                            "filename is a binary type. Auto-decoded ${decoded.size} " +
                            "bytes. Caller should set encoding=\"base64\" explicitly."
                    )
                }
            } else {
                val normalizedContent = normalizeLibraryTextContent(destination.name, content)
                destination.writeText(normalizedContent, Charsets.UTF_8)
            }
            val rel = mediaLibrary.relativize(destination)
            val kindName = mediaLibrary.classify(destination).name
            val resp = JSONObject()
                .put("status", "saved")
                .put("name", destination.name)
                .put("path", rel)
                .put("relativePath", rel)
                .put("kind", kindName)
                .put("sizeBytes", destination.length())
                .put("folder", destination.parentFile?.let { mediaLibrary.relativize(it) }.orEmpty())
                .put("rootShortHint", "Android/data/${context.packageName}/files/Media")
            newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
        } catch (e: Exception) {
            Log.w(TAG, "writeLibraryFile failed for $relativePath: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("status", "error").put("error", e.message ?: "Write failed").toString()
            )
        }
    }

    /** POST /api/library/generate  body: { folder }  → auto-create playlist. */
    private fun generateLibraryPlaylist(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: return badRequest("Empty body")
        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON")
        }
        val folder = json.optString("folder")
        val created = mediaLibrary.generatePlaylistForFolder(folder)
            ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json",
                """{"error":"No playable files in folder or invalid path"}"""
            )
        val resp = JSONObject()
            .put("status", "created")
            .put("path", mediaLibrary.relativize(created))
            .put("name", created.name)
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /**
     * POST /api/library/upload (multipart/form-data)
     * Fields:
     *   folder = <target folder relative path>
     *   file = <the uploaded file>  (one or more)
     * Moves the NanoHTTPD temp files into the target folder, preserving original filename.
     */
    private fun uploadLibraryMedia(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return badRequest("Upload parse failed: ${e.message}")
        }
        val folder = session.parms?.get("folder") ?: ""
        val target = mediaLibrary.resolveSafe(folder)
            ?: return badRequest("Invalid folder")
        if (!target.exists()) target.mkdirs()
        if (!target.isDirectory) return badRequest("Target is not a folder")
        val autoRouteFromRoot = folder.isBlank()

        val saved = JSONArray()
        val errors = JSONArray()
        for ((fieldName, tempPath) in files) {
            if (fieldName == "postData") continue
            val tempFile = File(tempPath)
            if (!tempFile.exists()) continue
            val originalName = session.parms?.get(fieldName) ?: tempFile.name
            val safeName = sanitizeFilename(originalName).ifBlank { "upload-${System.currentTimeMillis()}" }
            val effectiveTarget = if (autoRouteFromRoot) {
                mediaLibrary.resolveSafe(mediaLibrary.defaultFolderForFilename(safeName))
                    ?: target
            } else {
                target
            }
            if (!effectiveTarget.exists()) effectiveTarget.mkdirs()
            // Avoid overwrite — append numeric suffix if needed
            val destination = uniqueFile(effectiveTarget, safeName)
            try {
                FileInputStream(tempFile).use { input ->
                    FileOutputStream(destination).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.delete()
                saved.put(
                    JSONObject()
                        .put("name", destination.name)
                        .put("relativePath", mediaLibrary.relativize(destination))
                        .put("sizeBytes", destination.length())
                )
            } catch (e: Exception) {
                errors.put("${originalName}: ${e.message}")
                Log.w(TAG, "Upload copy failed for $originalName: ${e.message}")
            }
        }
        val resp = JSONObject()
            .put("status", if (errors.length() == 0) "ok" else "partial")
            .put("folder", mediaLibrary.relativize(target))
            .put("saved", saved)
            .put("errors", errors)
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /**
     * POST /api/library/mkdir  body: { path }  → create a folder (nested
     * paths allowed) inside the media library. Same path-safety checks as
     * the sibling library routes: ".." rejected outright, then
     * resolveSafe() confines the target to the library root.
     */
    private fun mkdirLibraryFolder(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        return try {
            session.parseBody(body)
            val json = JSONObject(body["postData"] ?: "{}")
            val rawPath = json.optString("path", "").trim().trim('/')
            if (rawPath.isBlank()) {
                return badRequest("Missing folder path")
            }
            if (rawPath.contains("..")) {
                return badRequest("Invalid folder path")
            }
            val target = mediaLibrary.resolveSafe(rawPath)
                ?: return badRequest("Invalid folder path")
            if (target.isFile) {
                return badRequest("A file with that name already exists")
            }
            if (!target.isDirectory && !target.mkdirs()) {
                return badRequest("Could not create folder")
            }
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                JSONObject()
                    .put("status", "ok")
                    .put("relativePath", mediaLibrary.relativize(target))
                    .toString()
            )
        } catch (e: Exception) {
            badRequest("mkdir failed: ${e.message}")
        }
    }

    /** POST /api/library/delete  body: { path }. */
    private fun deleteLibraryEntry(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: return badRequest("Empty body")
        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON")
        }
        val path = json.optString("path")
        if (path.isBlank()) return badRequest("Missing path")
        val ok = mediaLibrary.deleteEntry(path)
        val resp = JSONObject()
            .put("status", if (ok) "deleted" else "error")
            .put("path", path)
        return newFixedLengthResponse(
            if (ok) Response.Status.OK else Response.Status.BAD_REQUEST,
            "application/json",
            resp.toString()
        )
    }

    // ── Custom chat-panel orb image ─────────────────────────────────────
    //
    // Endpoints used by companion/orb.html. The cropper UI on the page
    // produces a square PNG (the bounds of the circular crop) plus
    // optionally the user's untouched original image (so they can
    // re-open the cropper later and reposition without re-uploading).
    // The chat panel reads the cropped square at display time and applies
    // a circular outline clip via ViewOutlineProvider — that is what
    // makes the orb look round in the AR glasses panel even though the
    // file on disk is a square. The visibility flag is independent: the
    // orb can be hidden entirely (no glow, no image) regardless of
    // whether a custom image is present.

    private val orbStore: OrbImageStore by lazy { OrbImageStore(context) }

    private fun serveOrbState(): Response {
        val resp = JSONObject()
            .put("visible", appPreferences.chatOrbVisible)
            .put("hasCustom", orbStore.hasCustom())
            .put("hasOriginal", orbStore.hasOriginal())
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    private fun saveOrbVisibility(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        try {
            session.parseBody(body)
        } catch (e: Exception) {
            return badRequest("Bad body: ${e.message}")
        }
        val postData = body["postData"] ?: return badRequest("Empty body")
        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON")
        }
        val visible = json.optBoolean("visible", true)
        appPreferences.chatOrbVisible = visible
        Log.d(TAG, "Orb visibility set to $visible")
        val resp = JSONObject().put("status", "ok").put("visible", visible)
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /**
     * Multipart upload with field name "file". `original=true` saves to
     * the originals slot (so the cropper can resume), `false` to the
     * cropped slot (what the chat panel renders).
     */
    private fun uploadOrbImage(session: IHTTPSession, original: Boolean): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return badRequest("Upload parse failed: ${e.message}")
        }
        val tempPath = files["file"] ?: files.entries.firstOrNull { it.key != "postData" }?.value
            ?: return badRequest("Missing 'file' field")
        val tempFile = File(tempPath)
        if (!tempFile.exists() || tempFile.length() == 0L) {
            return badRequest("Empty upload")
        }
        // Hard limit on uploads: 5 MB. Cropped is typically <100 KB; the
        // original might be larger but no reason to accept multi-MB shots.
        val maxBytes = 5L * 1024L * 1024L
        if (tempFile.length() > maxBytes) {
            tempFile.delete()
            return badRequest("Upload too large (${tempFile.length()} bytes; max ${maxBytes})")
        }
        val bytes = tempFile.readBytes()
        tempFile.delete()
        if (original) orbStore.saveOriginal(bytes) else orbStore.saveCropped(bytes)
        Log.d(
            TAG,
            "Orb upload saved original=$original size=${bytes.size}B " +
                "to=${if (original) "custom_orb_original.png" else "custom_orb.png"}"
        )
        val resp = JSONObject()
            .put("status", "ok")
            .put("kind", if (original) "original" else "cropped")
            .put("sizeBytes", bytes.size)
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /**
     * GET /api/orb/preview?which=cropped|original
     * Streams the requested file as image/png. 404 when the file is
     * not present (companion page should treat that as "no image yet").
     */
    private fun serveOrbPreview(session: IHTTPSession): Response {
        val which = session.parms?.get("which")?.lowercase().orEmpty()
        val targetFile = when (which) {
            "original" -> if (orbStore.hasOriginal()) orbStore.originalFile() else null
            "cropped", "" -> if (orbStore.hasCustom()) orbStore.customFile() else null
            else -> null
        } ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND, "text/plain", "No orb image"
        )
        val bytes = targetFile.readBytes()
        val response = newFixedLengthResponse(
            Response.Status.OK, "image/png",
            ByteArrayInputStream(bytes), bytes.size.toLong()
        )
        // Disable caching so the page sees the freshest crop right after upload.
        response.addHeader("Cache-Control", "no-store, max-age=0")
        return response
    }

    private fun resetOrbImage(): Response {
        orbStore.deleteAll()
        Log.d(TAG, "Orb image reset to default")
        val resp = JSONObject().put("status", "reset")
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    // ── TapBrowser bookmarks editor ─────────────────────────────────────
    //
    // The bookmarks the user sees inside TapBrowser are stored in
    // tapbrowser/BookmarkManager → SharedPreferences("BookmarkPrefs",
    // "bookmarks"). Distinct from main-app bookmarks in visionclaw_prefs
    // — that's a separate, app-level list. The browser-side list is the
    // one that actually appears in the on-glasses browser UI, so the
    // companion's Browser tab edits THIS one.
    //
    // Schema (matches tapbrowser/BookmarkEntry.kt):
    //   [{ "id": "<uuid>", "url": "https://...", "isHome": false }, ...]
    //
    // The companion may add new entries with empty/missing id; the GET
    // path normalises by filling in any missing ids on read so clients
    // don't have to know about UUIDs. The POST validates URLs and
    // ensures exactly one entry has isHome=true (defaults to first).

    private fun browserPrefs() =
        context.getSharedPreferences("BookmarkPrefs", Context.MODE_PRIVATE)

    private fun tapBrowserPrefs() =
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)

    private fun serveBrowserBookmarks(): Response {
        val prefs = browserPrefs()
        val raw = prefs.getString("bookmarks", "[]") ?: "[]"
        Log.d(
            TAG,
            "serveBrowserBookmarks: BookmarkPrefs/bookmarks rawLen=${raw.length} " +
                "preview=${raw.take(240)}"
        )
        // Diagnostic: also dump every key in BookmarkPrefs in case the
        // user's bookmarks were saved under a different key (legacy
        // schema, alternative TapBrowser fork, etc.). One line, easy to
        // grep for with adb logcat.
        runCatching {
            val keys = prefs.all.keys.joinToString(",")
            Log.d(TAG, "serveBrowserBookmarks: BookmarkPrefs all keys=[$keys]")
        }
        // Normalise to ensure every entry has { id, url, isHome } so the
        // companion JS doesn't need to deal with missing fields.
        val out = JSONArray()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").trim()
                if (url.isBlank()) continue
                out.put(
                    JSONObject()
                        .put("id", obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() })
                        .put("url", url)
                        .put("isHome", obj.optBoolean("isHome", false))
                )
            }
        }.onFailure { Log.w(TAG, "Bookmark parse failed: ${it.message}") }
        Log.d(TAG, "serveBrowserBookmarks: returning ${out.length()} entries")
        return newFixedLengthResponse(Response.Status.OK, "application/json", out.toString())
    }

    private fun saveBrowserBookmarks(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        try {
            session.parseBody(body)
        } catch (e: Exception) {
            return badRequest("Bad body: ${e.message}")
        }
        val postData = body["postData"] ?: return badRequest("Empty body")
        val incoming = try { JSONArray(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON array")
        }
        // Validate + normalise. TapBrowser expects exactly one isHome=true;
        // if the client sent zero or multiple, the first entry wins.
        val cleaned = JSONArray()
        var sawHome = false
        for (i in 0 until incoming.length()) {
            val obj = incoming.optJSONObject(i) ?: continue
            val rawUrl = obj.optString("url").trim()
            if (rawUrl.isBlank()) continue
            val urlWithScheme = if (rawUrl.startsWith("http://", true) ||
                rawUrl.startsWith("https://", true) ||
                rawUrl.startsWith("file://", true)
            ) rawUrl else "https://$rawUrl"
            val claimedHome = obj.optBoolean("isHome", false)
            val isHome = claimedHome && !sawHome
            if (isHome) sawHome = true
            cleaned.put(
                JSONObject()
                    .put("id", obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() })
                    .put("url", urlWithScheme)
                    .put("isHome", isHome)
            )
        }
        if (cleaned.length() > 0 && !sawHome) {
            // No isHome flag in payload — promote the first to be home so
            // BookmarkManager's "first launch" guarantee holds.
            cleaned.optJSONObject(0)?.put("isHome", true)
        }
        browserPrefs().edit().putString("bookmarks", cleaned.toString()).apply()
        Log.d(TAG, "Saved ${cleaned.length()} browser bookmarks")
        return newFixedLengthResponse(
            Response.Status.OK, "application/json",
            JSONObject().put("status", "ok").put("count", cleaned.length()).toString()
        )
    }

    // ── Groq API key (TapLinkPrefs / "groq_api_key") ────────────────────
    //
    // The original TapBrowser shipped a Groq-powered chat hook. The key
    // lives in TapLinkPrefs (separate from visionclaw_prefs) and was
    // previously only settable via an on-device prompt — painful with the
    // X3 keyboard. The companion now writes it directly. We never expose
    // the actual key on GET; status only reports whether one is set so
    // the page can show "Key configured" vs "No key" without leaking it.

    private fun serveGroqKeyStatus(): Response {
        val key = tapBrowserPrefs().getString("groq_api_key", "")?.trim().orEmpty()
        val resp = JSONObject()
            .put("hasKey", key.isNotBlank())
            .put("keyLength", key.length)
            // First 4 chars only (industry convention, no leak risk).
            .put("keyPreview", if (key.length > 8) "${key.take(4)}…${key.takeLast(2)}" else "")
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    private fun saveGroqKey(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        try {
            session.parseBody(body)
        } catch (e: Exception) {
            return badRequest("Bad body: ${e.message}")
        }
        val postData = body["postData"] ?: return badRequest("Empty body")
        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON")
        }
        val key = json.optString("key").trim()
        if (key.isBlank()) {
            return badRequest("Empty key — use DELETE to clear")
        }
        // Light shape check; Groq keys are 'gsk_…' prefixed and ~50+ chars.
        // We don't enforce strictly because the format may evolve.
        if (key.length < 10) {
            return badRequest("Key looks too short")
        }
        tapBrowserPrefs().edit().putString("groq_api_key", key).apply()
        Log.d(TAG, "Saved Groq API key (length=${key.length})")
        return newFixedLengthResponse(
            Response.Status.OK, "application/json",
            JSONObject().put("status", "ok").put("hasKey", true).toString()
        )
    }

    private fun clearGroqKey(): Response {
        tapBrowserPrefs().edit().remove("groq_api_key").apply()
        Log.d(TAG, "Cleared Groq API key")
        return newFixedLengthResponse(
            Response.Status.OK, "application/json",
            JSONObject().put("status", "cleared").toString()
        )
    }

    /**
     * GET /api/orb/default
     * Streams the bundled R.drawable.earth_orb resource as PNG bytes so
     * the companion-app preview can show "what the chat panel currently
     * looks like" even when no custom image has been uploaded yet.
     */
    private fun serveOrbDefault(): Response {
        return runCatching {
            val resId = context.resources.getIdentifier(
                "earth_orb", "drawable", context.packageName
            )
            if (resId == 0) {
                return@runCatching newFixedLengthResponse(
                    Response.Status.NOT_FOUND, "text/plain", "Default orb resource not found"
                )
            }
            context.resources.openRawResource(resId).use { input ->
                val bytes = input.readBytes()
                val response = newFixedLengthResponse(
                    Response.Status.OK, "image/png",
                    ByteArrayInputStream(bytes), bytes.size.toLong()
                )
                response.addHeader("Cache-Control", "public, max-age=86400")
                response
            }
        }.getOrElse { e ->
            Log.w(TAG, "serveOrbDefault failed: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Failed to read default orb"
            )
        }
    }

    /**
     * GET /media/file?path=<relative>&token=<session-token>
     * Streams a media file with HTTP Range support (206 Partial Content).
     * Token is checked manually (endpoint lives outside /api/ so HTML5
     * video/audio tags can reach it without custom headers).
     */
    private fun serveMediaFile(session: IHTTPSession): Response {
        // Manual token check — the <video>/<audio> element can't send custom
        // headers, so accept ?token=, cookie, or X-Session-Token.
        if (!isAuthorizedApiRequest(session)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized"
            )
        }
        val path = session.parms?.get("path")
            ?: return badRequest("Missing ?path=")
        val decoded = try { URLDecoder.decode(path, "UTF-8") } catch (e: Exception) { path }
        val file = mediaLibrary.resolveSafe(decoded)
            ?: return newFixedLengthResponse(
                Response.Status.FORBIDDEN, "text/plain", "Path escapes media root"
            )
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "Not found"
            )
        }

        val mime = guessMimeType(file.name)
        val fileLength = file.length()
        val rangeHeader = session.headers?.get("range")

        if (rangeHeader.isNullOrBlank()) {
            // Full body response with streaming
            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLength)
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", fileLength.toString())
            return response
        }

        // Parse "bytes=start-end" — supports:
        //   bytes=0-499      (explicit range)
        //   bytes=500-       (from byte 500 to end)
        //   bytes=-512       (last 512 bytes — suffix range)
        val raw = rangeHeader.trim().removePrefix("bytes=")
        val dash = raw.indexOf('-')
        if (dash < 0) {
            return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Bad Range header"
            ).also { it.addHeader("Content-Range", "bytes */$fileLength") }
        }
        val startStr = raw.substring(0, dash).trim()
        val endStr = raw.substring(dash + 1).trim()
        val start: Long
        val end: Long
        if (startStr.isEmpty()) {
            // Suffix range: bytes=-N → last N bytes
            val suffixLen = endStr.toLongOrNull() ?: 0L
            if (suffixLen <= 0) {
                return newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Bad suffix range"
                ).also { it.addHeader("Content-Range", "bytes */$fileLength") }
            }
            start = (fileLength - suffixLen).coerceAtLeast(0L)
            end = fileLength - 1
        } else {
            start = startStr.toLongOrNull() ?: 0L
            end = endStr.toLongOrNull()?.coerceAtMost(fileLength - 1) ?: (fileLength - 1)
        }
        if (start < 0 || start >= fileLength || end < start) {
            return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range out of bounds"
            ).also { it.addHeader("Content-Range", "bytes */$fileLength") }
        }
        val contentLength = end - start + 1
        val fis = FileInputStream(file)
        try {
            var remaining = start
            while (remaining > 0) {
                val skipped = fis.skip(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
        } catch (e: Exception) {
            fis.close()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Seek failed: ${e.message}"
            )
        }
        val response = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, mime, fis, contentLength
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
        response.addHeader("Content-Length", contentLength.toString())
        return response
    }

    /**
     * GET /media/dcim-video?path=<DCIM-relative-video>&token=<session-token>
     *
     * Local camera videos do not play reliably when streamed through
     * WebViewClient.shouldInterceptRequest: RayNeo's Chromium video stack
     * repeatedly fails intercepted 206 tail ranges with net::ERR_FAILED.
     * This endpoint serves the same DCIM file over the real loopback HTTPS
     * server, while keeping the path contained to /storage/emulated/0/DCIM.
     */
    private fun serveDcimVideoFile(session: IHTTPSession): Response {
        if (!isAuthorizedApiRequest(session)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized"
            )
        }
        val path = session.parms?.get("path")
            ?: return badRequest("Missing ?path=")
        val decoded = try { URLDecoder.decode(path, "UTF-8") } catch (e: Exception) { path }
        val relative = decoded
            .substringBefore('?')
            .substringBefore('#')
            .trimStart('/')
        if (relative.isBlank() || relative.contains("..") || relative.contains('\\')) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN, "text/plain", "Bad DCIM path"
            )
        }

        val root = File("/storage/emulated/0/DCIM")
        val file = File(root, relative)
        val canonicalRoot = try { root.canonicalPath } catch (_: Exception) { root.absolutePath }
        val canonicalFile = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
        if (!canonicalFile.startsWith(canonicalRoot + File.separator)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN, "text/plain", "Path escapes DCIM root"
            )
        }
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "Not found"
            )
        }

        val mime = when (file.extension.lowercase(Locale.ROOT)) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            else -> return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "Unsupported video type"
            )
        }

        return serveDcimFileWithRange(file, mime, session.headers?.get("range"))
    }

    private fun serveDcimFileWithRange(file: File, mime: String, rangeHeader: String?): Response {
        val fileLength = file.length()
        if (rangeHeader.isNullOrBlank()) {
            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLength)
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Cache-Control", "no-store")
            return response
        }

        val range = parseDcimRangeHeader(rangeHeader, fileLength)
            ?: return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Range out of bounds"
            ).also {
                it.addHeader("Accept-Ranges", "bytes")
                it.addHeader("Content-Range", "bytes */$fileLength")
            }
        val (start, end) = range
        val contentLength = end - start + 1
        val fis = FileInputStream(file)
        try {
            fis.channel.position(start)
        } catch (e: Exception) {
            fis.close()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Seek failed: ${e.message}"
            )
        }

        val response = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, mime, fis, contentLength
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun parseDcimRangeHeader(rangeHeader: String, totalLength: Long): Pair<Long, Long>? {
        if (totalLength <= 0L) return null
        val raw = rangeHeader.trim().removePrefix("bytes=").substringBefore(',').trim()
        val dash = raw.indexOf('-')
        if (dash < 0) return null
        val startStr = raw.substring(0, dash).trim()
        val endStr = raw.substring(dash + 1).trim()
        val start: Long
        val end: Long
        if (startStr.isEmpty()) {
            val suffixLen = endStr.toLongOrNull() ?: return null
            if (suffixLen <= 0L) return null
            start = (totalLength - suffixLen).coerceAtLeast(0L)
            end = totalLength - 1
        } else {
            start = startStr.toLongOrNull() ?: return null
            end = endStr.toLongOrNull()?.coerceAtMost(totalLength - 1) ?: (totalLength - 1)
        }
        if (start < 0L || start >= totalLength || end < start) return null
        return start to end
    }

    // ── Fish.audio proxy ───────────────────────────────────────────────────
    //
    // All endpoints under /api/fish/* run on a fresh OkHttp connection per
    // call (we don't pool here because the companion-app interaction is
    // bursty: configure once, then never call again until the user opens the
    // page). The Fish.audio REST API is stable enough that a thin proxy is
    // sufficient — we don't try to model voice metadata on our side, just
    // pass it through and persist the picked voice ID in SharedPreferences.
    //
    // Authentication header per Fish.audio docs: `Authorization: Bearer <key>`.
    //
    // We never expose the key in responses (so a JS bug or a leaked log line
    // can't compromise it), and `serveConfig` masks it the same way it does
    // for other secret keys (the GET /api/config response carries a
    // `fish_api_key` value, so the companion page can detect "configured" vs
    // "not configured" without ever needing to print the key out).

    // Local Fish.audio endpoint constants — kept at instance scope (object
    // would collide with the existing top-of-class companion object) and
    // initialized once per CompanionServer instance.
    private val FISH_BASE = "https://api.fish.audio"
    private val FISH_USER_AGENT = "TapInsight-Companion/1.0 (+companion-app)"

    private fun fishApiKey(): String =
        prefs.getString("fish_api_key", "")?.trim().orEmpty()

    private fun fishAuthHeaders(): Map<String, String> {
        val key = fishApiKey()
        if (key.isBlank()) return emptyMap()
        return mapOf(
            "Authorization" to "Bearer $key",
            "User-Agent" to FISH_USER_AGENT
        )
    }

    private fun fishKeyMissing(): Response =
        newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "application/json",
            JSONObject().put("error", "Fish.audio API key not configured. Set it in the Readout Voice section.").toString()
        )

    /**
     * GET /api/fish/voices  → returns the user's saved voice list as JSON.
     *
     * The "saved voices" list is owned by the companion app, not Fish.audio:
     * it's a local catalog of {id, name, source} entries. Each entry's `id`
     * is a Fish.audio model ID — once saved here, the user can pick it as
     * the active voice and the glasses will synthesize through it.
     */
    private fun serveFishVoices(): Response {
        val raw = prefs.getString("fish_saved_voices_json", "") ?: ""
        val arr = try {
            if (raw.isBlank()) JSONArray() else JSONArray(raw)
        } catch (e: Exception) {
            Log.w(TAG, "fish_saved_voices_json corrupt — resetting: ${e.message}")
            JSONArray()
        }
        val activeId = prefs.getString("fish_active_voice_id", "") ?: ""
        val activeName = prefs.getString("fish_active_voice_name", "") ?: ""
        val resp = JSONObject()
            .put("voices", arr)
            .put("activeId", activeId)
            .put("activeName", activeName)
            .put("hasKey", fishApiKey().isNotBlank())
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /**
     * Helper that updates `fish_saved_voices_json` by inserting/overwriting
     * an entry by id, then optionally promotes that entry to the active
     * voice. Returns the persisted catalog so the response can include it.
     */
    private fun upsertFishVoiceEntry(
        id: String,
        name: String,
        source: String,
        makeActive: Boolean
    ): JSONArray {
        val raw = prefs.getString("fish_saved_voices_json", "") ?: ""
        val list = try {
            if (raw.isBlank()) JSONArray() else JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
        // Remove any existing entry with this id, prepend the new one. Using
        // a JSONArray rebuild keeps insertion order stable (most-recent-first)
        // which is what users intuitively expect in the picker.
        val rebuilt = JSONArray()
        rebuilt.put(
            JSONObject()
                .put("id", id)
                .put("name", name)
                .put("source", source)
                .put("savedAtMs", System.currentTimeMillis())
        )
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("id") == id) continue
            rebuilt.put(o)
        }
        val editor = prefs.edit()
        editor.putString("fish_saved_voices_json", rebuilt.toString())
        if (makeActive) {
            editor.putString("fish_active_voice_id", id)
            editor.putString("fish_active_voice_name", name)
        }
        editor.apply()
        return rebuilt
    }

    /**
     * POST /api/fish/voices/save
     *   body JSON: { id, name, source, makeActive }
     *
     * Used after the user picks a voice from the public library — that flow
     * doesn't create a new Fish model, it just records the chosen reference
     * so the glasses can pass `reference_id` on every /tts call.
     */
    private fun saveFishVoice(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: return badRequest("Empty body")
        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON: ${e.message}")
        }
        val id = json.optString("id").trim()
        if (id.isBlank()) return badRequest("Missing voice id")
        val name = json.optString("name").trim().ifBlank { id.take(8) }
        val source = json.optString("source").trim().ifBlank { "library" }
        val makeActive = json.optBoolean("makeActive", true)
        val rebuilt = upsertFishVoiceEntry(id, name, source, makeActive)
        val resp = JSONObject()
            .put("status", "saved")
            .put("voices", rebuilt)
            .put("activeId", if (makeActive) id else (prefs.getString("fish_active_voice_id", "") ?: ""))
            .put("activeName", if (makeActive) name else (prefs.getString("fish_active_voice_name", "") ?: ""))
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /**
     * POST /api/fish/voices/clone  (multipart/form-data)
     *   form fields: name, file (audio reference, mp3/wav/etc.), makeActive
     *
     * Uploads the audio to Fish's voice-model endpoint to mint a permanent
     * model. We use HttpURLConnection for the multipart upload because OkHttp's
     * `MultipartBody` is fine but adding a one-off upload doesn't justify
     * pulling in a dependency on the "files" module from the companion server.
     */
    private fun cloneFishVoice(session: IHTTPSession): Response {
        if (fishApiKey().isBlank()) return fishKeyMissing()
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return badRequest("Upload parse failed: ${e.message}")
        }
        val name = (session.parms?.get("name") ?: "").trim().ifBlank { "Cloned Voice" }
        val makeActive = (session.parms?.get("makeActive") ?: "true").toBoolean()
        // Find the first non-postData file field — the companion HTML always
        // posts a single audio file field named "file", but accept any other
        // field name as well so future UI iterations don't have to coordinate.
        val tempPath = files.entries.firstOrNull { it.key != "postData" && it.value.isNotBlank() }?.value
            ?: return badRequest("No audio file provided")
        val tempFile = File(tempPath)
        if (!tempFile.exists() || tempFile.length() == 0L) return badRequest("Uploaded file is empty")
        // Filename hint helps Fish detect the format — fall back to .audio
        // if NanoHTTPD's parms map doesn't carry the original name.
        val originalName = files.entries
            .firstOrNull { it.key != "postData" }
            ?.let { session.parms?.get(it.key) }
            ?: "reference.audio"

        return try {
            val audioBytes = FileInputStream(tempFile).use { it.readBytes() }
            tempFile.delete()
            val response = postFishMultipartModel(name, audioBytes, originalName)
            if (response.code !in 200..299) {
                Log.w(TAG, "Fish clone failed code=${response.code} body=${response.body.take(220)}")
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    JSONObject()
                        .put("error", summarizeFishError(response.body) ?: "Fish.audio clone failed (HTTP ${response.code}).")
                        .toString()
                )
            }
            val parsed = JSONObject(response.body)
            // Fish's response shape varies a bit across API versions — accept
            // any of the common id field names and bail with a clear error
            // rather than crashing if none are present.
            val voiceId = listOf("id", "_id", "model_id", "reference_id")
                .map { parsed.optString(it).trim() }
                .firstOrNull { it.isNotBlank() }
                ?: return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    JSONObject().put("error", "Fish response missing voice id: ${response.body.take(160)}").toString()
                )
            val rebuilt = upsertFishVoiceEntry(voiceId, name, "clone", makeActive)
            val resp = JSONObject()
                .put("status", "cloned")
                .put("voiceId", voiceId)
                .put("voiceName", name)
                .put("voices", rebuilt)
                .put("activeId", if (makeActive) voiceId else (prefs.getString("fish_active_voice_id", "") ?: ""))
                .put("activeName", if (makeActive) name else (prefs.getString("fish_active_voice_name", "") ?: ""))
            newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Fish clone exception", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", "Clone failed: ${e.message ?: e.javaClass.simpleName}").toString()
            )
        }
    }

    /**
     * Shells out to HttpURLConnection because OkHttp `MultipartBody` works
     * fine but pulling its imports into this giant file just for one
     * one-off audio upload isn't worth it. Returns the raw response body
     * + status code wrapped as an `ActiveNetworkHttp.HttpResponse`.
     */
    private fun postFishMultipartModel(
        name: String,
        audioBytes: ByteArray,
        filename: String
    ): ActiveNetworkHttp.HttpResponse {
        val url = java.net.URL("$FISH_BASE/model")
        val boundary = "TapInsightFish_${System.currentTimeMillis()}"
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${fishApiKey()}")
            setRequestProperty("User-Agent", FISH_USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
                fun writeUtf8(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                writeAscii("--$boundary\r\n")
                writeAscii("Content-Disposition: form-data; name=\"title\"\r\n\r\n")
                writeUtf8(name); writeAscii("\r\n")
                // Best-effort: include both `voices` and `voice` so we cover
                // both old and current Fish API field names. The server
                // simply ignores whichever it doesn't recognize.
                writeAscii("--$boundary\r\n")
                writeAscii("Content-Disposition: form-data; name=\"description\"\r\n\r\n")
                writeUtf8("Cloned via TapInsight companion app"); writeAscii("\r\n")
                writeAscii("--$boundary\r\n")
                writeAscii("Content-Disposition: form-data; name=\"voices\"; filename=\"${filename.replace("\"", "")}\"\r\n")
                writeAscii("Content-Type: application/octet-stream\r\n\r\n")
                out.write(audioBytes)
                writeAscii("\r\n")
                writeAscii("--$boundary--\r\n")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return ActiveNetworkHttp.HttpResponse(
                code = code,
                body = body,
                headers = okhttp3.Headers.Builder().build()
            )
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * POST /api/fish/voices/describe
     *   body JSON: { name, description, makeActive }
     *
     * Generates a brand-new voice from a text description (Fish.audio's
     * "create from prompt" flow). The endpoint is the same /model URL as
     * cloning, but we send only JSON instead of multipart audio.
     */
    private fun describeFishVoice(session: IHTTPSession): Response {
        // Wrap the entire body in a try/catch — when this throws (e.g. an
        // OkHttp DNS hiccup, a NullPointer in error parsing, anything else
        // unexpected) the outer catch returns a plain-text 500 that the
        // companion JS can't parse as JSON, leaving the user staring at a
        // bare "HTTP 500" with no detail. Doing it here lets us always
        // surface a real, actionable JSON error.
        return try {
            describeFishVoiceImpl(session)
        } catch (t: Throwable) {
            Log.e(TAG, "describeFishVoice unhandled exception", t)
            newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                JSONObject()
                    .put("error", "Describe-voice proxy crashed: ${t.message ?: t.javaClass.simpleName}")
                    .toString()
            )
        }
    }

    private fun describeFishVoiceImpl(session: IHTTPSession): Response {
        if (fishApiKey().isBlank()) return fishKeyMissing()
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: return badRequest("Empty body")
        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON: ${e.message}")
        }
        val name = json.optString("name").trim().ifBlank { "Described Voice" }
        val description = json.optString("description").trim()
        if (description.isBlank()) return badRequest("Description is required.")
        val makeActive = json.optBoolean("makeActive", true)

        // Fish.audio's POST /model endpoint requires multipart/form-data,
        // not JSON. Even when generating a voice from description (no
        // audio file attached) the same multipart shape is required, with
        // these mandatory fields: title, description, type, train_mode,
        // visibility. Sending JSON gets a 500 from Fish; we used to do
        // that, hence the original "HTTP 500" with no detail.
        val response = postFishModelDescribeMultipart(name, description)
        if (response.code !in 200..299) {
            Log.w(TAG, "Fish describe failed code=${response.code} body=${response.body.take(400)}")
            // Surface BOTH the upstream HTTP code AND the truncated body
            // so the companion page can show the real Fish error rather
            // than a generic "describe failed". Fish typically returns
            // a JSON envelope like {"detail":[{"msg":"…"}]} for 4xx; we
            // try to extract the message but always fall back to the
            // raw body so nothing useful is hidden from the user.
            val pretty = summarizeFishError(response.body)
                ?: response.body.take(220).ifBlank { "Fish.audio describe failed." }
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                JSONObject()
                    .put("error", "HTTP ${response.code}: $pretty")
                    .put("upstreamCode", response.code)
                    .toString()
            )
        }
        val parsed = try { JSONObject(response.body) } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                JSONObject().put("error", "Fish returned non-JSON: ${response.body.take(160)}").toString()
            )
        }
        val voiceId = listOf("id", "_id", "model_id")
            .map { parsed.optString(it).trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                JSONObject().put("error", "Fish response missing voice id.").toString()
            )
        val rebuilt = upsertFishVoiceEntry(voiceId, name, "description", makeActive)
        val resp = JSONObject()
            .put("status", "described")
            .put("voiceId", voiceId)
            .put("voiceName", name)
            .put("voices", rebuilt)
            .put("activeId", if (makeActive) voiceId else (prefs.getString("fish_active_voice_id", "") ?: ""))
            .put("activeName", if (makeActive) name else (prefs.getString("fish_active_voice_name", "") ?: ""))
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /**
     * Multipart POST to Fish's /model endpoint with NO audio file — used
     * for the "generate from text description" flow. Mirrors the shape of
     * [postFishMultipartModel] but without the `voices` part. We include
     * the mandatory `type` / `train_mode` / `visibility` fields so Fish's
     * validator is happy; without them, the request is rejected with a
     * confusing 500 instead of a clean 422.
     */
    private fun postFishModelDescribeMultipart(
        title: String,
        description: String
    ): ActiveNetworkHttp.HttpResponse {
        val url = java.net.URL("$FISH_BASE/model")
        val boundary = "TapInsightFishDesc_${System.currentTimeMillis()}"
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${fishApiKey()}")
            setRequestProperty("User-Agent", FISH_USER_AGENT)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                fun writeAscii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
                fun writeUtf8(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                fun writeField(name: String, value: String) {
                    writeAscii("--$boundary\r\n")
                    writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    writeUtf8(value); writeAscii("\r\n")
                }
                writeField("title", title)
                writeField("description", description)
                writeField("type", "tts")
                writeField("train_mode", "fast")
                writeField("visibility", "private")
                writeAscii("--$boundary--\r\n")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return ActiveNetworkHttp.HttpResponse(
                code = code,
                body = body,
                headers = okhttp3.Headers.Builder().build()
            )
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * GET /api/fish/voices/library?q=<term>
     *
     * Searches the public Fish.audio voice library. Used by the companion
     * page's "Browse Voice Library" picker. The Fish API supports a `?title=`
     * search parameter — we forward whatever the companion page passed
     * verbatim, with a couple of safety bounds (page size, length cap).
     */
    private fun searchFishVoiceLibrary(session: IHTTPSession): Response {
        return try {
            searchFishVoiceLibraryImpl(session)
        } catch (e: Throwable) {
            Log.w(TAG, "Fish voice-library search failed before upstream response: ${e.message}", e)
            newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                JSONObject()
                    .put("error", e.message ?: "Fish.audio library search failed.")
                    .toString()
            )
        }
    }

    private fun searchFishVoiceLibraryImpl(session: IHTTPSession): Response {
        if (fishApiKey().isBlank()) return fishKeyMissing()
        val q = (session.parms?.get("q") ?: "").trim().take(100)
        val pageSize = (session.parms?.get("page_size") ?: "20").toIntOrNull()?.coerceIn(1, 50) ?: 20
        val urlBuilder = StringBuilder("$FISH_BASE/model?page_size=$pageSize")
        if (q.isNotBlank()) urlBuilder.append("&title=").append(URLEncoder.encode(q, "UTF-8"))
        // `self_only=false` is the default; surface it explicitly so the
        // intent is clear from logs and from anyone reading the call site.
        urlBuilder.append("&self_only=false&sort_by=score")
        val response = ActiveNetworkHttp.get(
            url = urlBuilder.toString(),
            headers = fishAuthHeaders(),
            connectTimeoutMs = 15_000,
            readTimeoutMs = 30_000
        )
        if (response.code !in 200..299) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                JSONObject().put("error", summarizeFishError(response.body) ?: "Library search failed.").toString()
            )
        }
        // Pass-through: the companion HTML knows the Fish response shape.
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.body)
    }

    /**
     * POST /api/fish/voices/delete  body JSON: { id }
     *
     * Removes the voice from the local catalog AND clears the active
     * pointer if it referenced this voice. Does NOT delete the Fish.audio
     * model itself (that requires explicit user intent on Fish's site;
     * deleting locally just hides it from the picker).
     */
    private fun deleteFishVoice(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: return badRequest("Empty body")
        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON: ${e.message}")
        }
        val id = json.optString("id").trim()
        if (id.isBlank()) return badRequest("Missing id")
        val raw = prefs.getString("fish_saved_voices_json", "") ?: ""
        val rebuilt = JSONArray()
        try {
            if (raw.isNotBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("id") != id) rebuilt.put(o)
                }
            }
        } catch (_: Exception) {}
        val editor = prefs.edit()
        editor.putString("fish_saved_voices_json", rebuilt.toString())
        val activeId = prefs.getString("fish_active_voice_id", "") ?: ""
        if (activeId == id) {
            editor.remove("fish_active_voice_id")
            editor.remove("fish_active_voice_name")
        }
        editor.apply()
        val resp = JSONObject()
            .put("status", "deleted")
            .put("voices", rebuilt)
            .put("activeId", prefs.getString("fish_active_voice_id", "") ?: "")
            .put("activeName", prefs.getString("fish_active_voice_name", "") ?: "")
        return newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
    }

    /**
     * POST /api/fish/preview  body JSON: { text, voiceId? }
     *
     * Generates a short audio preview of the active (or specified) voice and
     * returns it as a `audio/mpeg` body so the companion page can drop it
     * into an `<audio>` element. Uses the Fish v1 /tts endpoint directly.
     */
    private fun previewFishVoice(session: IHTTPSession): Response {
        if (fishApiKey().isBlank()) return fishKeyMissing()
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: return badRequest("Empty body")
        val json = try { JSONObject(postData) } catch (e: Exception) {
            return badRequest("Invalid JSON: ${e.message}")
        }
        val text = json.optString("text").trim().take(500).ifBlank {
            "This is a preview of the selected Fish dot audio voice."
        }
        val voiceId = json.optString("voiceId").trim()
            .ifBlank { prefs.getString("fish_active_voice_id", "") ?: "" }
        val model = (prefs.getString("fish_model", "") ?: "").trim().ifBlank { "s2-pro" }
        val format = (prefs.getString("fish_format", "") ?: "").trim().ifBlank { "mp3" }
        val latency = (prefs.getString("fish_latency", "") ?: "").trim().ifBlank { "balanced" }
        val req = JSONObject()
            .put("text", text)
            .put("format", format)
            .put("latency", latency)
            .put("normalize", prefs.getBoolean("fish_normalize", true))
        if (voiceId.isNotBlank()) req.put("reference_id", voiceId)
        val mimeForFormat = when (format.lowercase()) {
            "wav" -> "audio/wav"
            "opus" -> "audio/opus"
            else -> "audio/mpeg"
        }
        val url = "$FISH_BASE/v1/tts"
        // Fish.audio's TTS responds with raw audio bytes (not JSON), so we
        // must use HttpURLConnection here — OkHttp's response.body.string()
        // fits in memory but corrupts binary data through the String round-trip.
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer ${fishApiKey()}")
                setRequestProperty("User-Agent", FISH_USER_AGENT)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("model", model)
            }
            try {
                conn.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errText = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    Log.w(TAG, "Fish preview failed code=$code body=${errText.take(220)}")
                    return newFixedLengthResponse(
                        Response.Status.lookup(code) ?: Response.Status.SERVICE_UNAVAILABLE,
                        "application/json",
                        JSONObject().put("error", summarizeFishError(errText) ?: "Fish preview failed (HTTP $code).").toString()
                    )
                }
                val audio = conn.inputStream.use { it.readBytes() }
                val resp = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeForFormat,
                    ByteArrayInputStream(audio),
                    audio.size.toLong()
                )
                resp.addHeader("Content-Length", audio.size.toString())
                resp.addHeader("Cache-Control", "no-store")
                resp
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fish preview exception", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put("error", "Preview failed: ${e.message ?: e.javaClass.simpleName}").toString()
            )
        }
    }

    /**
     * Fish.audio returns errors as `{"detail":[{"msg":"…"}]}` or
     * `{"message":"…"}` depending on the endpoint. Best-effort summarize
     * so the UI shows a real message instead of a wall of JSON.
     */
    private fun summarizeFishError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val root = JSONObject(body)
            val direct = root.optString("message").trim().takeIf { it.isNotBlank() }
            if (direct != null) return direct
            val detail = root.opt("detail")
            when (detail) {
                is String -> detail.trim().takeIf { it.isNotBlank() }
                is JSONArray -> detail.optJSONObject(0)?.optString("msg")?.trim()?.takeIf { it.isNotBlank() }
                is JSONObject -> detail.optString("msg").trim().takeIf { it.isNotBlank() }
                else -> body.take(220)
            }
        } catch (_: Exception) {
            body.take(220)
        }
    }

    // ── Engine-aware Readout Voice test endpoint ────────────────────────
    //
    // POST /api/tts/test  body: { engine?: "gemini"|"fish", text?: string }
    //   * engine — defaults to whatever's saved in `readout_engine`
    //   * text   — defaults to a fixed canned phrase
    //
    // Returns the synthesized audio as a binary stream (audio/wav for
    // Gemini, audio/mpeg for Fish), so the companion page's <audio>
    // element can play it directly. On failure returns
    // application/json with `{error, status}` and the same HTTP code
    // we got from upstream — that way users can see "Gemini TTS HTTP
    // 500" right in the test status without rebuilding the APK.

    private fun testReadoutVoice(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        try { session.parseBody(body) } catch (e: Exception) {
            return badRequest("Invalid request body: ${e.message}")
        }
        val postData = body["postData"] ?: ""
        val req = if (postData.isBlank()) JSONObject() else
            try { JSONObject(postData) } catch (e: Exception) {
                return badRequest("Invalid JSON: ${e.message}")
            }
        val text = req.optString("text").trim().take(800).ifBlank {
            "This is a test of the readout voice. " +
                "If you can hear this clearly, the engine you selected is working."
        }
        val engineRequested = req.optString("engine").trim().lowercase().ifBlank {
            (prefs.getString("readout_engine", "gemini") ?: "gemini").lowercase()
        }
        // Optional overrides for the Live Voice preview button (and any
        // future caller that wants to validate a specific voice/model
        // combination without saving). Empty / missing values fall back
        // to the saved Reader Voice preferences.
        val voiceOverride = req.optString("voice").trim()
        val modelOverride = req.optString("model").trim()
        return when (engineRequested) {
            "fish" -> testFishReadout(text)
            else -> testGeminiReadout(text, voiceOverride, modelOverride)
        }
    }

    private fun testGeminiReadout(
        text: String,
        voiceOverride: String = "",
        modelOverride: String = ""
    ): Response {
        val apiKey = (prefs.getString("gemini_api_key", "") ?: "").trim()
        if (apiKey.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                JSONObject().put("error", "Gemini API key not configured.").toString()
            )
        }
        val model = modelOverride.ifBlank {
            (prefs.getString("research_tts_model", "") ?: "")
                .trim().ifBlank { "gemini-2.5-flash-preview-tts" }
        }
        val voiceName = voiceOverride.ifBlank {
            (prefs.getString("research_tts_voice_name", "") ?: "")
                .trim().ifBlank { "Kore" }
        }
        val languageCode = (prefs.getString("research_tts_language", "") ?: "")
            .trim().ifBlank { "en-US" }
        val reqJson = JSONObject()
            .put("model", model)
            .put("contents", org.json.JSONArray().put(
                JSONObject().put("parts", org.json.JSONArray().put(
                    JSONObject().put("text",
                        "Read the following text aloud in a natural, " +
                            "clear voice. Do not add any commentary.\n\n$text")
                ))
            ))
            .put("generationConfig", JSONObject()
                .put("responseModalities", org.json.JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject()
                    .put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject()
                            .put("voiceName", voiceName)))
                    .put("languageCode", languageCode)
                ))
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 10_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            try {
                conn.outputStream.use { it.write(reqJson.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errText = conn.errorStream
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() } ?: ""
                    Log.w(TAG, "Gemini TTS test failed code=$code body=${errText.take(300)}")
                    return newFixedLengthResponse(
                        Response.Status.lookup(code) ?: Response.Status.SERVICE_UNAVAILABLE,
                        "application/json",
                        JSONObject()
                            .put("error", "Gemini TTS HTTP $code")
                            .put("model", model)
                            .put("body", errText.take(800))
                            .toString()
                    )
                }
                val bodyText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val (pcm, mime) = extractGeminiAudio(bodyText)
                    ?: return newFixedLengthResponse(
                        Response.Status.SERVICE_UNAVAILABLE,
                        "application/json",
                        JSONObject().put(
                            "error",
                            "Gemini returned no audio data."
                        ).toString()
                    )
                val sampleRate = parseSampleRateFromMime(mime) ?: 24000
                val wav = wrapPcmAsWav(pcm, sampleRate, channels = 1, bitsPerSample = 16)
                val resp = newFixedLengthResponse(
                    Response.Status.OK,
                    "audio/wav",
                    java.io.ByteArrayInputStream(wav),
                    wav.size.toLong()
                )
                resp.addHeader("Content-Length", wav.size.toString())
                resp.addHeader("Cache-Control", "no-store")
                resp.addHeader("X-Tts-Engine", "gemini")
                resp.addHeader("X-Tts-Model", model)
                resp.addHeader("X-Tts-Sample-Rate", sampleRate.toString())
                resp
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini TTS test exception", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put(
                    "error",
                    "Network error: ${e.message ?: e.javaClass.simpleName}"
                ).toString()
            )
        }
    }

    /** Extract base64 PCM audio + mime from a Gemini generateContent JSON response. */
    private fun extractGeminiAudio(body: String): Pair<ByteArray, String>? {
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        val candidates = root.optJSONArray("candidates") ?: return null
        val out = java.io.ByteArrayOutputStream()
        var mime = "audio/L16;rate=24000"
        for (i in 0 until candidates.length()) {
            val parts = candidates.optJSONObject(i)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val inline = parts.optJSONObject(j)?.optJSONObject("inlineData") ?: continue
                val data = inline.optString("data", "").trim()
                if (data.isBlank()) continue
                val decoded = runCatching { java.util.Base64.getDecoder().decode(data) }.getOrNull()
                    ?: continue
                mime = inline.optString("mimeType", mime).ifBlank { mime }
                out.write(decoded)
            }
        }
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes to mime
    }

    /** Parse `audio/L16;rate=24000` → 24000. */
    private fun parseSampleRateFromMime(mime: String): Int? {
        val m = Regex("rate\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(mime)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** Wrap raw little-endian PCM bytes in a minimal WAV (RIFF) header. */
    private fun wrapPcmAsWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val totalSize = 36 + dataSize
        val buf = java.nio.ByteBuffer.allocate(44 + dataSize)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                                  // PCM fmt chunk size
        buf.putShort(1)                                 // PCM format
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        buf.put(pcm)
        return buf.array()
    }

    /**
     * Reachability probe for the Gemini Live model.
     *
     * IMPORTANT: Live models (gemini-*-flash-live-preview etc.) don't
     * expose the REST `generateContent` endpoint at all — they're
     * WebSocket-only (`bidiGenerateContent`). Hitting :generateContent
     * on them returns 404 even when the model is perfectly healthy.
     *
     * The right probe is `GET /v1beta/models/<model-name>` — Google's
     * model-metadata endpoint, which works for every model type
     * (REST-only, Live-only, or both) and returns:
     *   { name, displayName, supportedGenerationMethods: [...], ... }
     *
     * A 200 means the model exists and is accessible to the user's
     * API key. If `bidiGenerateContent` is in `supportedGenerationMethods`,
     * it's a real Live model. Anything else (404, 401, 5xx) is a real
     * problem we can surface to the user.
     *
     * Returns JSON:
     *   ok      → { ok: true, status: 200, model, elapsedMs,
     *               displayName, supportedMethods: [...], live: true|false }
     *   not-ok  → { ok: false, status, model, elapsedMs, error, body }
     */
    private fun testLiveModel(session: IHTTPSession): Response {
        val apiKey = (prefs.getString("gemini_api_key", "") ?: "").trim()
        if (apiKey.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                JSONObject().put("error", "Gemini API key not configured.").toString()
            )
        }
        // Body override > saved pref > default. Lets the UI test a
        // dropdown selection before clicking Save (otherwise the
        // button always tested whatever was last persisted).
        val bodyOverride: String = try {
            val body = HashMap<String, String>()
            session.parseBody(body)
            val postData = body["postData"]
            if (postData.isNullOrBlank()) ""
            else JSONObject(postData).optString("model", "").trim()
        } catch (_: Exception) { "" }
        val savedModel = (prefs.getString("gemini_model_override", "") ?: "").trim()
        val model = bodyOverride.ifBlank { savedModel.ifBlank { "gemini-2.5-flash-native-audio-preview-12-2025" } }
        // Allow the dropdown's "Default (Gemini decides)" empty value
        // to fall through here too — strip the leading "models/" if
        // any caller accidentally included it.
        val cleanModel = model.removePrefix("models/").trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel"
        val t0 = System.currentTimeMillis()
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("x-goog-api-key", apiKey)
            }
            try {
                val code = conn.responseCode
                val elapsed = System.currentTimeMillis() - t0
                if (code !in 200..299) {
                    val errText = conn.errorStream
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() } ?: ""
                    Log.w(TAG, "Live model probe (models.get) failed code=$code body=${errText.take(300)}")
                    val resp = JSONObject()
                        .put("ok", false)
                        .put("status", code)
                        .put("model", cleanModel)
                        .put("elapsedMs", elapsed)
                        .put("error", when (code) {
                            404 -> "Model not found (404). Check spelling, or call ListModels for available names."
                            401, 403 -> "Auth failed ($code). API key invalid or model not enabled in your project."
                            429 -> "Rate limited (429). Wait a moment and retry."
                            in 500..599 -> "Google-side error ($code). Usually transient."
                            else -> "HTTP $code"
                        })
                        .put("body", errText.take(800))
                    return newFixedLengthResponse(
                        Response.Status.lookup(code) ?: Response.Status.SERVICE_UNAVAILABLE,
                        "application/json",
                        resp.toString()
                    )
                }
                val bodyText = conn.inputStream
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val (displayName, methods) = try {
                    val root = JSONObject(bodyText)
                    val arr = root.optJSONArray("supportedGenerationMethods")
                        ?: org.json.JSONArray()
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) list.add(arr.optString(i, ""))
                    root.optString("displayName", "") to list
                } catch (_: Exception) {
                    "" to emptyList<String>()
                }
                val isLive = methods.any { it.equals("bidiGenerateContent", ignoreCase = true) }
                val supportsRest = methods.any { it.equals("generateContent", ignoreCase = true) }
                val resp = JSONObject()
                    .put("ok", true)
                    .put("status", 200)
                    .put("model", cleanModel)
                    .put("elapsedMs", elapsed)
                    .put("displayName", displayName)
                    .put("live", isLive)
                    .put("supportsRest", supportsRest)
                    .put("supportedMethods", org.json.JSONArray(methods))
                newFixedLengthResponse(Response.Status.OK, "application/json", resp.toString())
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            Log.e(TAG, "Live model probe exception", e)
            val resp = JSONObject()
                .put("ok", false)
                .put("status", 0)
                .put("model", cleanModel)
                .put("elapsedMs", elapsed)
                .put("error", "Network error: ${e.message ?: e.javaClass.simpleName}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", resp.toString())
        }
    }

    /**
     * GET /api/live/list — list every model available to the user's
     * Gemini API key whose supportedGenerationMethods include
     * bidiGenerateContent. Used by the companion UI's "List Live
     * Models" button so the user doesn't have to memorize
     * Google's rotating Live model names.
     *
     * Returns:
     *   { liveModels: [{ name, displayName, supportedMethods: [...] }, ...] }
     * Or 401/4xx/5xx with { error } on auth/HTTP failures.
     */
    private fun listLiveModels(@Suppress("UNUSED_PARAMETER") session: IHTTPSession): Response {
        val apiKey = (prefs.getString("gemini_api_key", "") ?: "").trim()
        if (apiKey.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                JSONObject().put("error", "Gemini API key not configured.").toString()
            )
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("x-goog-api-key", apiKey)
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errText = conn.errorStream
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() } ?: ""
                    return newFixedLengthResponse(
                        Response.Status.lookup(code) ?: Response.Status.SERVICE_UNAVAILABLE,
                        "application/json",
                        JSONObject()
                            .put("error", "ListModels HTTP $code")
                            .put("body", errText.take(800))
                            .toString()
                    )
                }
                val bodyText = conn.inputStream
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(bodyText)
                val models = root.optJSONArray("models") ?: org.json.JSONArray()
                val live = org.json.JSONArray()
                for (i in 0 until models.length()) {
                    val m = models.optJSONObject(i) ?: continue
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var isLive = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j).equals("bidiGenerateContent", ignoreCase = true)) {
                            isLive = true; break
                        }
                    }
                    if (!isLive) continue
                    val supported = mutableListOf<String>()
                    for (j in 0 until methods.length()) supported.add(methods.optString(j, ""))
                    live.put(
                        JSONObject()
                            .put("name", m.optString("name", ""))
                            .put("displayName", m.optString("displayName", ""))
                            .put("supportedMethods", org.json.JSONArray(supported))
                    )
                }
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    JSONObject()
                        .put("liveModels", live)
                        .put("totalModels", models.length())
                        .toString()
                )
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "ListModels exception", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put(
                    "error",
                    "Network error: ${e.message ?: e.javaClass.simpleName}"
                ).toString()
            )
        }
    }

    /**
     * List Gemini TTS models available to the caller's API key. Mirrors
     * [listLiveModels] but filters by model name containing "tts"
     * rather than by `bidiGenerateContent` support — the ListModels
     * response doesn't surface "this is a TTS model" as a generation
     * method, but Google's TTS models all include "-tts" in their
     * canonical name (e.g. `gemini-2.5-flash-preview-tts`).
     *
     * Returns 200 with `{ ttsModels: [{name, displayName, ...}], totalModels: N }`
     * on success, or 401/4xx/5xx with `{ error }` otherwise.
     */
    private fun listTtsModels(@Suppress("UNUSED_PARAMETER") session: IHTTPSession): Response {
        val apiKey = (prefs.getString("gemini_api_key", "") ?: "").trim()
        if (apiKey.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                JSONObject().put("error", "Gemini API key not configured.").toString()
            )
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"
        return try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                doInput = true
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("x-goog-api-key", apiKey)
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errText = conn.errorStream
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() } ?: ""
                    return newFixedLengthResponse(
                        Response.Status.lookup(code) ?: Response.Status.SERVICE_UNAVAILABLE,
                        "application/json",
                        JSONObject()
                            .put("error", "ListModels HTTP $code")
                            .put("body", errText.take(800))
                            .toString()
                    )
                }
                val bodyText = conn.inputStream
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(bodyText)
                val models = root.optJSONArray("models") ?: org.json.JSONArray()
                val tts = org.json.JSONArray()
                for (i in 0 until models.length()) {
                    val m = models.optJSONObject(i) ?: continue
                    val name = m.optString("name", "")
                    // Google's TTS models have "tts" in their name (e.g.
                    // `models/gemini-2.5-flash-preview-tts`). Filter by
                    // substring rather than supportedGenerationMethods
                    // because the API surfaces TTS via generateContent
                    // with responseModalities:[AUDIO], which isn't a
                    // distinguishing method on its own.
                    if (!name.contains("tts", ignoreCase = true)) continue
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    val supported = mutableListOf<String>()
                    for (j in 0 until methods.length()) supported.add(methods.optString(j, ""))
                    tts.put(
                        JSONObject()
                            .put("name", name)
                            .put("displayName", m.optString("displayName", ""))
                            .put("supportedMethods", org.json.JSONArray(supported))
                    )
                }
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    JSONObject()
                        .put("ttsModels", tts)
                        .put("totalModels", models.length())
                        .toString()
                )
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "ListTtsModels exception", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put(
                    "error",
                    "Network error: ${e.message ?: e.javaClass.simpleName}"
                ).toString()
            )
        }
    }

    /** Fish path for /api/tts/test — same flow as previewFishVoice but a
     *  non-Fish-specific success/failure shape.  */
    private fun testFishReadout(text: String): Response {
        if (fishApiKey().isBlank()) return fishKeyMissing()
        val voiceId = (prefs.getString("fish_active_voice_id", "") ?: "").trim()
        val model = (prefs.getString("fish_model", "") ?: "").trim().ifBlank { "s2-pro" }
        val format = (prefs.getString("fish_format", "") ?: "").trim().ifBlank { "mp3" }
        val latency = (prefs.getString("fish_latency", "") ?: "").trim().ifBlank { "balanced" }
        val req = JSONObject()
            .put("text", text)
            .put("format", format)
            .put("latency", latency)
            .put("normalize", prefs.getBoolean("fish_normalize", true))
        if (voiceId.isNotBlank()) req.put("reference_id", voiceId)
        val mime = when (format.lowercase()) {
            "wav" -> "audio/wav"
            "opus" -> "audio/opus"
            else -> "audio/mpeg"
        }
        return try {
            val conn = (java.net.URL("$FISH_BASE/v1/tts").openConnection()
                as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer ${fishApiKey()}")
                setRequestProperty("User-Agent", FISH_USER_AGENT)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("model", model)
            }
            try {
                conn.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val errText = conn.errorStream
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() } ?: ""
                    return newFixedLengthResponse(
                        Response.Status.lookup(code) ?: Response.Status.SERVICE_UNAVAILABLE,
                        "application/json",
                        JSONObject()
                            .put("error", summarizeFishError(errText)
                                ?: "Fish TTS HTTP $code")
                            .put("model", model)
                            .put("body", errText.take(800))
                            .toString()
                    )
                }
                val audio = conn.inputStream.use { it.readBytes() }
                val resp = newFixedLengthResponse(
                    Response.Status.OK,
                    mime,
                    java.io.ByteArrayInputStream(audio),
                    audio.size.toLong()
                )
                resp.addHeader("Content-Length", audio.size.toString())
                resp.addHeader("Cache-Control", "no-store")
                resp.addHeader("X-Tts-Engine", "fish")
                resp.addHeader("X-Tts-Model", model)
                resp
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fish TTS test exception", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                JSONObject().put(
                    "error",
                    "Network error: ${e.message ?: e.javaClass.simpleName}"
                ).toString()
            )
        }
    }

    // ── Library helpers ────────────────────────────────────────────────────

    private fun badRequest(msg: String): Response =
        newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json",
            JSONObject().put("error", msg).toString()
        )

    private fun mediaEntryToJson(entry: MediaLibraryService.MediaEntry): JSONObject =
        JSONObject()
            .put("name", entry.name)
            .put("relativePath", entry.relativePath)
            .put("kind", entry.kind.name)
            .put("sizeBytes", entry.sizeBytes)
            .put("lastModifiedMs", entry.lastModifiedMs)

    private fun playlistEntryToJson(
        entry: MediaLibraryService.PlaylistEntry,
        token: String
    ): JSONObject {
        val url: String = if (entry.isAbsoluteUrl) {
            entry.rawPath
        } else {
            val enc = URLEncoder.encode(entry.resolvedRelativePath, "UTF-8")
            "/media/file?path=$enc&token=$token"
        }
        return JSONObject()
            .put("title", entry.title)
            .put("rawPath", entry.rawPath)
            .put("relativePath", entry.resolvedRelativePath)
            .put("url", url)
            .put("kind", entry.kind.name)
            .put("durationSeconds", entry.durationSeconds ?: JSONObject.NULL)
            .put("isAbsoluteUrl", entry.isAbsoluteUrl)
    }

    private fun buildBreadcrumbs(relativePath: String): JSONArray {
        val arr = JSONArray()
        arr.put(JSONObject().put("name", "Media").put("path", ""))
        if (relativePath.isBlank()) return arr
        val parts = relativePath.split('/').filter { it.isNotBlank() }
        val acc = StringBuilder()
        for (p in parts) {
            if (acc.isNotEmpty()) acc.append('/')
            acc.append(p)
            arr.put(JSONObject().put("name", p).put("path", acc.toString()))
        }
        return arr
    }

    private fun sanitizeFilename(name: String): String {
        // Keep it simple: strip path separators and control chars, keep dots, dashes, underscores.
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
        return cleaned.replace(Regex("[\\u0000-\\u001F<>:\"|?*]"), "_").trim()
    }

    private fun defaultLibraryFilename(kind: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val cleanKind = kind.trim().lowercase(Locale.ROOT)
        val ext = when {
            cleanKind.contains("playlist") || cleanKind.contains("m3u") -> "m3u"
            cleanKind.contains("text") || cleanKind.contains("document") -> "txt"
            cleanKind.contains("video") -> "mp4"
            cleanKind.contains("audio") || cleanKind.contains("music") -> "mp3"
            else -> "txt"
        }
        return "tapinsight-$stamp.$ext"
    }

    private fun ensureLibraryExtension(filename: String, kind: String): String {
        if (filename.isBlank()) return filename
        val existing = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (existing.length in 2..8) return filename
        val fallback = defaultLibraryFilename(kind).substringAfterLast('.', "txt")
        return "$filename.$fallback"
    }

    /**
     * Whether the filename's extension indicates a binary file format
     * (audio, video, image) where the JSON `content` field of
     * /api/library/write SHOULD be treated as base64 — never as plain
     * text. Used by the writeLibraryFile defensive auto-decode path
     * for callers that forgot to set `encoding="base64"`.
     */
    private fun isBinaryLibraryExtension(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in setOf(
            // audio
            "mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac", "weba",
            // video
            "mp4", "m4v", "webm", "mkv", "mov", "3gp", "avi",
            // images
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"
        )
    }

    /**
     * Lightweight base64 sniff for /api/library/write content. We call
     * this only when the destination is a known-binary extension; in
     * that context, even a small base64 sample is a strong signal.
     * Checks the first 256 chars are all base64 alphabet (A-Z, a-z,
     * 0-9, +, /, =) plus optional whitespace.
     */
    private fun contentLooksLikeBase64(content: String): Boolean {
        if (content.length < 16) return false
        val sample = content.take(256)
        var b64 = 0
        var checked = 0
        for (c in sample) {
            val isB64 = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '+' || c == '/' || c == '='
            val isWs = c == '\n' || c == '\r' || c == ' ' || c == '\t'
            if (!(isB64 || isWs)) return false
            if (isB64) b64++
            checked++
        }
        return checked > 0 && (b64 * 100 / checked) >= 95
    }

    private fun normalizeLibraryTextContent(filename: String, content: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext == "m3u" || ext == "m3u8") {
            val trimmed = content.trimStart()
            if (!trimmed.startsWith("#EXTM3U", ignoreCase = true)) {
                return "#EXTM3U\n$content"
            }
        }
        return content
    }

    private fun uniqueFile(folder: File, desiredName: String): File {
        var candidate = File(folder, desiredName)
        if (!candidate.exists()) return candidate
        val dot = desiredName.lastIndexOf('.')
        val stem = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val ext = if (dot > 0) desiredName.substring(dot) else ""
        var i = 1
        while (true) {
            candidate = File(folder, "$stem-$i$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    private fun guessMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/opus"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "weba" -> "audio/webm"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "m3u", "m3u8" -> "audio/x-mpegurl"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "Companion server started on port $listeningPort")
            // Touch the media library once to trigger lazy bootstrap (creates
            // /Music, /Videos, README.txt inside Android/data/…/Media).
            try { mediaLibrary.ensureBootstrap() } catch (_: Throwable) {}
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start companion server: ${e.message}", e)
        }
    }

    fun stopServer() {
        stop()
        Log.d(TAG, "Companion server stopped")
    }
}
