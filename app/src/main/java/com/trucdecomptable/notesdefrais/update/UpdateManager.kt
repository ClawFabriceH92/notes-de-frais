package com.trucdecomptable.notesdefrais.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.trucdecomptable.notesdefrais.BuildConfig
import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Update flow driven from inside the app.
 *
 * The download is done here rather than through DownloadManager: GitHub hands
 * the APK over to a CDN through a redirect chain DownloadManager does not
 * always follow, which produced truncated files that the installer refused
 * with "problème lors de l'analyse du package".
 */
object UpdateManager {

    sealed interface State {
        data object Idle : State
        data class Available(val version: String, val notes: String, val apkUrl: String) : State
        data class Downloading(val version: String, val progress: Float) : State
        data class ReadyToInstall(val version: String, val file: File) : State
        data class UpToDate(val version: String) : State
        data class Failed(val detail: String?) : State
    }

    private const val DIR = "updates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun checkAtLaunch(context: Context?, skippedVersion: String) {
        if (BuildConfig.DEBUG) return
        check(context, skippedVersion = skippedVersion, manual = false)
    }

    fun checkNow(context: Context? = null) = check(context, skippedVersion = "", manual = true)

    @Volatile
    private var lastCheckAt = UpdateSchedule.NEVER

    private fun check(context: Context?, skippedVersion: String, manual: Boolean) {
        if (job?.isActive == true) return
        val now = System.currentTimeMillis()
        if (!UpdateSchedule.isDue(manual, lastCheckAt, now)) return
        lastCheckAt = now
        job = scope.launch {
            when (val result = UpdateChecker.check()) {
                is UpdateChecker.CheckResult.Available -> {
                    val info = result.info
                    if (info.version == skippedVersion) {
                        Diag.record("update", "version ${info.version} écartée par l'utilisateur")
                        _state.value = State.Idle
                        return@launch
                    }
                    Diag.record("update", "version ${info.version} disponible")
                    _state.value = State.Available(info.version, info.notes, info.apkUrl)
                    // A new version is only worth a dialog when installing it costs
                    // a tap: on an unmetered network the APK is fetched right away,
                    // so the user finds "Installer" waiting next time.
                    if (!manual && context != null) preparerEnSilence(context, info.version, info.apkUrl)
                }

                UpdateChecker.CheckResult.UpToDate -> {
                    Diag.record("update", "aucune version plus récente que ${BuildConfig.VERSION_NAME}")
                    _state.value = if (manual) State.UpToDate(BuildConfig.VERSION_NAME) else State.Idle
                }

                is UpdateChecker.CheckResult.Unreachable -> {
                    Diag.record("update", UpdateChecker.logLine(result))
                    // A failed check must not hold the hourly slot.
                    lastCheckAt = UpdateSchedule.NEVER
                    _state.value = if (manual) State.Failed(result.reason) else State.Idle
                }
            }
        }
    }

    /**
     * Downloads the update without being asked, but only over Wi-Fi / unmetered
     * networks: pulling 55 MB from mobile data unannounced is not a favour.
     * Everything else stays the explicit "Télécharger" button.
     */
    private suspend fun preparerEnSilence(context: Context, version: String, url: String) {
        val dejaLa = fichierValide(context, version)
        if (dejaLa != null) {
            _state.value = State.ReadyToInstall(version, dejaLa)
            return
        }
        if (!reseauNonFacture(context)) {
            Diag.record("update", "téléchargement différé (réseau facturé) v$version")
            return
        }
        _state.value = State.Downloading(version, 0f)
        val result = runCatching { fetch(context, version, url) }
        _state.value = result.fold(
            onSuccess = { State.ReadyToInstall(version, it) },
            onFailure = {
                Diag.record("update", it)
                State.Available(version, "", url)
            },
        )
    }

    private fun reseauNonFacture(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val reseau = cm.activeNetwork ?: return false
        val capacites = cm.getNetworkCapabilities(reseau) ?: return false
        capacites.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capacites.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    /** The APK already on disk for [version], if it is a real one. */
    private fun fichierValide(context: Context, version: String): File? {
        val fichier = File(File(context.filesDir, DIR), "notes-de-frais-v$version.apk")
        if (!fichier.exists() || fichier.length() == 0L) return null
        val entete = ByteArray(2)
        runCatching { fichier.inputStream().use { it.read(entete) } }.getOrNull()
        return if (String(entete) == "PK") fichier else null
    }

    fun download(context: Context) {
        val available = _state.value as? State.Available ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Downloading(available.version, 0f)
            val result = runCatching { fetch(context, available.version, available.apkUrl) }
            _state.value = result.fold(
                onSuccess = { State.ReadyToInstall(available.version, it) },
                onFailure = {
                    Diag.record("update", it)
                    State.Failed(it.message)
                },
            )
        }
    }

    private suspend fun fetch(context: Context, version: String, url: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "notes-de-frais-v$version.apk")

            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Corps vide")
                val total = body.contentLength()
                var written = 0L

                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = input.read(buffer)
                        while (read != -1) {
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                _state.value = State.Downloading(
                                    version,
                                    (written.toFloat() / total).coerceIn(0f, 1f),
                                )
                            }
                            read = input.read(buffer)
                        }
                    }
                }
            }
            if (target.length() == 0L) error("Téléchargement vide")
            // A file that does not start with "PK" is a web page, not an APK.
            val entete = ByteArray(2)
            target.inputStream().use { it.read(entete) }
            if (String(entete) != "PK") {
                target.delete()
                error("fichier téléchargé invalide (ce n'est pas un APK)")
            }
            target
        }

    fun install(context: Context) {
        val ready = _state.value as? State.ReadyToInstall ?: return
        AutoUpdater.install(context, ready.file)
    }

    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    fun openInstallSettings(context: Context) = AutoUpdater.openInstallSettings(context)

    fun dismiss() {
        _state.value = State.Idle
    }
}
