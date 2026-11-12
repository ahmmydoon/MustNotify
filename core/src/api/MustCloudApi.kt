package com.salahoard.mustnotify.api

import com.salahoard.mustnotify.domain.InverterSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Minimal client for the vendor cloud. Observed requirements (from manual testing):
 * - readings: only the `i18n` header plus devaddr/devcode/pn/sn; no login needed
 * - device list: `i18n` + `token` headers
 * - login: password is sent as lowercase SHA-1 hex
 * No `sign`, `auth` or JWT is needed. If the vendor starts enforcing auth, [readDevice] will return
 * [ApiResult.AuthRequired] and you can pass a [token].
 *
 * No logging plugin is installed on purpose: requests contain credentials and device identifiers.
 */
class MustCloudApi internal constructor(
    private val client: HttpClient = defaultHttpClient(),
    private val baseUrl: String = "https://api.valueclouds.com",
) : ReadingsSource {
    suspend fun login(account: String, passwordSha1Hex: String): ApiResult<LoginData> {
        val body = buildJsonObject {
            put("project", PROJECT)
            put("account", normalizeAccount(account))
            put("password", passwordSha1Hex)
        }.toString()
        return execute(
            request = {
                client.post("$baseUrl/ppr/app/login/pub/login") {
                    header("i18n", LANGUAGE)
                    header("project", PROJECT)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            },
            decode = { json.decodeFromJsonElement(LoginData.serializer(), it) },
        )
    }

    suspend fun listDevices(token: String, pageSize: Int = 20): ApiResult<List<DeviceItem>> = execute(
        request = {
            client.get("$baseUrl/dev/api/auth/app/dev/V2/listTDeviceInfo") {
                header("i18n", LANGUAGE)
                header("token", token)
                parameter("page", 1)
                parameter("pageSize", pageSize)
            }
        },
        decode = { json.decodeFromJsonElement(DeviceListData.serializer(), it).items },
    )

    override suspend fun readDevice(device: DeviceRef, token: String?): ApiResult<InverterSnapshot> = execute(
        request = {
            client.get("$baseUrl/ppe/api/auth/app/queryDeviceOneDataxxx") {
                header("i18n", LANGUAGE)
                if (token != null) header("token", token)
                parameter("devaddr", device.devaddr)
                parameter("devcode", device.devcode)
                parameter("pn", device.pn)
                parameter("sn", device.sn)
            }
        },
        decode = { data ->
            val items = json.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ReadingItem.serializer()),
                data as? JsonArray ?: throw IllegalArgumentException("data is not an array"),
            )
            InverterSnapshot(
                raw = items.mapNotNull { item -> item.value?.let { item.id to it } }.toMap(),
                deviceTime = items.firstNotNullOfOrNull { it.date },
            )
        },
    )

    private suspend fun <T> execute(
        request: suspend () -> HttpResponse,
        decode: (JsonElement) -> T,
    ): ApiResult<T> {
        val text = try {
            request().bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.NetworkError(e)
        }
        return try {
            val root = json.parseToJsonElement(text) as? JsonObject
                ?: return ApiResult.BadResponse("response is not a JSON object")
            val code = (root["code"] as? JsonPrimitive)?.intOrNull
                ?: return ApiResult.BadResponse("missing code")
            val message = (root["message"] as? JsonPrimitive)?.contentOrNull
            when (code) {
                0 -> ApiResult.Ok(decode(root["data"] ?: JsonNull))
                401 -> ApiResult.AuthRequired
                CODE_BAD_CREDENTIALS -> ApiResult.BadCredentials
                else -> ApiResult.ServerError(code, message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliberately not including the body: it may contain identifiers.
            ApiResult.BadResponse("unexpected response format (${e::class.simpleName})")
        }
    }

    companion object {
        private const val PROJECT = "IOT"
        private const val LANGUAGE = "en_US"
        private const val CODE_BAD_CREDENTIALS = 115140

        private val json = Json { ignoreUnknownKeys = true }

        internal fun defaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
                socketTimeoutMillis = 20_000
            }
        }

        /** Phone logins are sent without the leading 0 ("0912345678" -> "912345678"); e-mail logins are untouched. */
        fun normalizeAccount(input: String): String {
            val trimmed = input.trim()
            return if (trimmed.length > 1 && trimmed.startsWith("0") && trimmed.all { it.isDigit() }) trimmed.drop(1) else trimmed
        }
    }
}
