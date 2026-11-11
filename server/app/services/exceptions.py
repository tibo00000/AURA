"""
Service layer exceptions.

Exceptions raised by services (not directly from providers).
Services map provider exceptions to these domain exceptions.
"""


class ServiceError(Exception):
    """Base exception for service errors."""
    pass


class NotFound(ServiceError):
    """Resource not found."""
    pass


class ProviderUnavailable(ServiceError):
    """Provider is temporarily unavailable."""
    pass


class AmbiguousMutation(ProviderUnavailable):
    """SQL completed with an unverifiable representation; keep HTTP 503."""
    pass


class BadRequest(ServiceError):
    """Request is invalid."""
    pass


class Conflict(ServiceError):
    """The resource changed concurrently or still has an active attempt."""
    pass


class PartialFailure(ServiceError):
    """Operation succeeded partially."""
    pass


class RequiresResolutionException(ServiceError):
    """Fuzzy match score is too low, requiring user choice among YTM candidates."""
    def __init__(self, candidates: list):
        self.candidates = candidates
        super().__init__("Download requires user resolution of YTM candidates")
