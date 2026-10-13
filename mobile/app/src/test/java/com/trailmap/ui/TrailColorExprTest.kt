package com.trailmap.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.maplibre.android.style.expressions.Expression

/** The line-color expression maps mtb:scale onto trail-sign colours: white, green, blue, black, double black, pro. */
class TrailColorExprTest {
    private fun original(dark: Boolean, surface: Expression): Expression = Expression.match(
        Expression.get("mtb"),
        Expression.literal("0"), Expression.color((if (dark) 0xFFFFFFFF else 0xFFFFFFFF).toInt()), // white
        Expression.literal("1"), Expression.color((if (dark) 0xFF4CC86A else 0xFF2E9E44).toInt()), // green
        Expression.literal("2"), Expression.color((if (dark) 0xFF4C9BFF else 0xFF1E6FD9).toInt()), // blue
        Expression.literal("3"), Expression.color((if (dark) 0xFF000000 else 0xFF111111).toInt()), // black
        Expression.literal("4"), Expression.color((if (dark) 0xFF000000 else 0xFF111111).toInt()), // double black
        Expression.literal("5"), Expression.color((if (dark) 0xFFFF9800 else 0xFFF57C00).toInt()), // pro
        Expression.literal("6"), Expression.color((if (dark) 0xFFFF9800 else 0xFFF57C00).toInt()), // pro
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
