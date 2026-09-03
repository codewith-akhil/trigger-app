package com.example.model

data class Country(
    val name: String,
    val nativeName: String = "",
    val dialCode: String,
    val flagEmoji: String,
    val codeIso: String = ""
)

object CountryRepository {
    val countries: List<Country> = listOf(
        Country("India", "", "+91", "🇮🇳", "IN"),
        Country("Pakistan", "پاکستان", "+92", "🇵🇰", "PK"),
        Country("South Africa", "iNingizimu Afrika", "+27", "🇿🇦", "ZA"),
        Country("United Kingdom", "", "+44", "🇬🇧", "GB"),
        Country("United States", "", "+1", "🇺🇸", "US"),
        Country("Afghanistan", "افغانستان", "+93", "🇦🇫", "AF"),
        Country("Åland Islands", "Åland", "+358", "🇦🇽", "AX"),
        Country("Albania", "Shqipëri", "+355", "🇦🇱", "AL"),
        Country("Algeria", "الجزائر", "+213", "🇩🇿", "DZ"),
        Country("American Samoa", "Amerika Sāmoa", "+1684", "🇦🇸", "AS"),
        Country("Andorra", "", "+376", "🇦🇩", "AD"),
        Country("Angola", "", "+244", "🇦🇴", "AO"),
        Country("Anguilla", "", "+1264", "🇦🇮", "AI"),
        Country("Argentina", "", "+54", "🇦🇷", "AR"),
        Country("Armenia", "Հայաստան", "+374", "🇦🇲", "AM"),
        Country("Australia", "", "+61", "🇦🇺", "AU"),
        Country("Austria", "Österreich", "+43", "🇦🇹", "AT"),
        Country("Bahamas", "", "+1242", "🇧🇸", "BS"),
        Country("Bahrain", "البحرين", "+973", "🇧🇭", "BH"),
        Country("Bangladesh", "বাংলাদেশ", "+880", "🇧🇩", "BD"),
        Country("Brazil", "Brasil", "+55", "🇧🇷", "BR"),
        Country("Canada", "", "+1", "🇨🇦", "CA"),
        Country("China", "中国", "+86", "🇨🇳", "CN"),
        Country("Egypt", "مصر", "+20", "🇪🇬", "EG"),
        Country("France", "", "+33", "🇫🇷", "FR"),
        Country("Germany", "Deutschland", "+49", "🇩🇪", "DE"),
        Country("Indonesia", "", "+62", "🇮🇩", "ID"),
        Country("Japan", "日本", "+81", "🇯🇵", "JP"),
        Country("Malaysia", "", "+60", "🇲🇾", "MY"),
        Country("Nepal", "नेपाल", "+977", "🇳🇵", "NP"),
        Country("New Zealand", "", "+64", "🇳🇿", "NZ"),
        Country("Nigeria", "", "+234", "🇳🇬", "NG"),
        Country("Philippines", "Pilipinas", "+63", "🇵🇭", "PH"),
        Country("Saudi Arabia", "المملكة العربية السعودية", "+966", "🇸🇦", "SA"),
        Country("Singapore", "", "+65", "🇸🇬", "SG"),
        Country("Sri Lanka", "ශ්‍රී ලංකා", "+94", "🇱🇰", "LK"),
        Country("United Arab Emirates", "الإمارات العربية المتحدة", "+971", "🇦🇪", "AE")
    )
}
