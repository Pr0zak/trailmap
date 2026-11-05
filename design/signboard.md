# Signboard — the trailmap UI revision

Design notes for the look introduced after 0.28.0, kept so a later pass can see what was chosen and why
rather than re-deriving it.

## Brief

A personal trail map for Kansas City, read standing at a trailhead or straddling a bike in full
sun. The screen already carries real colour: paved green, gravel gold, dirt sienna, horse purple,
and the six sign colours of mountain-bike difficulty. The UI's job is to frame that, not compete
with it.

## What was wrong with the old look

- Cream background, green primary, default Roboto: the stock Material 3 app with a warm tint.
- Green was both the chrome accent *and* the colour meaning "paved", so a green Details button sat
  next to a green Paved badge and meant two different things.
- Gold selected chips next to a gold Gravel badge, same problem.
- Trail rows joined everything with middle dots ("Paved · 0.4 mi · 0.6 mi away"); nothing lined up.
- Surface badges shouted in capitals ("GRAVEL"); everything was a pill.

## Reference

The trailhead kiosk: a dark board with the park's name across the top; under it, a trail table —
name on the left, mileage on the right, a coloured blaze marking which trail. Blazes on trees are
short painted rectangles. Road and recreation signs are set in Highway Gothic.

## Tokens

| Token | Light | Dark | Role |
|---|---|---|---|
| Ink | `#17291F` | `#E9EEE6` (Bone) | text, filled buttons, selected chips, nav indicator |
| Paper | `#F2F4EE` | `#0E1511` (Pine) | background |
| Plate | `#FFFFFF` | `#090E0B` | cards, the search plate, the peek card |
| Band | `#17291F` | `#1F2B22` | the signboard at the top of a screen (`inverseSurface`) |
| Muted | `#525B52` | `#B9C3B7` | secondary text |
| Hairline | `#C7CEC3` | `#333F35` | dividers, card outlines |
| Star gold | `#A9780E` | `#E8C27A` | the saved star only (the pin from the app icon) |

Data colours (surfaces, levels, "you" blue, conditions) are unchanged.

**Type:** Overpass, bundled (`res/font/overpass_*.ttf`, OFL). One family: ExtraBold for screen
names, Bold for trail names and figures, SemiBold for labels, Regular for body. Tabular figures
everywhere (`tnum`) so mileage columns align.

**Shapes:** 8 dp on controls (buttons are `SignButton` / `SignOutlinedButton`, since Material pins
its own to a pill), 12 dp on cards and the search plate, 16 dp on sheets and the peek card, 4 dp on
badges and the blaze.

## Layout

```
┌──────────────────────────────────────┐
│ ████████████  band  █████████████████│
│  Trails                           ☆  │
│  9 within 10 mi, nearest first       │
│  ┌────────────────────────────────┐  │
│  │ ⌕ Search trails   All│MTB   ≡ │  │   the search plate, set into the band
│  └────────────────────────────────┘  │
│██████████████████████████████████████│
│  Sort: Distance ▾                    │
│  ▮ Town of Kansas Bridge Walk  0.4 mi ☆
│    Paved  🚶                0.6 mi away
│  ──────────────────────────────────  │
│  ▮ Kessler Park Trail         1.6 mi ☆
│    Dirt  🚶  ○ Easiest · S0 1.5 mi away
```

Left-aligned text, right-aligned figures. The map screen has no band — the map is the hero — but
its floating controls use the same plate and ink.

## Principles

1. Colour belongs to the trail data. Chrome is ink and paper.
2. Figures line up in a column; they are never buried in a sentence.
3. The band is the one bold move per screen. Everything under it stays quiet.
4. Sign plates, not pills, except for badges, which are blazes.

## Tried and dropped

- An overlapping search plate (half in the band, half out): fragile across the status-bar inset
  and the keyboard; fully inside the band reads cleaner.
- A bone band in dark mode: too loud against the pine background; the dark band is a lifted pine
  with bone text instead.
