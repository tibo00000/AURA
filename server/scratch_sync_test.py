"""
Scratch script to validate the AURA Sync Engine locally.

Tests:
1. Bootstrap hydration & SyncToken generation.
2. Push batch of client mutations (settings, playlists, likes).
3. Idempotency duplication protection (second identical push-batch).
4. Pull batch server changes extraction based on SyncTokens.
"""

import sys
import os
import httpx

BASE_URL = "http://localhost:8000"
USER_TOKEN = "12345678-1234-1234-1234-1234567890ab"  # Deterministic UUID token
HEADERS = {
    "Authorization": f"Bearer {USER_TOKEN}",
    "Content-Type": "application/json"
}


def run_tests():
    print("=== AURA SYNC ENGINE INTEGRATION TEST ===")
    
    # Check if server is running
    try:
        httpx.get(f"{BASE_URL}/health")
    except Exception:
        print("Error: FastAPI server is not running on http://localhost:8000.")
        print("Please start it using 'npm run dev' or 'uvicorn app.main:app' before running this test.")
        sys.exit(1)

    client = httpx.Client(headers=HEADERS, base_url=BASE_URL, timeout=10.0)

    # 1. TEST BOOTSTRAP
    print("\n--- 1. Testing POST /me/sync/bootstrap ---")
    boot_payload = {
        "device_id": "test_pixel_9",
        "app_version": "1.0.0",
        "capabilities": {"supports_batch_push": True}
    }
    res = client.post("/me/sync/bootstrap", json=boot_payload)
    if res.status_code != 200:
        print(f"FAILED: Bootstrap status {res.status_code}, detail: {res.text}")
        sys.exit(1)
        
    boot_data = res.json()["data"]
    sync_token = boot_data["sync_token"]
    print(f"SUCCESS: Received initial SyncToken: {sync_token['value']} issued at {sync_token['issued_at']}")
    print(f"Snapshot Content keys: {list(boot_data['snapshot'].keys())}")

    # 2. TEST PUSH BATCH (Mutations)
    print("\n--- 2. Testing POST /me/sync/push-batch (Mutations) ---")
    push_payload = {
        "device_id": "test_pixel_9",
        "batch_id": "batch_001",
        "sent_at": "2026-05-29T21:00:00Z",
        "operations": [
            {
                "operation_id": "op_set_999",
                "entity_type": "user_settings",
                "entity_id": "default",
                "operation_type": "patch",
                "device_id": "test_pixel_9",
                "occurred_at": "2026-05-29T21:00:00Z",
                "payload": {
                    "sync_enabled": True,
                    "online_search_network_policy": "wifi_only"
                }
            },
            {
                "operation_id": "op_pl_999",
                "entity_type": "playlist",
                "entity_id": "pl_test_999",
                "operation_type": "update",
                "device_id": "test_pixel_9",
                "occurred_at": "2026-05-29T21:00:00Z",
                "payload": {
                    "name": "My Sync Playlist",
                    "is_pinned": True
                }
            },
            {
                "operation_id": "op_like_999",
                "entity_type": "track_like",
                "entity_id": "trk_deezer_386214655",
                "operation_type": "set",
                "device_id": "test_pixel_9",
                "occurred_at": "2026-05-29T21:00:00Z",
                "payload": {
                    "track_id": "trk_deezer_386214655",
                    "is_liked": True
                }
            }
        ]
    }
    
    res = client.post("/me/sync/push-batch", json=push_payload)
    if res.status_code != 200:
        print(f"FAILED: Push Batch status {res.status_code}, detail: {res.text}")
        sys.exit(1)
        
    push_data = res.json()["data"]
    next_pull_token = push_data["next_pull_token"]
    print("SUCCESS: Operations pushed successfully.")
    for result in push_data["results"]:
        print(f"  -> Operation {result['operation_id']} ({result['entity_type']}): Status = {result['status']}")

    # 3. TEST IDEMPOTENCY PROTECTION
    print("\n--- 3. Testing Idempotency Protection (Duplicate push-batch) ---")
    res = client.post("/me/sync/push-batch", json=push_payload)
    if res.status_code != 200:
        print(f"FAILED: Duplicate push batch status {res.status_code}, detail: {res.text}")
        sys.exit(1)
        
    dup_data = res.json()["data"]
    print("SUCCESS: Idempotency protected.")
    for result in dup_data["results"]:
        print(f"  -> Operation {result['operation_id']} ({result['entity_type']}): Status = {result['status']}")
        if result["status"] != "ignored_duplicate":
            print(f"FAILED: Operation {result['operation_id']} was not marked as ignored_duplicate!")
            sys.exit(1)

    # 4. TEST PULL BATCH (Changes extraction)
    print("\n--- 4. Testing POST /me/sync/pull-batch ---")
    pull_payload = {
        "device_id": "test_pixel_9",
        "since_token": sync_token["value"],  # Pull since bootstrap token
        "limit": 100
    }
    res = client.post("/me/sync/pull-batch", json=pull_payload)
    if res.status_code != 200:
        print(f"FAILED: Pull Batch status {res.status_code}, detail: {res.text}")
        sys.exit(1)
        
    pull_data = res.json()["data"]
    changes = pull_data["changes"]
    print(f"SUCCESS: Pulled {len(changes)} server-side changes.")
    for chg in changes:
        print(f"  -> Change {chg['change_id']} ({chg['entity_type']}): Type = {chg['change_type']}")
        
    print("\n=== ALL SYNC ENGINE TESTS PASSED SUCCESSFULLY ===")


if __name__ == "__main__":
    run_tests()
