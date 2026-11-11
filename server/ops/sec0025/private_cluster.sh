#!/bin/sh
# Local disposable Docker only; caller must enforce network none, tmpfs, caps and swap limits.
set -eu
umask 077
test "$(id -u)" = 101
test ! -e /restore/data
case "${RESTORE_BOOTSTRAP_ROLE:-}" in supabase_admin|sec0025_restore_admin) ;; *) exit 64 ;; esac
initdb -D /restore/data -U "$RESTORE_BOOTSTRAP_ROLE" -A trust --no-locale > /restore/init.log 2>&1
exec postgres -D /restore/data -k /tmp -c listen_addresses= \
  -c shared_preload_libraries= -c logging_collector=off -c log_statement=none \
  -c log_min_error_statement=panic >> /restore/postgres.log 2>&1
