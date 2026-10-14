package dev.opencode.mobile.domain.t3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

object T3Json {
    val parser: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

    fun parseObject(raw: String): JsonObject = parser.parseToJsonElement(raw).jsonObject

    fun environmentDescriptor(raw: String): T3EnvironmentDescriptor {
        val obj = parseObject(raw)
        return T3EnvironmentDescriptor(
            environmentId = obj.string("environmentId").orEmpty(),
            label = obj.string("label").orEmpty(),
            orchestrationProtocolVersion = obj.int("orchestrationProtocolVersion"),
            maxFileUploadBytes =
                T3Assets.clampFileBytes(
                    obj.obj("capabilities")?.obj("fileAttachments")?.long("maxUploadBytes"),
                ),
        )
    }

    fun accessToken(raw: String): String {
        val token = parseObject(raw).string("access_token")?.trim().orEmpty()
        if (token.isEmpty()) throw T3ProtocolException("Token exchange did not return access_token")
        return token
    }

    fun wsTicket(raw: String): T3WsTicket {
        val obj = parseObject(raw)
        val ticket = obj.string("ticket")?.trim().orEmpty()
        if (ticket.isEmpty()) throw T3ProtocolException("WebSocket ticket response did not include ticket")
        return T3WsTicket(ticket = ticket, expiresAt = obj.string("expiresAt"))
    }

    fun shellSnapshot(raw: String): T3ShellSnapshot = shellSnapshot(parser.parseToJsonElement(raw))

    fun shellSnapshot(element: JsonElement): T3ShellSnapshot {
        val obj = element.jsonObject
        return T3ShellSnapshot(
            threads = obj.array("threads").mapNotNull { thread(it) },
            projects = obj.array("projects").mapNotNull { project(it) },
            snapshotSequence = obj.long("snapshotSequence") ?: 0L,
            archivedThreads = obj.array("archivedThreads").mapNotNull { thread(it) },
        )
    }

    /**
     * Threads from an archived-shell snapshot. Null when [key] is absent, so a
     * non-snapshot payload cannot wipe a list the active shell already holds.
     */
    fun optionalThreads(element: JsonElement, key: String = "threads"): List<T3Thread>? {
        val obj = element as? JsonObject ?: return null
        if (!obj.containsKey(key)) return null
        return obj.array(key).mapNotNull { thread(it) }
    }

    fun threadSnapshot(raw: String): T3ThreadSnapshot = threadSnapshot(parser.parseToJsonElement(raw))

    fun threadSnapshot(element: JsonElement): T3ThreadSnapshot {
        val obj = element.jsonObject
        val projection = obj.obj("projection") ?: obj
        val messages =
            projection.array("messages").mapNotNull { item ->
                conversationMessage(item)
            }
        return T3ThreadSnapshot(
            messages = messages,
            snapshotSequence = obj.long("snapshotSequence") ?: projection.long("snapshotSequence") ?: 0L,
            turnItems = turnItemsOf(projection),
            runtimeRequests = projection.array("runtimeRequests").mapNotNull(::runtimeRequest),
            runs = projection.array("runs").mapNotNull(::run),
            plans = projection.array("plans").mapNotNull(::plan),
            thread = projection.obj("thread")?.let(::thread),
        )
    }

    /**
     * One `todo_list` plan. `proposed_plan` and a row without an id are skipped.
     * A `plan.updated` payload is the same object.
     */
    fun plan(element: JsonElement): T3Plan? {
        val obj = element as? JsonObject ?: return null
        if (obj.string("kind")?.trim()?.lowercase() != "todo_list") return null
        val id = obj.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        return T3Plan(
            id = id,
            runId = obj.string("runId")?.trim()?.takeIf { it.isNotEmpty() },
            steps = obj.array("steps").mapNotNull(::planStep),
        )
    }

    private fun planStep(element: JsonElement): T3PlanStep? {
        val obj = element as? JsonObject ?: return null
        val text = obj.string("text")?.trim().orEmpty()
        if (text.isEmpty()) return null
        return T3PlanStep(
            text = text,
            status = obj.string("status")?.trim().orEmpty().ifEmpty { "pending" },
        )
    }

