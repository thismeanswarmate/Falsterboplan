package com.falsterboplan.lights.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DirigeraClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DirigeraClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DirigeraClient(server.url("/v1/"), OkHttpClient(), token = "tok")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun pkceChallengeMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        assertEquals(128, Pkce.newVerifier().length)
    }

    @Test
    fun pairingRequestsCodeThenPollsForToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":"abc"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Button not pressed"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"jwt","token_type":"Bearer"}"""))

        assertEquals("abc", client.requestCode("verifier"))
        val authorize = server.takeRequest()
        assertEquals("/v1/oauth/authorize", authorize.requestUrl!!.encodedPath)
        assertEquals("homesmart.local", authorize.requestUrl!!.queryParameter("audience"))
        assertEquals(Pkce.challenge("verifier"), authorize.requestUrl!!.queryParameter("code_challenge"))
        assertEquals("S256", authorize.requestUrl!!.queryParameter("code_challenge_method"))

        assertNull(client.exchangeToken("abc", "verifier", "phone"))
        assertEquals("jwt", client.exchangeToken("abc", "verifier", "phone"))
        server.takeRequest()
        val token = server.takeRequest()
        assertEquals("POST", token.method)
        assertEquals("/v1/oauth/token", token.path)
        assertEquals(
            "code=abc&name=phone&grant_type=authorization_code&code_verifier=verifier",
            token.body.readUtf8(),
        )
    }

    @Test
    fun lightsKeepsOnlyLightsAndParsesAttributes() = runBlocking {
        server.enqueue(MockResponse().setBody(DEVICES))

        val lights = client.lights()

        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
        assertEquals(listOf("light-1", "light-2"), lights.map { it.id })
        val bulb = lights[0]
        assertEquals("Sofa lamp", bulb.name)
        assertEquals("Living room", bulb.room?.name)
        assertTrue(bulb.attributes.isOn)
        assertEquals(42, bulb.attributes.lightLevel)
        assertTrue(bulb.canDim && bulb.canSetTemperature && bulb.canSetColor)
        assertEquals(2202..4000, bulb.temperatureRange)
        val plain = lights[1]
        assertFalse(plain.isReachable)
        assertFalse(plain.canSetColor)
        assertNull(plain.room)
    }

    @Test
    fun setDeviceSendsAttributePatch() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))

        client.setDevice("light-1", buildJsonObject { put("lightLevel", 60) })

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/devices/light-1", request.path)
        assertEquals("""[{"attributes":{"lightLevel":60}}]""", request.body.readUtf8())
    }

    @Test
    fun setRoomLightsTargetsLightsInRoom() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))

        client.setRoomLights("room-1", buildJsonObject { put("isOn", false) })

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/devices/room/room-1?deviceType=light", request.path)
        assertEquals("""[{"attributes":{"isOn":false}}]""", request.body.readUtf8())
    }

    @Test
    fun createScheduleSendsTimeTriggeredScene() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"scene-1"}"""))

        val id = client.createSchedule("Sofa lamp on 07:00", true, "07:00", listOf("Mon", "Fri"), listOf("light-1"))

        assertEquals("scene-1", id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/scenes", request.path)
        assertEquals(
            """{"info":{"name":"Sofa lamp on 07:00","icon":"scenes_wake_up"},"type":"userScene",""" +
                """"triggers":[{"type":"time","disabled":false,"trigger":{"days":["Mon","Fri"],"time":"07:00"}}],""" +
                """"actions":[{"type":"device","enabled":true,"deviceId":"light-1","attributes":{"isOn":true}}]}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun createScheduleRetriesWithOtherDeviceKeyOnBadRequest() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))
        server.enqueue(MockResponse().setBody("""{"id":"scene-2"}"""))

        assertEquals("scene-2", client.createSchedule("x", false, "22:30", listOf("Sun"), listOf("light-1")))

        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains(""""id":"light-1""""))
    }

    @Test
    fun scenesSkipsEntriesItCannotParseAndDeleteWorks() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":"s1","info":{"name":"Night","icon":"scenes_moon"},"type":"userScene",
                   "triggers":[{"id":"t0","type":"app","disabled":false},
                               {"id":"t1","type":"time","disabled":false,"trigger":{"days":["Mon"],"time":"22:00"},"nextTriggerAt":"x"}],
                   "actions":[{"id":"light-1","type":"device","attributes":{"isOn":false}}]},
                  {"id":42,"triggers":"weird"},
                  {"id":"s2","triggers":[{"type":"sunriseSunset","trigger":{"type":"sunset","offset":600}}]}]""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        val scenes = client.scenes()
        assertEquals(listOf("s1", "s2"), scenes.map { it.id })
        assertEquals("22:00", scenes[0].timeTrigger?.time)
        assertNull(scenes[1].timeTrigger)

        client.deleteScene("s1")
        server.takeRequest()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/scenes/s1", delete.path)
    }

    @Test
    fun pairingModePatchesTheGateway() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        client.setPairingMode("gw", true)
        val request = server.takeRequest()
        assertEquals("/v1/devices/gw", request.path)
        assertEquals("""[{"attributes":{"permittingJoin":true}}]""", request.body.readUtf8())
    }

    @Test
    fun renameCreateRoomAndMove() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setBody("""[{"id":"room-1","name":"Living room","color":"ikea_green_no_65","icon":"rooms_sofa"}]"""))
        server.enqueue(MockResponse().setBody("""{"id":"room-2"}"""))
        server.enqueue(MockResponse().setResponseCode(200))

        client.rename("light-1", "Reading lamp")
        assertEquals("""[{"attributes":{"customName":"Reading lamp"}}]""", server.takeRequest().body.readUtf8())

        assertEquals(listOf(Room("room-1", "Living room")), client.rooms())
        assertEquals("/v1/rooms", server.takeRequest().path)

        assertEquals("room-2", client.createRoom("Hallway"))
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/v1/rooms", create.path)
        assertEquals("""{"name":"Hallway","icon":"rooms_home","color":"ikea_green_no_65"}""", create.body.readUtf8())

        client.moveToRoom("room-2", listOf("light-1"))
        val move = server.takeRequest()
        assertEquals("PATCH", move.method)
        assertEquals("/v1/rooms/room-2/devices", move.path)
        assertEquals("""{"deviceIds":["light-1"]}""", move.body.readUtf8())
    }

    @Test
    fun errorsSurfaceStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val error = runCatching { client.lights() }.exceptionOrNull()
        assertEquals(401, (error as HubException).status)
    }

    private companion object {
        val DEVICES = """
        [
          {"id":"light-1","type":"light","deviceType":"light","isReachable":true,
           "attributes":{"customName":"Sofa lamp","model":"TRADFRI bulb E27 CWS 806lm","isOn":true,
             "lightLevel":42,"colorTemperature":2700,"colorTemperatureMin":4000,"colorTemperatureMax":2202,
             "colorHue":30.0,"colorSaturation":0.7,"colorMode":"temperature","startupOnOff":"startPrevious"},
           "capabilities":{"canSend":[],"canReceive":["customName","isOn","lightLevel","colorTemperature","colorHue","colorSaturation"]},
           "room":{"id":"room-1","name":"Living room","color":"ikea_green_no_65","icon":"rooms_sofa"},
           "deviceSet":[],"remoteLinks":[],"isHidden":false},
          {"id":"light-2","type":"light","deviceType":"light","isReachable":false,
           "attributes":{"customName":"","model":"TRADFRI bulb E14 WW","isOn":false,"lightLevel":null},
           "capabilities":{"canSend":[],"canReceive":["customName","isOn","lightLevel"]}},
          {"id":"remote-1","type":"controller","deviceType":"lightController","isReachable":true,
           "attributes":{"customName":"Remote","batteryPercentage":80,"isOn":false},
           "capabilities":{"canSend":["isOn","lightLevel"],"canReceive":["customName"]}}
        ]
        """.trimIndent()
    }
}
