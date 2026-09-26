# trailmap

A personal Android app (Kotlin / Jetpack Compose) that shows nearby walking & biking trails —
**paved, gravel, dirt** — on a map, colored by surface, with a dedicated **mountain-bike mode**.
App-only: no backend. Trails come from pre-built **per-state trail packs** (rebuilt weekly from
OpenStreetMap by GitHub Actions) for the states you choose in the app; elsewhere it asks OpenStreetMap's
Overpass API directly.

<p align="center"><em>Built and tested around Kansas City.</em></p>

## Features

- **Map** (MapLibre GL Native, keyless) with trail polylines **colored by surface** (paved/gravel/dirt),
  a theme-aware basemap (OpenFreeMap liberty / dark), zoom-scaled line widths + casing, and a color **legend**.
- **Surface & activity filters** (paved/gravel/dirt · walk/bike/horse), a **min-length** filter, and **name search**.
- **Trail list + detail** — nearby trails sorted by distance, with an **elevation profile** along the trail
  in riding order (the trail's OSM pieces are chained end to end first).
- **MTB mode** — mountain-bike trails only (`route=mtb` / `mtb:scale`), a **10/25/40-mile** radius selector,
  IMBA-style **difficulty badges** (S0–S6), difficulty-colored map lines, and trails **clustered into systems**
  named after their enclosing OSM park (e.g. *Kessler Park*, *Swope Park*).
- **Your rides, from [myvitals](https://github.com/Pr0zak/myvitals)** (optional) — connect a self-hosted
  myvitals server and trailmap reads your recorded rides and walks with their GPS tracks, then:
  - marks the **trails you've ridden** (a blue glow on the map, "Ridden 12× · last Jun 16" in the list,
    coverage and every ride on the trail's page), with a **Not ridden yet** filter and a **Last ridden** sort;
  - estimates trail times at **your own pace** (bike, e-bike, mountain bike or walking);
  - draws **your tracks** as a map layer, and shows any recorded ride on the map;
  - lists your **recorded rides** in the Rides tab, with the trails each one used, and saves one as a planned ride;
  - shows **trail conditions** (open / closed, from the RainoutLine board myvitals polls) on trailheads,
    park headers and trail pages.

  **Send to trailmap** in the myvitals app fills in the address and access key for you to confirm.
  It syncs when the app opens (at most hourly) or on demand; nothing is written back to myvitals, and the
  server address, access key and tracks stay on the phone (left out of Android backups).

## Data sources (all free, keyless)

| Need | Source |
|------|--------|
| Trails + parks | **OpenStreetMap**: the trail pack (built from [Geofabrik](https://download.geofabrik.de/) extracts by `trailpack/build_pack.py`), else the [Overpass API](https://wiki.openstreetmap.org/wiki/Overpass_API) (with mirror fallback) |
| Elevation | **Open-Topo-Data** (`ned10m`), USGS EPQS spot-check |
| Basemap | [OpenFreeMap](https://openfreemap.org) vector styles: `liberty` (light) / `dark` (dark mode) |
| Your rides + trail conditions (optional) | your own [myvitals](https://github.com/Pr0zak/myvitals) server |

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
