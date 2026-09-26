package com.trailmap.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * What trailmap accepts from the myvitals app's "Send to trailmap", and from whom. The address
 * and key are made up; the key only has to look like one.
 */
class MyVitalsHandoffTest {
    private val url = "http://myvitals.local:8000"
    private val key = "made-up_KEY.0123456789~abc"

    @Test fun takesAnAddressAndKey() {
        assertEquals(MyVitalsOffer(url, key), MyVitalsHandoff.parse(url, key))
    }

    @Test fun trimsBoth() {
        assertEquals(MyVitalsOffer(url, key), MyVitalsHandoff.parse("  $url\n", "\t$key\r\n "))
    }

    @Test fun blankOrMissingIsNoOffer() {
        assertNull(MyVitalsHandoff.parse(null, key))
        assertNull(MyVitalsHandoff.parse(url, null))
        assertNull(MyVitalsHandoff.parse("", key))
        assertNull(MyVitalsHandoff.parse(url, ""))
        assertNull(MyVitalsHandoff.parse(" \n ", key))
        assertNull(MyVitalsHandoff.parse(url, " \t "))
    }

    @Test fun oversizeIsNoOffer() {
        val longUrl = "http://myvitals.local/" + "a".repeat(2048 - 22)
        assertEquals(2048, longUrl.length)
        assertEquals(longUrl, MyVitalsHandoff.parse(longUrl, key)?.url)
        assertNull(MyVitalsHandoff.parse(longUrl + "a", key))

        val longKey = "k".repeat(4096)
        assertEquals(longKey, MyVitalsHandoff.parse(url, longKey)?.token)
        assertNull(MyVitalsHandoff.parse(url, longKey + "k"))
    }

    // --- the address -----------------------------------------------------------------------

    @Test fun webAddressesAreOffers() {
        for (ok in listOf(
            url, "https://myvitals.example", "HTTPS://Myvitals.Example:8443/api/", "myvitals.local:8080",
            "myvitals.local", "http://myvitals.local/api", "http://myvitals.local:8000/path/@not-userinfo",
            "myvitals.local:8080/api/", "localhost:8000", "http://[::1]:8000",
        )) {
            assertNotNull(ok, MyVitalsHandoff.parse(ok, key))
        }
    }

    @Test fun otherSchemesAreNoOffer() {
        for (bad in listOf(
            "ftp://myvitals.local", "file:///sdcard/settings.json", "content://com.example.provider/x",
            "intent://myvitals.local#Intent;scheme=http;end", "javascript:alert(1)", "data:text/html,hi",
            "ws://myvitals.local:8000", "market://details?id=app.myvitals", "file:/etc/hosts", "intent:#Intent;end",
            "http:/myvitals.local", "http:myvitals.local", "content:myvitals",
        )) {
            assertNull(bad, MyVitalsHandoff.parse(bad, key))
        }
    }

    @Test fun userinfoInFrontOfTheHostIsNoOffer() {
        for (bad in listOf(
            "http://user@myvitals.local", "http://user:@myvitals.local:8000", "http://:secret@myvitals.local:8000",
            "http://us%65r:s%65cret@myvitals.local", "http://@myvitals.local",
            "http://myvitals.local@elsewhere.example", "myvitals.local:8000@elsewhere.example",
            "https://myvitals.local%40elsewhere.example", "mailto:someone@elsewhere.example",
        )) {
            assertNull(bad, MyVitalsHandoff.parse(bad, key))
        }
    }

    @Test fun hiddenCharactersInTheAddressAreNoOffer() {
        for (bad in listOf(
            "http://myvitals\u0000.local", "http://myvitals.local\u0007", "http://my\u200Bvitals.local", // control, BEL, zero-width space
            "http://\u202Elacol.slativym", "http://myvitals.local\u2066/api", "\uFEFFhttp://myvitals.local", // RTL override, isolate, BOM
            "http://myvitals.local/\u00AD", "http://myvitals.local/\uDB40\uDC41", // soft hyphen; a tag character, outside the BMP
            "http://myvitals.local/\uD800", "http://my vitals.local", "http://myvitals.local/a\u00A0b", "http://myvitals.local/\u2028api",
        )) {
            assertNull(bad.map { if (it.code < 0x20 || it.code > 0x7E) "U+%04X".format(it.code) else "$it" }.joinToString(""), MyVitalsHandoff.parse(bad, key))
        }
    }

    // --- the key ---------------------------------------------------------------------------

    @Test fun keyIsPrintableAsciiOnly() {
        for (bad in listOf(
            "made up", "made\tup", "made\nup", "made\u00A0up", "made\u0000up", "made\u007Fup", "made\u001Bup",
            "m\u00E4deup", "made\u200Bup", "made\u202Eup", "made\uD83D\uDEB2up", "made\uFF41up",
        )) {
            assertNull(bad, MyVitalsHandoff.parse(url, bad))
        }
        val everyPrintable = (0x21..0x7E).map { it.toChar() }.joinToString("")
        assertEquals(everyPrintable, MyVitalsHandoff.parse(url, everyPrintable)?.token)
    }

    // --- who sent it -----------------------------------------------------------------------

