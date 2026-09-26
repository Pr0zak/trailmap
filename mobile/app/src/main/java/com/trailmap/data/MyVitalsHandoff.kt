package com.trailmap.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.IntentCompat
import com.trailmap.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/*
 * The myvitals phone app can send trailmap its server address and access key ("Send to
 * trailmap", in its Settings → Connection & sync), so nobody has to copy a long key from one
 * app to the other. It starts MainActivity with an explicit intent (setPackage("com.trailmap")),
 * and only when the installed trailmap carries trailmap's release signature.
 *
 * That guards the key on its way out. On the way in, any app on the phone can start
 * MainActivity with this action, and without a check one could fill the myvitals screen with a
 * server of its own under a banner saying it came from myvitals. So the offer carries a sender:
 * an immutable PendingIntent the myvitals app creates and never fires. The system records which
 * package created a PendingIntent (its creatorPackage) and no app can forge that. trailmap
 * requires app.myvitals, and that the installed app.myvitals is signed with myvitals' release
 * certificate, so something else installed under that name doesn't pass either. Debug builds of
 * trailmap skip the certificate, so a locally built myvitals can be tested, but never the name.
 * An offer that fails is dropped without a word on screen.
 *
 * trailmap never connects on its own: the offer fills in the myvitals screen, and the user
 * checks the address and taps Connect. Both apps spell these names the same way; renaming one
 * breaks the other.
 */
object MyVitalsHandoff {
    const val ACTION = "com.trailmap.action.CONNECT_MYVITALS"
    const val EXTRA_URL = "com.trailmap.extra.MYVITALS_URL"
    const val EXTRA_TOKEN = "com.trailmap.extra.MYVITALS_TOKEN"

    /** The immutable PendingIntent the myvitals app made. Never fired; only asked who created it. */
    const val EXTRA_SENDER = "com.trailmap.extra.MYVITALS_SENDER"

    /** The myvitals app. The manifest's `<queries>` names it too, or Android 11+ hides it from the check. */
    const val MYVITALS_PACKAGE = "app.myvitals"

    /** SHA-256 of the myvitals app's release signing certificate: a public fingerprint, not a secret. */
    const val MYVITALS_CERT_SHA256 = "4822db0ca48d34f4ca29388dcfe312dfd4b0631a3adffdd3cda948790851591a"

    private val myVitalsCert: ByteArray = decodeSha256Pin(MYVITALS_CERT_SHA256)

    /** Anything longer than this came from somewhere other than a real myvitals setup. */
    private const val MAX_URL = 2048
    private const val MAX_TOKEN = 4096

    /** Something that could be a scheme: letters and the like, then a colon. */
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** ...which a bare `host:port` also looks like, until the digits. */
    private val HOST_PORT = Regex("^[A-Za-z0-9.-]+:[0-9]{1,5}(?:[/?#]|$)")

    /**
     * What another app sent, checked: both present, a sane length, an address trailmap would
     * connect to ([isWebAddress]), and a key made only of printable ASCII — a space, a control
     * character or anything past `~` inside it means it was mangled on the way. Null when any
     * of that fails.
     */
    fun parse(url: String?, token: String?): MyVitalsOffer? {
        val u = url?.trim().orEmpty()
        val t = token?.trim().orEmpty()
        if (u.isEmpty() || t.isEmpty() || u.length > MAX_URL || t.length > MAX_TOKEN) return null
        if (!MyVitalsClient.isKeyText(t) || !isWebAddress(u)) return null
        return MyVitalsOffer(u, t)
    }