    private fun runtimeRequest(element: JsonElement): T3RuntimeRequest? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        return T3RuntimeRequest(
            id = id,
            kind = obj.string("kind")?.trim().orEmpty(),
            status = obj.string("status")?.trim().orEmpty().ifEmpty { "pending" },
        )
    }

    fun userInputQuestion(element: JsonElement): T3UserInputQuestion? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id")?.trim().orEmpty()
        val question = obj.string("question")?.trim().orEmpty()
        if (id.isEmpty() || question.isEmpty()) return null
        return T3UserInputQuestion(
            id = id,
            header = obj.string("header")?.trim().orEmpty(),
            question = question,
            options =
                obj.array("options").mapNotNull { option ->
                    val optionObj = option as? JsonObject ?: return@mapNotNull null
                    val label = optionObj.string("label")?.trim().orEmpty()
                    if (label.isEmpty()) return@mapNotNull null
                    T3UserInputOption(
                        label = label,
                        description = optionObj.string("description")?.trim().orEmpty(),
                        value = stringValue(optionObj["value"]),
                    )
                },
            multiSelect = obj.boolean("multiSelect") ?: false,
            allowCustomAnswer = obj.boolean("allowCustomAnswer") ?: true,
        )
    }

    /**
     * A visible row wraps the turn item. A raw turn item carries `type` at the top level.
     * One bad row is skipped so it cannot fail the snapshot.
     */
    fun turnItem(element: JsonElement): T3TurnItem? =
        runCatching {
            val obj = element as? JsonObject ?: return@runCatching null
            parseTurnItem(obj.obj("item") ?: obj)
        }.getOrNull()

    private fun turnItemsOf(projection: JsonObject): List<T3TurnItem> {
        val visible = projection.array("visibleTurnItems")
        val chosen = if (visible.isNotEmpty()) visible else projection.array("turnItems")
        return chosen.mapNotNull(::turnItem)
    }

    private fun parseTurnItem(obj: JsonObject): T3TurnItem? {
        val type = obj.string("type")?.trim().orEmpty().lowercase()
        val id = obj.string("id")?.trim().orEmpty()
        if (type.isEmpty() || id.isEmpty()) return null
        val input = obj["input"]
        return T3TurnItem(
            id = id,
            type = type,
            ordinal = obj.long("ordinal"),
            status = obj.string("status"),
            title = obj.string("title"),
            startedAt = obj.string("startedAt"),
            completedAt = obj.string("completedAt"),
            text = obj.string("text"),
            streaming = obj.boolean("streaming") ?: false,
            messageId = obj.string("messageId")?.trim()?.takeIf { it.isNotEmpty() },
            attachments = obj.array("attachments").mapNotNull(::attachment),
            toolName = obj.string("toolName"),
            stringInput = stringValue(input),
            inputFields = stringFields(input),
            output = stringValue(obj["output"]),
            exitCode = obj.int("exitCode"),
            pattern = obj.string("pattern"),
            patterns = stringList(obj.array("patterns")),
            fileName = obj.string("fileName"),
            prompt = obj.string("prompt"),
            progress = obj.string("progress"),
            result = obj.string("result"),
            childThreadId = obj.string("childThreadId"),
            requestId = obj.string("requestId")?.trim()?.takeIf { it.isNotEmpty() },
            requestKind = obj.string("requestKind")?.trim()?.takeIf { it.isNotEmpty() },
            appName = obj.string("appName")?.trim()?.takeIf { it.isNotEmpty() },
            responseMode = obj.string("responseMode")?.trim()?.takeIf { it.isNotEmpty() },
            questions = obj.array("questions").mapNotNull(::userInputQuestion),
        )
    }

    private fun stringValue(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }

    private fun stringFields(element: JsonElement?): Map<String, String> {
        val obj = element as? JsonObject ?: return emptyMap()
        return buildMap {
            for ((key, value) in obj) {
                val text = stringValue(value) ?: continue
                if (text.isBlank()) continue
                put(key, text)
            }
        }
    }

    private fun stringList(elements: List<JsonElement>): List<String> =
        elements.mapNotNull { element ->
            stringValue(element)
        }

    fun conversationMessage(element: JsonElement): T3ConversationMessage? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        return T3ConversationMessage(
            id = id,
            role = obj.string("role")?.trim().orEmpty(),
            text = obj.string("text").orEmpty(),
            streaming = obj.boolean("streaming") ?: false,
            createdAt = obj.string("createdAt"),
            attachments = obj.array("attachments").mapNotNull(::attachment),
            automatic = obj.hasValue("notification") || obj.hasValue("delegatedCompletion"),
        )
    }

    /** One `OrchestrationV2Run`. A row without an id or user message is skipped. */
    fun run(element: JsonElement): T3Run? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id")?.trim().orEmpty()
        val userMessageId = obj.string("userMessageId")?.trim().orEmpty()
        if (id.isEmpty() || userMessageId.isEmpty()) return null
        return T3Run(
            id = id,
            status = obj.string("status")?.trim().orEmpty(),
            userMessageId = userMessageId,
            ordinal = obj.long("ordinal") ?: 0L,
            queuePosition = obj.long("queuePosition"),
        )
    }

    private fun attachment(element: JsonElement): T3Attachment? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id")?.trim().orEmpty()
        val name = obj.string("name")?.trim().orEmpty()
        if (id.isEmpty() || name.isEmpty()) return null
        return T3Attachment(
            id = id,
            type = obj.string("type")?.trim().orEmpty().ifEmpty { "file" },
            name = name,
            mimeType = obj.string("mimeType")?.trim().orEmpty().ifEmpty { "application/octet-stream" },
            sizeBytes = obj.long("sizeBytes") ?: 0L,
        )
    }

    fun thread(element: JsonElement): T3Thread? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        val model = modelSelection(obj["modelSelection"])
        val pending = obj["pendingRuntimeRequest"] as? JsonObject
        return T3Thread(
            id = id,
            title = obj.string("title").orEmpty(),
            projectId = obj.string("projectId").orEmpty(),
            status = decodeStatus(obj["status"]),
            archivedAt = obj.string("archivedAt"),
            settledAt = obj.string("settledAt"),
            settledOverride = obj.string("settledOverride"),
            deletedAt = obj.string("deletedAt"),
            activeRunId = obj.string("activeRunId"),
            activityRunStatus = obj.string("activityRunStatus"),
            providerInstanceId = obj.string("providerInstanceId") ?: model?.instanceId,
            runtimeMode = obj.string("runtimeMode"),
            interactionMode = obj.string("interactionMode"),
            updatedAt = obj.string("updatedAt"),
            worktreePath = obj.string("worktreePath"),
            modelSelection = model,
            pendingRequestId = pending?.string("id"),
            pendingRequestKind = pending?.string("kind"),
            createdAt = obj.string("createdAt"),
            snoozedUntil = obj.string("snoozedUntil"),
        )
    }

    /** `server.getConfig` success value: the provider catalogue and the global default selection. */
    fun serverConfig(element: JsonElement): T3ServerConfig {
        val obj = element as? JsonObject ?: throw T3ProtocolException("server.getConfig did not return an object")
        val settings = obj.obj("settings")
        return T3ServerConfig(
            harnesses = obj.array("providers").mapNotNull { runCatching { harness(it) }.getOrNull() },
            defaultModelSelection = modelSelection(settings?.get("defaultModelSelection")),
        )
    }

    /** One `ServerProvider`; an entry without an instance id is skipped. */
    fun harness(element: JsonElement): T3Harness? {
        val obj = element as? JsonObject ?: return null
        val instanceId = obj.string("instanceId")?.trim().orEmpty()
        if (instanceId.isEmpty()) return null
        val driver = obj.string("driver")?.trim().orEmpty()
        val status = obj.string("status")?.trim().orEmpty()
        val authStatus = obj.obj("auth")?.string("status")?.trim()
        val installed = obj.boolean("installed") ?: true
        val available =
            installed &&
                obj.string("availability") != "unavailable" &&
                authStatus != "unauthenticated" &&
                status !in setOf("error", "disabled")
        val runtimeModes =
            (obj["supportedRuntimeModes"] as? JsonArray)?.let { modes ->
                stringList(modes.toList()).filter { it in T3RuntimeModeLabels.allModes }
            }
        return T3Harness(
            instanceId = instanceId,
            displayName =
                obj.string("displayName")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: driver.takeIf { it.isNotEmpty() }?.let(T3ProviderLabels::driverDisplayName)
                    ?: T3ProviderLabels.displayName(instanceId)
                    ?: instanceId,
            driver = driver,
            enabled = obj.boolean("enabled") ?: true,
            available = available,
            status = status,
            supportedRuntimeModes = runtimeModes,
            requiresNewThreadForModelChange = obj.boolean("requiresNewThreadForModelChange") ?: false,
            models = obj.array("models").mapNotNull(::harnessModel),
            skills = obj.array("skills").mapNotNull(::providerSkill),
            workspaceSkills = workspaceSkills(obj.array("workspaceSnapshots")),
        )
    }

    private fun providerSkill(element: JsonElement): T3ProviderSkill? {
        val obj = element as? JsonObject ?: return null
        val name = obj.string("name")?.trim().orEmpty()
        if (name.isEmpty()) return null
        return T3ProviderSkill(
            name = name,
            description = obj.string("description")?.trim()?.takeIf { it.isNotEmpty() },
            displayName = obj.string("displayName")?.trim()?.takeIf { it.isNotEmpty() },
            shortDescription = obj.string("shortDescription")?.trim()?.takeIf { it.isNotEmpty() },
            path = obj.string("path")?.trim().orEmpty(),
            enabled = obj.boolean("enabled") ?: true,
            userInvocable = obj.boolean("userInvocable") ?: true,
        )
    }

    private fun workspaceSkills(elements: List<JsonElement>): Map<String, List<T3ProviderSkill>> {
        val snapshots = LinkedHashMap<String, List<T3ProviderSkill>>()
        for (element in elements) {
            val obj = element as? JsonObject ?: continue
            val cwd = obj.string("cwd")?.trim().orEmpty()
            if (cwd.isEmpty()) continue
            snapshots[cwd] = obj.array("skills").mapNotNull(::providerSkill)
        }
        return snapshots
    }

    private fun harnessModel(element: JsonElement): T3HarnessModel? {
        val obj = element as? JsonObject ?: return null
        val slug = obj.string("slug")?.trim().orEmpty()
        if (slug.isEmpty()) return null
        return T3HarnessModel(
            slug = slug,
            name =
                obj
                    .string("name")
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { slug },
            shortName = obj.string("shortName")?.trim()?.takeIf { it.isNotEmpty() },
            isDefault = obj.boolean("isDefault") ?: false,
            isLegacy = obj.boolean("isLegacy") ?: false,
            isCustom = obj.boolean("isCustom") ?: false,
        )
    }

    fun launchThreadId(value: JsonElement): String {
        val obj = value as? JsonObject
        val id = obj?.string("threadId") ?: obj?.string("id")
        if (id.isNullOrBlank()) throw T3ProtocolException("launchThread did not return a thread id")
        return id
    }

    fun project(element: JsonElement): T3Project? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id")?.trim().orEmpty()
        if (id.isEmpty()) return null
        val model = modelSelection(obj["defaultModelSelection"])
        return T3Project(
            id = id,
            title = obj.string("title").orEmpty(),
            workspaceRoot = obj.string("workspaceRoot").orEmpty(),
            defaultInstanceId = model?.instanceId,
            defaultModel = model?.model,
        )
    }

    private fun modelSelection(element: JsonElement?): T3ModelSelection? {
        val obj = element as? JsonObject ?: return null
        val instanceId =
            obj.string("instanceId")?.trim().orEmpty().ifEmpty {
                obj.string("provider")?.trim().orEmpty()
            }
        if (instanceId.isEmpty()) return null
        return T3ModelSelection(instanceId = instanceId, model = obj.string("model"))
    }

    private fun decodeStatus(element: JsonElement?): String {
        if (element == null || element is JsonNull) return "idle"
        val primitive = (element as? JsonPrimitive)?.contentOrNull
        if (primitive != null) return primitive
        val obj = element as? JsonObject ?: return "idle"
        val type = obj.string("type") ?: obj.string("status")
        return type ?: "running"
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolean(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.toBooleanStrictOrNull() ?: primitive.contentOrNull?.toBoolean()
    }

    private fun JsonObject.hasValue(key: String): Boolean {
        val value = this[key] ?: return false
        return value !is JsonNull
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList().orEmpty()
}
