package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionMarkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var marker: SessionMarker

    @Before
    fun setUp() {
        marker = SessionMarker(File(tempFolder.root, SessionMarker.FILE_NAME))
    }

    @Test
    fun noMarkerInitially() {
        assertNull(marker.read())
    }

    @Test
    fun markConnectedRoundTrips() {
        marker.markConnected(versionCode = 105, connectedAtMillis = 1_700_000_000_000L)
        val info = marker.read()
        assertEquals(105, info?.versionCode)
        assertEquals(1_700_000_000_000L, info?.connectedAtMillis)
        assertEquals("Connected", info?.state)
    }

    @Test
    fun clearRemovesMarker() {
        marker.markConnected(versionCode = 1, connectedAtMillis = 2L)
        marker.clear()
        assertNull(marker.read())
    }
}
