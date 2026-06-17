# CLAUDE.md — trailmap

Personal Android app (Pixel 9a) that shows nearby walking/biking trails — paved, gravel, dirt —
on a map, colored by surface. Test location: **Kansas City** (39.0997, -94.5786).
Single-user, personal. App-only (no backend): the phone talks to OpenStreetMap + elevation APIs directly.

## Self-update (in-app)
`update/UpdateChecker.kt` polls the **GitHub Releases API** (`/repos/Pr0zak/trailmap/releases/latest`) on launch, compares the `app-v*` tag to `BuildConfig.VERSION_NAME`, and if newer shows a dialog (in `MainActivity.UpdateGate`) → downloads the APK to externalCache → installs via FileProvider + `ACTION_VIEW` (needs `REQUEST_INSTALL_PACKAGES` + the `${applicationId}.fileprovider` provider). `versionName` defaults to the repo `VERSION` file locally (CI overrides with `-Pversion.name` from the tag). **Testing caveat:** a debug-signed build can't install a release-signed downloaded APK (signature mismatch) — the installer launches but the final install fails; production is release→release (same keystore) so it works. To test the *prompt+download*, build debug with `-Pversion.name=0.1.0` so it sees the latest release as newer.

## Repo & releases
- GitHub: **`Pr0zak/trailmap`** (public). `main` branch. Commit identity is the non-personal `Pr0zak <pr0zak@users.noreply.github.com>`.
- CI (`.github/workflows/ci.yml`): builds the debug APK on every push/PR.
- Release (`.github/workflows/release.yml`): push a **`app-vX.Y.Z`** tag → builds + signs the APK → attaches it to a GitHub Release. Bump `VERSION` to match.
- **Signing key** lives at `~/.trailmap-signing/` (`release.jks` + `signing.env` with the password; **not** in the repo). The same values are GitHub repo secrets `KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS(=trailmap) / KEY_PASSWORD`. Don't lose the keystore — it's required to ship app updates.
- Before any push: run `bash ~/.claude/skills/github-publish-check/scan.sh /home/spider/trailmap` (public repo — keep the OSM User-Agent + everything else free of personal data).

## Stack
- **Kotlin / Jetbrains Compose**, Material 3, **MapLibre GL Native 11.5.2** (keyless raster basemap).
- OkHttp + kotlinx-serialization (no Retrofit). Location = fused (`play-services-location`) **with a `LocationManager` fallback** (so device/emulator GPS works even without Play Services), then a Kansas City fallback.
- Kotlin 2.0.20, AGP 8.5.2, compileSdk 35, minSdk 26. App module under `mobile/`.