    /**
     * An address fit to show as "from the myvitals app": http or https (a bare `host:port` is
     * taken as http, as the form does), a real host, nothing in front of that host — a
     * `user:password@` prefix can make `http://myvitals.local@elsewhere.example` read as the
     * wrong host — and no control, spacing or invisible formatting characters, since a
     * right-to-left override or a zero-width space can make one host look like another.
     */
    internal fun isWebAddress(u: String): Boolean {
        val hidden = u.codePoints().anyMatch { cp ->
            Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp) ||
                Character.getType(cp) == Character.FORMAT.toInt() || Character.getType(cp) == Character.SURROGATE.toInt()
        }
        if (hidden) return false
        // Any scheme but http(s): ftp://, file:, content://, intent:, javascript:, data:… Left
        // alone, normalizeUrl would put "http://" in front of `file:/x` and OkHttp read that as
        // a host called "file" with an empty port.
        val http = u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)
        if (!http && SCHEME.containsMatchIn(u) && !HOST_PORT.containsMatchIn(u)) return false
        val normalized = MyVitalsClient.normalizeUrl(u)
        val authority = normalized.substringAfter("://").takeWhile { it !in "/\\?#" }
        if ('@' in authority) return false
        val parsed = normalized.toHttpUrlOrNull() ?: return false
        return parsed.host.isNotEmpty() && parsed.username.isEmpty() && parsed.password.isEmpty()
    }

    /**
     * Whether an offer came from the myvitals app, apart from Android so it can be tested.
     * [creatorPackage] is the package the system says created the sender PendingIntent, null
     * when there was none or it wasn't one. [signedByMyVitals] asks whether the installed
     * app.myvitals carries myvitals' release certificate; it is only asked once the name
     * matches, and not at all in [debug] builds. The name is never skipped.
     */
    fun isFromMyVitals(creatorPackage: String?, debug: Boolean, signedByMyVitals: () -> Boolean): Boolean {
        if (creatorPackage != MYVITALS_PACKAGE) return false
        if (debug) return true
        return try {
            signedByMyVitals()
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * The certificate check before API 28, given each signer's certificate: one signer, and it
     * hashes to [sha256]. An app with several signers fails, as hasSigningCertificate fails it.
     */
    fun certificatesMatch(certificates: List<ByteArray>, sha256: ByteArray): Boolean {
        val only = certificates.singleOrNull() ?: return false
        return MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(only), sha256)
    }

    /**
     * 64 hex characters to the 32 bytes the certificate check compares. Strict: a mistyped pin
     * has to fail loudly at startup, not quietly match nothing.
     */
    fun decodeSha256Pin(hex: String): ByteArray {
        require(hex.length == 64) { "a SHA-256 pin is 64 hex characters, not ${hex.length}" }
        return ByteArray(32) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            require(hi >= 0 && lo >= 0) { "a SHA-256 pin is hex; position ${2 * i} isn't" }
            ((hi shl 4) or lo).toByte()
        }
    }

    /**
     * The offer in [intent], if it is a handoff from the myvitals app, with the address, key
     * and sender then taken out of it so a recreated activity can't offer them again. The
     * extras come from another app, and a malformed Bundle throws as soon as it is read; that
     * counts as no offer. One that fails the sender check is dropped with a log line that
     * names neither the address nor the key.
     */
    fun take(context: Context, intent: Intent?, debug: Boolean = BuildConfig.DEBUG): MyVitalsOffer? {
        if (intent?.action != ACTION) return null
        // Reopened from Recents, Android replays the intent that started the task. That offer
        // was dealt with the first time round.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
        val verified = isFromMyVitals(creatorOf(intent), debug) {
            isSignedWith(context.packageManager, MYVITALS_PACKAGE, myVitalsCert)
        }
        val offer = if (!verified) {
            DiagLog.log("myvitals", "ignored a connection offer from an unverified app")
            null
        } else {
            try {
                parse(intent.getStringExtra(EXTRA_URL), intent.getStringExtra(EXTRA_TOKEN))
            } catch (e: RuntimeException) {
                null
            }
        }
        try {
            intent.removeExtra(EXTRA_URL)
            intent.removeExtra(EXTRA_TOKEN)
            intent.removeExtra(EXTRA_SENDER)
        } catch (e: RuntimeException) {
            // The same malformed Bundle; there is nothing readable in it to leave behind.
        }
        return offer
    }

    /**
     * The package that created the sender PendingIntent, as the system recorded it. Null when
     * the extra is missing, is some other type, or the Bundle won't unparcel.
     */
    private fun creatorOf(intent: Intent): String? = try {
        IntentCompat.getParcelableExtra(intent, EXTRA_SENDER, PendingIntent::class.java)?.creatorPackage
    } catch (e: RuntimeException) {
        null
    }

    /**
     * Whether the installed [pkg] is signed with the certificate whose SHA-256 is [sha256].
     * API 28 has a call for exactly this, which also follows a rotated key's lineage; on 26-27
     * the package's signatures are hashed here instead.
     */
    @Suppress("DEPRECATION")
    private fun isSignedWith(pm: PackageManager, pkg: String, sha256: ByteArray): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.hasSigningCertificate(pkg, sha256, PackageManager.CERT_INPUT_SHA256)
        } else {
            val signatures = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            certificatesMatch(signatures.map { it.toByteArray() }, sha256)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * A connection the myvitals app sent over, waiting for the user to check it and connect.
 *
 * [id] tells one offer from the next: the ViewModel numbers each one as it arrives (0 until
 * then). The same address and key sent twice are two offers, so the second refills fields the
 * user has since changed, and a recreated screen can tell the offer it already applied from a
 * new one. [sameConnection] compares the address and key alone.
 */
data class MyVitalsOffer(val url: String, val token: String, val id: Long = 0L) {
    /** Just the host, which is what the screen puts first and all a log line gets; null when [url] isn't a web address. */
    val host: String? get() = MyVitalsClient.normalizeUrl(url).toHttpUrlOrNull()?.host

    /** The same address, once normalised, and the same key, whatever the ids. */
    fun sameConnection(url: String, token: String): Boolean =
        MyVitalsClient.normalizeUrl(this.url) == MyVitalsClient.normalizeUrl(url) && this.token == token

    // A log line, a crash report or a debugger that prints an offer never shows the key.
    override fun toString() = "MyVitalsOffer(id=$id, url=$url, token=<redacted>)"
}
