package com.trailmap.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.android.style.expressions.Expression

/** The line-color expression maps mtb:scale onto trail-sign colours by level: white, green, blue, black, double black, pro. */
class TrailColorExprTest {
    private fun original(dark: Boolean, surface: Expression): Expression = Expression.match(
        Expression.get("lvl"),
        Expression.literal("WHITE"), Expression.color((if (dark) 0xFFFFFFFF else 0xFFFFFFFF).toInt()),
        Expression.literal("GREEN"), Expression.color((if (dark) 0xFF4CC86A else 0xFF2E9E44).toInt()),
        Expression.literal("BLUE"), Expression.color((if (dark) 0xFF4C9BFF else 0xFF1E6FD9).toInt()),
        Expression.literal("BLACK"), Expression.color((if (dark) 0xFF000000 else 0xFF111111).toInt()),
        Expression.literal("DOUBLE_BLACK"), Expression.color((if (dark) 0xFF000000 else 0xFF111111).toInt()),
        Expression.literal("PRO"), Expression.color((if (dark) 0xFFFF9800 else 0xFFF57C00).toInt()),
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
