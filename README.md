# trailmap

A personal Android app (Kotlin / Jetpack Compose) that shows nearby walking & biking trails —
**paved, gravel, dirt** — on a map, colored by surface, with a dedicated **mountain-bike mode**.
App-only: the phone talks directly to OpenStreetMap and free elevation APIs — no backend.

<p align="center"><em>Built and tested around Kansas City.</em></p>

## Features

- **Map** (MapLibre GL Native, keyless) with trail polylines **colored by surface** (paved/gravel/dirt),
  a theme-aware basemap (light OSM / CARTO dark), zoom-scaled line widths + casing, and a color **legend**.
- **Surface & activity filters** (paved/gravel/dirt · walk/bike), a **min-length** filter, and **name search**.
- **Trail list + detail** — nearby trails sorted by distance, with an **elevation profile** sampled along the trail.
- **MTB mode** — mountain-bike trails only (`route=mtb` / `mtb:scale`), a **10/25/40-mile** radius selector,
  IMBA-style **difficulty badges** (S0–S6), difficulty-colored map lines, and trails **clustered into systems**
  named after their enclosing OSM park (e.g. *Kessler Park*, *Swope Park*).

## Data sources (all free, keyless)

| Need | Source |
|------|--------|
| Trails + parks | **OpenStreetMap** via the [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API) (with mirror fallback) |
| Elevation | **Open-Topo-Data** (`ned10m`), USGS EPQS spot-check |
| Basemap | OSM raster (light) / CARTO dark (dark mode) |

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
- Heavy public-Overpass use is fine for personal scale; self-hosting is the path if it ever grows.

## License

MIT