## Modes & filters (TrailsViewModel.TrailsUiState)
- **MapMode.ALL** — paved/gravel/dirt **off-road trails/paths only** (highway path/cycleway/track/bridleway + non-sidewalk footway), default ~5 mi radius, selectable **3 / 5 / 10 mi**. Deliberately **excludes streets + sidewalks**: `route=bicycle` relations were tried but dropped because most are on-street bike routes (drew lines over roads). Off-road bike *paths* are still captured as standalone `cycleway` ways.
- **Trail selection:** tapping the map selects the nearest trail (a padded box query, so taps *near* a thin line register) → shows a peek card + a yellow highlight glow layer. Nothing is auto-selected at startup. The list / system headers drive the map separately. The peek card has a Details button + a close (✕); the right-edge FAB stack lifts above it when a trail is selected (no overlap), and the peek stays clear of the MapLibre attribution.
- **MapMode.MTB** — mountain-bike trails only, default 25 mi radius, selectable **10 / 25 / 40 mi**. Shows OSM `mtb:scale` difficulty badges (S0 Beginner → S6 Extreme, `MtbDifficulty`). The Trails list **clusters** trails into systems/parks (`clusterTrailSystems`, union-find by ~2.5 km centroid proximity) under headers, **named after the enclosing OSM park** (a second `leisure=park|nature_reserve|…` / `boundary=protected_area` query + point-in-polygon on each trail's center → e.g. "Kessler Park", "Swope Park"); falls back to the member-name common prefix, else the longest member, when no park contains them. Map lines are colored by difficulty (rated) or surface (unrated). A **legend** card keys the colors (difficulty in MTB, surface in ALL).
- **Filters** (apply to map + list, via `.filtered`): surface chips (paved/gravel/dirt), use chips (walk/bike), **min-length** (Any/1+/3+/5+/10+ mi), **name search** (substring), and a permanent **named-only** filter (drops "Unnamed path" connectors).
- **Dark mode**: the basemap follows the system theme — light = OSM raster, dark = CARTO dark; surface line colors brighten on dark and every line gets a casing so it pops on either basemap. A **map theme toggle** FAB (BrightnessAuto → Light → Dark, persisted in `Prefs`) overrides the system theme.
- **Saved trails** (star on list rows + detail; persisted in `Prefs`; a "saved only" toggle in the Trails app bar), **Share / Open-in-Maps** on the detail screen (geo: + maps URL intents), and **tappable MTB system headers** (recenter the map on that park via `vm.focusOn` + tab switch).
- **Offline** (`offline/OfflinePacks.kt`): the cloud-download FAB caches the visible map area's tiles via MapLibre `OfflineManager` (z≈11–15). NOTE: the offline downloader can't read an `asset://` style, so it points at the SAME style JSON **hosted on the public repo** (`raw.githubusercontent.com/Pr0zak/trailmap/main/.../*.json`) — keep those asset files on `main`. Trail/park *data* offline comes free from the disk cache (see below).

## Data sources (all free, keyless)
- **Trails: OSM Overpass API** (`https://overpass-api.de/api/interpreter`, POST, **must set a User-Agent**).
  ALL-mode query = `highway` in path/cycleway/track/bridleway + footway **excluding** `footway=sidewalk|crossing|traffic_island|access_aisle` (critical — without the sidewalk exclusion a 6 km KC pull is ~25k ways, ~96% sidewalks; with it, ~750). `out geom;` inlines coordinates.
- **MTB-mode query** = `way[mtb:scale]` + `way[highway=path][bicycle=designated][surface~ground|dirt|…]` + `relation[route=mtb]`, all `out geom;`. Relations carry the named loops (Wudchuk Run, the Augie phases); the parser builds one trail per relation from its member-way geometry and dedupes member ways out of the standalone-way list. A second **park-polygon query** runs (sequentially — concurrent requests trip the per-IP 429) for system naming.
- **On-device cache:** `OverpassClient(cacheDir)` caches raw Overpass responses under `<cacheDir>/overpass/<CACHE_SCHEMA>_<kind>_<lat>_<lon>_<radius>.json` (bump `CACHE_SCHEMA` when queries change, to invalidate stale caches) (kind ∈ all/mtb/parks; center rounded to 3 dp; **7-day TTL**). Repeat loads of an area are instant + offline; on network failure (429/offline) it falls back to a **stale** cached copy. The my-location **FAB force-refreshes** (bypasses cache); mode/radius/initial loads are cache-first. This is the "don't self-host, just cache" answer to the public-API latency + rate limits.
- **Overpass resilience:** `OverpassClient.post()` tries mirrors in order — `overpass-api.de` → `maps.mail.ru` → `overpass.private.coffee` (the last is often down/504) — and the OkHttp `readTimeout` is **130 s** to match the queries' `[timeout:120]` (the 40-mi MTB + park responses are ~3 MB each). Heavy public-API use is the reason the README/follow-ups suggest self-hosting Overpass eventually.
- **Surface buckets:** raw OSM `surface=*` → PAVED / GRAVEL / DIRT / UNKNOWN (`SurfaceType.fromOsmSurface`), with a highway-type fallback when untagged (`inferFromHighway`: cycleway→paved, track→gravel, path/bridleway→dirt). ~70% of cycleways carry a real surface; paths are spottier.
- **Elevation: Open-Topo-Data `ned10m`** (`https://api.opentopodata.org/v1/ned10m?locations=lat,lon|...`), max 100 pts/req, ~1 req/sec — the client chunks + throttles. USGS EPQS (`https://epqs.nationalmap.gov/v1/json`) is the authoritative US spot-check fallback (~897 ft at KC center).

## Layout (`mobile/app/src/main/java/com/trailmap/`)
```
data/
  Models.kt         GeoPoint, SurfaceType, UseType, MtbDifficulty, Trail(+mtbScale), ElevationProfile  (domain contract)
  Geo.kt            haversine, polyline length, resample  (shared by data + elevation)
  OverpassClient.kt suspend fetchTrails(center, radiusMeters, mtb=false): List<Trail>  (ALL + MTB queries; groups by name, buckets surface, drops <80m unnamed)
  ElevationClient.kt suspend profile(points): ElevationProfile
  Locator.kt        fused → LocationManager → Kansas City fallback
ui/
  TrailsViewModel.kt  state (center/radius/mode/filters/query) + .filtered + setMode/setRadiusMiles/setMinLength/setQuery + ensureProfile()
  MapScreen.kt        MapLibre map; theme-aware basemap (light OSM / CARTO dark), casing + colored line layers, zoom-scaled width, filter overlay, nearest-trail peek, my-location FAB
  TrailListScreen.kt  search box + filter chips + list sorted by distance; SurfaceBadge / MtbBadge / UseIcons live here (reused)
  TrailDetailScreen.kt name, surface + MTB badge, stat cards, ElevationChart
  FilterChips.kt      mode toggle + radius + length + surface + use chips
  ElevationChart.kt / theme/  "Trailhead" palette. Surface colors: green=paved, GOLD(#DAA520)=gravel, SIENNA(#A0522D)=dirt (gold vs sienna chosen to be clearly distinct; gravel/brown were too similar)
MainActivity.kt       bottom-nav (Map / Trails) + detail route. NOTE: trail ids use `_` separators (name_<slug>, way_<id>, rel_<id>) — NOT `/`, which breaks the `detail/{id}` nav route.
assets/osm_raster_style.json   keyless OSM raster (light)   |   assets/carto_dark_style.json  CARTO dark raster (dark mode)
design/mockups/       Stitch "Trailhead" mockups (map/filters/list/detail .png + .html)
```

## Build / run (emulator)
```bash
export ANDROID_HOME=/home/spider/tools/android-sdk
cd mobile && ./gradlew :app:assembleDebug          # ~1-2 min
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb emu geo fix -94.5786 39.0997   # pin GPS to KC (lon lat)
$ANDROID_HOME/platform-tools/adb shell am start -n com.trailmap/.MainActivity
```
AVD: `trailmap_pixel9a` (android-35 x86_64, pixel_7 profile — SDK has no pixel_9 device def).
The `default` (non-Google) system image has **no Play Services**, so fused location throws → `Locator` falls through to `LocationManager` (`adb emu geo fix LON LAT`), then to Kansas City. **Gotcha:** another app on this AVD (`com.starmap.app.debug`) grabs foreground on location updates and can leave a sticky mock GPS fix that `emu geo fix` won't override. Fix = **cold boot** the emulator (`adb emu kill` → relaunch with `-no-snapshot`), which clears the wedged location state and doesn't auto-start starmap; then `emu geo fix` to KC works (or `Locator` falls back to KC if no fix). Note a cold boot can revert uninstalled-but-uncommitted app state — **reinstall the APK after cold-booting**. (All a non-issue on a real Pixel 9a.)

## Known follow-ups / ideas
- No caching (direct Overpass) — fine for personal use; add a backend + PostGIS later if rate limits bite (mirror the lakemap CT pattern).
- Map tap uses queryRenderedFeatures + a nearest-trail peek-card fallback.
- MTB lines on the map are still surface-colored (dirt brown); could color them by `mtbScale` difficulty instead.
- Could cluster MTB results into trail *systems/parks* (the standalone Overpass research script clusters by 2.5 km proximity); the app currently lists individual named trails/loops.
