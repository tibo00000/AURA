"""
Pydantic schemas for the synchronization batch endpoints.
"""

from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class SyncToken(BaseModel):
    """Sync token cursor."""
    value: str
    issued_at: str


# --- BOOTSTRAP ---

class BootstrapRequest(BaseModel):
    """Request schema for bootstrap hydration."""
    device_id: str
    app_version: Optional[str] = "0.1.0"
    capabilities: Optional[Dict[str, Any]] = Field(default_factory=dict)


class BootstrapResponse(BaseModel):
    """Response schema for bootstrap hydration."""
    sync_token: SyncToken
    snapshot: Dict[str, Any]


# --- PUSH BATCH ---

class SyncOperation(BaseModel):
    """Single sync mutation operation from the client's outbox."""
    operation_id: str
    entity_type: str
    entity_id: str
    operation_type: str
    device_id: str
    occurred_at: str
    base_server_updated_at: Optional[str] = None
    payload: Dict[str, Any] = Field(default_factory=dict)


class PushBatchRequest(BaseModel):
    """Request schema to push a batch of local client operations."""
    device_id: str
    batch_id: str
    sent_at: str
    operations: List[SyncOperation]


class SyncOperationResult(BaseModel):
    """Outcome result of a single sync operation."""
    operation_id: str
    entity_type: str
    entity_id: str
    status: str  # "applied", "merged", "conflict", "ignored_duplicate"
    server_updated_at: str
    resolved_entity: Optional[Dict[str, Any]] = None
    conflict: Optional[Dict[str, Any]] = None


class PushBatchResponse(BaseModel):
    """Response schema after pushing a batch of operations."""
    batch_id: str
    results: List[SyncOperationResult]
    next_pull_token: SyncToken


# --- PULL BATCH ---

class PullBatchRequest(BaseModel):
    """Request schema to pull latest server-side changes."""
    device_id: str
    since_token: str
    limit: Optional[int] = 200
    entity_types: Optional[List[str]] = None


class ServerChange(BaseModel):
    """Single change occurred on the server."""
    change_id: str
    entity_type: str
    entity_id: str
    change_type: str  # "upsert" or "delete"
    server_updated_at: str
    payload: Dict[str, Any]


class PullBatchResponse(BaseModel):
    """Response schema after pulling a batch of changes."""
    changes: List[ServerChange]
    next_pull_token: SyncToken
    has_more: bool
