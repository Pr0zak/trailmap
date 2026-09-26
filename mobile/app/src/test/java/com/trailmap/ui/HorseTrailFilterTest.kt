package com.trailmap.ui

import com.trailmap.data.HorseTrailFilter
import com.trailmap.snap.Samples
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Horse trails filter shows, hides, or isolates trails built for horses. */
class HorseTrailFilterTest {
    private val horse = "Longview Equestrian Trail"

    private fun names(f: HorseTrailFilter) = Samples.ui.copy(horseTrails = f).filtered.map { it.name }

    @Test fun showHideOnly() {
        val all = names(HorseTrailFilter.SHOW)
        assertEquals(true, horse in all)
        assertEquals(all - horse, names(HorseTrailFilter.HIDE))
        assertEquals(listOf(horse), names(HorseTrailFilter.ONLY))
    }
}
