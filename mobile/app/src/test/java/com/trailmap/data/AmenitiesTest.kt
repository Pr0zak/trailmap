package com.trailmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which OSM objects count as trail amenities, and what the app shows of them. */
class AmenitiesTest {
    @Test fun kindsAndExclusions() {
        assertEquals(AmenityKind.TRAILHEAD, Amenities.kindOf(mapOf("highway" to "trailhead")))
        assertEquals(AmenityKind.PARKING, Amenities.kindOf(mapOf("amenity" to "parking")))
        assertEquals(AmenityKind.REPAIR, Amenities.kindOf(mapOf("amenity" to "bicycle_repair_station")))
        assertNull("private lot", Amenities.kindOf(mapOf("amenity" to "parking", "access" to "private")))
        assertNull("customers only", Amenities.kindOf(mapOf("amenity" to "toilets", "access" to "customers")))
        assertNull("bus stop", Amenities.kindOf(mapOf("amenity" to "shelter", "shelter_type" to "public_transport")))
        assertEquals(AmenityKind.SHELTER, Amenities.kindOf(mapOf("amenity" to "shelter", "shelter_type" to "picnic_shelter")))
        assertNull(Amenities.kindOf(mapOf("amenity" to "bench")))
    }

    @Test fun onlyWebLinksAreKept() {
        assertEquals("https://kcparks.org", Amenities.websiteOf(mapOf("website" to " https://kcparks.org ")))
        assertEquals("http://a.example", Amenities.websiteOf(mapOf("website" to "kcparks.org", "contact:website" to "http://a.example")))
        assertNull(Amenities.websiteOf(mapOf("website" to "javascript:alert(1)")))
        assertNull(Amenities.websiteOf(mapOf("url" to "intent://x#Intent;end")))
    }

    @Test fun feeText() {
        fun fee(v: String?) = Amenity("node_1", AmenityKind.PARKING, GeoPoint(0.0, 0.0), fee = v).feeText
        assertEquals("Free", fee("no"))
        assertEquals("Fee", fee("yes"))
        assertEquals("Fee: $5", fee("$5"))
        assertNull(fee(null))
    }

    @Test fun nearATrailMeansWithinAShortWalk() {
        val path = listOf(GeoPoint(39.0, -94.5), GeoPoint(39.01, -94.5))
        val trail = Trail("t", "T", SurfaceType.PAVED, mapOf(SurfaceType.PAVED to 1.0), setOf(UseType.WALK),
            1100.0, 0.0, listOf(path), GeoPoint(39.005, -94.5))
        val at = Amenity("node_1", AmenityKind.TOILETS, GeoPoint(39.005, -94.5015)) // ~130 m off
        val far = Amenity("node_2", AmenityKind.WATER, GeoPoint(39.005, -94.51)) // ~860 m off
        val head = Amenity("node_3", AmenityKind.TRAILHEAD, GeoPoint(39.0, -94.5))
        assertEquals(listOf("node_3", "node_1"), Amenities.near(trail, listOf(at, far, head)).map { it.id })
    }
}
