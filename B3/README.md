# Task B3: Statement vs PreparedStatement vs Batch

**Files:** `PassLoader.java` (Complete the Code), `InsertLab.java` (all experiments), `Db.java`, schema in `../sql/test_db.sql` (`bus_pass` table)
**Run:** `javac -cp ../lib/mariadb-java-client-2.7.6.jar *.java && java -cp .:../lib/mariadb-java-client-2.7.6.jar InsertLab` · output: `output/InsertLab.txt`
Database: MariaDB 10.11 (MySQL-compatible), local; driver MariaDB Connector/J 2.7.6.

## Complete the Code
```java
for (String[] p : passes) {
    // TODO 1
    ps.setString(1, p[0]); ps.setString(2, p[1]); ps.setString(3, p[2]);
    ps.setInt(4, Integer.parseInt(p[3]));                 // route as int
    ps.setDouble(5, Double.parseDouble(p[4]));            // amount as double; "abc" -> NumberFormatException
    ps.addBatch();
    n++;
    if (n % 1000 == 0) ps.executeBatch();                 // send every 1,000 rows
}
ps.executeBatch();
con.commit();                                             // TODO 2: make every row permanent
return n;
...
} catch (SQLException | RuntimeException e) {
    con.rollback();                                       // TODO 3: undo every insert in this transaction
    throw e;
}
```
| Call | Returned | Rows in table | Time |
|---|---|---|---|
| `loadAll(1,000)` | 1,000 | 1,000 | 33 ms |
| `loadAll(10,000)` | 10,000 | 10,000 | 417 ms |
| `loadAll(100,000)` | 100,000 | 100,000 | 3,213 ms |
| `loadAll` with an **invalid amount at row 5,001** of 10,000 | `NumberFormatException` re-thrown | **0** (the first 5 batches = 5,000 rows were already *sent*, but rolled back) | |

## B3.1 Correctness on 10 rows
All three approaches stored **10 rows, every value equal to the input** (checked field by field with `SELECT`). `ResultSetMetaData`:
| column | type | size | scale | nullable |
|---|---|---|---|---|
| pass_id | VARCHAR | 12 | 0 | NO |
| reg_no | CHAR | 9 | 0 | NO |
| name | VARCHAR | 60 | 0 | NO |
| route_no | INTEGER | 11 | 0 | NO |
| amount | DECIMAL | 8 | 2 | NO |
| campus | VARCHAR | 20 | 0 | NO (default 'MAIN') |
The Java `double` amount arrives as `DECIMAL(8,2)` (3000 → `3000.00`), so money is stored exactly.

## B3.2 Larger data, auto-commit on (the JDBC default)
| rows | Statement | PreparedStatement | Batch (1,000) |
|---|---|---|---|
| 1,000 | 217 ms | 217 ms | 244 ms |
| 10,000 | 2,069 ms | 2,050 ms | 1,828 ms |
| 100,000 | 20,049 ms | 17,594 ms | 14,834 ms |

## B3.3 Auto-commit vs one transaction
| rows | PreparedStatement, auto-commit | PreparedStatement, 1 txn | Batch, auto-commit | **Batch, 1 txn** |
|---|---|---|---|---|
| 1,000 | 178 ms | 65 ms | 160 ms | **29 ms** |
| 10,000 | 1,821 ms | 577 ms | 1,496 ms | **284 ms** |
| 100,000 | 17,481 ms | 5,950 ms | 14,732 ms | **2,978 ms** |

**Failure half-way** (10,000 rows, row 5,001 has amount `abc`):
| Mode | Table after the failure |
|---|---|
| batch, auto-commit on | **5,000 rows**: half a file imported, and no record of where it stopped |
| `PassLoader.loadAll` (one transaction) | **0 rows**: all or nothing; fix the file and re-run |

## B3.4 Engineering trade-off
**What the measurements show:**
1. **Statement vs PreparedStatement:** about the same speed here (one round trip per row dominates). PreparedStatement wins on **safety** (no injection, B2), **correct typing** (no hand-formatting of numbers, dates or quotes), and on servers with prepared-statement caching.
2. **Commits dominate:** with auto-commit, **every row is a separate transaction**, each forcing a log flush to disk. One transaction made the same inserts **3–6× faster** (17.5 s → 5.9 s; batch 14.7 s → 3.0 s).
3. **Batching** reduces round trips. This driver's default still sends batched rows individually (pipelined), so plain batching gained only about 20%. With `rewriteBatchedStatements=true` it rewrites each batch into multi-row INSERTs: **100,000 rows in 0.6 s** (see the alternatives below).

**Decisions:**
- **(a) Nightly import of 100,000 passes →** `PreparedStatement` + `addBatch()` in batches of 1,000, **one transaction** (or one per chunk with a restart marker), with `rewriteBatchedStatements=true`. Validate first and report bad rows (B4). This takes about 0.6–3 s, and a failure leaves the table unchanged.
- **(b) A single pass issued at the counter →** **one `PreparedStatement` INSERT in its own short transaction** (auto-commit is fine for a single statement). Batching gives nothing for one row. It must commit immediately so the student's pass exists and other counters see it, and the PRIMARY KEY prevents duplicates across counters.

## Alternative concept: multi-row INSERT or `LOAD DATA`
```sql
INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount) VALUES ('P1','1923..','A',1,3000),('P2',...), ... ;   -- 1,000 rows per statement
LOAD DATA LOCAL INFILE '/tmp/bus_pass.csv' INTO TABLE bus_pass FIELDS TERMINATED BY ',' (pass_id, reg_no, name, route_no, amount);
```
**Measured, 100,000 rows, one transaction:**
| Approach | Time | Round trips | Client memory for a batch of b rows |
|---|---|---|---|
| PreparedStatement batch (driver default) | 3,103 ms | ≈ n (pipelined) | b sets of parameters: O(b) |
| PreparedStatement batch + `rewriteBatchedStatements=true` | **597 ms** | ≈ n / b | O(b): the driver builds the multi-row SQL |
| multi-row INSERT, 1,000 rows per statement | **551 ms** | n / 1,000 = 100 | one SQL string of ≈ 46,665 chars (≈ 91 KB in Java) per statement: O(b) |
| `LOAD DATA LOCAL INFILE` (incl. writing the CSV) | **495 ms** | 1 (the file is streamed) | O(1) in the client (streams the file) + O(n) temporary file on disk |
- **Total time:** all are **O(n)**. The difference is the constant: the number of **round trips and statement executions** (n vs n/b vs 1). With n = 100,000, cutting round trips from 100,000 to 100 gave a **5–6× speed-up**.
- **Trade-offs:** a hand-built multi-row INSERT is **string concatenation, so it has injection risk** unless every value is validated or escaped, and it is limited by `max_allowed_packet`. `LOAD DATA` is the fastest, but needs `local_infile` enabled on both client and server (often disabled for security), is MySQL-specific, and its error reporting is coarse (warnings, not per-row exceptions). The **batched PreparedStatement with rewriting** gets close to the same speed while staying safe, portable and transactional, so it is the recommended default.
