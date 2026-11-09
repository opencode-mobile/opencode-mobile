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
import dev.opencode.hexcode.home.BrowserData
import dev.opencode.hexcode.home.ImageInput
import dev.opencode.hexcode.home.TalkLog
import dev.opencode.hexcode.home.UploadRoute
import dev.opencode.hexcode.session.DailySessionSetup
import dev.opencode.hexcode.session.EventsApiTransport
import dev.opencode.hexcode.session.OpenCodeTodayApi
import dev.opencode.hexcode.session.TodaySession
import dev.opencode.hexcode.session.TodaySessionDemo
import dev.opencode.mobile.data.local.DataStoreConnectionProfileStore
import dev.opencode.mobile.data.local.EncryptedSecureCredentialStore
import dev.opencode.mobile.data.remote.AttachmentUploadApi
import dev.opencode.mobile.data.remote.EventsApi
import dev.opencode.mobile.data.remote.FilesystemApi
import dev.opencode.mobile.data.remote.ModelsApi
import dev.opencode.mobile.data.remote.ProjectsApi
import dev.opencode.mobile.data.remote.SessionsApi
import dev.opencode.mobile.domain.connection.ConnectionProbeService
import dev.opencode.mobile.domain.connection.ConnectionProfile
import dev.opencode.mobile.domain.connection.ConnectionProfileIdentity
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
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /** Debug builds: a WAV every take plays as the microphone (`--es HexCodeTakeAudio`). */
    var takeAudio: String? = null

    /** Debug builds: home runs one locked take of [takeAudio] once speech is ready (`--ez HexCodeTakeProbe`). */
    var takeProbe = false

    /**
     * The speech model's first load in this process (app-plan.md, Dictation › Load): about 1 s after the first frame
     * ([DeferredLoad.afterFirstFrame], from the activity), or at once when the talk button is pressed before then
     * ([DeferredLoad.now]), so it doesn't compete with start-up.
     */
    val speechLaunch = DeferredLoad(appScope, ColdStart.SPEECH_DELAY_MS, ::loadSpeechAtLaunch)

    /**
     * Loads the speech model, once per process, when it's on the phone or first run is done ([speechLaunch] calls
     * it). Before that, first run's scan step starts the download ([loadSpeech]).
     */
    private fun loadSpeechAtLaunch() {
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
            beforeSetup = ::beforeSetup,
            setup = DailySessionSetup(api, settings),
            api = api,
            transport = EventsApiTransport({ events }, ready = { connectionLoaded.await() }),
            directory = directory,
            model = model,
            summaryStore = settings,
            askStore = settings,
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

    private val uploadApi = AttachmentUploadApi(httpClient)

    /** `paths.tmp` per server address, once read; addresses where an upload found the route changed. */
    private val uploadTmp = ConcurrentHashMap<String, String>()
    private val noUploads = ConcurrentHashMap.newKeySet<String>()

    /**
     * Where Files uploads go (app-plan.md, Attach files › Upload): `paths.tmp`, read with the upload route from the
     * server's API contract by the kit once per address, and the kit's streamed write.
     */
    val uploads =
        object : UploadRoute {
            override suspend fun tmp(): String? {
                connectionLoaded.await()
                val server = connection.current() ?: return null
                val key = server.baseUrl
                if (key in noUploads) return null
                uploadTmp[key]?.let { return it }
                val features = probing.probeV2Features(server)
                val tmp = features.attachmentUploadTmp ?: return null
                return tmp.also { uploadTmp[key] = it }
            }

            override fun changed() {
                connection.current()?.baseUrl?.let { noUploads += it }
            }

            override suspend fun write(
                serverPath: String,
                file: java.io.File,
                directory: String,
                onProgress: (sent: Long, total: Long) -> Unit,
            ): Pair<Int, String?> {
                connectionLoaded.await()
                val response = uploadApi.write(serverPath, file, directory, onProgress)
                return response.status to response.path
            }
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
            // The version setup last read from `/api/info`, else the probe's.
            version = settings.current().serverVersion ?: connection.currentServerVersion(),
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

    /**
     * At the start of every setup (app-plan.md, Settings and finish (M6) › Server): the version is read from
     * `GET /api/info` and saved, and a sign-in more than a week old is renewed. Neither fails setup.
     */
    private suspend fun beforeSetup() {
        connectionLoaded.await()
        coroutineScope {
            launch { runCatching { refreshVersion() } }
            signIn.renewIfDue()
        }
    }

    private suspend fun refreshVersion() {
        val response = httpClient.get().get(V2ServerContract.INFO_PATH)
        if (!response.status.isSuccess()) return
        val version = (response.body<JsonObject>()["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (version != settings.current().serverVersion) settings.setServerVersion(version)
    }

    /** The pairing sign-in's silent renewal, with the current credentials. */
    val signIn =
        SignInRenewal(
            api =
                object : SignInApi {
                    override suspend fun pairCode(): String {
                        val response = httpClient.get().post("api/pair")
                        check(response.status.isSuccess()) { "POST /api/pair: HTTP ${response.status.value}" }
                        return (response.body<JsonObject>()["code"] as? JsonPrimitive)?.content ?: error("No pairing code")
                    }

                    override suspend fun redeem(code: String): String {
                        val response =
                            httpClient.get().get("auth/connect/${code.encodeURLPath()}") {
                                // text/html would set a cookie and redirect instead.
                                headers[HttpHeaders.Accept] = "application/json"
                            }
                        check(response.status.isSuccess()) { "GET /auth/connect: HTTP ${response.status.value}" }
                        return (response.body<JsonObject>()["token"] as? JsonPrimitive)?.content ?: error("No token")
                    }
                },
            store =
                object : SignInStore {
                    override suspend fun signedInAt() = settings.signedInAt()

                    override suspend fun token() = profiles.activeProfile()?.password?.takeIf { it.isNotEmpty() }

                    override suspend fun saveToken(
                        token: String,
                        at: Long,
                    ) {
                        val profile = profiles.activeProfile() ?: return
                        profiles.saveProfile(profile.copy(password = token))
                        connection.reloadFromStore()
                        httpClient.rebuild()
                        sseClient.rebuild()
                        settings.setSignedInAt(at)
                    }
                },
        )

    /** Changes each time the computer is forgotten or switched, so first run and home start afresh. */
    val setupGeneration = MutableStateFlow(0)

    /** After Pair again switched computers: first run goes on at its project page on this one. */
    var resumeFirstRunAt: ServerLabel? = null

    private val forgetting =
        ComputerForgetting(
            profiles,
            credentials,
            settings,
            disconnect = {
                connection.disconnect()
                httpClient.rebuild()
                sseClient.rebuild()
            },
            clearBrowser = { withContext(Dispatchers.Main) { BrowserData.clear(appContext) } },
        )

    /**
     * Forget this computer: the profile and its password, the setup, the saved card, the Ask history, the last date and
     * id, and the browser's website data. First run starts at Welcome.
     */
    suspend fun forget() {
        forgetting.forget()
        folderListings.clear()
        setupGeneration.value += 1
    }

    /** The saved computer is the one [result] paired with (Pair again). */
    suspend fun isSameComputer(result: dev.opencode.hexcode.firstrun.ConnectResult.Connected): Boolean {
        val saved = profiles.activeProfile() ?: profiles.listProfiles().firstOrNull() ?: return false
        return PairAgain.isSameComputer(saved.name, result.hostname, saved.serverUrls + saved.baseUrl, result.profile.serverUrls)
    }

    /** Pair again with another computer, after "Switch to …?": the old one goes, and first run goes on at Project. */
    suspend fun switchServer(
        result: dev.opencode.hexcode.firstrun.ConnectResult.Connected,
        home: suspend () -> String?,
    ) {
        forgetting.switchTo {
            useServer(result.profile)
            settings.setServer(home = home(), version = result.version)
            settings.setSignedInAt(System.currentTimeMillis())
        }
        folderListings.clear()
        resumeFirstRunAt = ServerLabel(result.name, result.hostname?.lowercase()?.removeSuffix(".local"), result.version)
        setupGeneration.value += 1
    }

    /** Server details: every address from the pairing code, the one in use first. */
    suspend fun serverAddresses(): ServerAddresses {
        connectionLoaded.await()
        val profile = profiles.activeProfile() ?: return ServerAddresses(emptyList(), null)
        val inUse = (connection.current()?.baseUrl ?: profile.baseUrl).let(ConnectionProfileIdentity::normalizeBaseUrl)
        val all = (listOf(inUse) + profile.serverUrls.map(ConnectionProfileIdentity::normalizeBaseUrl)).distinct()
        return ServerAddresses(all.map { it.trimEnd('/') }, inUse.trimEnd('/'))
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

/** Server details' addresses (URLs without the trailing slash), and the one in use. */
data class ServerAddresses(
    val all: List<String>,
    val inUse: String?,
)

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
