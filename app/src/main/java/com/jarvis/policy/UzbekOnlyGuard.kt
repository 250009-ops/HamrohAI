package com.jarvis.policy

data class GuardDecision(
    val accepted: Boolean,
    val messageForUser: String? = null
)

class UzbekOnlyGuard {

    private val commonUzbekTokens = setOf(
        "salom", "rahmat", "iltimos", "yordam", "soat", "eslatma", "budilnik", "qo'ng'iroq", "och"
    )
    private val commonEnglishTokens = setOf(
        "hello", "please", "open", "call", "alarm", "reminder", "what", "who", "where", "why"
    )

    fun validateInput(userText: String): GuardDecision {
        val normalized = userText.lowercase()
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val uzbekScore = words.count { token -> commonUzbekTokens.any { token.contains(it) } }
        val englishScore = words.count { token -> commonEnglishTokens.any { token.contains(it) } }
        if (englishScore > uzbekScore && words.isNotEmpty()) {
            return GuardDecision(
                accepted = false,
                messageForUser = "Men faqat o'zbek tilida ishlayman. Iltimos, so'rovni o'zbek tilida ayting."
            )
        }
        return GuardDecision(accepted = true)
    }

    fun sanitizeAssistantOutput(text: String): String {
        if (text.isBlank()) {
            return "Uzr, javob tayyor bo'lmadi. Qayta urinib ko'ring."
        }
        val normalized = text.lowercase()
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val englishScore = words.count { token -> commonEnglishTokens.any { token.contains(it) } }
        if (englishScore > 2) {
            return "Men faqat o'zbek tilida javob beraman. Savolingizni o'zbek tilida davom ettiring."
        }
        return text
    }
}
