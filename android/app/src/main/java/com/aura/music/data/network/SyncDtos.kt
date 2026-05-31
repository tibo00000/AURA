package com.aura.music.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SyncTokenDto(
    @Json(name = "value") val value: String,
    @Json(name = "issued_at") val issuedAt: String
)

@JsonClass(generateAdapter = true)
data class BootstrapRequestDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "app_version") val appVersion: String = "0.1.0",
    @Json(name = "capabilities") val capabilities: Map<String, String> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class BootstrapResponseDto(
    @Json(name = "sync_token") val syncToken: SyncTokenDto,
    @Json(name = "snapshot") val snapshot: Map<String, Any?>
)

@JsonClass(generateAdapter = true)
data class SyncOperationDto(
    @Json(name = "operation_id") val operationId: String,
    @Json(name = "entity_type") val entityType: String,
    @Json(name = "entity_id") val entityId: String,
    @Json(name = "operation_type") val operationType: String,
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "occurred_at") val occurredAt: String,
    @Json(name = "base_server_updated_at") val baseServerUpdatedAt: String? = null,
    @Json(name = "payload") val payload: Map<String, Any?> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class PushBatchRequestDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "batch_id") val batchId: String,
    @Json(name = "sent_at") val sentAt: String,
    @Json(name = "operations") val operations: List<SyncOperationDto>
)

@JsonClass(generateAdapter = true)
data class SyncOperationResultDto(
    @Json(name = "operation_id") val operationId: String,
    @Json(name = "entity_type") val entityType: String,
    @Json(name = "entity_id") val entityId: String,
    @Json(name = "status") val status: String, // "applied", "merged", "conflict", "ignored_duplicate"
    @Json(name = "server_updated_at") val serverUpdatedAt: String,
    @Json(name = "resolved_entity") val resolvedEntity: Map<String, Any?>? = null,
    @Json(name = "conflict") val conflict: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class PushBatchResponseDto(
    @Json(name = "batch_id") val batchId: String,
    @Json(name = "results") val results: List<SyncOperationResultDto>,
    @Json(name = "next_pull_token") val nextPullToken: SyncTokenDto
)

@JsonClass(generateAdapter = true)
data class PullBatchRequestDto(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "since_token") val sinceToken: String,
    @Json(name = "limit") val limit: Int = 200,
    @Json(name = "entity_types") val entityTypes: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class ServerChangeDto(
    @Json(name = "change_id") val changeId: String,
    @Json(name = "entity_type") val entityType: String,
    @Json(name = "entity_id") val entityId: String,
    @Json(name = "change_type") val changeType: String, // "upsert" or "delete"
    @Json(name = "server_updated_at") val serverUpdatedAt: String,
    @Json(name = "payload") val payload: Map<String, Any?>
)

@JsonClass(generateAdapter = true)
data class PullBatchResponseDto(
    @Json(name = "changes") val changes: List<ServerChangeDto>,
    @Json(name = "next_pull_token") val nextPullToken: SyncTokenDto,
    @Json(name = "has_more") val hasMore: Boolean
)
