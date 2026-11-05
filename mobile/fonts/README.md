# Bundled fonts

`app/src/main/res/font/overpass_{regular,semibold,bold,extrabold}.ttf` are **Overpass 4.000**
(Delve Fonts for Red Hat, https://github.com/RedHatOfficial/Overpass), licensed under the SIL
Open Font License 1.1 — the licence is in `OFL-Overpass.txt`. It declares no Reserved Font Name.

## One change from the upstream files

Upstream Overpass classes the middle dot `·` (`periodcentered`, U+00B7) as a *combining mark* in
its GDEF table. HarfBuzz — which Android's text stack uses — gives mark glyphs zero advance, so
every "A · B" in the app rendered as "A ·B". The bundled files set that glyph's class to *base*
and drop the now-void `DSIG` table; nothing else differs. To reproduce from the upstream static
TTFs with fontTools:

```python
from fontTools.ttLib import TTFont
f = TTFont("Overpass-Regular.ttf")
f["GDEF"].table.GlyphClassDef.classDefs["periodcentered"] = 1
del f["DSIG"]
f.save("overpass_regular.ttf")
```
