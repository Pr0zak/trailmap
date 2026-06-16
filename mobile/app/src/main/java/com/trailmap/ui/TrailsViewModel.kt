package com.trailmap.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trailmap.data.ElevationClient
import com.trailmap.data.ElevationProfile
import com.trailmap.data.GeoPoint
import com.trailmap.data.Locator
import com.trailmap.data.OverpassClient
import com.trailmap.data.SurfaceType
import com.trailmap.data.Trail
import com.trailmap.data.TrailSystem
import com.trailmap.data.UseType
import com.trailmap.data.clusterTrailSystems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Map data mode: all trails (paved/gravel/dirt) vs mountain-bike trails only. */
enum class MapMode { ALL, MTB }

data class TrailsUiState(
    val center: GeoPoint = Locator.KANSAS_CITY,
    val radiusMeters: Int = 5000,
    val mode: MapMode = MapMode.ALL,
    val loading: Boolean = false,
    val error: String? = null,
    val trails: List<Trail> = emptyList(),
    val selectedSurfaces: Set<SurfaceType> =
        setOf(SurfaceType.PAVED, SurfaceType.GRAVEL, SurfaceType.DIRT, SurfaceType.UNKNOWN),
    val selectedUses: Set<UseType> = setOf(UseType.WALK, UseType.BIKE),
    val minLengthMiles: Double = 0.0,
    val query: String = "",
) {
    val radiusMiles: Double get() = radiusMeters / 1609.344

    /** Named/labeled trails passing the active filters + name search, nearest first. */
    val filtered: List<Trail>
        get() = trails
            // Only show trails that carry an OSM name — drop the many unnamed connector paths.
            .filter { it.name != "Unnamed path" }
            .filter { it.surface in selectedSurfaces }
            .filter { selectedUses.isEmpty() || it.uses.any { u -> u in selectedUses } }
            .filter { it.lengthMiles >= minLengthMiles }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedBy { it.distanceMeters }

    /** In MTB mode, [filtered] grouped into nearby trail systems; empty otherwise. */
    val systems: List<TrailSystem>
        get() = if (mode == MapMode.MTB) clusterTrailSystems(filtered) else emptyList()
}

class TrailsViewModel(app: Application) : AndroidViewModel(app) {
    private val overpass = OverpassClient()
    private val elevation = ElevationClient()
    private val locator = Locator(app)

    private val _state = MutableStateFlow(TrailsUiState())
    val state: StateFlow<TrailsUiState> = _state.asStateFlow()

    // Elevation profiles cached per trail id (lazy-loaded when a detail screen opens).
    private val _profiles = MutableStateFlow<Map<String, ElevationProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, ElevationProfile>> = _profiles.asStateFlow()

    fun trailById(id: String): Trail? = _state.value.trails.firstOrNull { it.id == id }

    /** Center on the device location (KC fallback) and load trails there. */
    fun locateAndLoad() {
        viewModelScope.launch {
            val here = locator.current()
            _state.update { it.copy(center = here) }
            load(here, _state.value.radiusMeters)
        }
    }

    fun load(center: GeoPoint = _state.value.center, radiusMeters: Int = _state.value.radiusMeters) {
        val mtb = _state.value.mode == MapMode.MTB
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, center = center, radiusMeters = radiusMeters) }
            try {
                val trails = overpass.fetchTrails(center, radiusMeters, mtb = mtb)
                _state.update { it.copy(loading = false, trails = trails) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load trails") }
            }
        }
    }

    fun setRadius(meters: Int) {
        _state.update { it.copy(radiusMeters = meters) }
        load(_state.value.center, meters)
    }

    /** Radius selector in miles (used by MTB mode: 10 / 25 / 40 mi). */
    fun setRadiusMiles(miles: Int) = setRadius((miles * 1609.344).toInt())

    /** Switch ALL ↔ MTB. MTB defaults to a wide 25-mi radius; ALL returns to the local 5 km. */
    fun setMode(mode: MapMode) {
        if (_state.value.mode == mode) return
        val radius = if (mode == MapMode.MTB) (25 * 1609.344).toInt() else 5000
        _state.update { it.copy(mode = mode, radiusMeters = radius) }
        load(_state.value.center, radius)
    }

    /** Minimum-length filter in miles (0 = any). */
    fun setMinLength(miles: Double) = _state.update { it.copy(minLengthMiles = miles) }

    /** Free-text name search (filters the list + map by trail name substring). */
    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun toggleSurface(s: SurfaceType) = _state.update {
        val next = it.selectedSurfaces.toMutableSet().apply { if (!add(s)) remove(s) }
        it.copy(selectedSurfaces = next)
    }

    fun toggleUse(u: UseType) = _state.update {
        val next = it.selectedUses.toMutableSet().apply { if (!add(u)) remove(u) }
        it.copy(selectedUses = next)
    }

    /** Lazy-load the elevation profile for a trail when its detail screen opens. */
    fun ensureProfile(trailId: String) {
        if (_profiles.value.containsKey(trailId)) return
        val trail = trailById(trailId) ?: return
        viewModelScope.launch {
            try {
                val profile = elevation.profile(trail.allPoints)
                _profiles.update { it + (trailId to profile) }
            } catch (e: Exception) {
                _profiles.update { it + (trailId to ElevationProfile.EMPTY) }
            }
        }
    }
}
