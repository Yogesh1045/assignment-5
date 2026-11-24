# Task B1: Implement and Instrument the File Store

**Files:** `PassStore.java` (Complete the Code), `BusPassFile.java` (the full A1 design: 7 fields, escaping, damaged-line report, atomic rewrite), `FileStoreLab.java` (tests, buffering and growth measurements)
**Run:** `javac *.java && java PassStore && java -Xmx2g FileStoreLab` (creates its test files in `work/`)
**Evidence:** `output/PassStore.txt`, `output/FileStoreLab.txt` (+ `.png`)

## Complete the Code
```java
public String[] find(String passId) throws IOException {
    try (BufferedReader r = Files.newBufferedReader(file)) {          // TODO 1
        String line;
        while ((line = r.readLine()) != null) {
            String[] f = line.split(",", -1);    // -1: keep empty trailing fields so they are counted
            if (f.length != 5) continue;         // damaged line: skip it, keep searching
            if (f[0].equals(passId)) return f;
        }
    }
    return null;
}
```
Output (matches the expected output):
```
[P1003, 192311003, Divya, 7, 3800.0]
null
```
**Why the damaged P1002 line does not stop the search:** `"P1002,DAMAGED LINE".split(",")` has **2 fields**, not 5, so `continue` skips that one line and the loop moves on to the next. No exception is thrown, and nothing indexes `f[3]` or `f[4]` on the short array, which would throw `ArrayIndexOutOfBoundsException`. That is why `find("P1003")`, which comes *after* the damaged line, still succeeds, and `find("P1002")` returns `null`: the only P1002 record is unusable.

## B1.1 Test table (full 7-field store, `BusPassFile`)
| Test | Input | Result | What it verifies |
|---|---|---|---|
| T0 | find on a file that does not exist yet | `null`, no exception | missing file = no passes (A1.3) |
| T1 normal | find `P1004` | the full Pass record | add + find, parsing of all 7 types |
| T2 delimiter in data | name `Mary \| Ann` | stored as `Mary \| Ann`, read back as `Mary | Ann` | escaping keeps field boundaries correct |
| T3 **missing ID** | find `P9999` | `null`; damaged line 3 reported | a full scan with no match returns null, and the bad line is recorded with its reason (`expected 7 fields, found 3`) |
| T4 **damaged line** | find `P1003` (its only line is damaged) | `null`, search continued | damaged lines are skipped, not fatal |
| T5 list | route 12 | `[P1001, P1002]` | list operation, including the escaped record |
| T6 list, empty | route 99 | `[]` | empty result is not an error |
| T7 duplicate | add `P1001` again | rejected: `Pass ID already exists` | the key stays unique |

## B1.2 Implementation vs the Part A pseudocode
- **Same structure** for add, find and list, and the same streams (`BufferedWriter` in append mode, `BufferedReader`).
- **Changes made while implementing:**
  1. `split("|")` cannot be used, because a regex `|` means "or", and splitting would also break on escaped bars. A small **character-by-character splitter** (`splitLine`) honours `\|` and `\\`.
  2. `split(",")` in `PassStore` **drops trailing empty fields**, so `"P1,R,N,7,"` looks like 4 fields. `split(",", -1)` was needed to count fields correctly.
  3. **Damaged-line reporting** became a list of `(lineNo, line, reason)` records instead of printing, so callers (and the B4 migration) can show or save the report.
  4. `NoSuchFileException` is caught specifically, so a missing file is treated as empty.
  5. The **amount** uses `%.2f` when writing, so `4500` and `4500.0` are stored identically.

## B1.3 Effect of buffering (counting lines)
| file | lines | `read()` calls (unbuffered) | unbuffered | `BufferedInputStream` 8 KB | `byte[64 KB]` | `Files.readAllLines` |
|---|---|---|---|---|---|---|
| 1 MB | 24,391 | 1,000,032 | 0.31 s | 0.022 s | 0.011 s | 0.020 s |
| 10 MB | 243,903 | 10,000,024 | 3.27 s | 0.218 s | 0.007 s | 0.064 s |
| 50 MB | 1,219,513 | 50,000,034 | **15.94 s** | 1.07 s | **0.022 s** | 0.234 s |

**Against the A2.1 prediction** (about 50 million native calls, ≈ 15 s for 50 MB): **confirmed**. Unbuffered time is **linear in size** at ≈ 0.32 µs per byte, i.e. per system call. Buffering divides the OS calls by 8,192, and the time drops 15×. What remains in the 8 KB version is one `synchronized` Java method call per byte; reading into a byte array removes that too (**≈ 700× faster than unbuffered**). `readAllLines` is fast but used **≈ 118 MB of heap** for the 50 MB file.

## B1.4 Growth of `find()`
| passes | file size | median find (random ID) | worst case (last ID) | HashMap: load once | HashMap: per get |
|---|---|---|---|---|---|
| 1,000 | 58 KB | 3.4 ms | 3.7 ms | 3.6 ms | ≈ 2 µs* |
| 10,000 | 592 KB | 18.2 ms | 23.8 ms | 25.4 ms | ≈ 1.4 µs* |
| 100,000 | 6,021 KB | 58.1 ms | 157 ms | 262 ms | ≈ 0.9 µs* |
\*mostly the cost of building the key with `String.format`; the `get` itself is about 50 ns.

**Trend:** `find()` reads and parses lines until the match, so it is **O(n)**: on average half the file, and the whole file for the last ID or a missing ID. Time grows roughly with file size: from 1k to 100k the worst case grew from 3.7 to 157 ms, about 42× for 100× the data. Small files are dominated by fixed costs (opening the file, JIT warm-up), which is why the 1k → 10k step is less than 10×.

## Alternative concept: load once into `HashMap<String, String[]>`
```java
Map<String, String[]> index = new HashMap<>();
try (BufferedReader r = Files.newBufferedReader(file)) {
    String line; while ((line = r.readLine()) != null) { String[] f = line.split(",", -1); if (f.length == 5) index.put(f[0], f); }
}
String[] find(String id) { return index.get(id); }         // O(1)
// add(): append to the file AND index.put(...) so both stay in step
```
| | scan every time | load once into HashMap |
|---|---|---|
| time for q searches on n passes | **O(q × n)**: 1,000 searches on 100k ≈ 1,000 × 58 ms ≈ **58 s** | **O(n + q)**: one 262 ms load + 1,000 × ≈ 50 ns ≈ **0.26 s** |
| memory | **O(1)**: one line at a time (8 KB buffer) | **O(n)**: about 300–400 B per pass (6 Strings + array + map entry), ≈ 35 MB for 100,000 passes |
| consistency | always reads the current file | must update the map on every add, and reload if another program changes the file |
The **break-even point is about 4–5 searches**. After that the map is always faster, so a counter application that searches all day should load the map at start-up.
