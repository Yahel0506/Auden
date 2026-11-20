package dev.auden.transfer.services

import android.webkit.CookieManager
import android.webkit.WebStorage
import dev.auden.library.Service
import dev.auden.transfer.MusicSource
import dev.auden.transfer.TransferSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The sessions of the services Auden reads through their web players (Apple Music, Tidal): one place that asks for a
 * sign-in, collects what the web player's requests show of the session, and says when a library can be read.
 *
 * The session is kept only while the app runs — the tokens a web player sends are short-lived, and the sign-in itself
 * stays in the WebView's cookies, so connecting again is a tap. Screens ask with [request] and watch [connected]; one
 * host composable (`ServiceCaptureHost`) draws the sign-in sheet over the app.
 */
object ServiceConnect {
    /** A sign-in being asked for; [thenTransfer] goes on to the chooser of what to bring once it connects. */
    data class Request(val account: AccountService, val thenTransfer: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val credentials = AccountService.entries.associateWith { CapturedCredentials() }
    private val libraries = ConcurrentHashMap<Service, AccountLibrary>()
    private var connecting: Job? = null

    private val _connected = MutableStateFlow<Set<Service>>(emptySet())
    val connected: StateFlow<Set<Service>> = _connected.asStateFlow()

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    fun isConnected(service: Service): Boolean = service in _connected.value

    /** Opens the service's sign-in; once a library can be read the sheet closes (and the chooser opens, if asked). */
    fun request(service: Service, thenTransfer: Boolean = true) {
        val account = AccountService.of(service) ?: return
        _request.value = Request(account, thenTransfer)
    }

    fun cancel() { _request.value = null }

    /** A request header the web player sent, seen by the sign-in page. */
    fun onHeader(account: AccountService, name: String, value: String, url: String) {
        credentials.getValue(account).addHeader(name, value, url)
    }

    /** Called as the page loads and every couple of seconds: tries what was captured, once, if nothing is connected yet. */
    fun tick(account: AccountService) {
        if (isConnected(account.service) || connecting?.isActive == true) return
        connecting = scope.launch { attempt(account) }
    }

    private suspend fun attempt(account: AccountService) {
        val creds = credentials.getValue(account)
        val connector = if (account == AccountService.APPLE_MUSIC) AppleMusicConnector else TidalConnector
        val library = try {
            connector.connect(creds)
        } catch (e: AccountRetryLater) {
            delay(e.seconds * 1000)
            return
        } catch (e: Exception) {
            return
        } ?: return
        libraries[account.service] = library
        _connected.value = _connected.value + account.service
        val asked = _request.value?.takeIf { it.account == account }
        if (asked != null) {
            _request.value = null
            if (asked.thenTransfer) TransferSession.open(account.service)
        }
    }

    /** The service's library as a source, or null while it is not connected. */
    fun source(service: Service): MusicSource? = libraries[service]?.let { AccountMusicSource(service, it) }

    /** Forgets the session and signs the web view out, so the next sign-in can use another account. */
    fun disconnect(service: Service) {
        val account = AccountService.of(service) ?: return
        credentials.getValue(account).clear()
        libraries.remove(service)
        _connected.value = _connected.value - service
        clearWebSession(account.webOrigins)
    }

    private fun clearWebSession(origins: List<String>) {
        val cookies = CookieManager.getInstance()
        origins.forEach { origin ->
            val host = origin.removePrefix("https://")
            val parent = host.split('.').takeLast(2).joinToString(".")
            cookies.getCookie(origin)?.split(';')?.forEach { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    cookies.setCookie(origin, "$name=; Max-Age=0; Path=/")
                    cookies.setCookie(origin, "$name=; Max-Age=0; Path=/; Domain=.$parent")
                }
            }
            // Web players keep their session in local storage too.
            WebStorage.getInstance().deleteOrigin(origin)
        }
        cookies.flush()
    }
}
