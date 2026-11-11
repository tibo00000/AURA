"""Offline, fail-closed SEC-002.1 inventory comparison. No database connection or data mutation."""
import argparse
import json
from pathlib import Path

PRIVATE = ("profiles", "user_settings", "playlists", "playlist_items", "likes", "playback_snapshots",
           "history_items", "listening_sessions", "playback_events", "processed_operations")
REQUIRED_COLUMNS = {
    "profiles":{"id":"uuid"}, "user_settings":{"user_id":"uuid"},
    "playlists":{"id":"text","user_id":"uuid","updated_at":"timestamp with time zone"},
    "playlist_items":{"id":"text","playlist_id":"text","track_id":"text","position":"integer",
                      "added_at":"timestamp with time zone","added_from_context_type":"text","added_from_context_id":"text"},
    "likes":{"user_id":"uuid"}, "playback_snapshots":{"user_id":"uuid"},
    "history_items":{"id":"text","user_id":"uuid","listening_session_id":"text"},
    "listening_sessions":{"id":"text","user_id":"uuid"},
    "playback_events":{"id":"text","user_id":"uuid","session_id":"text"},
    "processed_operations":{"operation_id":"text","user_id":"uuid"},
    "download_jobs":{"id":"text","user_id":"uuid"},
}


def assess(metadata, counts=None, batches=None):
    blocks, review = [], []
    if metadata.get("format") != "aura-sec002-inventory-v1" or metadata.get("transaction_read_only") != "on":
        blocks.append("inventory_format_or_read_only_not_proven")
    if metadata.get("server_version_num", 0) < 150000:
        blocks.append("postgresql_15_required")
    tables = {t["name"]: t for t in metadata.get("tables", [])}
    for name in (*PRIVATE, "download_jobs"):
        if name not in tables:
            blocks.append("missing_table:"+name)
    roles = {r["name"]: r for r in metadata.get("roles", [])}
    for role in ("anon", "authenticated", "service_role"):
        if role not in roles:
            blocks.append("missing_role:"+role)
    for role in ("anon", "authenticated"):
        r = roles.get(role, {})
        if r.get("superuser") or r.get("bypassrls") or r.get("service_member"):
            blocks.append("privileged_client_role:"+role)
    for role in ("anon","authenticated"):
        if any(t.get(role+"_owner_member") for t in tables.values() if t["name"] in (*PRIVATE,"processed_batches")):
            blocks.append(role+"_inherits_table_owner")
    columns = {(c["table"],c["name"]):c["type"] for c in metadata.get("columns",[])}
    required = dict(REQUIRED_COLUMNS)
    if "processed_batches" in tables:
        required["processed_batches"] = {"batch_id":"text","user_id":"uuid"}
    for table, fields in required.items():
        for field, kind in fields.items():
            if columns.get((table,field)) != kind:
                blocks.append("missing_or_incompatible_column:"+table+"."+field)
    for f in metadata.get("functions", []):
        if f.get("name") == "aura_mutate_playlist_items":
            blocks.append("rpc_name_already_exists_requires_migration_state_review")
        if f.get("definer") and f.get("result") != "trigger" and (f.get("anon_execute") or f.get("authenticated_execute")):
            blocks.append("exposed_definer_requires_review:"+f["name"])
        elif f.get("anon_execute") or f.get("authenticated_execute"):
            review.append("callable_function_body_and_callees:"+f["name"])
    for t in metadata.get("triggers", []):
        if not t.get("internal") and t.get("definer") and t.get("table") in (*PRIVATE,"processed_batches"):
            blocks.append("privileged_trigger_requires_review:"+t["name"])
    for v in metadata.get("views", []):
        if v.get("anon_select") or v.get("authenticated_select"):
            review.append("view_definition_and_dependencies:"+v["name"])
    constraints = metadata.get("constraints", [])
    position = [c for c in constraints if c.get("table") == "playlist_items" and c.get("name") == "unique_playlist_position"]
    if len(position) != 1 or position[0].get("type") != "u" or position[0].get("columns") != ["playlist_id","position"]:
        blocks.append("expected_unique_playlist_position_missing_or_different")
    for c in constraints:
        if c.get("name", "").startswith("sec002_"):
            blocks.append("migration_may_already_be_applied:"+c["name"])
        if c.get("type") == "f" and c.get("referenced_table", "").removeprefix("public.") in ("processed_operations", "processed_batches"):
            blocks.append("dependent_foreign_key_on_marker_key:"+c["name"])
    count_tables = ("profiles","playlists","playlist_items","listening_sessions","history_items","playback_events","processed_operations")
    complete_rights = all(tables.get(t,{}).get("executor_select") and
                          tables.get(t,{}).get("executor_rls_active") is False for t in count_tables)
    if not complete_rights:
        blocks.append("aggregate_read_rights_incomplete_or_rls_filtered")
    if counts is None:
        blocks.append("aggregate_counts_not_collected")
    else:
        for key in ("events_invalid_session","history_invalid_session","operation_orphan_owner",
                    "playlist_orphan_owner","item_orphan_parent","duplicate_playlist_positions","negative_playlist_positions"):
            if type(counts.get(key)) is not int or counts[key] != 0:
                blocks.append("inconsistent_or_unknown_count:"+key)
    if "processed_batches" in tables:
        if not tables["processed_batches"].get("executor_select") or tables["processed_batches"].get("executor_rls_active") is not False:
            blocks.append("batch_aggregate_read_rights_incomplete")
        if batches is None or type(batches.get("batch_orphan_owner")) is not int or batches["batch_orphan_owner"] != 0:
            blocks.append("batch_orphan_count_unknown_or_nonzero")
    # Automated metadata cannot establish semantics of hashes, dynamic SQL or schema drift.
    review += ["columns_types_nullability_vs_tracked_schema", "all_inherited_and_column_grants",
               "all_policy_hashes_and_function_callees", "view_and_pk_dependencies", "exposed_Data_API_schemas",
               "independent_REST_RPC_consistency_gate", "isolated_real_Auth_PostgREST_AB_gate"]
    return {"status":"NO_GO" if blocks else "MANUAL_REVIEW_REQUIRED", "blocks":sorted(set(blocks)),
            "manual_review":sorted(set(review)), "remote_write_authorized":False,
            "note":"Never a deployment GO; no SQL definitions or private rows exported."}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--metadata", type=Path, required=True)
    p.add_argument("--counts", type=Path)
    p.add_argument("--batches", type=Path)
    args = p.parse_args()
    load = lambda path: json.loads(path.read_text(encoding="utf-8-sig")) if path else None
    result = assess(load(args.metadata),load(args.counts),load(args.batches))
    print(json.dumps(result,indent=2))
    return 2 if result["blocks"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
