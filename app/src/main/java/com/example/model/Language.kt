package com.example.model

data class Language(
    val code: String,
    val title: String,
    val subtitle: String
)

object LanguageRepository {
    val languages: List<Language> = listOf(
        Language("en", "English", "(device\'s language)"),
        Language("hi", "हिन्दी", "Hindi"),
        Language("mr", "मराठी", "Marathi"),
        Language("gu", "ગુજરાતી", "Gujarati"),
        Language("ta", "தமிழ்", "Tamil"),
        Language("bn", "বাংলা", "Bangla"),
        Language("te", "తెలుగు", "Telugu"),
        Language("kn", "ಕನ್ನಡ", "Kannada"),
        Language("ml", "മലയാളം", "Malayalam"),
        Language("pa", "ਪੰਜਾਬੀ", "Punjabi"),
        Language("ur", "اردو", "Urdu"),
        Language("es", "Español", "Spanish"),
        Language("fr", "Français", "French"),
        Language("de", "Deutsch", "German"),
        Language("pt", "Português", "Portuguese")
    )
}
