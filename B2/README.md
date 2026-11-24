# Task B2: Engineer an Adversarial Input

**Files:** `LoginLab.java` (unsafe, PreparedStatement, escaping and regex versions; damaged file), `BusPassRecord.java` (the same validation as B1), `Db.java`
**Database:** only the local test database from `../sql/test_db.sql` (table `users`, created for this assignment).
**Run:** `javac -cp ../lib/mariadb-java-client-2.7.6.jar *.java && java -cp .:../lib/mariadb-java-client-2.7.6.jar LoginLab` · output: `output/LoginLab.txt`

## Adversarial logins
| Input (`id` / `pwd`) | Unsafe string SQL | PreparedStatement | Escape `'`→`''` | Regex allow-list |
|---|---|---|---|---|
| `192311001` / `arun@123` (valid) | ACCEPTED | ACCEPTED | ACCEPTED | ACCEPTED |
| `192311001` / `guess` (wrong pwd) | rejected | rejected | rejected | rejected |
| `admin` / **`' OR '1'='1`** | **ACCEPTED** | rejected | rejected | rejected |
| **`admin'-- `** / anything | **ACCEPTED** | rejected | rejected | rejected |
| **`admin'#`** / anything | **ACCEPTED** | rejected | rejected | rejected |
| **`' OR 1=1 LIMIT 1-- `** / x | **ACCEPTED** | rejected | rejected | rejected |
| **`admin\'`** / ` OR 1=1-- ` | rejected | rejected | **ACCEPTED** | rejected |

**What each reveals:**
- **Tautology** `' OR '1'='1`: the query becomes `… AND pwd='' OR '1'='1'`, true for every row. The unsafe design lets **data change the logic** of the query.
- **Comment** `admin'-- ` / `admin'#`: everything after the comment marker, *including the password check*, is ignored. The attacker needs only a user name.
- **`' OR 1=1 LIMIT 1`**: logs in as the first user in the table, often the administrator, with **no name or password known**.
- **Backslash trick against escaping:** MySQL/MariaDB treat `\'` as an escaped quote. Doubling quotes turns `admin\'` into `admin\''`; the server reads `\'` as a literal quote and the *second* `'` **closes the string**, so `OR 1=1-- ` runs as SQL. **Escaping that does not match the server's exact parsing rules can be bypassed.**
- **PreparedStatement rejected every attack:** the parameters were never parsed as SQL.

## Damaged bus-pass file
| Line | Content (shortened) | Store's response |
|---|---|---|
| 1 | valid | loaded |
| 2 | `P2002|…|Too|Few|Fields` | skipped: expected 7 fields, found 5 |
| 3 | amount `45OO.00` (letter O) | skipped: amount not a number |
| 4 | *(empty line)* | skipped: empty line |
| 5 | route `seven` | skipped: route not a number |
| 6 | regNo `19231` | skipped: bad register number |
| 7 | date `31-05-2027` | skipped: bad date |
| 8 | amount `-50` | skipped: negative amount |
| 9 | name `Gopi \| Raj` (escaped bar) | loaded: the escape works |
| 10 | valid | loaded |
**Result:** 3 valid records loaded, 7 damaged lines reported with reasons, **no crash**. This shows the original `PassStore` check (`fields == 5`) was too weak: it would have accepted lines 3, 5, 6, 7 and 8 and failed later in `parseDouble`, or stored a negative fee. **Every field needs validating, not just the count.**

## Alternative concept: escaping or regex validation instead of PreparedStatement
```java
String sql = "SELECT * FROM users WHERE id='" + id.replace("'", "''") + "' AND pwd='" + pwd.replace("'", "''") + "'";
// or
if (!id.matches("[A-Za-z0-9@!._-]{1,30}") || !pwd.matches("[A-Za-z0-9@!._-]{1,30}")) return false;   // then build the string
```
**Why it is weaker:**
1. **The query is still built from text**, so safety depends on the escape rules matching the server *exactly*: character sets, backslash mode (`NO_BACKSLASH_ESCAPES`), quote types, other servers. The backslash attack above **bypassed** quote doubling.
2. **Every developer, at every query, must remember** to escape or validate. One forgotten call reopens the hole. A PreparedStatement is safe by construction.
3. **A regex allow-list blocks legitimate data:** a real password `O'Brien#1` or a name like D'Souza is **rejected** (observed), and it is easy to write a pattern that is either too strict or still unsafe.
4. Escaping handles strings only. Numbers and identifiers (`ORDER BY` + input) need separate handling.

**Time cost** (median of 5 × 2,000 checks):
| Version | per check |
|---|---|
| unsafe Statement | ≈ 28 µs |
| PreparedStatement (new each time) | ≈ 27 µs |
| PreparedStatement (reused) | ≈ 28–62 µs (noise; same order) |
| escape + Statement | ≈ 30–65 µs |
| regex + Statement | ≈ 28–66 µs |
| *the regex check alone* | **0.14 µs** |
| *the escape alone* | **0.01 µs** |
The validation itself costs **O(length of input)**, a fraction of a microsecond. Every version is dominated by the **database round trip** (≈ 30 µs locally), so a PreparedStatement is **no slower**. Server-side, a reused PreparedStatement can even skip re-parsing. **There is no performance reason to prefer escaping.** Validation is still useful as an extra check on *business* rules (e.g. regNo is 9 digits), but never as the injection defence.
