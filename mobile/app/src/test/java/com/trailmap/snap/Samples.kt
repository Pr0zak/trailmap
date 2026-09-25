package com.trailmap.snap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.trailmap.data.ElevPoint
import com.trailmap.data.ElevationProfile
import com.trailmap.data.GeoPoint
import com.trailmap.data.Ride
import com.trailmap.data.RideTrail
import com.trailmap.data.SurfaceType
import com.trailmap.data.Trail
import com.trailmap.data.UseType
import com.trailmap.data.Geo
import com.trailmap.ui.MapMode
import com.trailmap.ui.TrailsUiState
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sample Kansas City state for UI snapshots. Names are real KC trails; the geometry, lengths
 * and elevations are made up — these images review layout, not data.
 */
object Samples {
    private val KC = GeoPoint(39.0997, -94.5786)

    /** A wiggly polyline heading off from [start] at [bearingDeg] for [km]. */
    private fun wiggle(start: GeoPoint, bearingDeg: Double, km: Double, seed: Int): List<GeoPoint> {
        val steps = 40
        val b = Math.toRadians(bearingDeg)
        return (0..steps).map { i ->
            val t = i / steps.toDouble()
            val d = km * t
            val side = 0.35 * sin(t * 6.0 + seed) * (km / 6.0)
            val dx = d * sin(b) + side * cos(b)
            val dy = d * cos(b) - side * sin(b)
            GeoPoint(start.lat + dy / 111.0, start.lon + dx / (111.0 * cos(Math.toRadians(start.lat))))
        }
    }

    private fun trail(
        id: String, name: String, surface: SurfaceType, uses: Set<UseType>,
        dLat: Double, dLon: Double, bearing: Double, km: Double, seed: Int,
        mtb: Int? = null, park: String? = null,
        mix: Map<SurfaceType, Double> = mapOf(surface to 1.0),
    ): Trail {
        val start = GeoPoint(KC.lat + dLat, KC.lon + dLon)
        val path = wiggle(start, bearing, km, seed)
        val dist = Math.hypot(dLat * 111.0, dLon * 86.0) * 1000.0
        return Trail(
            id = id, name = name, surface = surface, surfaceMix = mix, uses = uses,
            lengthMeters = Geo.lengthMeters(path),
            distanceMeters = dist, paths = listOf(path), center = path[path.size / 2],
            mtbScale = mtb, parkName = park,
        )
    }

    private val both = setOf(UseType.WALK, UseType.BIKE)
    private val walk = setOf(UseType.WALK)

    val trails: List<Trail> = listOf(
        trail("name_trolley_track_trail", "Trolley Track Trail", SurfaceType.GRAVEL, both, -0.03, 0.005, 175.0, 9.0, 1,
            mix = mapOf(SurfaceType.GRAVEL to 0.8, SurfaceType.PAVED to 0.2)),
        trail("name_brush_creek_trail", "Brush Creek Trail", SurfaceType.PAVED, both, -0.035, -0.02, 95.0, 6.0, 2),
        trail("name_line_creek_trail", "Line Creek Trail", SurfaceType.PAVED, both, 0.09, -0.04, 20.0, 11.0, 3),
        trail("name_indian_creek_trail", "Indian Creek Trail", SurfaceType.PAVED, both, -0.14, -0.06, 80.0, 14.0, 4,
            mix = mapOf(SurfaceType.PAVED to 0.95, SurfaceType.GRAVEL to 0.05)),
        trail("name_blue_river_trail", "Blue River Parkway Trail", SurfaceType.DIRT, both, -0.12, 0.07, 160.0, 7.0, 5, mtb = 2, park = "Blue River Parkway"),
        trail("name_rocky_ridge", "Rocky Ridge", SurfaceType.DIRT, both, -0.07, 0.09, 40.0, 3.0, 6, mtb = 3, park = "Swope Park"),
        trail("name_swope_park_gnome_trail", "Gnome Trail", SurfaceType.DIRT, both, -0.075, 0.1, 120.0, 2.2, 7, mtb = 1, park = "Swope Park"),
        trail("name_kessler_park_trail", "Kessler Park Trail", SurfaceType.DIRT, walk, 0.02, 0.01, 60.0, 2.5, 8, mtb = 0, park = "Kessler Park"),
        trail("name_little_blue_trace", "Little Blue Trace", SurfaceType.GRAVEL, both, -0.02, 0.2, 5.0, 12.0, 9),
        trail("name_town_of_kansas_bridge", "Town of Kansas Bridge Walk", SurfaceType.PAVED, walk, 0.008, -0.004, 30.0, 0.6, 10),
    )

    val profile: ElevationProfile = run {
        val pts = (0..60).map { i ->
            val d = i * trails[0].lengthMeters / 60.0
            ElevPoint(d, 270.0 + 18 * sin(i / 7.0) + 9 * sin(i / 2.3) + i * 0.35)
        }
        ElevationProfile(pts, ascentMeters = 64.0, descentMeters = 43.0, minMeters = pts.minOf { it.elevationMeters }, maxMeters = pts.maxOf { it.elevationMeters })
    }

