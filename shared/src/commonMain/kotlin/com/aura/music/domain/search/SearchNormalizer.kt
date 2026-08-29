package com.aura.music.domain.search

/**
 * Normaliseur et tokeniseur de texte pour la recherche sémantique tolérante (AURA Search).
 *
 * NOTE D'ARCHITECTURE (Frontière de domaine) :
 * - [SearchNormalizer] est conçu exclusivement pour la RECHERCHE TOLÉRANTE (tolérance accents NFKD,
 *   leetspeak musical, suppression du bruit de features, stopwords).
 * - Pour la RÉCONCILIATION STRICTE 1-to-1 (ex: matching MediaStore <-> Cloud VPS), utiliser
 *   [com.aura.music.ui.utils.NormalizedTrackKey] qui applique une normalisation conservative stricte.
 */
object SearchNormalizer {

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "of", "in", "on", "at", "to", "for", "with", "by", "from",
        "feat", "featuring", "ft", "prod", "prodby", "remix", "version", "edit", "radio", "official",
        "le", "la", "les", "un", "une", "des", "du", "de", "d", "et", "ou", "en", "dans", "sur",
        "pour", "par", "avec", "sans", "sous"
    )

    // Expressions régulières pré-compilées pour performance optimale
    private val PARENTHESIS_NOISE_REGEX = Regex("(?i)\\s*[\\[(](feat|ft|featuring|prod|remix|version|edit|official|clip|video|audio).*?[\\])]")
    private val DECORATIVE_PUNCTUATION_REGEX = Regex("[\\p{Punct}&&[^'\\$]]+")
    private val MULTI_WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Normalise une chaîne de recherche ou de métadonnée :
     * 1. Décomposition Unicode & suppression diacritiques (NFKD)
     * 2. Nettoyage du bruit de métadonnées (feat., remakes entre parenthèses)
     * 3. Remplacement du Leetspeak musical sécurisé (protection des nombres purs)
     * 4. Remplacement de la ponctuation et mise en minuscules
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""

        var cleaned = text.trim()

        // 1. Nettoyage du bruit typique entre parenthèses
        cleaned = PARENTHESIS_NOISE_REGEX.replace(cleaned, " ")

        // 2. Décomposition des accents / caractères spéciaux
        cleaned = stripDiacritics(cleaned)

        // 3. Gestion des acronymes avec '&' (ex: "R&B" -> "rnb", "Simon & Garfunkel" -> "simon and garfunkel")
        cleaned = cleaned.replace(Regex("(?i)\\br\\s*&\\s*b\\b"), "rnb")
        cleaned = cleaned.replace("&", " and ")

        // 4. Tokenisation intermédiaire pour appliquer le leetspeak de façon sélective
        val rawTokens = cleaned.split(MULTI_WHITESPACE_REGEX).filter { it.isNotBlank() }
        val transformedTokens = rawTokens.map { token ->
            transformLeetspeakToken(token)
        }

        val result = transformedTokens.joinToString(" ")
            .lowercase()
            .replace(DECORATIVE_PUNCTUATION_REGEX, " ")
            .replace("'", "")
            .replace(MULTI_WHITESPACE_REGEX, " ")
            .trim()

        return result
    }

    /**
     * Découpe un texte normalisé en tokens utiles pour la recherche.
     * Filtre les tokens de longueur <= 1 (sauf si la requête entière fait 1 caractère).
     */
    fun tokenize(text: String?): List<String> {
        val normalized = normalize(text)
        if (normalized.isBlank()) return emptyList()

        val tokens = normalized.split(" ").filter { it.isNotBlank() }
        if (tokens.size == 1) {
            return tokens
        }

        // Pour les requêtes multi-mots, on élimine les tokens à 1 seule lettre
        return tokens.filter { it.length > 1 }
    }

    /**
     * Vérifie si un token est un stopword (mot vide).
     */
    fun isStopword(token: String): Boolean = STOPWORDS.contains(token.lowercase())

    /**
     * Applique la translittération Leetspeak uniquement sur les tokens mixtes ou symboliques.
     * GARDE-FOU : Un token 100% numérique (ex: "2024", "1999", "24") reste inchangé.
     */
    private fun transformLeetspeakToken(token: String): String {
        val lower = token.lowercase()

        // 1. Si le token est purement composé de chiffres, on le préserve strictement intact
        if (lower.all { it.isDigit() }) {
            return lower
        }

        var res = lower

        // 2. Symboles musicaux courants (ex: Ke$ha, A$AP, P!nk)
        res = res.replace("$", "s")
        res = res.replace("@", "a")
        res = res.replace("!", "i")
        res = res.replace("€", "e")

        // 3. Chiffres leetspeak dans les mots mixtes (ex: "t3r3nc3" -> "terence", "6lack" -> "black")
        if (res.any { it.isLetter() } && res.any { it.isDigit() }) {
            res = res.map { ch ->
                when (ch) {
                    '3' -> 'e'
                    '0' -> 'o'
                    '1' -> 'i'
                    '4' -> 'a'
                    '5' -> 's'
                    '7' -> 't'
                    else -> ch
                }
            }.joinToString("")
        }

        return res
    }

    /**
     * Supprime les accents et caractères diacritiques de manière robuste.
     */
    private fun stripDiacritics(input: String): String {
        val sb = StringBuilder(input.length)
        for (char in input) {
            when (char) {
                'à', 'á', 'â', 'ã', 'ä', 'å', 'ā', 'ă', 'ą' -> sb.append('a')
                'À', 'Á', 'Â', 'Ã', 'Ä', 'Å', 'Ā', 'Ă', 'Ą' -> sb.append('A')
                'ç', 'ć', 'ĉ', 'ċ', 'č' -> sb.append('c')
                'Ç', 'Ć', 'Ĉ', 'Ċ', 'Č' -> sb.append('C')
                'è', 'é', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě' -> sb.append('e')
                'È', 'É', 'Ê', 'Ë', 'Ē', 'Ĕ', 'Ė', 'Ę', 'Ě' -> sb.append('E')
                'ì', 'í', 'î', 'ï', 'ĩ', 'ī', 'ĭ', 'į', 'ı' -> sb.append('i')
                'Ì', 'Í', 'Î', 'Ï', 'Ĩ', 'Ī', 'Ĭ', 'Į', 'İ' -> sb.append('I')
                'ñ', 'ń', 'ņ', 'ň', 'ŋ' -> sb.append('n')
                'Ñ', 'Ń', 'Ņ', 'Ň', 'Ŋ' -> sb.append('N')
                'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō', 'ŏ', 'ő' -> sb.append('o')
                'Ò', 'Ó', 'Ô', 'Õ', 'Ö', 'Ø', 'Ō', 'Ŏ', 'Ő' -> sb.append('O')
                'ù', 'ú', 'û', 'ü', 'ũ', 'ū', 'ŭ', 'ů', 'ű', 'ų' -> sb.append('u')
                'Ù', 'Ú', 'Û', 'Ü', 'Ũ', 'Ū', 'Ŭ', 'Ů', 'Ű', 'Ų' -> sb.append('U')
                'ý', 'ÿ', 'ŷ' -> sb.append('y')
                'Ý', 'Ÿ', 'Ŷ' -> sb.append('Y')
                'æ' -> sb.append("ae")
                'Æ' -> sb.append("AE")
                'œ' -> sb.append("oe")
                'Œ' -> sb.append("OE")
                'ß' -> sb.append("ss")
                'ð', 'đ' -> sb.append('d')
                'Ð', 'Đ' -> sb.append('D')
                'ł' -> sb.append('l')
                'Ł' -> sb.append('L')
                'þ' -> sb.append("th")
                'Þ' -> sb.append("TH")
                else -> sb.append(char)
            }
        }
        return sb.toString()
    }
}
