package com.hoststorm.livestorm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

internal class YoutubeLiveApi {

    data class ChannelInfo(
        val id: String,
        val title: String
    )

    data class BroadcastInfo(
        val id: String,
        val title: String,
        val lifecycleStatus: String,
        val privacyStatus: String,
        val scheduledStartTime: String,
        val boundStreamId: String?
    )

    data class StreamProfile(
        val resolution: String,
        val frameRate: String
    )

    data class PreparedLive(
        val broadcastId: String,
        val title: String,
        val server: String,
        val streamKey: String,
        val watchUrl: String,
        val streamResolution: String,
        val streamFrameRate: String
    )

    class ApiException(
        val httpCode: Int,
        val reason: String?,
        message: String
    ) : IOException(message)

    fun loadChannel(accessToken: String): ChannelInfo {
        val response = request(
            method = "GET",
            endpoint = "channels",
            token = accessToken,
            query = mapOf(
                "part" to "id,snippet",
                "mine" to "true",
                "maxResults" to "1"
            )
        )
        val item = response.optJSONArray("items")?.optJSONObject(0)
            ?: throw ApiException(404, "channelNotFound", "Nenhum canal do YouTube foi encontrado nesta conta.")
        return ChannelInfo(
            id = item.optString("id"),
            title = item.optJSONObject("snippet")?.optString("title").orEmpty()
        )
    }

