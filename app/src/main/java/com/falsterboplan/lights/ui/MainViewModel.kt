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
import com.falsterboplan.lights.data.Moment
import com.falsterboplan.lights.data.Room
import com.falsterboplan.lights.data.Routine
import com.falsterboplan.lights.data.RoutineStore
import com.falsterboplan.lights.data.Step
import com.falsterboplan.lights.data.isManual
import com.falsterboplan.lights.data.lightIds
import com.falsterboplan.lights.data.onOff
import com.falsterboplan.lights.data.sceneAttributes
import com.falsterboplan.lights.data.scheduleIcon
import com.falsterboplan.lights.data.setRoomOrEach
import com.falsterboplan.lights.data.trigger
import com.falsterboplan.lights.data.wakeUpSteps
import com.falsterboplan.lights.data.windDownSteps
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
    val rooms: List<Room> = emptyList(),
    val loading: Boolean = false,
    val timers: List<LightTimer> = emptyList(),
    val schedules: List<Scene> = emptyList(),
    /** Scenes that run only when tapped, including ones made in the IKEA app. */
    val scenes: List<Scene> = emptyList(),
    /** Wake-ups, wind-downs and away mode: groups of schedules this app made. */
    val routines: List<Routine> = emptyList(),
    val addLamp: AddLamp = AddLamp.Idle,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = HubStore(app)
    private val timerStore = TimerStore(app)
    private val scheduleStore = ScheduleStore(app)
    private val routineStore = RoutineStore(app)
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
                val accessToken = token ?: error("Ingen tryckte på hubbens knapp i tid. Försök igen.")
                val pin = seenPin ?: error("Kunde inte läsa hubbens certifikat.")

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
        timerStore.all().forEach { TimerScheduler.cancel(getApplication(), it.id) }
        timerStore.clear()
        // Schedules stay on the hub (they belong to the home, not this phone); we just stop tracking them.
        scheduleStore.clear()
        routineStore.clear()
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
            WidgetUpdater.push(getApplication(), WidgetSnapshot.of(lights))
            // Rooms are listed separately so that rooms without lights still show up.
            runCatching { c.rooms() }.onSuccess { rooms -> _state.update { it.copy(rooms = rooms) } }
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

    // ---- Names and rooms ---------------------------------------------

    fun renameLight(device: Device, name: String) {
        val c = client ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == device.attributes.customName) return
        updateLocally(setOf(device.id)) { it.copy(customName = trimmed) }
        send { c.rename(device.id, trimmed) }
    }

    fun renameRoom(roomId: String, name: String) {
        val c = client ?: return
        val trimmed = name.trim()
        val room = _state.value.rooms.firstOrNull { it.id == roomId }
            ?: _state.value.lights.firstNotNullOfOrNull { l -> l.room?.takeIf { it.id == roomId } }
            ?: return
        if (trimmed.isEmpty() || trimmed == room.name) return
        _state.update { s ->
            s.copy(
                rooms = s.rooms.map { if (it.id == roomId) it.copy(name = trimmed) else it },
                lights = s.lights.map { l -> if (l.room?.id == roomId) l.copy(room = l.room.copy(name = trimmed)) else l },
            )
        }
        send { c.renameRoom(room, trimmed) }
    }

    fun moveLight(device: Device, room: Room) {
        val c = client ?: return
        if (device.room?.id == room.id) return
        _state.update { s -> s.copy(lights = s.lights.map { if (it.id == device.id) it.copy(room = room) else it }) }
        send { c.moveToRoom(room.id, listOf(device.id)) }
    }

    /** Creates a room on the hub and, when [andMove] is given, puts that light in it. */
    fun addRoom(name: String, andMove: Device? = null) {
        val c = client ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        send {
            val id = c.createRoom(trimmed)
            if (andMove != null) c.moveToRoom(id, listOf(andMove.id))
            load(c, showSpinner = false)
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

    // ---- Scenes, schedules and routines (all stored on and run by the hub) ----

    private suspend fun loadSchedules(c: DirigeraClient) {
        val mine = scheduleStore.ids()
        val routines = routineStore.all()
        val inRoutine = routines.flatMap { it.sceneIds }.toSet()
        _state.update { it.copy(routines = routines) }
        runCatching { c.scenes() }.onSuccess { all ->
            _state.update { state ->
                state.copy(
                    schedules = all.filter { it.id in mine },
                    scenes = all
                        .filter { it.isManual && it.id !in mine && it.id !in inRoutine && (it.type.isEmpty() || it.type == "userScene") }
                        .sortedBy { it.info.name.lowercase() },
                )
            }
        }
    }

    fun allOff() {
        val c = client ?: return
        val lit = _state.value.lights.filter { it.isReachable && it.attributes.isOn }
        if (lit.isEmpty()) return
        updateLocally(lit.map { it.id }.toSet()) { it.copy(isOn = false) }
        send {
            lit.groupBy { it.room?.id }.forEach { (roomId, members) ->
                c.setRoomOrEach(roomId, members.map { it.id }, onOff(false))
            }
        }
    }

    /** Saves what the given lights are doing right now as a scene that can be brought back later. */
    fun saveScene(name: String, lightIds: List<String>) {
        val c = client ?: return
        val trimmed = name.trim()
        val actions = _state.value.lights.filter { it.id in lightIds && it.isReachable }.map { it.id to it.sceneAttributes() }
        if (trimmed.isEmpty() || actions.isEmpty()) return
        send {
            c.saveScene(null, trimmed, "scenes_heart", emptyList(), actions)
            loadSchedules(c)
        }
    }

    fun runScene(scene: Scene) {
        val c = client ?: return
        send {
            c.runScene(scene.id)
            delay(600)
            load(c, showSpinner = false)
        }
    }

    fun deleteScene(id: String) {
        val c = client ?: return
        send {
            deleteQuietly(c, id)
            scheduleStore.remove(id)
            loadSchedules(c)
        }
    }

    private suspend fun deleteQuietly(c: DirigeraClient, id: String) {
        try {
            c.deleteScene(id)
        } catch (e: HubException) {
            if (e.status != 404) throw e // already removed in the IKEA app
        }
    }

    fun addSchedule(label: String, lightIds: List<String>, on: Boolean, moment: Moment, days: List<String>) {
        val c = client ?: return
        if (lightIds.isEmpty() || days.isEmpty()) return
        send {
            val name = "$label ${onOffText(on)} ${moment.text()}"
            val id = c.saveScene(null, name, scheduleIcon(on), listOf(moment.trigger(days)), lightIds.map { it to onOff(on) })
            scheduleStore.add(id)
            loadSchedules(c)
        }
    }

    /** Changes when an existing schedule runs and what it does; the lights stay the same. */
    fun updateSchedule(scene: Scene, on: Boolean, moment: Moment, days: List<String>) {
        val c = client ?: return
        if (days.isEmpty() || scene.lightIds.isEmpty()) return
        send {
            val label = scene.info.name.split(Regex(" (på|av|on|off) "), limit = 2).first()
            val name = "$label ${onOffText(on)} ${moment.text()}"
            c.saveScene(scene.id, name, scheduleIcon(on), listOf(moment.trigger(days)), scene.lightIds.map { it to onOff(on) })
            loadSchedules(c)
        }
    }

    fun addWakeUp(label: String, lightIds: List<String>, hour: Int, minute: Int, minutes: Int, days: List<String>) = addRoutine(
        kind = "wake",
        label = "Väckning $label",
        summary = "Fullt ljus %02d:%02d, börjar ${duration(minutes)} innan · ${dayText(days)}".format(hour, minute),
        icon = "scenes_wake_up",
        lightIds = lightIds,
        steps = wakeUpSteps(hour, minute, minutes, days),
    )

    fun addWindDown(label: String, lightIds: List<String>, hour: Int, minute: Int, minutes: Int, days: List<String>) = addRoutine(
        kind = "wind",
        label = "Nedvarvning $label",
        summary = "Från %02d:%02d, släckt efter ${duration(minutes)} · ${dayText(days)}".format(hour, minute),
        icon = "scenes_moon",
        lightIds = lightIds,
        steps = windDownSteps(hour, minute, minutes, days),
    )

    /**
     * Away mode: each chosen room comes on around sunset and goes off late in the evening, at
     * times that differ between rooms and between alternating days, so the home looks lived in.
     */
    fun startAway(roomIds: List<String>) {
        val rooms = roomIds.mapNotNull { id ->
            val members = _state.value.lights.filter { it.room?.id == id }.map { it.id }
            val name = _state.value.rooms.firstOrNull { it.id == id }?.name ?: return@mapNotNull null
            if (members.isEmpty()) null else Triple(id, name, members)
        }
        if (rooms.isEmpty()) return
        val c = client ?: return
        send {
            val ids = mutableListOf<String>()
            try {
                for ((_, name, members) in rooms) {
                    for (days in listOf(listOf("Mon", "Wed", "Fri", "Sun"), listOf("Tue", "Thu", "Sat"))) {
                        val on = Moment.Sun(sunrise = false, offsetMinutes = Random.nextInt(-10, 41))
                        val off = Moment.Clock(22 + Random.nextInt(2), Random.nextInt(60)).let {
                            if (it.hour == 22 && it.minute < 30) it.copy(minute = it.minute + 30) else it
                        }
                        ids += c.saveScene(null, "Bortaläge $name på", "scenes_leave_home", listOf(on.trigger(days)), members.map { it to onOff(true) })
                        ids += c.saveScene(null, "Bortaläge $name av", "scenes_leave_home", listOf(off.trigger(days)), members.map { it to onOff(false) })
                    }
                }
            } catch (e: Exception) {
                ids.forEach { runCatching { c.deleteScene(it) } }
                throw e
            }
            routineStore.add(Routine(System.currentTimeMillis(), "away", "Bortaläge", rooms.joinToString(", ") { it.second }, ids))
            loadSchedules(c)
        }
    }

    private fun addRoutine(kind: String, label: String, summary: String, icon: String, lightIds: List<String>, steps: List<Step>) {
        val c = client ?: return
        if (lightIds.isEmpty()) return
        send {
            val ids = mutableListOf<String>()
            try {
                steps.forEachIndexed { i, step ->
                    ids += c.saveScene(null, "$label ${i + 1}/${steps.size}", icon, listOf(step.trigger), lightIds.map { it to step.attributes })
                }
            } catch (e: Exception) {
                // Don't leave half a routine on the hub.
                ids.forEach { runCatching { c.deleteScene(it) } }
                throw e
            }
            routineStore.add(Routine(System.currentTimeMillis(), kind, label, summary, ids))
            loadSchedules(c)
        }
    }

    fun deleteRoutine(id: Long) {
        val c = client ?: return
        val routine = routineStore.all().firstOrNull { it.id == id } ?: return
        send {
            routine.sceneIds.forEach { deleteQuietly(c, it) }
            routineStore.remove(id)
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
                    ?: error("Hubben hittades inte bland enheterna, så parkopplingsläget kan inte slås på.")
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
                            AddLamp.Failed("Ingen ny lampa anslöt inom två minuter.")
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

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun describe(e: Exception): String = when {
        e is HubException && e.status == 401 -> "Hubben godkänner inte längre appen. Glöm hubben och anslut igen."
        e is HubException -> e.message ?: "Hubben svarade med ett fel."
        e is java.net.UnknownHostException || e is java.net.ConnectException ||
            e is java.net.SocketTimeoutException || e is java.net.NoRouteToHostException ->
            "Når inte hubben. Kontrollera att mobilen är på samma wifi."
        else -> e.message ?: e.javaClass.simpleName
    }
}
