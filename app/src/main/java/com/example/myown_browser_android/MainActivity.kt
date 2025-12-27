package com.example.myown_browser_android

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: CustomWebView
    private lateinit var urlInput: EditText
    private lateinit var goButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var urlBar: View
    private var isUrlBarVisible = true

    private lateinit var adHosts: Set<String>
    private var isAdBlockerEnabled = true
    private var blockThirdPartyCookies = true
    private var allowCamera = false
    private var allowMic = false
    private var allowLocation = false

    private var uploadMessage: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        var results: Array<Uri>? = null
        if (it.resultCode == RESULT_OK) {
            results = WebChromeClient.FileChooserParams.parseResult(it.resultCode, it.data)
        }
        uploadMessage?.onReceiveValue(results)
        uploadMessage = null
    }

    // THIS IS THE NEW LAUNCHER
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.getBooleanExtra("settings_changed", false) == true) {
            loadSettings()
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptThirdPartyCookies(webView, !blockThirdPartyCookies)
            webView.reload()

            Toast.makeText(this, "Settings updated and page reloaded.", Toast.LENGTH_SHORT).show()
        }
    }

    private val websitePermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            lastPermissionRequest?.grant(lastPermissionRequest?.resources)
            saveSitePermissions(lastPermissionRequest?.origin.toString(), lastPermissionRequest?.resources, granted = true)
        } else {
            lastPermissionRequest?.deny()
            saveSitePermissions(lastPermissionRequest?.origin.toString(), lastPermissionRequest?.resources, granted = false)
        }
    }

    private fun saveSitePermissions(origin: String, resources: Array<String>?, granted: Boolean) {
        if (resources == null) return
        val sharedPrefs = getSharedPreferences("SitePermissions", MODE_PRIVATE)
        val newPermissions = (sharedPrefs.getStringSet(origin, null) ?: mutableSetOf()).toMutableSet()

        if (granted) {
            newPermissions.addAll(resources)
        } else {
            newPermissions.removeAll(resources.toSet())
        }
        sharedPrefs.edit {
            putStringSet(origin, newPermissions)
        }
    }

    private var lastPermissionRequest: PermissionRequest? = null
    private var lastGeolocationRequestOrigin: String? = null
    private var geolocationCallback: GeolocationPermissions.Callback? = null

    private val locationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->

            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            geolocationCallback?.invoke(
                lastGeolocationRequestOrigin,
                granted && allowLocation,
                false // NEVER remember
            )
        }

    private val onDownloadComplete: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == intent.action) {
                val query = DownloadManager.Query().setFilterById(id)
                val downloadManager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val cursor = downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                    val status = cursor.getInt(statusIndex)
                    val filename = cursor.getString(titleIndex)
                    cursor.close()

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Toast.makeText(context, "Download complete: $filename", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Download failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ContextCompat.registerReceiver(this, onDownloadComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED)

        webView = findViewById(R.id.webview)
        urlInput = findViewById(R.id.url_input)
        goButton = findViewById(R.id.go_button)
        settingsButton = findViewById(R.id.settings_button)
        urlBar = findViewById(R.id.url_bar)

        loadSettings()

        urlBar.post {
            webView.setPadding(0, urlBar.height + (urlBar.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin * 2, 0, 0)
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptThirdPartyCookies(webView, !blockThirdPartyCookies)

        val webSettings = webView.settings
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true // Required for file uploads
        webSettings.allowContentAccess = false
        webSettings.setGeolocationEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Could not handle URL scheme", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (isAdBlockerEnabled) {
                    val host = request?.url?.host
                    if (host != null && adHosts.any { host == it || host.endsWith(".$it") }) {
                        val url = request.url.toString()
                        Log.d("AdBlocker", "Blocked request to $url")
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d("WebView", "Page started loading: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("WebView", "Page finished loading: $url")
                url?.let { urlInput.setText(it) }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                val message = "Error: ${error?.description} for ${request?.url}"
                Log.e("WebView", message)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                val message = "HTTP Error: ${errorResponse?.statusCode} for ${request?.url}"
                Log.e("WebView", message)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                super.onReceivedSslError(view, handler, error)
                Toast.makeText(this@MainActivity, "SSL Error: ${error?.primaryError}", Toast.LENGTH_LONG).show()
            }

            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                val builder = AlertDialog.Builder(this@MainActivity)
                builder.setTitle("Authentication Required")
                val layout = LinearLayout(this@MainActivity)
                layout.orientation = LinearLayout.VERTICAL
                val usernameInput = EditText(this@MainActivity)
                usernameInput.hint = "Username"
                layout.addView(usernameInput)
                val passwordInput = EditText(this@MainActivity)
                passwordInput.hint = "Password"
                passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                layout.addView(passwordInput)
                builder.setView(layout)
                builder.setPositiveButton("OK") { _, _ ->
                    handler?.proceed(usernameInput.text.toString(), passwordInput.text.toString())
                }
                builder.setNegativeButton("Cancel") { _, _ ->
                    handler?.cancel()
                }
                builder.show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {

                // Cancel any previous request
                uploadMessage?.onReceiveValue(null)
                uploadMessage = filePathCallback

                val intent = try {
                    fileChooserParams?.createIntent()?.apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                } catch (_: Exception) {
                    uploadMessage = null
                    return false
                }

                fileChooserLauncher.launch(intent)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {

                    val grants = mutableListOf<String>()
                    val osPermissions = mutableListOf<String>()

                    request.resources.forEach { res ->
                        when (res) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                if (!allowCamera) return@runOnUiThread request.deny()
                                if (ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    grants += res
                                } else {
                                    osPermissions += Manifest.permission.CAMERA
                                }
                            }

                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                if (!allowMic) return@runOnUiThread request.deny()
                                if (ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    grants += res
                                } else {
                                    osPermissions += Manifest.permission.RECORD_AUDIO
                                }
                            }
                        }
                    }

                    if (grants.isNotEmpty()) {
                        request.grant(grants.toTypedArray())
                    }

                    if (osPermissions.isNotEmpty()) {
                        lastPermissionRequest = request
                        websitePermissionsLauncher.launch(osPermissions.toTypedArray())
                    }

                    if (grants.isEmpty() && osPermissions.isEmpty()) {
                        request.deny()
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(                origin: String,
                                                                            callback: GeolocationPermissions.Callback
            ) {
                // Store the origin and callback to use them after the user responds
                lastGeolocationRequestOrigin = origin
                geolocationCallback = callback

                // Check if the app has location permissions
                val hasFineLocation = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val hasCoarseLocation = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                // If app already has permission and location is enabled in settings, grant it.
                if ((hasFineLocation || hasCoarseLocation) && allowLocation) {
                    callback.invoke(origin, true, false)
                } else {
                    // Otherwise, request permissions from the user.
                    // The result will be handled by your 'locationPermissionRequest' launcher.
                    locationPermissionRequest.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }

        webView.setDownloadListener { url, userAgent, _, _, _ ->
            startDownload(url, userAgent)
        }

        goButton.setOnClickListener {
            loadUrl()
        }

        // UPDATED ONCLICK LISTENER
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            settingsLauncher.launch(intent)
        }

        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                urlInput.post { urlInput.selectAll() }
            }
        }

        urlInput.setOnEditorActionListener { _, _, _ ->
            loadUrl()
            true
        }

        webView.onScrollChangedCallback = { _, scrollY, _, oldScrollY ->
            val scrollThreshold = 25
            // Always show the URL bar when at the top of the page
            if (scrollY <= 0) {
                showUrlBar()
            } else if (scrollY > oldScrollY && isUrlBarVisible) {
                hideUrlBarAndKeyboard()
            } else if (scrollY < oldScrollY - scrollThreshold && !isUrlBarVisible) {
                showUrlBar()
            }
        }

        webView.onSwipeDownCallback = {
            showUrlBar()
        }

        webView.onTapCallback = {
            hideUrlBarAndKeyboard()
        }

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        if (savedInstanceState == null) {
            val sharedPrefs = getSharedPreferences("AdBlockerPrefs", MODE_PRIVATE)
            val homePage = sharedPrefs.getString("home_page_url", "https://www.google.com")
            webView.loadUrl(homePage!!)
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
    }

    private fun startDownload(
        url: String,
        userAgent: String?
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                fun openConnection(method: String): HttpURLConnection {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = method
                    conn.instanceFollowRedirects = true
                    userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                    CookieManager.getInstance().getCookie(url)?.let {
                        conn.setRequestProperty("Cookie", it)
                    }
                    return conn
                }

                // 1) Try HEAD first
                var conn = openConnection("HEAD")
                conn.connect()

                var finalUrl = conn.url.toString()
                var disposition = conn.getHeaderField("Content-Disposition")

                // 2) If HEAD is useless, retry with GET
                if (disposition.isNullOrBlank() || disposition.contains("attachment", true).not()) {
                    conn.disconnect()
                    conn = openConnection("GET")
                    conn.connect()
                    conn.inputStream.close()

                    finalUrl = conn.url.toString()
                    disposition = conn.getHeaderField("Content-Disposition")
                }

                conn.disconnect()

                // 3) Extract filename properly
                val filenameFromHeader = extractFilenameFromContentDisposition(disposition)

                val fileName = when {
                    !filenameFromHeader.isNullOrBlank() ->
                        filenameFromHeader

                    else ->
                        finalUrl.toUri().lastPathSegment ?: "download"
                }

                // 4) Sanitize, but KEEP extension
                val cleanName = fileName
                    .replace(Regex("[/\\:*?\"<>|]"), "_")
                    .trim()

                // 5) Final safety: do NOT allow MIME to override extension
                val finalFileName = cleanName

                val request = DownloadManager.Request(finalUrl.toUri()).apply {
                    setTitle(finalFileName)
                    setDescription("Downloading file")
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        finalFileName
                    )

                    userAgent?.let { addRequestHeader("User-Agent", it) }
                    CookieManager.getInstance().getCookie(finalUrl)?.let {
                        addRequestHeader("Cookie", it)
                    }
                }

                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Download started: $finalFileName",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {
                Log.e("Download", "Download failed", e)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Download failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun extractFilenameFromContentDisposition(cd: String?): String? {
        if (cd == null) return null
        var fileName: String? = null
        val regex = Regex("filename\\*?=['\"]?([^;\"']+)['\"]?")
        val matchResult = regex.find(cd)
        if (matchResult != null) {
            val value = matchResult.groupValues[1]
            if (value.startsWith("UTF-8''")) {
                try {
                    fileName = URLDecoder.decode(value.substring(7), "UTF-8")
                } catch (_: Exception) {
                    // Fallback
                }
            }
            if (fileName == null) {
                fileName = value
            }
        }
        return fileName
    }

    private fun isIpAddress(input: String): Boolean =
        Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(input)

    private fun isLocalhost(input: String): Boolean =
        input.equals("localhost", true) || input.startsWith("localhost:")

    private fun looksLikeDomain(input: String): Boolean =
        input.contains('.') &&
                !input.contains(' ') &&
                Regex("""^[a-zA-Z0-9.-]+(:\d+)?(/.*)?$""").matches(input)

    private fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")

        // choose ONE, configurable later
        return "https://duckduckgo.com/?q=$encoded"
        // return "https://searx.example/search?q=$encoded"
    }

    private fun loadUrl() {

        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) return

        val hasScheme =
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(raw)

        val target = when {
            hasScheme -> raw
            isLocalhost(raw) || isIpAddress(raw) || looksLikeDomain(raw) ->
                "https://$raw"
            else ->
                buildSearchUrl(raw)
        }

        webView.loadUrl(target)
        hideUrlBarAndKeyboard()
    }

    private fun hideUrlBarAndKeyboard() {
        if (isUrlBarVisible) {
            isUrlBarVisible = false
            urlBar.animate().translationY(-(urlBar.height + (urlBar.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin * 2).toFloat()).duration = 220
        }

        urlInput.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    private fun showUrlBar() {
        if (!isUrlBarVisible) {
            isUrlBarVisible = true
            urlBar.animate().translationY(0f).duration = 220
        }
    }

    private fun loadSettings() {
        val sharedPrefs = getSharedPreferences("AdBlockerPrefs", MODE_PRIVATE)
        isAdBlockerEnabled = sharedPrefs.getBoolean("ad_blocker_enabled", true)
        adHosts = sharedPrefs.getStringSet("ad_hosts", setOf()) ?: setOf()
        blockThirdPartyCookies = sharedPrefs.getBoolean("block_third_party_cookies", true)
        allowCamera = sharedPrefs.getBoolean("allow_camera", false)
        allowMic = sharedPrefs.getBoolean("allow_mic", false)
        allowLocation = sharedPrefs.getBoolean("allow_location", false)
    }
}
