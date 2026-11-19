"""Conservative comparison of catalogue labels, never a recording identity merge."""
import re
import unicodedata

from rapidfuzz import fuzz

_ARTISTS = re.compile(r",\s*|\s+&\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+vs\.?\s+|\s+x\s+", re.I)
_FEATURE = re.compile(r"\s*[\(\[]\s*(?:feat|ft)\.?\s+[^)\]]*[\)\]]|\s+(?:feat|ft)\.?\s+.*$", re.I)
_VERSIONS = re.compile(r"\b(live|remix|remaster(?:ed)?|acoustic|instrumental|sped|slowed)\b", re.I)


def _label(text):
    text = "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c))
    return " ".join(re.sub(r"[^\w]+", " ", text.casefold()).split())


def artist_match_score(expected, found):
    names = [name for name in found if isinstance(name, str) and name.strip()]
    if not expected or not names:
        return 0
    direct = fuzz.ratio(_label(expected), _label(", ".join(names)))
    primary = _ARTISTS.split(expected)[0].strip()
    parts = [part.strip() for name in names for part in _ARTISTS.split(name) if part.strip()]
    primary_score = max((fuzz.ratio(_label(primary), _label(part)) for part in parts), default=0)
    # A guest alone is not proof of the requested principal artist.
    return max(direct, primary_score) if primary_score >= 80 else 0


def title_match_score(expected, found):
    expected, found = _label(_FEATURE.sub("", expected)), _label(_FEATURE.sub("", found))
    if not expected or not found or set(_VERSIONS.findall(expected)) != set(_VERSIONS.findall(found)):
        return 0
    return fuzz.ratio(expected, found)
