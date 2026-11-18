package com.falsterboplan.lights.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.falsterboplan.lights.data.Attributes
import com.falsterboplan.lights.data.Device
import com.falsterboplan.lights.data.DirigeraClient
import com.falsterboplan.lights.data.DiscoveredHub
import com.falsterboplan.lights.data.Hub
import com.falsterboplan.lights.data.HubDiscovery
import com.falsterboplan.lights.data.Scene
import com.falsterboplan.lights.data.ScheduleStore
import com.falsterboplan.lights.timer.LightTimer
import com.falsterboplan.lights.timer.TimerScheduler
import com.falsterboplan.lights.timer.TimerStore
import com.falsterboplan.lights.widget.WidgetSnapshot
import com.falsterboplan.lights.widget.WidgetUpdater
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.random.Random
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

sealed interface AddLamp {
    data object Idle : AddLamp
    data object Searching : AddLamp
    data class Found(val names: List<String>) : AddLamp
    data class Failed(val message: String) : AddLamp
}

data class UiState(
    val paired: Boolean = false,
    val discovered: List<DiscoveredHub> = emptyList(),
    val pairing: Pairing = Pairing.Idle,
    val lights: List<Device> = emptyList(),
    val loading: Boolean = false,
    val timers: List<LightTimer> = emptyList(),
    val schedules: List<Scene> = emptyList(),
    val chaos: Boolean = false,
    val addLamp: AddLamp = AddLamp.Idle,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = HubStore(app)
    private val timerStore = TimerStore(app)
    private val scheduleStore = ScheduleStore(app)
    private val discovery = HubDiscovery(app)

    private var hub: PairedHub? = store.load()
    private var client: DirigeraClient? = hub?.let(Hub::clientFor)

    private val _state = MutableStateFlow(UiState(paired = hub != null))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pairingJob: Job? = null
    private var discoveryJob: Job? = null
    private var liveJob: Job? = null

    init {
        if (hub != null) startLive() else startDiscovery()
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
                client = Hub.clientFor(paired)
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
        chaosJob?.cancel()
        timerStore.all().forEach { TimerScheduler.cancel(getApplication(), it.id) }
        timerStore.clear()
        // Schedules stay on the hub (they belong to the home, not this phone); we just stop tracking them.
        scheduleStore.clear()
        store.clear()
        hub = null
        client = null
        _state.value = UiState()
        viewModelScope.launch { WidgetUpdater.push(getApplication(), WidgetSnapshot(paired = false)) }
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
            val now = System.currentTimeMillis()
            _state.update {
                it.copy(
                    lights = lights,
                    loading = false,
                    error = null,
                    timers = timerStore.all().filter { t -> t.atMillis > now - 60_000 },
                )
            }
            if (!_state.value.chaos) WidgetUpdater.push(getApplication(), WidgetSnapshot.of(lights))
            loadSchedules(c)
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

    // ---- Timers (fired by this phone) ---------------------------------

    fun addTimer(label: String, lightIds: List<String>, on: Boolean, minutes: Int) {
        if (lightIds.isEmpty() || minutes <= 0) return
        val at = System.currentTimeMillis() + minutes * 60_000L
        val timer = LightTimer((at and 0x7fffffff).toInt(), label, lightIds, on, at)
        timerStore.add(timer)
        TimerScheduler.schedule(getApplication(), timer)
        _state.update { it.copy(timers = timerStore.all()) }
    }

    fun cancelTimer(id: Int) {
        TimerScheduler.cancel(getApplication(), id)
        timerStore.remove(id)
        _state.update { it.copy(timers = timerStore.all()) }
    }

    // ---- Schedules (stored on and run by the hub) ----------------------

    private suspend fun loadSchedules(c: DirigeraClient) {
        val mine = scheduleStore.ids()
        if (mine.isEmpty()) {
            _state.update { it.copy(schedules = emptyList()) }
            return
        }
        runCatching { c.scenes() }.onSuccess { scenes ->
            _state.update { it.copy(schedules = scenes.filter { s -> s.id in mine }.sortedBy { s -> s.timeTrigger?.time }) }
        }
    }

    fun addSchedule(label: String, lightIds: List<String>, on: Boolean, hour: Int, minute: Int, days: List<String>) {
        val c = client ?: return
        if (lightIds.isEmpty() || days.isEmpty()) return
        send {
            val time = "%02d:%02d".format(hour, minute)
            val id = c.createSchedule("$label ${if (on) "on" else "off"} $time", on, time, days, lightIds)
            scheduleStore.add(id)
            loadSchedules(c)
        }
    }

    fun deleteSchedule(id: String) {
        val c = client ?: return
        send {
            try {
                c.deleteScene(id)
            } catch (e: HubException) {
                if (e.status != 404) throw e // already removed from the IKEA app
            }
            scheduleStore.remove(id)
            loadSchedules(c)
        }
    }

    // ---- Adding lamps --------------------------------------------------

    private var addLampJob: Job? = null

    /** Opens the hub's pairing window and waits up to two minutes for a new device to join. */
    fun startAddLamp() {
        val c = client ?: return
        addLampJob?.cancel()
        addLampJob = viewModelScope.launch {
            _state.update { it.copy(addLamp = AddLamp.Searching) }
            try {
                val before = c.devices()
                val gateway = before.firstOrNull { it.type == "gateway" }
                    ?: error("The hub didn't report itself, so pairing mode can't be switched on.")
                val known = before.map { it.id }.toSet()
                c.setPairingMode(gateway.id, true)
                var added = emptyList<Device>()
                try {
                    repeat(40) {
                        if (added.isEmpty()) {
                            delay(3000)
                            added = runCatching { c.devices() }.getOrDefault(emptyList()).filter { it.id !in known }
                        }
                    }
                } finally {
                    withContext(NonCancellable) { runCatching { c.setPairingMode(gateway.id, false) } }
                }
                _state.update {
                    it.copy(
                        addLamp = if (added.isEmpty()) {
                            AddLamp.Failed("No new lamp joined within two minutes.")
                        } else {
                            AddLamp.Found(added.map { d -> d.name })
                        },
                    )
                }
                load(c, showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(addLamp = AddLamp.Failed(describe(e))) }
            }
        }
    }

    fun stopAddLamp() {
        addLampJob?.cancel()
        _state.update { it.copy(addLamp = AddLamp.Idle) }
    }

    // ---- Chaos ---------------------------------------------------------

    private var chaosJob: Job? = null

    /** Ten seconds of every light changing at random, then everything goes back to how it was. */
    fun startChaos() {
        val c = client ?: return
        if (_state.value.chaos) return
        val before = _state.value.lights.filter { it.isReachable }
        if (before.isEmpty()) return
        chaosJob = viewModelScope.launch {
            _state.update { it.copy(chaos = true) }
            try {
                each(before.filter { !it.attributes.isOn }) { c.setDevice(it.id, buildJsonObject { put("isOn", true) }) }
                val end = System.currentTimeMillis() + 10_000
                var tick = 0
                while (System.currentTimeMillis() < end) {
                    val started = System.currentTimeMillis()
                    val step = tick++
                    each(before) { c.setDevice(it.id, chaosAttributes(it, step)) }
                    // At most two changes a second: lively, but below the rate that is risky for
                    // people with photosensitive epilepsy, and gentle on the Zigbee network.
                    delay((500 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
                }
            } finally {
                withContext(NonCancellable) {
                    each(before) { restore(c, it) }
                    _state.update { it.copy(chaos = false) }
                    load(c, showSpinner = false)
                }
            }
        }
    }

    private suspend fun each(lights: List<Device>, block: suspend (Device) -> Unit) = coroutineScope {
        lights.map { async { runCatching { block(it) } } }.awaitAll()
    }

    private fun chaosAttributes(light: Device, step: Int): JsonObject = buildJsonObject {
        when {
            light.canSetColor -> {
                put("colorHue", Random.nextInt(360).toDouble())
                put("colorSaturation", 1.0)
            }
            light.canSetTemperature -> {
                val range = light.temperatureRange
                put("colorTemperature", if (Random.nextBoolean()) range.first else range.last)
            }
            light.canDim -> put("lightLevel", if (Random.nextBoolean()) 100 else Random.nextInt(1, 30))
            else -> put("isOn", (step + light.id.hashCode()) % 2 == 0)
        }
    }

    private suspend fun restore(c: DirigeraClient, light: Device) {
        val a = light.attributes
        if (light.canSetColor && a.colorMode == "color" && a.colorHue != null) {
            c.setDevice(light.id, buildJsonObject {
                put("colorHue", a.colorHue)
                put("colorSaturation", a.colorSaturation ?: 1.0)
            })
        } else if (light.canSetTemperature && a.colorTemperature != null) {
            c.setDevice(light.id, buildJsonObject { put("colorTemperature", a.colorTemperature) })
        }
        if (light.canDim && a.lightLevel != null) {
            c.setDevice(light.id, buildJsonObject { put("lightLevel", a.lightLevel) })
        }
        c.setDevice(light.id, buildJsonObject { put("isOn", a.isOn) })
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
