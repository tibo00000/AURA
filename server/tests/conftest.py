import os

os.environ["TESTING"] = "1"

# These are required dependencies. Load them before legacy test modules can
# install collection-time substitutes based only on sys.modules membership.
import rapidfuzz  # noqa: E402,F401
import yt_dlp  # noqa: E402,F401
import ytmusicapi  # noqa: E402,F401
