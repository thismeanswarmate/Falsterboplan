package com.falsterboplan.lights.update

import com.falsterboplan.lights.data.HubJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun readsTheReleaseFileAndComparesVersions() {
        // The shape the release workflow writes, plus a field a later version might add.
        val release = HubJson.decodeFromString<Release>(
            """{"versionCode": 61, "versionName": "1.61", "apk": "falsterboplan.apk", "notes": "Kalender", "sha": "abc"}""",
        )
        assertEquals(Release(61, "1.61", "falsterboplan.apk", "Kalender"), release)
        assertTrue(release.isNewerThan(60))
        assertFalse(release.isNewerThan(61))
        assertFalse(release.isNewerThan(62))
        assertEquals("falsterboplan.apk", HubJson.decodeFromString<Release>("""{"versionCode": 3}""").apk)
    }
}
