package com.example.lyricsflow

import com.example.lyricsflow.core.ReleaseVersion
import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun numericOrderingAndEquivalentTags() {
        fun v(raw: String) = ReleaseVersion.parse(raw)!!
        assertTrue(v("1.0.10") > v("1.0.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertEquals(0, v("v1.0.2").compareTo(v("1.0.2.0")))
        assertTrue(v("1.0.1") < v("1.0.2"))
    }

    @Test fun rejectPreviewsAndMalformedVersions() {
        listOf("", "latest", "1", "1.0.2-beta", "1.0.2+debug", "1.-2.3", "1.0.999999999999", "v1.0/evil", "1.0.0.0.0")
            .forEach { assertNull(it, ReleaseVersion.parse(it)) }
    }
}
