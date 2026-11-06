package dev.opencode.hexcode.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.opencode.hexcode.firstrun.ModelChoice
import dev.opencode.hexcode.firstrun.ModelTester
import dev.opencode.hexcode.firstrun.PairingConnector
import dev.opencode.hexcode.firstrun.ServerLabel
import dev.opencode.hexcode.firstrun.ServerName
import dev.opencode.hexcode.firstrun.ServerNaming
import dev.opencode.hexcode.firstrun.ServerRouteCheck
import dev.opencode.hexcode.home.ImageInput
import dev.opencode.hexcode.home.TalkLog
import dev.opencode.hexcode.session.DailySessionSetup
import dev.opencode.hexcode.session.EventsApiTransport
import dev.opencode.hexcode.session.OpenCodeTodayApi
import dev.opencode.hexcode.session.TodaySession
import dev.opencode.hexcode.session.TodaySessionDemo
import dev.opencode.mobile.data.local.DataStoreConnectionProfileStore
import dev.opencode.mobile.data.local.EncryptedSecureCredentialStore
import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.data.remote.FilesystemApi
import dev.opencode.mobile.data.remote.ModelsApi
import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.domain.connection.ConnectionProbeService
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectivityDeviceNetworkPath
import dev.opencode.mobile.domain.connection.PairingCodeRedeemService
import dev.opencode.mobile.domain.connection.V2ServerContract
import dev.opencode.mobile.domain.filesystem.DefaultFilesystemRepository
import dev.opencode.mobile.domain.filesystem.DirectoryListing
import dev.opencode.mobile.domain.filesystem.FilesystemDomainAdapter
import dev.opencode.mobile.domain.model.DefaultModelRepository
import dev.opencode.mobile.domain.model.ModelDomainAdapter
import dev.opencode.mobile.domain.project.DefaultProjectRepository
import dev.opencode.mobile.domain.project.ProjectDomainAdapter
import dev.opencode.mobile.network.ActiveConnectionManager
import dev.opencode.mobile.network.KtorClientFactory
import dev.opencode.mobile.network.OpencodeHttpClient
import dev.opencode.mobile.network.OpencodeSseHttpClient
import dev.opencode.mobile.network.ServerConnection
import dev.opencode.voiceengine.NoSpeechException
import dev.opencode.voiceengine.VoiceEngine
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hex Code's objects, built once per process from OpenCode Kit the way OpenCode Mobile's Hilt modules build them.
 * Hex Code talks to one server.
 */
