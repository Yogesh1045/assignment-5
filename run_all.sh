#!/usr/bin/env bash
# Compiles and runs every program; output saved to <task>/output/<Program>.txt. Needs JDK 17+.
# Database tasks need MySQL/MariaDB with sql/test_db.sql applied (database 'campus', user 'student'/'student123'),
# or pass -Ddb.url=... -Ddb.user=... -Ddb.password=... through JAVA_OPTS.
unset JAVA_TOOL_OPTIONS _JAVA_OPTIONS
ROOT="$(cd "$(dirname "$0")" && pwd)"; JDBC="$ROOT/lib/mariadb-java-client-2.7.6.jar"
run() { local t="$1" main="$2"; mkdir -p "$ROOT/$t/output" "$ROOT/out/$t"; echo "== $t : $main"
  javac -cp "$JDBC" -d "$ROOT/out/$t" "$ROOT/$t"/*.java && \
  ( cd "$ROOT/$t" && java -Xmx3g $JAVA_OPTS -cp "$ROOT/out/$t:$JDBC" "$main" ) 2>&1 | tee "$ROOT/$t/output/$main.txt"; echo; }
run A1 RandomAccessLab
run A3 DaoDemo
run A4 HostelSerialization
run A5 WalletTransfer
run B1 PassStore
run B1 FileStoreLab
run B2 LoginLab
run B3 InsertLab
run B4 Migration
rm -rf "$ROOT"/*/work