    @Test fun pinDecodesToTheCertificateHash() {
        val pin = MyVitalsHandoff.decodeSha256Pin(MyVitalsHandoff.MYVITALS_CERT_SHA256)
        assertEquals(32, pin.size)
        assertEquals(0x48.toByte(), pin[0])
        assertEquals(0x22.toByte(), pin[1])
        assertEquals(0x1a.toByte(), pin[31])
        // Upper case is the same pin, and the bytes go back to the same hex.
        assertArrayEquals(pin, MyVitalsHandoff.decodeSha256Pin(MyVitalsHandoff.MYVITALS_CERT_SHA256.uppercase()))
        assertEquals(MyVitalsHandoff.MYVITALS_CERT_SHA256, pin.joinToString("") { "%02x".format(it) })
    }

    @Test fun malformedPinFailsLoudly() {
        val good = MyVitalsHandoff.MYVITALS_CERT_SHA256
        for (bad in listOf("", good.dropLast(1), good + "0", good.dropLast(1) + "g", "0x" + good.drop(2), good.replaceFirst('4', ' '))) {
            val e = runCatching { MyVitalsHandoff.decodeSha256Pin(bad) }.exceptionOrNull()
            assertTrue("'$bad' gave $e", e is IllegalArgumentException)
        }
    }

    @Test fun onlyTheMyVitalsPackageIsTrusted() {
        var asked = 0
        val signed = { asked++; true }
        for (other in listOf(null, "", "com.example.evil", "app.myvitals.debug", "App.MyVitals", " app.myvitals", "com.trailmap")) {
            assertFalse("$other", MyVitalsHandoff.isFromMyVitals(other, debug = false, signed))
            // A debug build skips the certificate, never the name.
            assertFalse("$other (debug)", MyVitalsHandoff.isFromMyVitals(other, debug = true, signed))
        }
        // The certificate isn't even looked up for the wrong name.
        assertEquals(0, asked)
    }

    @Test fun releaseBuildsNeedMyVitalsCertificate() {
        assertTrue(MyVitalsHandoff.isFromMyVitals("app.myvitals", debug = false) { true })
        assertFalse(MyVitalsHandoff.isFromMyVitals("app.myvitals", debug = false) { false })
        // A lookup that blows up counts as untrusted.
        assertFalse(MyVitalsHandoff.isFromMyVitals("app.myvitals", debug = false) { throw SecurityException("hidden") })
    }

    @Test fun debugBuildsSkipOnlyTheCertificate() {
        var asked = false
        assertTrue(MyVitalsHandoff.isFromMyVitals("app.myvitals", debug = true) { asked = true; false })
        assertFalse(asked)
    }

    @Test fun certificatesMatchOnlyASingleSignerWithThePinnedHash() {
        val cert = "made-up certificate bytes".toByteArray()
        val other = "some other certificate".toByteArray()
        val pin = MessageDigest.getInstance("SHA-256").digest(cert)
        assertTrue(MyVitalsHandoff.certificatesMatch(listOf(cert), pin))
        assertFalse(MyVitalsHandoff.certificatesMatch(listOf(other), pin))
        assertFalse(MyVitalsHandoff.certificatesMatch(emptyList(), pin))
        // Several signers fail, as they do with hasSigningCertificate on API 28+.
        assertFalse(MyVitalsHandoff.certificatesMatch(listOf(cert, other), pin))
        assertFalse(MyVitalsHandoff.certificatesMatch(listOf(cert, cert), pin))
        // The hash of the certificate, not the certificate itself.
        assertFalse(MyVitalsHandoff.certificatesMatch(listOf(pin), pin))
    }

    // --- the offer -------------------------------------------------------------------------

    @Test fun offersAreToldApartByIdButCanBeTheSameConnection() {
        val a = MyVitalsOffer(url, key, id = 1)
        val b = MyVitalsOffer(url, key, id = 2)
        assertNotEquals(a, b)
        assertTrue(a.sameConnection(b.url, b.token))
        // The saved address is normalised; the offer's may not be.
        assertTrue(MyVitalsOffer("myvitals.local:8000/", key, id = 3).sameConnection(url, key))
        assertFalse(a.sameConnection("http://myvitals.local:8080", key))
        assertFalse(a.sameConnection(url, key + "x"))
    }

    @Test fun printingNeverShowsTheKey() {
        val offer = MyVitalsOffer(url, key, id = 7)
        assertFalse(offer.toString().contains(key))
        assertTrue(offer.toString().contains(url))
        // Anything holding an offer prints it through the redacted toString.
        assertFalse(listOf(offer).toString().contains(key))

        val settings = MyVitalsSettings(url = url, token = key, apiBase = url)
        assertFalse(settings.toString().contains(key))
        assertTrue(settings.toString().contains(url))

        val pasted = PastedKey.of(key)
        assertFalse(pasted.toString().contains(key))
    }

    @Test fun hostIsAllALogGets() {
        assertEquals("myvitals.local", MyVitalsOffer(url, key).host)
        assertEquals("myvitals.local", MyVitalsOffer("myvitals.local:8080/api/", key).host)
    }
}