    fun listBroadcasts(accessToken: String): List<BroadcastInfo> {
        val response = request(
            method = "GET",
            endpoint = "liveBroadcasts",
            token = accessToken,
            query = mapOf(
                "part" to "id,snippet,status,contentDetails",
                // A API aceita apenas um filtro principal por chamada.
                // mine=true já limita a resposta às transmissões da conta autorizada.
                "mine" to "true",
                "maxResults" to "50"
            )
        )
        val items = response.optJSONArray("items") ?: JSONArray()
        val result = mutableListOf<BroadcastInfo>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val status = item.optJSONObject("status")
            val lifecycle = status?.optString("lifeCycleStatus").orEmpty()
            if (lifecycle in setOf("complete", "revoked")) continue
            val snippet = item.optJSONObject("snippet")
            val content = item.optJSONObject("contentDetails")
            result += BroadcastInfo(
                id = item.optString("id"),
                title = snippet?.optString("title").orEmpty().ifBlank { "Live sem título" },
                lifecycleStatus = lifecycle.ifBlank { "upcoming" },
                privacyStatus = status?.optString("privacyStatus").orEmpty(),
                scheduledStartTime = snippet?.optString("scheduledStartTime").orEmpty(),
                boundStreamId = content?.optString("boundStreamId")
                    ?.takeIf { it.isNotBlank() }
            )
        }
        return result.sortedWith(
            compareBy<BroadcastInfo> {
                when (it.lifecycleStatus) {
                    "live" -> 0
                    "testing" -> 1
                    "ready" -> 2
                    "created" -> 3
                    else -> 4
                }
            }.thenBy { it.scheduledStartTime }
        )
    }

    fun prepareExistingBroadcast(
        accessToken: String,
        broadcast: BroadcastInfo,
        profile: StreamProfile
    ): PreparedLive {
        val stream = broadcast.boundStreamId?.let { loadStream(accessToken, it) }
            ?: createStream(accessToken, "${broadcast.title} • Live Storm", profile).also {
                bind(accessToken, broadcast.id, it.id)
            }
        return prepared(broadcast.id, broadcast.title, stream)
    }

    fun createLive(
        accessToken: String,
        title: String,
        description: String,
        privacyStatus: String,
        madeForKids: Boolean,
        profile: StreamProfile
    ): PreparedLive {
        val broadcast = createBroadcast(
            accessToken = accessToken,
            title = title,
            description = description,
            privacyStatus = privacyStatus,
            madeForKids = madeForKids
        )
        val stream = createStream(accessToken, "$title • Live Storm", profile)
        bind(accessToken, broadcast.id, stream.id)
        return prepared(broadcast.id, broadcast.title, stream)
    }

    private data class StreamInfo(
        val id: String,
        val streamName: String,
        val rtmpsAddress: String,
        val resolution: String,
        val frameRate: String
    )

    private fun createBroadcast(
        accessToken: String,
        title: String,
        description: String,
        privacyStatus: String,
        madeForKids: Boolean
    ): BroadcastInfo {
        val body = JSONObject()
            .put(
                "snippet",
                JSONObject()
                    .put("title", title.take(100))
                    .put("description", description.take(5000))
                    .put("scheduledStartTime", Instant.now().plusSeconds(60).toString())
            )
            .put(
                "status",
                JSONObject()
                    .put("privacyStatus", privacyStatus)
                    .put("selfDeclaredMadeForKids", madeForKids)
            )
            .put(
                "contentDetails",
                JSONObject()
                    .put("enableAutoStart", true)
                    .put("enableAutoStop", true)
                    .put("enableDvr", true)
                    .put("recordFromStart", true)
                    .put("enableEmbed", true)
            )

        val response = request(
            method = "POST",
            endpoint = "liveBroadcasts",
            token = accessToken,
            query = mapOf("part" to "id,snippet,status,contentDetails"),
            body = body
        )
        val snippet = response.optJSONObject("snippet")
        val status = response.optJSONObject("status")
        return BroadcastInfo(
            id = response.optString("id"),
            title = snippet?.optString("title").orEmpty().ifBlank { title },
            lifecycleStatus = status?.optString("lifeCycleStatus").orEmpty(),
            privacyStatus = status?.optString("privacyStatus").orEmpty(),
            scheduledStartTime = snippet?.optString("scheduledStartTime").orEmpty(),
            boundStreamId = null
        )
    }

    private fun createStream(
        accessToken: String,
        title: String,
        profile: StreamProfile
    ): StreamInfo {
        val body = JSONObject()
            .put("snippet", JSONObject().put("title", title.take(100)))
            .put(
                "cdn",
                JSONObject()
                    .put("ingestionType", "rtmp")
                    .put("resolution", profile.resolution)
                    .put("frameRate", profile.frameRate)
            )
            .put("contentDetails", JSONObject().put("isReusable", true))

        val response = request(
            method = "POST",
            endpoint = "liveStreams",
            token = accessToken,
            query = mapOf("part" to "id,snippet,cdn,contentDetails,status"),
            body = body
        )
        return parseStream(response)
    }

    private fun loadStream(accessToken: String, streamId: String): StreamInfo {
        val response = request(
            method = "GET",
            endpoint = "liveStreams",
            token = accessToken,
            query = mapOf(
                "part" to "id,snippet,cdn,status",
                "id" to streamId
            )
        )
        val item = response.optJSONArray("items")?.optJSONObject(0)
            ?: throw ApiException(404, "liveStreamNotFound", "A chave vinculada a esta live não foi encontrada.")
        return parseStream(item)
    }

    private fun bind(accessToken: String, broadcastId: String, streamId: String) {
        request(
            method = "POST",
            endpoint = "liveBroadcasts/bind",
            token = accessToken,
            query = mapOf(
                "id" to broadcastId,
                "streamId" to streamId,
                "part" to "id,contentDetails,status"
            )
        )
    }

    private fun parseStream(item: JSONObject): StreamInfo {
        val cdn = item.optJSONObject("cdn")
        val ingestion = cdn?.optJSONObject("ingestionInfo")
        val key = ingestion?.optString("streamName").orEmpty()
        val server = ingestion?.optString("rtmpsIngestionAddress").orEmpty()
            .ifBlank { DEFAULT_YOUTUBE_RTMPS }
        if (key.isBlank()) {
            throw ApiException(
                422,
                "missingIngestionInfo",
                "O YouTube criou a live, mas ainda não retornou a chave RTMPS."
            )
        }
        return StreamInfo(
            id = item.optString("id"),
            streamName = key,
            rtmpsAddress = server.trimEnd('/'),
            resolution = cdn?.optString("resolution").orEmpty(),
            frameRate = cdn?.optString("frameRate").orEmpty()
        )
    }

    private fun prepared(
        broadcastId: String,
        title: String,
        stream: StreamInfo
    ): PreparedLive {
        return PreparedLive(
            broadcastId = broadcastId,
            title = title,
            server = stream.rtmpsAddress,
            streamKey = stream.streamName,
            watchUrl = "https://www.youtube.com/watch?v=$broadcastId",
            streamResolution = stream.resolution,
            streamFrameRate = stream.frameRate
        )
    }

    private fun request(
        method: String,
        endpoint: String,
        token: String,
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null
    ): JSONObject {
        val encodedQuery = query.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val url = URL(
            buildString {
                append(API_BASE)
                append(endpoint)
                if (encodedQuery.isNotBlank()) {
                    append('?')
                    append(encodedQuery)
                }
            }
        )
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw parseError(status, text)
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(status: Int, payload: String): ApiException {
        val root = runCatching { JSONObject(payload) }.getOrNull()
        val error = root?.optJSONObject("error")
        val message = error?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: "O YouTube recusou a solicitação."
        val reason = error
            ?.optJSONArray("errors")
            ?.optJSONObject(0)
            ?.optString("reason")
            ?.takeIf { it.isNotBlank() }
        val translated = when (reason) {
            "liveStreamingNotEnabled" ->
                "Este canal ainda não está habilitado para transmissões ao vivo."
            "livePermissionBlocked" ->
                "O YouTube bloqueou temporariamente a criação de lives neste canal."
            "insufficientLivePermissions", "insufficientPermissions" ->
                "A conta não concedeu todas as permissões necessárias para administrar lives."
            "dailyLimitExceeded", "quotaExceeded" ->
                "A cota diária da API do YouTube foi atingida."
            else -> message
        }
        return ApiException(status, reason, translated)
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private companion object {
        const val API_BASE = "https://www.googleapis.com/youtube/v3/"
        const val DEFAULT_YOUTUBE_RTMPS = "rtmps://a.rtmps.youtube.com:443/live2"
    }
}
