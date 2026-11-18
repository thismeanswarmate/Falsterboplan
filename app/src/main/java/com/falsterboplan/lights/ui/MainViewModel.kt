package com.falsterboplan.lights.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.falsterboplan.lights.data.Attributes
import com.falsterboplan.lights.data.Device
import com.falsterboplan.lights.data.DirigeraClient
import com.falsterboplan.lights.data.DiscoveredHub
import com.falsterboplan.lights.data.HubDiscovery
import com.falsterboplan.lights.data.HubException
import com.falsterboplan.lights.data.HubStore
import com.falsterboplan.lights.data.PairedHub
import com.falsterboplan.lights.data.PinningTrustManager
import com.falsterboplan.lights.data.Pkce
import com.falsterboplan.lights.data.hubHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface Pairing {
    data object Idle : Pairing
    data class WaitingForButton(val host: String) : Pairing
    data class Failed(val message: String) : Pairing
}

data class UiState(
    val paired: Boolean = false,
    val discovered: List<DiscoveredHub> = emptyList(),
    val pairing: Pairing = Pairing.Idle,
    val lights: List<Device> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = HubStore(app)
    private val discovery = HubDiscovery(app)

    private var hub: PairedHub? = store.load()
    private var client: DirigeraClient? = hub?.let(::clientFor)

    private val _state = MutableStateFlow(UiState(paired = hub != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pairingJob: Job? = null
    private var discoveryJob: Job? = null
    private var liveJob: Job? = null

    init {
        if (hub != null) startLive() else startDiscovery()
    }

    private fun clientFor(hub: PairedHub): DirigeraClient {
        val trust = PinningTrustManager(expectedPin = { hub.pin }, onUnpinned = {})
        return DirigeraClient(DirigeraClient.baseUrlFor(hub.host), hubHttpClient(trust), hub.token)
    }

    // ---- Pairing -------------------------------------------------------

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            discovery.hubs().catch { }.collect { hubs -> _state.update { it.copy(discovered = hubs) } }
        }
    }

    fun pair(hostInput: String) {
        val host = hostInput.trim()
        if (host.isEmpty()) return
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            _state.update { it.copy(pairing = Pairing.WaitingForButton(host)) }
            try {
                var seenPin: String? = null
                val trust = PinningTrustManager(expectedPin = { seenPin }, onUnpinned = { seenPin = it })
                val pairingClient = DirigeraClient(DirigeraClient.baseUrlFor(host), hubHttpClient(trust))

                val verifier = Pkce.newVerifier()
                val code = pairingClient.requestCode(verifier)
                var token: String? = null
                // The hub accepts a button press for about a minute.
                repeat(40) {
                    if (token == null) {
                        delay(1500)
                        token = pairingClient.exchangeToken(code, verifier, "Falsterboplan (${Build.MODEL})")
                    }
                }
                val accessToken = token ?: error("Nobody pressed the button on the hub in time. Try again.")
                val pin = seenPin ?: error("Could not read the hub's certificate.")

                val paired = PairedHub(host, accessToken, pin)
                store.save(paired)
                hub = paired
                client = clientFor(paired)
                discoveryJob?.cancel()
                _state.update { it.copy(paired = true, pairing = Pairing.Idle, error = null) }
                startLive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(pairing = Pairing.Failed(describe(e))) }
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        _state.update { it.copy(pairing = Pairing.Idle) }
    }

    fun forgetHub() {
        liveJob?.cancel()
        store.clear()
        hub = null
        client = null
        _state.value = UiState()
        startDiscovery()
    }

    // ---- Lights --------------------------------------------------------

    /** Loads the lights, then keeps them fresh from the hub's event stream, reconnecting as needed. */
    private fun startLive() {
        liveJob?.cancel()
        liveJob = viewModelScope.launch {
            while (true) {
                val c = client ?: return@launch
                load(c, showSpinner = _state.value.lights.isEmpty())
                try {
                    c.events().collectLatest {
                        delay(300) // a single action often produces a burst of events
                        load(c, showSpinner = false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // socket dropped; fall through and reconnect
                }
                delay(5000)
            }
        }
    }

    fun refresh() {
        val c = client ?: return
        viewModelScope.launch { load(c, showSpinner = true) }
    }

    private suspend fun load(c: DirigeraClient, showSpinner: Boolean) {
        if (showSpinner) _state.update { it.copy(loading = true) }
        try {
            val lights = c.lights()
            _state.update { it.copy(lights = lights, loading = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = describe(e)) }
        }
    }

    fun setOn(device: Device, on: Boolean) =
        change(device, { it.copy(isOn = on) }, buildJsonObject { put("isOn", on) })

    fun setLevel(device: Device, level: Int) =
        change(device, { it.copy(lightLevel = level) }, buildJsonObject { put("lightLevel", level) })

    fun setTemperature(device: Device, kelvin: Int) = change(
        device,
        { it.copy(colorTemperature = kelvin, colorMode = "temperature") },
        buildJsonObject { put("colorTemperature", kelvin) },
    )

    fun setColor(device: Device, hue: Double, saturation: Double) = change(
        device,
        { it.copy(colorHue = hue, colorSaturation = saturation, colorMode = "color") },
        buildJsonObject {
            put("colorHue", hue)
            put("colorSaturation", saturation)
        },
    )

    fun setRoomOn(roomId: String, on: Boolean) {
        val c = client ?: return
        val members = _state.value.lights.filter { it.room?.id == roomId && it.isReachable }
        val ids = members.map { it.id }.toSet()
        updateLocally(ids) { it.copy(isOn = on) }
        val attributes = buildJsonObject { put("isOn", on) }
        send {
            try {
                c.setRoomLights(roomId, attributes)
            } catch (e: HubException) {
                // Fall back to switching each light if the hub rejects the room-wide request.
                members.forEach { c.setDevice(it.id, attributes) }
            }
        }
    }

    private fun change(device: Device, local: (Attributes) -> Attributes, attributes: JsonObject) {
        val c = client ?: return
        updateLocally(setOf(device.id), local)
        send { c.setDevice(device.id, attributes) }
    }

    /** Reflect the change immediately; the hub's event stream confirms or corrects it. */
    private fun updateLocally(ids: Set<String>, local: (Attributes) -> Attributes) {
        _state.update { s ->
            s.copy(lights = s.lights.map { if (it.id in ids) it.copy(attributes = local(it.attributes)) else it })
        }
    }

    private fun send(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = describe(e)) }
                client?.let { load(it, showSpinner = false) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun describe(e: Exception): String = when {
        e is HubException && e.status == 401 -> "The hub no longer accepts this app. Forget the hub and pair again."
        e is HubException -> e.message ?: "The hub returned an error."
        e is java.net.UnknownHostException || e is java.net.ConnectException ||
            e is java.net.SocketTimeoutException || e is java.net.NoRouteToHostException ->
            "Can't reach the hub. Check that your phone is on the same Wi-Fi."
        else -> e.message ?: e.javaClass.simpleName
    }
}
