package com.trailmap.data

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Trail amenities: where to park, start, fill a bottle, fix a flat or get out of the rain.
 * From OSM, through the state packs (`amen/` tiles) or the Overpass amenity query — both keep
 * every trailhead and only the other kinds that sit close to a trail, since a city holds
 * thousands of car parks and bus-stop shelters and only the ones at a trail are of use here.
 */
enum class AmenityKind(val label: String, val glyph: String) {
    TRAILHEAD("Trailhead", "🥾"),
    PARKING("Parking", "🅿️"),
    TOILETS("Toilets", "🚻"),
    WATER("Drinking water", "🚰"),
    REPAIR("Bike repair stand", "🔧"),
    SHELTER("Shelter", "🛖"),
}

data class Amenity(
    /** `node_<id>` / `way_<id>`, unique across kinds. */
    val id: String,
    val kind: AmenityKind,
    val point: GeoPoint,
    val name: String? = null,
    /** OSM `fee`: "yes", "no", or a price as mapped. */
    val fee: String? = null,
    val hours: String? = null,
    val operator: String? = null,
    /** An http(s) page only — the address comes from map data anyone can edit. */
    val website: String? = null,
) {
    /** "Free", "Fee", or the mapped price; null when the map doesn't say. */
    val feeText: String? get() = when (fee?.trim()?.lowercase()) {
        null, "" -> null
        "no" -> "Free"
        "yes" -> "Fee"
        else -> "Fee: ${fee.trim()}"
    }
}

object Amenities {
    private val NO_ACCESS = setOf("private", "no", "customers", "permit", "delivery")

    /** Which amenity an OSM object is, with the same exclusions as the pack builder. */
    fun kindOf(tags: Map<String, String>): AmenityKind? {
        if (tags["highway"] == "trailhead") return AmenityKind.TRAILHEAD
        if (tags["access"] in NO_ACCESS) return null
        return when (tags["amenity"]) {
            "parking" -> AmenityKind.PARKING
            "toilets" -> AmenityKind.TOILETS
            "drinking_water" -> AmenityKind.WATER
            "bicycle_repair_station" -> AmenityKind.REPAIR
            "shelter" -> if (tags["shelter_type"] == "public_transport") null else AmenityKind.SHELTER
            else -> null
        }
    }

    fun of(type: String, id: Long, tags: Map<String, String>, point: GeoPoint): Amenity? {
        val kind = kindOf(tags) ?: return null
        return Amenity(
            id = "${type}_$id",
            kind = kind,
            point = point,
            name = tags["name"]?.trim()?.takeIf { it.isNotEmpty() },
            fee = tags["fee"],
            hours = tags["opening_hours"]?.trim()?.takeIf { it.isNotEmpty() },
            operator = tags["operator"]?.trim()?.takeIf { it.isNotEmpty() },
            website = websiteOf(tags),
        )
    }

    /** The first http(s) address among the website tags OSM uses. */
    fun websiteOf(tags: Map<String, String>): String? =
        listOf("website", "contact:website", "url").firstNotNullOfOrNull { k ->
            tags[k]?.trim()?.takeIf { isWebLink(it) }
        }

    /**
     * Amenities within [meters] of [trail]'s line, by kind. Measured to the segments, not the
     * vertices: a straight cycleway can run a kilometre between two nodes.
     */
    fun near(trail: Trail, amenities: List<Amenity>, meters: Double = 250.0): List<Amenity> {
        if (amenities.isEmpty() || trail.paths.isEmpty()) return emptyList()
        val pts = trail.allPoints
        if (pts.isEmpty()) return emptyList()
        val cosLat = cos(Math.toRadians(trail.center.lat)).coerceAtLeast(0.1)
        val pad = meters / M_PER_DEG
        val minLat = pts.minOf { it.lat } - pad; val maxLat = pts.maxOf { it.lat } + pad
        val minLon = pts.minOf { it.lon } - pad / cosLat; val maxLon = pts.maxOf { it.lon } + pad / cosLat
        return amenities.filter { a ->
            val p = a.point
            p.lat in minLat..maxLat && p.lon in minLon..maxLon &&
                trail.paths.any { path -> distanceToLine(p, path, cosLat) <= meters }
        }.sortedBy { it.kind.ordinal }
    }

    /** Flat-earth metres from [p] to the nearest point of [path] — fine over a trail's extent. */
    private fun distanceToLine(p: GeoPoint, path: List<GeoPoint>, cosLat: Double): Double {
        fun x(g: GeoPoint) = (g.lon - p.lon) * M_PER_DEG * cosLat
        fun y(g: GeoPoint) = (g.lat - p.lat) * M_PER_DEG
        if (path.size == 1) return hypot(x(path[0]), y(path[0]))
        var best = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val ax = x(path[i]); val ay = y(path[i])
            val bx = x(path[i + 1]); val by = y(path[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            // The point is the origin: project it onto the segment, clamped to its ends.
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    private const val M_PER_DEG = 111_320.0
}
