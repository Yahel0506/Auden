package com.music.bitchord.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.R
import dev.auden.transfer.services.AccountService
import dev.auden.transfer.services.DESKTOP_USER_AGENT
import dev.auden.transfer.services.ServiceConnect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The sign-in for the services read through their web players (Apple Music, Tidal): their own page in a sheet over the
 * app. Drawn once, near the root; it shows only while [ServiceConnect] has a request, and closes itself when what the
 * page's requests show of the session reads a library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceCaptureHost() {
    val request by ServiceConnect.request.collectAsState()
    val current = request ?: return
    key(current.account) { ServiceCaptureSheet(current.account) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceCaptureSheet(account: AccountService) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    // A WebView composed while the sheet is still rising lays out at no height and shows blank: it waits for the sheet.
    var mounted by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch { sheetState.hide(); ServiceConnect.cancel() }
        }
    }
    LaunchedEffect(sheetState.currentValue) { if (sheetState.currentValue == SheetValue.Expanded) mounted = true }
    // Connected from under the sheet: it is taken away by the request ending, so just let go of it.
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler(onBack = close)
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.onBackground)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.sign_in_to, account.label), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(stringResource(R.string.sign_in_to_import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (mounted) {
                CaptureWebContent(account, Modifier.weight(1f).fillMaxWidth())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

/** The page name scripts report through, and the headers a web player sends with its session. */
private const val BRIDGE = "AudenCapture"
private val SESSION_HEADERS = setOf("authorization", "media-user-token")

/** Wraps fetch and XHR so the session headers the web player sends reach the app, even where the engine does not show them. */
private val HOOK_JS = """
(function() {
  if (window.__audenHooked) return;
  window.__audenHooked = true;
  var wanted = { 'authorization': 1, 'media-user-token': 1 };
  var seen = {}; var count = 0;
  function report(name, value, url) {
    try {
      name = String(name).toLowerCase();
      if (!wanted[name] || !value) return;
      var key = name + '|' + value;
      if (seen[key]) return;
      if (count > 200) { seen = {}; count = 0; }
      seen[key] = 1; count++;
      window.$BRIDGE.onHeader(name, String(value), String(url || location.href));
    } catch (e) {}
  }
  function scan(headers, url) {
    try {
      if (!headers) return;
      if (typeof Headers !== 'undefined' && headers instanceof Headers) headers.forEach(function(v, k) { report(k, v, url); });
      else if (Array.isArray(headers)) headers.forEach(function(p) { report(p[0], p[1], url); });
      else if (typeof headers === 'object') Object.keys(headers).forEach(function(k) { report(k, headers[k], url); });
    } catch (e) {}
  }
  try {
    var f = window.fetch;
    if (f) window.fetch = function(input, init) {
      try {
        var url = (typeof input === 'string') ? input : (input && input.url);
        if (input && typeof input === 'object' && input.headers) scan(input.headers, url);
        if (init && init.headers) scan(init.headers, url);
      } catch (e) {}
      return f.apply(this, arguments);
    };
  } catch (e) {}
  try {
    var open = XMLHttpRequest.prototype.open, set = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.open = function(m, url) { try { this.__au = url; } catch (e) {} return open.apply(this, arguments); };
    XMLHttpRequest.prototype.setRequestHeader = function(n, v) { report(n, v, this.__au); return set.apply(this, arguments); };
  } catch (e) {}
})();
""".trimIndent()

private class CaptureBridge(private val report: (String, String, String) -> Unit) {
    @JavascriptInterface
    fun onHeader(name: String?, value: String?, url: String?) {
        if (name.isNullOrBlank() || value.isNullOrBlank()) return
        report(name, value, url.orEmpty())
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun CaptureWebContent(account: AccountService, modifier: Modifier = Modifier) {
    var main by remember { mutableStateOf<WebView?>(null) }
    var popup by remember { mutableStateOf<WebView?>(null) }
    val bridge = remember(account) { CaptureBridge { n, v, u -> ServiceConnect.onHeader(account, n, v, u) } }

    fun isPlayer(url: String?) = url != null && account.playerOrigins.any { url.startsWith(it) }

    fun WebView.configure(isPopup: Boolean) {
        // match_parent on purpose: with none, the page lays out at zero height and a sign-in shows blank.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = account.desktopSite
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // Sign-in pages read the "wv" and version bits of a WebView's user agent as an embedded browser.
        settings.userAgentString = if (account.desktopSite) DESKTOP_USER_AGENT
        else settings.userAgentString.replace("; wv", "").replace(Regex("""\s?Version/\d+(\.\d+)*"""), "")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) isForceDarkAllowed = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(bridge, BRIDGE)

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (isPlayer(url)) view.evaluateJavascript(HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (isPlayer(url)) view.evaluateJavascript(HOOK_JS, null)
                ServiceConnect.tick(account)
            }

            // A second way to see the headers: the request as the engine sends it.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url?.toString().orEmpty()
                request.requestHeaders?.forEach { (name, value) -> if (name.lowercase() in SESSION_HEADERS) ServiceConnect.onHeader(account, name, value, url) }
                return null
            }
        }

        webChromeClient = object : WebChromeClient() {
            // Apple's authorize step and "continue with Google/Apple" open popups that report back to this page.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                popup?.destroy()
                val created = WebView(view.context).apply { configure(isPopup = true) }
                popup = created
                transport.webView = created
                resultMsg.sendToTarget()
                return true
            }

            // The web players check for DRM before they start; WebView refuses it unless the app agrees.
            override fun onPermissionRequest(request: PermissionRequest) {
                if (request.resources.any { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                } else {
                    request.deny()
                }
            }

            override fun onCloseWindow(window: WebView) {
                if (window == popup) { popup = null; window.destroy() }
            }
        }
    }

    BackHandler {
        val p = popup
        val m = main
        when {
            p != null && p.canGoBack() -> p.goBack()
            p != null -> { popup = null; p.destroy() }
            m != null && m.canGoBack() -> m.goBack()
            else -> ServiceConnect.cancel()
        }
    }

    // A single-page app signs in without a new page load, so look again every couple of seconds.
    LaunchedEffect(main) {
        if (main == null) return@LaunchedEffect
        while (true) { delay(2000); ServiceConnect.tick(account) }
    }

    DisposableEffect(account) {
        onDispose {
            popup?.destroy(); popup = null
            main?.let { it.stopLoading(); it.destroy() }; main = null
        }
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    configure(isPopup = false)
                    loadUrl(account.loginUrl)
                    main = this
                }
            },
        )
        popup?.let { p -> key(p) { AndroidView(modifier = Modifier.fillMaxSize().background(Color.White), factory = { p }) } }
    }
}
