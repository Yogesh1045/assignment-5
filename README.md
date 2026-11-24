# Programming in Java: Assignment 5 (CO5)
## Data Persistence Engineering: Files and Databases

| | |
|---|---|
| **Name** | Yogesh |
| **Register Number** | _(add your register number)_ |
| **Course** | CSA09 – Programming in Java |
| **Java version** | OpenJDK 21 (Java 17 or newer needed) |
| **Database** | MySQL 8 / MariaDB 10 local test database (`sql/test_db.sql`); JDBC driver in `lib/` |

**Part A** (A1–A5) holds the record layouts, pseudocode (SIMATS standard), diagrams and reasoning in each folder's README. The small Java programs there exist only to **measure** the "Alternative Concept" claims; they do not replace the pseudocode.
**Part B** (B1–B4) holds the implementation, test inputs, expected and actual outputs, measurements and interpretation.
Every folder has an `output/` directory (raw console output as `.txt`, plus a `.png` screenshot of it).

## How to run
```bash
mysql -u root -p < sql/test_db.sql        # creates database 'campus' and the test tables
# create the user once:  CREATE USER 'student'@'localhost' IDENTIFIED BY 'student123'; GRANT ALL ON campus.* TO 'student'@'localhost';
./run_all.sh                               # compiles and runs everything (≈ 6 minutes), regenerates output/
```
To use another server or user: `JAVA_OPTS="-Ddb.url=jdbc:mysql://host:3306/campus -Ddb.user=me -Ddb.password=secret" ./run_all.sh`. The MariaDB driver in `lib/` also connects to MySQL.

## Results
| Task | Marks | Result |
|---|---|---|
| [A1](A1) File format | 7 | Text (escaped `\|`) and DataOutputStream layouts; pseudocode for add/find/list; damaged-line, missing-file and torn-write design. Fixed-length RandomAccessFile: O(1) seek (84 µs vs 20 ms scan) but about 50% padding. |
| [A2](A2) Buffering design | 5 | Predicted about 50M native calls and ≈ 15 s for 50 MB; confirmed in B1 (15.9 s). `readAllLines` used 118 MB of heap vs an 8 KB buffer. |
| [A3](A3) DAO pattern | 5 | `Dao<T,K>` + `JdbcDao` base + 20-line `StudentDao`, CRUD verified on MySQL. A column rename touches 1 class instead of every caller; no measurable cost (457 vs 439 µs). |
| [A4](A4) Serialization | 4 | `password` and `Connection` transient (verified absent from the file); graph cycles preserved. Old file + new field: **fails** without `serialVersionUID`, **works** with it. CSV smallest and fastest. |
| [A5](A5) Transactions and injection | 4 | Crash between updates: the transaction keeps the total at ₹2,100; two auto-commits **lose ₹500**. Injection explained; PreparedStatement redesign. |
| [B1](B1) File store | 7 | **Complete the Code output matches.** 8-case test table; buffered 15× / byte array 700× faster; `find` grows O(n) (3.7 → 157 ms); HashMap breaks even after about 5 searches. |
| [B2](B2) Adversarial input | 5 | 4 injection attacks log in on the unsafe version and none on PreparedStatement; the **backslash trick defeats quote-escaping**. 7 kinds of file damage reported with no crash. |
| [B3](B3) Statement / Prepared / Batch | 8 | **Complete the Code:** loadAll 1k/10k/100k, and a half-way failure leaves **0 rows** (auto-commit leaves 5,000). One transaction 3–6× faster; rewritten batches / multi-row / LOAD DATA load 100k rows in about 0.5–0.6 s. |
| [B4](B4) Final decision | 5 | Migration: 99,023 loaded + 978 rejected = every line accounted for, with a failure report. DatabaseMetaData report. 12 concurrent counters: 0 duplicates. **Decision: shared MySQL database** (0.25 ms lookup vs 0.7 s across per-campus files). |
