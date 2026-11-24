# Task B4: Final Engineering Decision (12 campuses, 100× scale, concurrent counters)

**Files:** `Migration.java` (migration + failure report, DatabaseMetaData, concurrent counters, per-campus serialized-file alternative), `Db.java`
**Run:** `javac -cp ../lib/mariadb-java-client-2.7.6.jar *.java && java -Xmx3g -cp .:../lib/mariadb-java-client-2.7.6.jar Migration` · output: `output/Migration.txt` (the failure report is written to `work/migration_failures.csv` when run)

## B4.1 Migration strategy (implemented)
1. **Read** the existing pass file line by line (`BufferedReader`, constant memory).
2. **Validate every line before touching the database**: field count, 9-digit regNo, numeric route and amount, empty lines, **duplicate pass IDs within the file**. Each failure becomes `(line number, reason, original text)`.
3. **Load the valid rows** with a `PreparedStatement` batch (1,000 rows per `executeBatch`) inside **one transaction**: commit at the end, roll back on any `SQLException`, so a database failure leaves the table unchanged and the job can simply be re-run.
4. **Write the failure report** (`migration_failures.csv`) for the office to correct and re-submit.

**Result on a 100,001-line legacy file with about 1% injected damage:**
```
Lines read 100,001 | loaded 99,023 | rejected 978 | loaded+rejected = 100,001 | 1.1 s
Failure report by reason: {bad register number=260, empty line=247, non-numeric route/amount=237,
                           expected 7 fields, found 6=233, duplicate pass ID=1}
   line     60  bad register number '12345'      P0000059|12345|Student 59|20|Stop5|2027-05-31|4900.00
   line    320  expected 7 fields, found 6       P0000319|192300319|Student 319|40|Stop4|2027-05-31
   line    385  empty line
Database now holds 99,023 passes, total amount Rs 391,153,200.00
```
**Every line is accounted for** (loaded + rejected = lines read), so no record is silently lost.

## B4.2 DatabaseMetaData
```
Database product : MariaDB 10.11.14-MariaDB-0ubuntu0.24.04.1
JDBC driver      : MariaDB Connector/J 2.7.6 (JDBC 4.2)
URL / user       : jdbc:mariadb://localhost:3306/campus?…&rewriteBatchedStatements=true / student
Transactions     : supported=true, default isolation=4 (REPEATABLE_READ), batch updates=true
Tables in catalog 'campus':
   bus_pass       PK=[pass_id] pass_id:VARCHAR reg_no:CHAR name:VARCHAR route_no:INT amount:DECIMAL campus:VARCHAR
   users          PK=[id] …      wallet  PK=[owner] …     (plus tables from other assignments in the same test database)
```
**Why this is useful when moving to another server:** the application can **check its environment at start-up instead of failing later**: (1) the product and version (MySQL 8 vs MariaDB vs PostgreSQL) decide which SQL features and syntax are safe, e.g. `LOAD DATA`, `ON DUPLICATE KEY`, CHECK constraints; (2) the driver version catches an outdated or mismatched driver JAR; (3) `supportsTransactions` and `supportsBatchUpdates` confirm the import design will work (e.g. MyISAM tables silently ignore rollback); (4) the table, column and primary-key listing verifies the schema was migrated correctly before the first pass is issued. Problems become one clear start-up error rather than a failure at the counter.

## Concurrent counters (measured)
12 counters (threads, each with its own connection) each issued 200 passes at the same time, then **all 12 tried to issue the same pass ID**:
```
inserted 2,401, rejected as duplicates 11 (expected 2,401 and 11) in 280 ms
```
The database's PRIMARY KEY and row locking let exactly one counter win and correctly refused the other 11, with **no code for locking in Java**.

## B4.3 Production decision: **a shared relational database (MySQL)**
| Requirement | Text file | Java serialization | **Database** |
|---|---|---|---|
| ~800,000 passes (100× of ~8,000) | find = O(n) scan: 58–157 ms at 100k (B1.4), so about 0.5–1.5 s per search at 800k | must **load the whole file** to read anything: 0.7 s to search all 12 campus files | **primary-key lookup 0.25 ms** (B-tree, O(log n)) |
| search by route, campus, regNo | full scan each time | full deserialization + scan | indexes (`idx_route`), plain SQL |
| **several counters at once** | appends from two processes can **interleave or corrupt** lines; no duplicate protection | each counter would overwrite the others' file: **lost updates** | **row locking + UNIQUE/PK**: 12 concurrent counters, 0 duplicates (measured) |
| safety on failure | partial lines (needs detection, A1.3) | a half-written file is unreadable | **transactions**: all or nothing (B3.3: 0 rows after a failed load; A5: no lost money) |
| bulk import | n/a | n/a | 100,000 rows in 0.6–3 s (B3) |
| security | file permissions only | deserializing untrusted data is a known risk; plain-text secrets (A4) | users and privileges, PreparedStatement (B2), backups, replication |
**Decision:** move to **one shared MySQL database**, with every counter connecting through a connection pool, using PreparedStatements, short transactions for each pass issued at a counter, batched transactional imports for bulk loads, and a `campus` column with an index. Text files remain useful only as an **import/export format** (CSV for the nightly import, the failure report, and backups), and serialization is not used for shared business data.

## Alternative concept: one serialized file per campus
```
campus_MAIN.ser, campus_NORTH.ser, … (12 files), each a serialized List<Pass>
search(passId): FOR each of the 12 files: deserialize the WHOLE list, scan it
issue(pass):    deserialize this campus's list, add, serialize the whole list back
```
**Measured (≈ 800,000 passes in total, 66,666 per campus):**
| | per-campus serialized files | shared database |
|---|---|---|
| write | 1.5 s for all 12 files, 37 MB | incremental: one INSERT per pass |
| find one pass across all campuses | **0.7 s** (deserialize 12 files, O(N) in time and memory) | **0.25 ms** (O(log N)) |
| space | 37 MB on disk + **all of a campus's objects in memory** to read even one pass | table + indexes on the server; the client holds only the rows it asked for |
| concurrent counters | a campus file has no locking: two counters loading, adding and saving at the same time means **the second save erases the first counter's passes**. Fixing this needs file locks (`FileChannel.lock`) held during the whole load-modify-save, so only one counter per campus can work at a time, and cross-campus duplicate checks need all 12 files | many counters in parallel, safe by row locks and keys |
| cross-campus questions (a student with passes on two campuses, university-wide totals) | open and deserialize all 12 files | one SQL query |
Per-campus files would scale **O(N)** for every search and serialize all writers on each campus. The database's indexed, transactional, concurrent design is required at this scale.
