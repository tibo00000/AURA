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


class BadRequest(ServiceError):
    """Request is invalid."""
    pass


class PartialFailure(ServiceError):
    """Operation succeeded partially."""
    pass
