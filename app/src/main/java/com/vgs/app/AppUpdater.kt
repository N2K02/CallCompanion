package com.shizucallcompanion.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
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
import java.lang.ref.WeakReference
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
 *    newer versionCode and a signing key the installed app trusts.
 * 4. Install: opens Android's own package installer screen (the same one as tapping a
 *    downloaded APK), with the file shared through [UpdateFileProvider]. The first time,
 *    Android asks to allow installs from this app.
 *
 * Nothing runs in the background and no data is sent anywhere except the requests to GitHub.
 */
object AppUpdater {
    /** "owner/repo" on GitHub. Set in [init]. */
    private var repo = ""
    private var appName = ""
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var activityRef: WeakReference<Activity>? = null

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
        /** Downloaded and checked; tap Install to open Android's installer (again). */
        data class Ready(val release: Release) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val prefs by lazy { app.getSharedPreferences("app_updater", Context.MODE_PRIVATE) }

    fun init(context: Context, repo: String, appName: String) {
        app = context.applicationContext
        this.repo = repo
        this.appName = appName
        if (context is Activity) activityRef = WeakReference(context)
        // A new app start: an update that installed (or was abandoned) leaves its APK behind.
        if (_state.value is State.Idle) runCatching { updateDir().deleteRecursively() }
    }

    val installedVersionName: String
        get() = runCatching { packageInfo(app.packageName).versionName }.getOrNull().orEmpty()

    private val installedVersionCode: Long
        get() = versionCodeOf(packageInfo(app.packageName))

    /** Call from the activity's onResume: checks if due, and continues an install waiting for permission. */
    fun onResume(activity: Activity) {
        activityRef = WeakReference(activity)
        when (val s = _state.value) {
            is State.NeedsPermission -> if (canInstall()) openInstaller(s.release)
            is State.Idle -> {
                val last = prefs.getLong("lastCheck", 0L)
                if (System.currentTimeMillis() - last > CHECK_EVERY_MS) check(manual = false)
            }
            else -> Unit
        }
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
            if (canInstall()) openInstaller(release) else _state.value = State.NeedsPermission(release)
        }
    }

    /** Install button: opens Android's installer for the downloaded update. */
    fun install(release: Release) {
        if (!apkFile(release).exists()) {
            update(release)
            return
        }
        if (canInstall()) openInstaller(release) else _state.value = State.NeedsPermission(release)
    }

    /** Opens Android's "Install unknown apps" setting for this app. */
    fun openInstallPermission(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
        }
    }

    fun openReleasePage(activity: Activity, release: Release?) {
        val url = release?.pageUrl ?: "https://github.com/$repo/releases/latest"
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /**
     * Hands the APK to Android's package installer (the standard install screen). If Play Protect
     * asks to scan it, that happens in the same screen. Installing replaces and restarts the app.
     */
    private fun openInstaller(release: Release) {
        val file = apkFile(release)
        _state.value = State.Ready(release)
        val activity = activityRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(UpdateFileProvider.uriFor(app, file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { activity.startActivity(intent) }
            .onFailure { _state.value = State.Failed("Couldn't open Android's installer.", release) }
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

    private fun updateDir() = File(app.cacheDir, UpdateFileProvider.DIR)

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

    private fun canInstall() = app.packageManager.canRequestPackageInstalls()

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
