package com.valhalla.thor.ext.antivirus.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.Serializable

@Serializable
data class ThreatReport(
    val maliciousCount: Int,
    val suspiciousCount: Int,
    val harmlessCount: Int,
    val undetectedCount: Int,
    val classification: String
)

class VirusTotalClient(private val apiKey: String) {

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
        defaultRequest {
            url("https://www.virustotal.com/api/v3/")
            header("x-apikey", apiKey)
        }
    }

    /**
     * Looks up file threat profile on VirusTotal v3.
     */
    suspend fun lookupFileHash(sha256Hex: String): ThreatReport? {
        return try {
            val response = client.get("files/$sha256Hex")
            if (response.status.value == 200) {
                val jsonElement = Json.parseToJsonElement(response.bodyAsText())
                val lastAnalysisStats = jsonElement.jsonObject["data"]
                    ?.jsonObject?.get("attributes")
                    ?.jsonObject?.get("last_analysis_stats")
                    ?.jsonObject

                if (lastAnalysisStats != null) {
                    val malicious = lastAnalysisStats["malicious"]?.jsonPrimitive?.int ?: 0
                    val suspicious = lastAnalysisStats["suspicious"]?.jsonPrimitive?.int ?: 0
                    val harmless = lastAnalysisStats["harmless"]?.jsonPrimitive?.int ?: 0
                    val undetected = lastAnalysisStats["undetected"]?.jsonPrimitive?.int ?: 0
                    
                    val classification = when {
                        malicious > 3 -> "MALICIOUS"
                        malicious in 1..3 || suspicious > 2 -> "SUSPICIOUS"
                        else -> "CLEAN"
                    }

                    ThreatReport(
                        maliciousCount = malicious,
                        suspiciousCount = suspicious,
                        harmlessCount = harmless,
                        undetectedCount = undetected,
                        classification = classification
                    )
                } else {
                    null
                }
            } else if (response.status.value == 404) {
                // Not found on VirusTotal — currently safe locally but uncatalogued
                ThreatReport(0, 0, 0, 0, "UNCATALOGUED")
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun close() {
        client.close()
    }
}
