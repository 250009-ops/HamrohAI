package com.jarvis.llm

import com.jarvis.BuildConfig
import com.jarvis.actions.ActionDispatcher
import com.jarvis.config.SystemPromptProvider
import com.jarvis.memory.LocalMemoryStore
import com.jarvis.policy.UzbekOnlyGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class OrchestratorReply(
    val text: String
)

class DialogueOrchestrator(
    private val uzbekGuard: UzbekOnlyGuard,
    private val actionDispatcher: ActionDispatcher,
    private val memoryStore: LocalMemoryStore
) {
    private val client = OkHttpClient()
    private var pendingCallPhone: String? = null

    suspend fun respondTo(
        userText: String,
        shortResponseMode: Boolean = true,
        onFirstToken: () -> Unit = {}
    ): OrchestratorReply {
        val guardDecision = uzbekGuard.validateInput(userText)
        if (!guardDecision.accepted) {
            return OrchestratorReply(uzbekGuard.sanitizeAssistantOutput(guardDecision.messageForUser.orEmpty()))
        }

        rememberProfileFactIfPresent(userText)

        if (isConfirmation(userText) && pendingCallPhone != null) {
            val phone = pendingCallPhone.orEmpty()
            pendingCallPhone = null
            val callResult = actionDispatcher.executeConfirmedCall(phone)
            return OrchestratorReply(uzbekGuard.sanitizeAssistantOutput(callResult.message))
        }

        val actionResult = actionDispatcher.dispatch(userText)
        if (actionResult.handled) {
            if (actionResult.needsConfirmation) {
                pendingCallPhone = actionResult.pendingPhoneNumber
            }
            return OrchestratorReply(uzbekGuard.sanitizeAssistantOutput(actionResult.message))
        }

        val responseText = fetchLlmResponse(userText, shortResponseMode, onFirstToken)
        memoryStore.addSessionSummary("User: $userText | Jarvis: $responseText")
        return OrchestratorReply(uzbekGuard.sanitizeAssistantOutput(responseText))
    }

    fun prefetchAcknowledgementPhrase(): String {
        val candidates = listOf(
            "Bir soniya, tekshirib beryapman.",
            "Xo'p, hozir javob tayyorlayman.",
            "Mayli, so'rovingizni qayta ishlayapman."
        )
        return candidates[(System.currentTimeMillis() % candidates.size).toInt()]
    }

    private suspend fun fetchLlmResponse(
        userText: String,
        shortResponseMode: Boolean,
        onFirstToken: () -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (BuildConfig.LLM_ENDPOINT.isBlank()) {
            onFirstToken()
            return@withContext "Men tayyorman. So'rovingizni o'zbek tilida davom ettiring."
        }

        val quick = withTimeoutOrNull(1400) {
            requestModel(
                userText = userText,
                shortResponseMode = true
            )
        }
        if (!quick.isNullOrBlank()) {
            onFirstToken()
            return@withContext quick
        }

        val normal = withTimeoutOrNull(7000) {
            requestModel(
                userText = userText,
                shortResponseMode = shortResponseMode
            )
        }
        if (!normal.isNullOrBlank()) {
            onFirstToken()
            return@withContext normal
        }

        onFirstToken()
        "Tarmoq biroz sekin. Qisqa javob: keyinroq yana urinib ko'ring."
    }

    private fun requestModel(
        userText: String,
        shortResponseMode: Boolean
    ): String {
        val payload = JSONObject().apply {
            put("system_prompt", SystemPromptProvider.strictUzbekPrompt())
            put("response_style", if (shortResponseMode) "short_uzbek" else "normal_uzbek")
            put("max_output_tokens", if (shortResponseMode) 120 else 280)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", userText)))
        }
        val request = Request.Builder()
            .url(BuildConfig.LLM_ENDPOINT)
            .header("Authorization", "Bearer ${BuildConfig.LLM_API_KEY}")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use ""
                }
                val body = response.body?.string().orEmpty()
                JSONObject(body).optString("text", "")
            }
        }.getOrElse {
            ""
        }
    }

    private fun isConfirmation(text: String): Boolean {
        val normalized = text.lowercase().trim()
        return normalized == "ha" ||
            normalized == "tasdiqlayman" ||
            normalized.contains("ha, tasdiqlayman")
    }

    private suspend fun rememberProfileFactIfPresent(userText: String) {
        val normalized = userText.lowercase()
        val name = Regex("mening ismim\\s+([a-zA-Z'`-]+)").find(normalized)?.groupValues?.getOrNull(1)
        if (!name.isNullOrBlank()) {
            memoryStore.upsertProfileFact("ism", name.replaceFirstChar { it.uppercase() })
        }
    }
}
