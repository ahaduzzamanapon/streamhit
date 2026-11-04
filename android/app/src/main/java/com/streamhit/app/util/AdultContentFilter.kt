package com.streamhit.app.util

import java.util.regex.Pattern

object AdultContentFilter {

    private val ADULT_GENRES = setOf(
        "erotic", "erotica", "hot", "adult", "adults", "18+", "18", "ecchi", "hentai", "porn",
        "pornography", "sex", "sensual", "nudity", "sexual", "uncensored", "nsfw",
        "softcore", "hardcore", "smut", "yaoi", "yuri", "mature", "taboo", "fetish", "eroge"
    )

    // Specific multi-word phrases and unique keywords (safe for substring matching)
    private val ADULT_PHRASES = listOf(
        // Ratings & general markers
        "18+", "18 +", "r18", "r-18", "r30", "r-30", "nc-17", "adult movie", "adult series",
        "adult film", "adults only", "adult animation", "adult anime", "uncensored",

        // Indian 18+ platforms & producers
        "ullu", "kooku", "primeshots", "hunters", "voovi", "moodx", "besharams",
        "rabbit movies", "neonx", "hotx", "feneo", "jalva", "boomx", "big bang",
        "cineprime", "nuefliks", "primeplay", "redprime", "cliff movies", "look entertainment",
        "showx", "cine7", "hitprime", "dreamfilm", "mojflix", "digi movieplex", "chikoo",
        "chikooflix", "fliz movies", "fliz", "hothit", "gupchup", "cinema dosti", "cinemadosti",
        "hotshots",

        // Famous Indian 18+ web series & tropes
        "charmsukh", "palang tod", "riti riwaj", "kavita bhabhi", "chull", "hotspot",
        "gandii baat", "kama sutra", "kamasutra", "mastram", "tandoor", "rasikan",
        "jalebi bai", "dunali", "walkman", "farebi yaar", "raja ka baaja",
        "samne wali khidki", "imli", "woh teacher", "garam masala", "choodiwala",
        "mona home delivery", "raghav", "tadap", "jane anjane mein", "doraha",
        "matki", "tauba tauba", "siskiyaan", "suraksha", "bidaai", "chhupa rustam",
        "chaudharahat", "aadhe adhoore", "ghar jamai", "jaal", "khidki", "seal",
        "sultan", "kamini", "kangan", "betaab", "shaukeen", "jhol", "lovely massage parlour",
        "honey trap", "call girl", "escort service", "savita bhabhi", "sapna bhabhi",
        "sarala bhabhi", "devar bhabhi", "suhagraat", "suhag raat", "desi porn",
        "desi sex", "desi bhabhi", "desi hot",

        // Adult Anime, Hentai & Ecchi
        "yoasobi gurashi", "turned into a beast", "do you like big girls",
        "toshoshitsu no kanojo", "seiso na kimi", "ochiru made", "overflow", "boku no pico",
        "kuroinu", "futanari", "ahegao", "chikan", "oppai", "boin", "nikuen", "shitsurakuen",
        "niku mesu", "seiso de bitch", "mesu-tachi", "succubus", "sexfriend", "kyonyuu",
        "bakunyuu", "onichichi", "resort boin", "ijirare", "tiny evil", "dokidoki little",
        "shoujo kyouiku", "ane wa yanmama", "sweet home (hentai)", "rance", "euphoria",
        "bible black", "taimanin", "kanojo x kanojo", "night shift nurses", "shoujo ramune",
        "starless", "discipline", "eromanga", "hentai ouji", "high school dxd",
        "shinmai maou", "yosuga no sora", "interspecies reviewers", "redo of healer",
        "world's end harem", "prison school", "gakuen de jikan yo tomare", "master piece",
        "mankitsu happening", "animefesta", "comicfesta", "queen bee", "mary jane",
        "bunnywalker", "poju", "pink pineapple", "white bear", "green bunny",
        "milky anime", "suzuki mirano", "ero anime", "erotic anime",

        // Famous explicit western erotica / sex films
        "paris 05:59", "theo & hugo", "theo and hugo", "nymphomaniac", "shortbus",
        "caligula", "baise-moi", "9 songs", "the brown bunny", "all about anna",
        "playboy", "penthouse", "hustler", "onlyfans", "camgirl", "webcam model",
        "sex tape", "sex video",

        // Famous adult actors
        "mia khalifa", "dani daniels", "eva elfie", "kendra lust", "johnny sins",
        "lana rhoades", "angela white", "riley reid", "leah gotti", "sunny leone adult",
        "tori black", "sasha grey", "brandi love", "lisa ann", "alexis texas",
        "mia malkova", "abell danger", "nicole aniston", "cherie deville", "cory chase"
    )

    // Word boundary regex for single sensitive words so "essex" or "analysis" are not false flagged
    private val ADULT_WORD_REGEX = Pattern.compile(
        "\\b(sex|sexy|porn|porno|xxx|nude|nudity|erotic|erotica|bhabhi|boobs|tits|fuck|fucking|" +
        "pussy|penis|vagina|anal|dildo|vibrator|horny|orgasm|milf|hentai|ecchi|softcore|hardcore|" +
        "masturbat\\w*|threesome|gangbang|blowjob|creampie|intercourse|cuckold|voyeur|incest|" +
        "deepthroat|swinger|striptease)\\b",
        Pattern.CASE_INSENSITIVE
    )

    fun isAdult(
        title: String?,
        genre: String? = null,
        detailPath: String? = null,
        coverUrl: String? = null
    ): Boolean {
        val tLower = title?.lowercase()?.trim() ?: ""
        val gLower = genre?.lowercase()?.trim() ?: ""
        val dLower = detailPath?.lowercase()?.trim() ?: ""
        val cLower = coverUrl?.lowercase()?.trim() ?: ""

        // 1. Check genre list
        if (gLower.isNotEmpty()) {
            val genres = gLower.split(",", "/", ";").map { it.trim() }
            if (genres.any { it in ADULT_GENRES }) return true
        }

        val combined = "$tLower $gLower $dLower $cLower"

        // 2. Check multi-word phrases and unique keywords
        for (phrase in ADULT_PHRASES) {
            if (combined.contains(phrase)) {
                return true
            }
        }

        // 3. Check regex word boundaries
        if (ADULT_WORD_REGEX.matcher(combined).find()) {
            return true
        }

        return false
    }
}
