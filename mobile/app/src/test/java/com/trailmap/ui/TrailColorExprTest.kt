package com.trailmap.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.android.style.expressions.Expression

/** The line-color expression built from the shared color tables matches the original literal one. */
class TrailColorExprTest {
    private fun original(dark: Boolean, surface: Expression): Expression = Expression.match(
        Expression.get("mtb"),
        Expression.literal("0"), Expression.color((if (dark) 0xFF66D08A else 0xFF43A047).toInt()),
        Expression.literal("1"), Expression.color((if (dark) 0xFF3FD89A else 0xFF1E9E6A).toInt()),
        Expression.literal("2"), Expression.color((if (dark) 0xFF5BB0F5 else 0xFF1E88E5).toInt()),
        Expression.literal("3"), Expression.color((if (dark) 0xFFBDBDBD else 0xFF424242).toInt()),
        Expression.literal("4"), Expression.color((if (dark) 0xFFFF6B6B else 0xFFE53935).toInt()),
        Expression.literal("5"), Expression.color((if (dark) 0xFFE57373 else 0xFFB71C1C).toInt()),
        Expression.literal("6"), Expression.color((if (dark) 0xFFD84343 else 0xFF7F0000).toInt()),
        surface,
    )

    /** Unrated lines check the "horse" prop before their surface. */
    @Test fun horseSectionsComeBeforeSurface() {
        for (dark in listOf(false, true)) {
            val default = com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(trailColorExpr(dark).toArray().last())
            org.junit.Assert.assertTrue(default, default.startsWith("""["case",["==",["get","horse"],true]"""))
        }
    }

    @Test fun matchesOriginal() {
        for (dark in listOf(false, true)) {
            val built = trailColorExpr(dark)
            // The default (last arg) is the surface expression; reuse it so only the MTB stops are compared.
            val arr = built.toArray()
            val surface = Expression.raw(com.google.gson.Gson().toJson(arr.last()))
            assertEquals(original(dark, surface).toString(), built.toString())
        }
    }
}
