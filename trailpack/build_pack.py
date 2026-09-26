#!/usr/bin/env python3
"""
Build trailmap's trail packs from Geofabrik OSM extracts — one pack per US state.

The app used to ask a public Overpass server for trails on every pan, and those servers are
routinely slow (10-45 s) or refusing work outright (HTTP 504). The packs move that work here:
GitHub Actions builds one per state every week, and the phone downloads the states the user
picks and answers every load inside them from disk, in milliseconds.

A pack is a zip of 0.25-degree tiles. Each entry holds exactly what the app's Overpass query for
that kind would have returned for the tile, in Overpass's own `out geom` JSON, so the app parses
it with the code it already has:

    meta.json              schema, tile size, build time, OSM data timestamp, covered tiles,
                           and the state's outline (to work out coverage across several states)
    all/<x>_<y>.json       ALL mode: named paths/cycleways/tracks/bridleways + non-sidewalk footways
    mtb/<x>_<y>.json       MTB mode: named mtb:scale ways, designated dirt bike paths, route=mtb
    parks/<x>_<y>.json     named parks/reserves/protected areas, for naming MTB trail systems
    <kind>/wide.json       elements wider than a tile (long-distance routes, national forests)

x = floor(lon / 0.25), y = floor(lat / 0.25). Each element is stored once (schema 2): in the tile
holding its centre if it is no wider than a tile, otherwise in <kind>/wide.json. The app reads the
tiles around a circle plus one ring, and the wide file, and de-duplicates by (type, id) across
states, since Geofabrik's extracts overlap at the borders.

The filters below must stay in step with buildQuery / buildMtbQuery / buildParkQuery in
mobile/app/src/main/java/com/trailmap/data/OverpassClient.kt. Bump PACK_SCHEMA (here and in the
app's TrailPack.SCHEMA) whenever the layout or the filters change.

Which states are built is trailpack/states.txt: one Geofabrik slug per line, `#` comments a state
out. A state taken out of that list is dropped from the index and its pack deleted on the next
publish, so the app stops offering it.

Usage:
    pip install osmium shapely          # + apt install osmium-tool for a faster pre-filter
    python build_pack.py build --regions kansas --out trailpack-kansas.zip --index-entry kansas.json
    python build_pack.py states [--only "kansas missouri"]     # JSON list, for the CI matrix
    python build_pack.py index --entries DIR [--old OLD.json] --out trailpack-index.json
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from collections import defaultdict

# osmium and shapely are imported where they are used: the `states` and `index` subcommands run
# in CI jobs that don't install them.

PACK_SCHEMA = 2
TILE_DEG = 0.25
GEOFABRIK = "https://download.geofabrik.de/north-america/us"
HERE = os.path.dirname(os.path.abspath(__file__))
STATES_FILE = os.path.join(HERE, "states.txt")

# Display names where title-casing the slug gets it wrong.
NAMES = {
    "district-of-columbia": "District of Columbia",
    "us-virgin-islands": "U.S. Virgin Islands",
}


def state_name(slug: str) -> str:
    return NAMES.get(slug) or " ".join(w.capitalize() for w in slug.split("-"))


def read_states(path: str = STATES_FILE) -> list[str]:
    with open(path) as f:
        return [l.split("#", 1)[0].strip() for l in f if l.split("#", 1)[0].strip()]

# --- Filters, mirroring the app's Overpass queries --------------------------------------------

ALL_HIGHWAYS = {"path", "cycleway", "track", "bridleway"}
SIDEWALKISH = re.compile(r"sidewalk|crossing|traffic_island|access_aisle")
MTB_SURFACE = re.compile(r"ground|dirt|earth|fine_gravel|gravel|compacted")
PARK_LEISURE = {"park", "nature_reserve", "recreation_ground"}
PARK_BOUNDARY = {"protected_area", "national_park"}

# Only the tags the app reads. Everything else is dead weight on the phone.
KEEP_TAGS = {
    "name", "highway", "surface", "tracktype", "footway", "foot", "bicycle", "horse",
    "mtb:scale", "route", "leisure", "boundary", "access",
}


def is_all_way(t) -> bool:
    if "name" not in t:
        return False
    hw = t.get("highway")
    if hw in ALL_HIGHWAYS:
        return True
    return hw == "footway" and not SIDEWALKISH.search(t.get("footway", ""))


def is_mtb_way(t) -> bool:
    if "name" not in t:
        return False
    if "mtb:scale" in t:
        return True
    return (
        t.get("highway") == "path"
        and t.get("bicycle") == "designated"
        and bool(MTB_SURFACE.search(t.get("surface", "")))
    )


def is_park(t) -> bool:
    return "name" in t and (t.get("leisure") in PARK_LEISURE or t.get("boundary") in PARK_BOUNDARY)


# --- Geometry helpers ------------------------------------------------------------------------

def tile_of(lat: float, lon: float) -> tuple[int, int]:
    return math.floor(lon / TILE_DEG), math.floor(lat / TILE_DEG)


def simplify(pts: list[tuple[float, float]], tol_deg: float) -> list[tuple[float, float]]:
    """Douglas-Peucker. Park rings only name trail systems, so metres of detail buy nothing."""
    if len(pts) < 3 or tol_deg <= 0:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        (ay, ax), (by, bx) = pts[a], pts[b]
        dx, dy = bx - ax, by - ay
        norm = math.hypot(dx, dy)
        best, idx = 0.0, -1
        for i in range(a + 1, b):
            py, px = pts[i]
            d = (abs(dy * (px - ax) - dx * (py - ay)) / norm) if norm else math.hypot(px - ax, py - ay)
            if d > best:
                best, idx = d, i
        if best > tol_deg and idx > 0:
            keep[idx] = True
            stack += [(a, idx), (idx, b)]
    return [p for p, k in zip(pts, keep) if k]


def geom_json(pts, digits: int):
    return [{"lat": round(lat, digits), "lon": round(lon, digits)} for lat, lon in pts]


# --- OSM passes ------------------------------------------------------------------------------

def handlers():
    """The two OSM passes, defined on first use so osmium is only needed for `build`."""
    import osmium

    class RelationPass(osmium.SimpleHandler):
        """Pass 1: relations we keep, and the member ways they need geometry for."""

        def __init__(self):
            super().__init__()
            self.mtb = {}     # id -> (tags, [(ref, role)])
            self.parks = {}
            self.needed = set()

        def relation(self, r):
            t = dict(r.tags)
            if t.get("route") == "mtb":
                target = self.mtb
            elif is_park(t):
                target = self.parks
            else:
                return
            members = [(m.ref, m.role) for m in r.members if m.type == "w"]
            if not members:
                return
            target[r.id] = (t, members)
            self.needed.update(ref for ref, _ in members)


    class WayPass(osmium.SimpleHandler):
        """Pass 2 (with node locations): matching ways, plus every relation member way."""

        def __init__(self, needed):
            super().__init__()
            self.needed = needed
            self.all = {}
            self.mtb = {}
            self.parks = {}
            self.geom = {}  # way id -> [(lat, lon)] for relation members

        def way(self, w):
            t = dict(w.tags)
            flags = (is_all_way(t), is_mtb_way(t), is_park(t), w.id in self.needed)
            if not any(flags):
                return
            try:
                pts = [(n.lat, n.lon) for n in w.nodes]
            except osmium.InvalidLocationError:
                pts = [(n.lat, n.lon) for n in w.nodes if n.location.valid()]
            if len(pts) < 2:
                return
            if flags[0]:
                self.all[w.id] = (t, pts)
            if flags[1]:
                self.mtb[w.id] = (t, pts)
            if flags[2]:
                self.parks[w.id] = (t, pts)
            if flags[3]:
                self.geom[w.id] = pts

    return RelationPass, WayPass


def read_poly(path: str):
    """Geofabrik .poly -> shapely geometry (outer rings minus '!' holes)."""
    from shapely.geometry import Polygon
    from shapely.ops import unary_union

    outers, holes = [], []
    with open(path) as f:
        lines = [l.strip() for l in f]
    i = 1
    while i < len(lines):
        name = lines[i]
        if name == "END" or not name:
            i += 1
            continue
        ring = []
        i += 1
        while lines[i] != "END":
            lon, lat = map(float, lines[i].split()[:2])
            ring.append((lon, lat))
            i += 1
        i += 1
        (holes if name.startswith("!") else outers).append(Polygon(ring))
    shape = unary_union(outers)
    if holes:
        shape = shape.difference(unary_union(holes))
    return shape


def download(url: str, dest: str):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return
    print(f"downloading {url}", flush=True)
    tmp = dest + ".part"
    urllib.request.urlretrieve(url, tmp)
    os.replace(tmp, dest)


def osm_timestamp(pbf: str) -> str | None:
    import osmium

    try:
        return osmium.io.Reader(pbf, osmium.osm.osm_entity_bits.NOTHING).header().get(
            "osmosis_replication_timestamp"
        )
    except Exception:
        return None


# --- Build -----------------------------------------------------------------------------------

def prefilter(pbf: str) -> str:
    """
    Cut the extract down to the objects the passes below can use, with osmium-tool when it is
    installed (CI). pyosmium alone works too; it just walks every way of a big state in Python.
    Referenced nodes and relation members are kept, so no geometry is lost.
    """
    if not shutil.which("osmium"):
        return pbf
    out = pbf.replace(".osm.pbf", ".trails.osm.pbf")
    subprocess.run(
        [
            "osmium", "tags-filter", "--overwrite", "-o", out, pbf,
            "w/highway=path,cycleway,track,bridleway,footway", "w/mtb:scale", "r/route=mtb",
            "wr/leisure=park,nature_reserve,recreation_ground", "wr/boundary=protected_area,national_park",
        ],
        check=True,
    )
    return out


def outline(shape, pad_deg: float, tol_deg: float):
    """
    The region's outline as rings of [lon, lat], one per polygon exterior.

    Padded before simplifying so the result still contains the whole region: the app decides
    from these whether its installed states together cover a tile, and Geofabrik's outlines of
    neighbouring states overlap by only ~100 m (Kansas and Missouri at 39.1 N), so simplifying
    alone would open slivers along the border that belong to neither.
    """
    g = shape.buffer(pad_deg) if pad_deg else shape
    g = g.simplify(tol_deg, preserve_topology=True)
    polys = list(g.geoms) if hasattr(g, "geoms") else [g]
    return [[[round(x, 4), round(y, 4)] for x, y in p.exterior.coords] for p in polys]


def build(regions: list[str], workdir: str, out: str, index_entry: str | None = None):
    from shapely.geometry import box
    from shapely.ops import unary_union
    from shapely.prepared import prep

    RelationPass, WayPass = handlers()
    os.makedirs(workdir, exist_ok=True)
    tiles: dict[str, dict[tuple[int, int], dict[tuple[str, int], dict]]] = {
        "all": defaultdict(dict), "mtb": defaultdict(dict), "parks": defaultdict(dict),
    }
    shapes = []
    stamps = []

    wide: dict[str, dict[tuple[str, int], dict]] = {"all": {}, "mtb": {}, "parks": {}}

    def place(kind: str, el: dict, vertices):
        # Stored once. An element no wider than a tile goes in the tile holding its centre, so
        # any part of it lies within one tile of there; the app reads a ring of tiles around
        # each circle. Anything wider goes in <kind>/wide.json, which the app always reads.
        # Writing elements into every tile they touched copied the Arizona Trail relation into
        # 39 tiles (123 MB of Arizona's 132 MB of MTB data) and each national forest into 25-45.
        lats = [v[0] for v in vertices]
        lons = [v[1] for v in vertices]
        key = (el["type"], el["id"])
        if max(max(lats) - min(lats), max(lons) - min(lons)) > TILE_DEG:
            wide[kind][key] = el
        else:
            tiles[kind][tile_of((min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2)][key] = el

    for region in regions:
        pbf = os.path.join(workdir, f"{region}.osm.pbf")
        poly = os.path.join(workdir, f"{region}.poly")
        download(f"{GEOFABRIK}/{region}-latest.osm.pbf", pbf)
        download(f"{GEOFABRIK}/{region}.poly", poly)
        shapes.append(read_poly(poly))
        stamps.append(osm_timestamp(pbf))
        src = prefilter(pbf)

        rels = RelationPass()
        rels.apply_file(src)
        ways = WayPass(rels.needed)
        ways.apply_file(src, locations=True, idx="flex_mem")
        print(
            f"{region}: {len(ways.all)} all-ways, {len(ways.mtb)} mtb-ways, {len(rels.mtb)} mtb-rels, "
            f"{len(ways.parks)} park-ways, {len(rels.parks)} park-rels",
            flush=True,
        )

        def tags(t):
            return {k: v for k, v in t.items() if k in KEEP_TAGS}

        # Trail geometry at 7 decimals, as Overpass sends it: the app finds junctions by exact
        # coordinate match, so rounding must not merge or split shared nodes.
        for wid, (t, pts) in ways.all.items():
            place("all", {"type": "way", "id": wid, "tags": tags(t), "geometry": geom_json(pts, 7)}, pts)
        for wid, (t, pts) in ways.mtb.items():
            place("mtb", {"type": "way", "id": wid, "tags": tags(t), "geometry": geom_json(pts, 7)}, pts)
        for rid, (t, members) in rels.mtb.items():
            mem, verts = [], []
            for ref, role in members:
                pts = ways.geom.get(ref)
                if pts:
                    mem.append({"type": "way", "ref": ref, "role": role, "geometry": geom_json(pts, 7)})
                    verts += pts
            if mem:
                place("mtb", {"type": "relation", "id": rid, "tags": tags(t), "members": mem}, verts)

        # Parks only answer "which park is this trail in?" — ~10 m of simplification and
        # 5 decimals (~1 m) cost nothing there and keep big forests from bloating every tile.
        tol = 10 / 111_320
        for wid, (t, pts) in ways.parks.items():
            s = simplify(pts, tol)
            if len(s) >= 3:
                place("parks", {"type": "way", "id": wid, "tags": tags(t), "geometry": geom_json(s, 5)}, s)
        for rid, (t, members) in rels.parks.items():
            mem, verts = [], []
            for ref, role in members:
                pts = ways.geom.get(ref)
                if pts:
                    s = simplify(pts, tol)
                    mem.append({"type": "way", "ref": ref, "role": role, "geometry": geom_json(s, 5)})
                    verts += s
            if mem:
                place("parks", {"type": "relation", "id": rid, "tags": tags(t), "members": mem}, verts)

    # A tile is "covered" when the regions contain it outright, so every trail near it is in the
    # pack. Tiles straddling a region's edge still get whatever data fell in them, but the app
    # asks Overpass for a circle centred there rather than showing half the trails.
    union = unary_union(shapes)
    region = prep(union)
    lo_x, lo_y, hi_x, hi_y = union.bounds
    covered = []
    for x in range(math.floor(lo_x / TILE_DEG), math.floor(hi_x / TILE_DEG) + 1):
        for y in range(math.floor(lo_y / TILE_DEG), math.floor(hi_y / TILE_DEG) + 1):
            if region.contains(box(x * TILE_DEG, y * TILE_DEG, (x + 1) * TILE_DEG, (y + 1) * TILE_DEG)):
                covered.append(f"{x}_{y}")

    stamp = min((s for s in stamps if s), default=None)
    meta = {
        "schema": PACK_SCHEMA,
        "tileDeg": TILE_DEG,
        "regions": regions,
        "built": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "osmTimestamp": stamp,
        "covered": sorted(covered),
        # ~500 m of padding, ~200 m of simplification: see outline().
        "outline": outline(union, 0.005, 0.002),
    }
    tmp = out + ".part"
    raw_total = 0
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr("meta.json", json.dumps(meta, separators=(",", ":")))
        for kind, by_tile in tiles.items():
            for (x, y), els in sorted(by_tile.items()):
                body = json.dumps({"elements": list(els.values())}, separators=(",", ":"), ensure_ascii=False)
                raw_total += len(body)
                z.writestr(f"{kind}/{x}_{y}.json", body)
        for kind, els in wide.items():
            if els:
                body = json.dumps({"elements": list(els.values())}, separators=(",", ":"), ensure_ascii=False)
                raw_total += len(body)
                z.writestr(f"{kind}/wide.json", body)
    os.replace(tmp, out)
    sizes = {k: f"{len(v)}+{len(wide[k])} wide" for k, v in tiles.items()}
    print(
        f"wrote {out}: {os.path.getsize(out) / 1e6:.1f} MB zipped, {raw_total / 1e6:.1f} MB raw, "
        f"tiles {sizes}, {len(covered)} covered, OSM data as of {stamp}",
        flush=True,
    )
    if index_entry:
        entry = {
            "slug": "+".join(regions),
            "name": " & ".join(state_name(r) for r in regions),
            "asset": os.path.basename(out),
            "bytes": os.path.getsize(out),
            "built": meta["built"],
            "osmTimestamp": stamp,
            "bbox": [round(v, 4) for v in union.bounds],
            # Coarse (~1 km): only answers "which state is the map looking at?".
            "outline": outline(union, 0, 0.01),
        }
        with open(index_entry, "w") as f:
            json.dump(entry, f, separators=(",", ":"))


def merge_index(entries_dir: str, old: str | None, out: str) -> list[str]:
    """
    Write the index the app lists states from: this run's entries over the previous index,
    limited to states.txt. A state whose build failed this week keeps last week's entry (its
    pack is still published); a state taken out of states.txt is dropped. Returns the dropped
    slugs, so the workflow can delete their packs.
    """
    states = read_states()
    by_slug = {}
    if old and os.path.exists(old):
        with open(old) as f:
            for e in json.load(f).get("states", []):
                by_slug[e["slug"]] = e
    if os.path.isdir(entries_dir):
        for name in sorted(os.listdir(entries_dir)):
            if name.endswith(".json"):
                with open(os.path.join(entries_dir, name)) as f:
                    e = json.load(f)
                by_slug[e["slug"]] = e
    dropped = sorted(s for s in by_slug if s not in states)
    index = {
        "schema": PACK_SCHEMA,
        "generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "states": [by_slug[s] for s in states if s in by_slug],
    }
    with open(out, "w") as f:
        json.dump(index, f, separators=(",", ":"))
    print(f"index: {len(index['states'])} states, dropped {dropped or 'none'}", file=sys.stderr)
    return dropped


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="build one pack")
    b.add_argument("--regions", nargs="+", required=True, help="Geofabrik US slugs (north-america/us/<slug>)")
    b.add_argument("--out", required=True)
    b.add_argument("--workdir", default="work", help="where extracts are downloaded and cached")
    b.add_argument("--index-entry", help="also write this pack's entry for the state index")

    st = sub.add_parser("states", help="print the states to build, as JSON")
    st.add_argument("--only", default="", help="space/comma-separated subset (must be in states.txt)")

    ix = sub.add_parser("index", help="merge per-state entries into trailpack-index.json")
    ix.add_argument("--entries", required=True)
    ix.add_argument("--old")
    ix.add_argument("--out", required=True)

    a = ap.parse_args()
    if a.cmd == "build":
        build(a.regions, a.workdir, a.out, a.index_entry)
    elif a.cmd == "states":
        states = read_states()
        only = [x for x in re.split(r"[\s,]+", a.only) if x]
        unknown = [x for x in only if x not in states]
        if unknown:
            sys.exit(f"not in states.txt: {unknown}")
        print(json.dumps(only or states))
    else:
        for slug in merge_index(a.entries, a.old, a.out):
            print(slug)


if __name__ == "__main__":
    sys.exit(main())
