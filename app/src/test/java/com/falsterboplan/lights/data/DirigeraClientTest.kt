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