    private fun rt(t: Trail) = RideTrail(t.id, t.name, t.lengthMeters, t.surface.name, t.mtbScale)

    val rides = listOf(
        Ride("r1", "Sunday loop", listOf(rt(trails[0]), rt(trails[1]), rt(trails[9]))),
        Ride("r2", "Swope after work", listOf(rt(trails[5]), rt(trails[6]))),
        Ride("r3", "Long gravel day", listOf(rt(trails[8]), rt(trails[0]), rt(trails[3]))),
    )

    val ui = TrailsUiState(
        trails = trails,
        trailsVersion = 1,
        radiusMeters = (10 * 1609.344).toInt(),
        savedIds = setOf("name_trolley_track_trail", "name_rocky_ridge"),
        rides = rides,
        loadedCenter = KC,
        loadedRadiusMeters = 16000,
    )

    val uiMtb = ui.copy(
        mode = MapMode.MTB,
        radiusMeters = (25 * 1609.344).toInt(),
        trails = trails.filter { it.mtbScale != null },
        trailsVersion = 2,
    )

    val diagLines = listOf(
        "21:31:07.412  map      drew 142 trails, 612 KB in 88 ms",
        "21:31:07.301  load     done in 3412 ms, 142 trails in a 16000 m circle, 302 shown across 3 areas",
        "21:31:07.290  http     maps.mail.ru OK 1441 KB in 3180 ms",
        "21:31:04.102  http     overpass-api.de failed: IOException 504 Gateway Timeout",
        "21:31:03.880  load     start r=16000 force=false mode=ALL",
        "21:30:51.009  cache    all memory hit, covers 16000 m (asked 8046)",
        "21:30:50.700  load     start r=8046 force=false mode=ALL",
        "21:30:49.221  app      activity created (restored=false)",
    )
}

/**
 * Stand-in for the MapLibre view, which can't render off-device: a pale basemap with a few
 * grey roads and the sample trails drawn in their surface colors.
 */
@Composable
fun FauxMap(trails: List<Trail>, dark: Boolean = false, modifier: Modifier = Modifier.fillMaxSize()) {
    val bg = if (dark) Color(0xFF1D2124) else Color(0xFFF1EEE6)
    val road = if (dark) Color(0xFF3A4046) else Color(0xFFFFFFFF)
    val park = if (dark) Color(0xFF203326) else Color(0xFFD4E8C8)
    val water = if (dark) Color(0xFF16283A) else Color(0xFFAAD3DF)
    Canvas(modifier) {
        drawRect(bg)
        drawCircle(park, radius = size.width * 0.16f, center = Offset(size.width * 0.72f, size.height * 0.62f))
        drawCircle(park, radius = size.width * 0.09f, center = Offset(size.width * 0.3f, size.height * 0.35f))
        val river = Path().apply {
            moveTo(0f, size.height * 0.28f)
            cubicTo(size.width * 0.3f, size.height * 0.22f, size.width * 0.5f, size.height * 0.36f, size.width, size.height * 0.3f)
        }
        drawPath(river, water, style = Stroke(width = 28f))
        for (i in 1..7) {
            val y = size.height * i / 8f
            drawLine(road, Offset(0f, y), Offset(size.width, y + 40f), strokeWidth = 6f)
            val x = size.width * i / 8f
            drawLine(road, Offset(x, 0f), Offset(x - 30f, size.height), strokeWidth = 6f)
        }
        val lats = trails.flatMap { t -> t.paths.flatten().map { it.lat } }
        val lons = trails.flatMap { t -> t.paths.flatten().map { it.lon } }
        if (lats.isEmpty()) return@Canvas
        val (s, n) = lats.min() to lats.max()
        val (w, e) = lons.min() to lons.max()
        fun pt(p: GeoPoint) = Offset(
            (0.08f + 0.84f * ((p.lon - w) / (e - w)).toFloat()) * size.width,
            (0.12f + 0.76f * (1 - ((p.lat - s) / (n - s)).toFloat())) * size.height,
        )
        for (t in trails) for (path in t.paths) {
            val p = Path().apply {
                moveTo(pt(path[0]).x, pt(path[0]).y)
                path.drop(1).forEach { lineTo(pt(it).x, pt(it).y) }
            }
            drawPath(p, Color(0x55000000), style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(p, surfaceLine(t.surface, dark), style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private fun surfaceLine(s: SurfaceType, dark: Boolean) = when (s) {
    SurfaceType.PAVED -> Color(if (dark) 0xFF4CC57F else 0xFF2E7D4F)
    SurfaceType.GRAVEL -> Color(if (dark) 0xFFF2C744 else 0xFFDAA520)
    SurfaceType.DIRT -> Color(if (dark) 0xFFCC7A4D else 0xFFA0522D)
    SurfaceType.UNKNOWN -> Color(0xFF7A7A7A)
}
