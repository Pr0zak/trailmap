# trailmap

A personal Android app (Kotlin / Jetpack Compose) that shows nearby walking & biking trails —
**paved, gravel, dirt** — on a map, colored by surface, with a dedicated **mountain-bike mode**.
App-only: no backend. Trails come from pre-built **per-state trail packs** (rebuilt weekly from
OpenStreetMap by GitHub Actions) for the states you choose in the app; elsewhere it asks OpenStreetMap's
Overpass API directly.

<p align="center"><em>Built and tested around Kansas City.</em></p>

## Features

- **Map** (MapLibre GL Native, keyless) with trail polylines **colored by surface** (paved/gravel/dirt),
  a theme-aware basemap (light OSM / OpenFreeMap dark), zoom-scaled line widths + casing, and a color **legend**.
- **Surface & activity filters** (paved/gravel/dirt · walk/bike/horse), a **min-length** filter, and **name search**.
- **Trail list + detail** — nearby trails sorted by distance, with an **elevation profile** along the trail
  in riding order (the trail's OSM pieces are chained end to end first).
- **MTB mode** — mountain-bike trails only (`route=mtb` / `mtb:scale`), a **10/25/40-mile** radius selector,
  IMBA-style **difficulty badges** (S0–S6), difficulty-colored map lines, and trails **clustered into systems**
  named after their enclosing OSM park (e.g. *Kessler Park*, *Swope Park*).

## Data sources (all free, keyless)

| Need | Source |
|------|--------|
| Trails + parks | **OpenStreetMap**: the trail pack (built from [Geofabrik](https://download.geofabrik.de/) extracts by `trailpack/build_pack.py`), else the [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API) (with mirror fallback) |
| Elevation | **Open-Topo-Data** (`ned10m`), USGS EPQS spot-check |
| Basemap | OSM raster (light) / [OpenFreeMap](https://openfreemap.org) `dark` (dark mode) |

## Build

```bash
export ANDROID_HOME=~/Android/Sdk     # or your SDK path
cd mobile
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
```

## Releases

Push a `app-vX.Y.Z` tag and GitHub Actions builds + signs the APK and attaches it to a
[GitHub Release](../../releases). Signing keys come from repo secrets
(`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

## Notes

- Not a certified navigation aid — a personal trail-discovery tool.
- Trail names, surfaces, and difficulty come from community OSM data and vary in completeness.
- Trail packs (`trailpack` release, `.github/workflows/trailpack.yml`) hold OpenStreetMap data,
  © OpenStreetMap contributors, available under the [ODbL](https://www.openstreetmap.org/copyright).
  `trailpack/states.txt` lists the states built; remove a line to drop a state from the build.

## License

MIT
