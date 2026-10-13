package com.streamhit.app.util

object AdultContentFilter {

    private val ADULT_GENRES = setOf(
        "erotic", "hot", "adult", "18+", "18", "ecchi", "hentai", "porn",
        "pornography", "sex", "sensual", "nudity", "sexual", "uncensored",
        "nsfw", "softcore", "hardcore"
    )

    private val ADULT_KEYWORDS = listOf(
        "18+", "18 +", "r18", "r-18", "nc-17", "erotic", "erotica", "softcore",
        "hardcore", "porn", "pornography", "adult movie", "adult series", "adult film",
        "sensual", "uncensored", "nudity", "sexual", "masturbat", "seduction",
        "striptease", "kaam sastra", "charmsukh", "palang tod", "riti riwaj",
        "kavita bhabhi", "chull", "hotspot", "gandii baat", "kama sutra",
        "kamasutra", "mastram", "tandoor", "rasikan", "dani daniels",
        "mia khalifa", "eva elfie", "kendra lust", "johnny sins", "lana rhoades",
        "angela white", "riley reid", "leah gotti", "ullu", "kooku", "primeshots",
        "hunters", "voovi", "moodx", "besharams", "rabbit movies", "neonx",
        "hotx", "feneo", "jalva", "boomx"
    )

    fun isAdult(title: String?, genre: String? = null, detailPath: String? = null): Boolean {
        val tLower = title?.lowercase()?.trim() ?: ""
        val gLower = genre?.lowercase()?.trim() ?: ""
        val dLower = detailPath?.lowercase()?.trim() ?: ""

        if (gLower.isNotEmpty()) {
            val genres = gLower.split(",", "/", ";").map { it.trim() }
            if (genres.any { it in ADULT_GENRES }) return true
        }

        val combined = "$tLower $gLower $dLower"
        for (kw in ADULT_KEYWORDS) {
            if (tLower.contains(kw) || dLower.contains(kw) || combined.contains(kw)) {
                return true
            }
        }

        return false
    }
}
