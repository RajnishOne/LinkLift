package com.rjnsdev.linklift.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rjnsdev.linklift.app.RemoteConfigHelper
import com.rjnsdev.linklift.app.ui.theme.LinkLiftTheme
import com.rjnsdev.linklift.app.util.CookieHelper
import java.io.File

class YouTubeAuthActivity : ComponentActivity() {

    companion object {
        const val YOUTUBE_LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"
        const val YOUTUBE_HOME_URL = "https://m.youtube.com"

        fun createIntent(context: Context): Intent =
            Intent(context, YouTubeAuthActivity::class.java)
    }

    private var webViewInstance: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RemoteConfigHelper.isYouTubeAvailable) {
            Toast.makeText(this, "YouTube features are currently unavailable", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        enableEdgeToEdge()

        setContent {
            LinkLiftTheme {
                YouTubeAuthScreen(
                    onBackClick = { finish() },
                    onSaveClick = { extractAndSaveCookies() },
                    onClearClick = { clearWebViewData() },
                )
            }
        }
    }

    private fun clearWebViewData() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        webViewInstance?.clearHistory()
        webViewInstance?.clearCache(true)
        webViewInstance?.loadUrl(YOUTUBE_LOGIN_URL)
        Toast.makeText(this, "Cleared session cookies", Toast.LENGTH_SHORT).show()
    }

    private fun extractAndSaveCookies() {
        val cm = CookieManager.getInstance()
        cm.flush()

        val ytCookies = cm.getCookie("https://www.youtube.com").orEmpty()
        val googleCookies = cm.getCookie("https://accounts.google.com").orEmpty()
        val baseGoogleCookies = cm.getCookie("https://google.com").orEmpty()

        val entries = mutableListOf<CookieHelper.CookieEntry>()

        fun parseAndAdd(cookieStr: String, domain: String) {
            if (cookieStr.isBlank()) return
            cookieStr.split(";").forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.contains("=")) {
                    val parts = trimmed.split("=", limit = 2)
                    val name = parts[0].trim()
                    val value = if (parts.size > 1) parts[1].trim() else ""
                    if (name.isNotEmpty()) {
                        entries.add(
                            CookieHelper.CookieEntry(
                                domain = domain,
                                path = "/",
                                isSecure = name.startsWith("__Secure-") || name.startsWith("__Host-") || name == "SAPISID" || name == "SSID",
                                expiry = System.currentTimeMillis() / 1000L + (180L * 24 * 3600),
                                name = name,
                                value = value,
                            )
                        )
                    }
                }
            }
        }

        parseAndAdd(ytCookies, ".youtube.com")
        parseAndAdd(googleCookies, ".google.com")
        parseAndAdd(baseGoogleCookies, ".google.com")

        // Fallback / supplement from WebView SQLite Cookies db if accessible
        tryExtractFromWebViewDb(entries)

        if (entries.isEmpty()) {
            Toast.makeText(this, "No cookies detected. Please sign in first.", Toast.LENGTH_LONG).show()
            return
        }

        val netscapeContent = CookieHelper.formatCookieEntriesToNetscape(entries)
        val result = CookieHelper.importFromText(this, netscapeContent)

        if (result.isSuccess) {
            val count = result.getOrDefault(entries.size)
            Toast.makeText(this, "Successfully saved $count cookies!", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        } else {
            Toast.makeText(this, "Failed to save cookies: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun tryExtractFromWebViewDb(entries: MutableList<CookieHelper.CookieEntry>) {
        runCatching {
            val dataDir = File(applicationInfo.dataDir)
            val cookieDb = dataDir.walkTopDown().find { it.name == "Cookies" && it.isFile } ?: return
            val db = SQLiteDatabase.openDatabase(cookieDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.use { database ->
                val cursor = database.query(
                    "cookies",
                    arrayOf("host_key", "name", "value", "path", "expires_utc", "is_secure"),
                    null, null, null, null, null,
                )
                cursor.use { c ->
                    val hostIdx = c.getColumnIndex("host_key")
                    val nameIdx = c.getColumnIndex("name")
                    val valIdx = c.getColumnIndex("value")
                    val pathIdx = c.getColumnIndex("path")
                    val expIdx = c.getColumnIndex("expires_utc")
                    val secIdx = c.getColumnIndex("is_secure")
                    while (c.moveToNext()) {
                        val hostKey = if (hostIdx >= 0) c.getString(hostIdx) else ""
                        val name = if (nameIdx >= 0) c.getString(nameIdx) else ""
                        val value = if (valIdx >= 0) c.getString(valIdx) else ""
                        val path = if (pathIdx >= 0) c.getString(pathIdx) else "/"
                        val expiry = if (expIdx >= 0) c.getLong(expIdx) else 0L
                        val secure = if (secIdx >= 0) c.getInt(secIdx) == 1 else true
                        if (name.isNotBlank() && (hostKey.contains("youtube") || hostKey.contains("google"))) {
                            entries.add(
                                CookieHelper.CookieEntry(
                                    domain = hostKey,
                                    path = path,
                                    isSecure = secure,
                                    expiry = if (expiry > 0) expiry / 1000000L else System.currentTimeMillis() / 1000L + (180L * 24 * 3600),
                                    name = name,
                                    value = value,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun YouTubeAuthScreen(
        onBackClick: () -> Unit,
        onSaveClick: () -> Unit,
        onClearClick: () -> Unit,
    ) {
        var progress by remember { mutableIntStateOf(0) }
        var isLoading by remember { mutableStateOf(true) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text("Sign in to YouTube")
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { webViewInstance?.reload() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                            )
                        }
                        IconButton(onClick = onClearClick) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear Session",
                            )
                        }
                        Button(
                            onClick = onSaveClick,
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            Text("Save")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setupWebView(this) { prog, loading ->
                                progress = prog
                                isLoading = loading
                            }
                            loadUrl(YOUTUBE_LOGIN_URL)
                            webViewInstance = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(
        webView: WebView,
        onProgressUpdate: (Int, Boolean) -> Unit,
    ) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgressUpdate(newProgress, newProgress < 100)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                cookieManager.flush()
            }
        }
    }
}