class HexServices(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Outlives screens: the speech model download, saving after pairing. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    val settings = HexSettings(appContext, json)
    val developer = DeveloperSettings(settings)
    private val credentials = EncryptedSecureCredentialStore(appContext)
    val profiles = DataStoreConnectionProfileStore(appContext, json, credentials)
    private val projectAdapter = ProjectDomainAdapter()
    val probing = ConnectionProbeService(json, projectAdapter)
    val connection = ActiveConnectionManager(profiles, probing)
    val httpClient = OpencodeHttpClient(json, connection)
    val projects = DefaultProjectRepository(ProjectsApi(httpClient), projectAdapter, connection)
    val filesystem = DefaultFilesystemRepository(FilesystemApi(httpClient), FilesystemDomainAdapter(), connection)
    val models = DefaultModelRepository(ModelsApi(httpClient, json), ModelDomainAdapter(), connection)
    val modelTester = ModelTester(json, httpClient::get)
    val pairing = PairingConnector(probing, PairingCodeRedeemService(json), OpenApiRouteCheck(json), ApiInfoNaming(json))
    val voice = VoiceEngine(appContext)
    private val speechStarted = AtomicBoolean(false)

    /** Debug builds: how long after launch the speech model counts as ready (`--ei HexCodeSpeechDelay`). */
    var speechHoldMs = 0L

    /** Debug builds: the first load counts as failed (`--ez HexCodeSpeechFail`). */
    var speechFailOnce = false

    /** Debug builds: home runs this many press, hold 1 s, release cycles once it shows (`--ei HexCodePressProbe`). */
    var pressProbe = 0

    /**
     * At process start (app-plan.md, The speech model): loads the speech model, once per process, when it's on the
     * phone or first run is done. Before that, first run's scan step starts the download ([loadSpeech]).
     */
    fun loadSpeechAtLaunch() {
        appScope.launch {
            val installed = voice.store.isInstalled(voice.activeModel.value)
            if (installed || settings.current().firstRunDone) loadSpeech()
        }
    }

    /** Downloads the speech model if needed and loads it, once per process; then warms it up. */
    fun loadSpeech() {
        if (speechStarted.compareAndSet(false, true)) appScope.launch { prepareSpeech() }
    }

    /** Try again, after the model failed to download or load. */
    fun reloadSpeech() {
        appScope.launch { prepareSpeech() }
    }

    /** Loads the model, then turns half a second of silence into text so the first real take is as fast as the rest. */
    private suspend fun prepareSpeech() {
        val started = SystemClock.elapsedRealtime()
        voice.prepare()
        val loaded = SystemClock.elapsedRealtime()
        if (voice.modelState.value != VoiceEngine.ModelState.Ready) {
            Log.w(TalkLog.TAG, "speech model didn't load after ${loaded - started} ms: ${voice.modelState.value}")
            return
        }
        try {
            voice.transcribe(FloatArray(VoiceEngine.SAMPLE_RATE / 2))
        } catch (_: NoSpeechException) {
            // Silence has no text; the engine has run once, which is the point.
        } catch (error: Exception) {
            Log.w(TalkLog.TAG, "speech warm-up failed", error)
        }
        Log.i(TalkLog.TAG, "speech model loaded in ${loaded - started} ms, warmed up in ${SystemClock.elapsedRealtime() - loaded} ms")
    }

    /** Home's fake session until it talks to OpenCode (round 2 of M2); Settings › Developer › Sample session picks it. */
    val sampleSession = MutableStateFlow<TodaySessionDemo?>(null)

    /** The event stream's own client: no read timeout, and its own connection pool. */
    val sseClient = OpencodeSseHttpClient(json, connection)
    val sessions = SessionsApi(httpClient, json)
    private val events = EventsApi(sseClient)
    val networkPath = ConnectivityDeviceNetworkPath(appContext)

    /** Today's session on the paired computer, in [directory] with [model]. Home owns it, in its [scope]. */
    fun todaySession(
        scope: CoroutineScope,
        directory: () -> String,
        model: () -> ModelChoice?,
    ): TodaySession {
        val api = OpenCodeTodayApi(sessions, ready = { connectionLoaded.await() })
        return TodaySession(
            scope = scope,
            setup = DailySessionSetup(api, settings),
            api = api,
            transport = EventsApiTransport({ events }, ready = { connectionLoaded.await() }),
            directory = directory,
            model = model,
            summaryStore = settings,
        )
    }

    /** Debug builds: the model counts as unable to see images (`--ez HexCodeNoVision true`). */
    var noVision = false

    /**
     * Whether [model] can see images (app-plan.md, Attach): `capabilities.input` from `GET /api/model`, read into
     * `supportsImageInput` by the kit. Null for no model, or one the server doesn't list, which counts as yes.
     */
    suspend fun imageInput(
        directory: String?,
        model: ModelChoice?,
        name: String?,
    ): ImageInput? {
        model ?: return null
        connectionLoaded.await()
        val option = models.listModelOptions(directory?.ifEmpty { null }).option(model.providerId, model.modelId)
        if (noVision) return ImageInput(name ?: option?.modelLabel ?: model.modelId, sees = false)
        option ?: return null
        return ImageInput(name ?: option.modelLabel, option.supportsImageInput)
    }

    /** Folder listings Browse folders has seen this session, shared by first run and Settings. */
    val folderListings = ConcurrentHashMap<String, DirectoryListing>()

    init {
        // Models of engines Hex Code no longer ships (the transcribe.cpp GGUF files) only take up space.
        appScope.launch(Dispatchers.IO) { voice.store.removeUnlisted(voice.models) }
    }

    /** The paired computer's name and OpenCode version, for Settings. */
    suspend fun serverLabel(): ServerLabel? {
        connectionLoaded.await()
        val profile = profiles.activeProfile() ?: return null
        // The profile keeps the computer's hostname; its friendly name is worked out from it each time.
        val hostname = profile.name.takeIf { it.isNotBlank() }
        return ServerLabel(
            name = ServerName.of(hostname, profile.serverUrls.firstOrNull() ?: profile.baseUrl),
            hostname = hostname?.lowercase()?.removeSuffix(".local"),
            version = connection.currentServerVersion(),
        )
    }

    /**
     * The paired computer's address as the phone reaches it (`192.168.1.20`, `mbp.local`), for links to `localhost` on
     * it; null before pairing.
     */
    suspend fun computerAddress(): String? {
        connectionLoaded.await()
        val url = connection.current()?.baseUrl ?: return null
        return runCatching { java.net.URI(url).host }.getOrNull()?.removeSurrounding("[", "]")
    }

    /** Completes once the saved server is bound (or there is none), so calls made at startup reach it. */
    val connectionLoaded = CompletableDeferred<Unit>()

    /** Binds the saved server, if there is one. */
    suspend fun loadConnection() {
        try {
            connection.ensureLoaded()
            httpClient.rebuild()
            sseClient.rebuild()
        } finally {
            connectionLoaded.complete(Unit)
        }
    }

    /**
     * Saves a server that just passed pairing and makes every API call use it. Hex Code keeps one server: pairing
     * again replaces the saved one, its password and addresses.
     */
    suspend fun useServer(profile: ConnectionProfile) {
        val existing = profiles.activeProfile() ?: profiles.listProfiles().firstOrNull()
        profiles.saveProfile(profile.copy(id = existing?.id ?: profile.id, name = existing?.name ?: profile.name))
        connection.reloadFromStore()
        httpClient.rebuild()
        sseClient.rebuild()
    }
}

/** Reads `hostname` from `/api/info`. */
class ApiInfoNaming(
    private val json: Json,
) : ServerNaming {
    override suspend fun name(connection: ServerConnection): String? {
        val client = KtorClientFactory.create(connection, json, probe = true)
        try {
            val info = client.get(V2ServerContract.INFO_PATH).body<JsonObject>()
            return (info["hostname"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        } finally {
            client.close()
        }
    }
}

/** Checks the server's API contract (`/openapi.json`) for the routes Hex Code builds on. */
class OpenApiRouteCheck(
    private val json: Json,
) : ServerRouteCheck {
    override suspend fun missingRoutes(connection: ServerConnection): List<String> {
        val client = KtorClientFactory.create(connection, json, probe = true)
        try {
            val document = client.get("openapi.json").body<JsonObject>()
            val paths = document["paths"] as? JsonObject ?: return REQUIRED_ROUTES
            return REQUIRED_ROUTES.filterNot(paths::containsKey)
        } finally {
            client.close()
        }
    }

    companion object {
        /** The model test, and the status summary and Ask on the side the main screen builds on. */
        val REQUIRED_ROUTES =
            listOf(
                "/api/experimental/generate/text",
                "/api/session/{sessionID}/generate",
            )
    }
}
