package com.vgs.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates from this app's GitHub Releases (the same APK the release page offers).
 *
 * 1. Check: asks GitHub for the latest release (on opening the app, at most every 12 hours,
 *    or when "Check for updates" is tapped). The release tag "v1.8" is turned into the same
 *    versionCode the build uses (1.8 -> 18) and compared with the installed one.
 * 2. Download: the APK goes to the app's private cache, with progress.
 * 3. Verify before installing: size and SHA-256 (from GitHub), same package name,
 *    newer versionCode and the same signing key as the installed app.
 * 4. Install with Android's PackageInstaller (the modern, supported way). Android asks the
 *    user to confirm; the first time it also asks to allow installs from this app.
 *
 * Nothing runs in the background and no data is sent anywhere except the request to GitHub.
 */
object AppUpdater {
    /** "owner/repo" on GitHub. Set in [init]. */
    private var repo = ""
    private var appName = ""
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

    class Release(
        val tag: String,
        val versionName: String,
        val versionCode: Long,
        /** "What's new" lines from the release notes. */
        val notes: List<String>,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        /** Lowercase hex, when GitHub provides it. */
        val sha256: String?,
        val pageUrl: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val manual: Boolean) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float?) : State
        /** Downloaded and checked; Android needs "install unknown apps" allowed for this app first. */
        data class NeedsPermission(val release: Release) : State
        data class Installing(val release: Release) : State
        /** Android is waiting for the user to confirm the install. */
        data class Confirm(val release: Release, val intent: Intent) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val prefs by lazy { app.getSharedPreferences("app_updater", Context.MODE_PRIVATE) }

    fun init(context: Context, repo: String, appName: String) {
        app = context.applicationContext
        this.repo = repo
        this.appName = appName
        // An update that installed: remove its downloaded APK.
        if (_state.value is State.Idle) runCatching { updateDir().deleteRecursively() }
    }

    val installedVersionName: String
        get() = runCatching { packageInfo(app.packageName).versionName }.getOrNull().orEmpty()

    private val installedVersionCode: Long
        get() = versionCodeOf(packageInfo(app.packageName))

    /** Call from the activity's onResume: checks if due, and continues an install waiting for permission. */
    fun onResume(activity: Activity) {
        val s = _state.value
        if (s is State.NeedsPermission && canInstall()) {
            install(s.release)
            return
        }
        if (s is State.Confirm) return
        val last = prefs.getLong("lastCheck", 0L)
        if (s is State.Idle && System.currentTimeMillis() - last > CHECK_EVERY_MS) check(manual = false)
    }

    /** Asks GitHub for the latest release. [manual]: also show "up to date" and ignore "skip". */
    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Checking
            val result = runCatching { latestRelease() }
            prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            val release = result.getOrNull()
            _state.value = when {
                result.isFailure -> if (manual) State.Failed("Couldn't reach GitHub. Check your connection.", null) else State.Idle
                release == null || release.versionCode <= installedVersionCode -> if (manual) State.UpToDate(true) else State.Idle
                !manual && prefs.getString("skipped", null) == release.tag -> State.Idle
                else -> State.Available(release)
            }
        }
    }

    /** Hides the offer until the next release. */
    fun skip(release: Release) {
        prefs.edit().putString("skipped", release.tag).apply()
        _state.value = State.Idle
    }

    fun dismiss() {
        job?.cancel()
        _state.value = State.Idle
    }

    /** Downloads, verifies and installs [release]. */
    fun update(release: Release) {
        if (job?.isActive == true) return
        job = scope.launch {
            val file = apkFile(release)
            try {
                if (!(file.exists() && verify(file, release) == null)) {
                    download(release, file)
                    verify(file, release)?.let { problem ->
                        file.delete()
                        _state.value = State.Failed(problem, release)
                        return@launch
                    }
                }
            } catch (e: Exception) {
                file.delete()
                _state.value = State.Failed("Download failed. Check your connection and try again.", release)
                return@launch
            }
            if (canInstall()) install(release) else _state.value = State.NeedsPermission(release)
        }
    }

    /** Opens Android's "Install unknown apps" setting for this app. */
    fun openInstallPermission(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
        }
    }

    /** Shows Android's install confirmation (if it didn't open by itself). */
    fun confirm(activity: Activity, s: State.Confirm) {
        runCatching { activity.startActivity(s.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { _state.value = State.Failed("Couldn't open the installer.", s.release) }
    }

    fun openReleasePage(activity: Activity, release: Release?) {
        val url = release?.pageUrl ?: "https://github.com/$repo/releases/latest"
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    // ---------------------------------------------------------------- GitHub

    private suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "$appName-updater (Android)")
            if (c.responseCode == 404) return@withContext null
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return@withContext null
            val tag = o.optString("tag_name")
            val name = tag.removePrefix("v")
            val code = versionCodeFromName(name) ?: return@withContext null
            val assets = o.optJSONArray("assets") ?: return@withContext null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: return@withContext null
            Release(
                tag = tag,
                versionName = name,
                versionCode = code,
                notes = whatsNew(o.optString("body")),
                apkUrl = apk.optString("browser_download_url"),
                apkName = apk.optString("name"),
                apkSize = apk.optLong("size"),
                sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
                pageUrl = o.optString("html_url"),
            )
        } finally {
            c.disconnect()
        }
    }

    /** Same rule as the build: "1.8" -> 18, "2.0" -> 20. */
    private fun versionCodeFromName(name: String): Long? {
        val m = Regex("""^(\d+)\.(\d)$""").find(name.trim()) ?: return null
        return m.groupValues[1].toLong() * 10 + m.groupValues[2].toLong()
    }

    /** The "- change" lines of the release notes, without headings and build details. */
    private fun whatsNew(body: String): List<String> {
        val lines = body.lineSequence().map { it.trim() }
        val section = lines.dropWhile { !it.startsWith("### What's new", ignoreCase = true) }.drop(1)
            .takeWhile { !it.startsWith("#") }.toList()
            .ifEmpty { body.lines().map { it.trim() } }
        return section.filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.drop(2).trim() }
            .filterNot { it.contains("[skip ci]") || it.startsWith("README", ignoreCase = true) }
            .take(12)
    }

    // ---------------------------------------------------------------- download + verify

    private fun updateDir() = File(app.cacheDir, "update")

    private fun apkFile(release: Release) =
        File(updateDir(), release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private suspend fun download(release: Release, file: File) = withContext(Dispatchers.IO) {
        updateDir().mkdirs()
        updateDir().listFiles()?.filter { it != file }?.forEach { it.delete() }
        _state.value = State.Downloading(release, null)
        var url = URL(release.apkUrl)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "$appName-updater (Android)")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                c.disconnect()
                url = URL(url, next)
                if (url.protocol != "https") throw IOException("Insecure download link")
                continue
            }
            if (code != 200) throw IOException("HTTP $code")
            break
        }
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            val tmp = File(file.path + ".part")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total != null && done - lastReport > 128 * 1024) {
                            lastReport = done
                            _state.value = State.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (!tmp.renameTo(file)) throw IOException("Couldn't save the download")
        } finally {
            c.disconnect()
        }
    }

    /** Null if the APK is good to install, else what's wrong (in plain words). */
    private suspend fun verify(file: File, release: Release): String? = withContext(Dispatchers.IO) {
        if (release.apkSize > 0 && file.length() != release.apkSize) return@withContext "The download was incomplete. Please try again."
        release.sha256?.let { expected ->
            if (sha256(file) != expected) return@withContext "The download is damaged (checksum mismatch). Please try again."
        }
        val pm = app.packageManager
        val apk = archiveInfo(file) ?: return@withContext "The downloaded file isn't a valid app."
        if (apk.packageName != app.packageName) return@withContext "The downloaded file is a different app."
        if (versionCodeOf(apk) <= installedVersionCode) return@withContext "The downloaded version isn't newer than this one."
        val mine = signerDigests(packageInfo(app.packageName, signatures = true))
        val theirs = signerDigests(apk)
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine.intersect(theirs).isEmpty()) {
            return@withContext "This update is signed with a different key, so Android can't install it over the current app."
        }
        null
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- install

    private fun canInstall() = app.packageManager.canRequestPackageInstalls()

    private fun install(release: Release) {
        val file = apkFile(release)
        if (!file.exists()) {
            _state.value = State.Available(release)
            return
        }
        _state.value = State.Installing(release)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val installer = app.packageManager.packageInstaller
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(app.packageName)
                        setSize(file.length())
                        if (Build.VERSION.SDK_INT >= 31) {
                            // Lets Android update without a prompt when this app installed itself before.
                            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                        }
                        if (Build.VERSION.SDK_INT >= 33) {
                            setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                        }
                    }
                    val id = installer.createSession(params)
                    installer.openSession(id).use { session ->
                        file.inputStream().use { input ->
                            session.openWrite("base.apk", 0, file.length()).use { out ->
                                input.copyTo(out, 64 * 1024)
                                session.fsync(out)
                            }
                        }
                        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                        val pi = PendingIntent.getBroadcast(
                            app, id,
                            Intent(app, UpdateReceiver::class.java).setPackage(app.packageName),
                            flags,
                        )
                        session.commit(pi.intentSender)
                    }
                }
            } catch (e: Exception) {
                _state.value = State.Failed("Couldn't start the install: ${e.message ?: "unknown error"}", release)
            }
        }
    }

    /** Result of the install, from [UpdateReceiver]. */
    internal fun onInstallStatus(context: Context, intent: Intent) {
        val release = when (val s = _state.value) {
            is State.Installing -> s.release
            is State.Confirm -> s.release
            else -> null
        }
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }) ?: return
                if (release != null) _state.value = State.Confirm(release, confirm)
                // Open Android's confirmation now; the card also has a button in case this is blocked.
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Usually the app is restarted before this arrives.
                _state.value = State.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                _state.value = if (release != null) State.Available(release) else State.Idle
            }
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                _state.value = State.Failed("Android refused the update (signing key or version conflict).", release)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                _state.value = State.Failed("Not enough storage to install the update.", release)
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                _state.value = State.Failed("The install didn't finish${msg?.let { ": $it" } ?: "."}", release)
            }
        }
    }

    // ---------------------------------------------------------------- package info helpers

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun packageInfo(pkg: String, signatures: Boolean = false): PackageInfo {
        val flags = if (!signatures) 0 else if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) {
            app.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            app.packageManager.getPackageInfo(pkg, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) {
            app.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            app.packageManager.getPackageArchiveInfo(file.path, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }
}

/** Receives the result of an update install from Android's PackageInstaller. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppUpdater.onInstallStatus(context, intent)
    }
}
