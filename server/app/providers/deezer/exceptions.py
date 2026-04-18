"""
Exceptions for Deezer provider adapter.
"""


class DeezerError(Exception):
    """Base exception for Deezer provider errors."""
    pass


class DeezerNotFound(DeezerError):
    """Resource not found on Deezer."""
    retryable = False


class DeezerRateLimited(DeezerError):
    """Deezer API rate limit exceeded."""
    retryable = True


class DeezerTimeout(DeezerError):
    """Deezer API request timed out."""
    retryable = True


class DeezerNetworkError(DeezerError):
    """Network error when calling Deezer API."""
    retryable = True


class DeezerProviderUnavailable(DeezerError):
    """Deezer provider is temporarily unavailable."""
    retryable = True


class DeezerParseError(DeezerError):
    """Error parsing Deezer response."""
    retryable = False
