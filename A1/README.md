# Task A1: Design a File Format (Transport Office Bus-Pass Records)

**Supporting program (alternative-concept measurements only):** `RandomAccessLab.java` · `javac RandomAccessLab.java && java RandomAccessLab` · output: `output/RandomAccessLab.txt`
The text-file design below is implemented and tested in **B1** (`../B1/BusPassFile.java`).

## A1.1 Record layouts
**(a) Delimited text file** (`passes.txt`, UTF-8, one pass per line)
```
passId | regNo | name | route | stop | validUntil | amount
P1001|192311001|Arun Kumar|12|Tambaram|2027-05-31|4500.00
P1002|192311002|Mary \| Ann|12|Guindy|2027-05-31|4500.00
```
| # | Field | Format | Example |
|---|---|---|---|
| 1 | passId | text, unique key | `P1001` |
| 2 | regNo | exactly 9 digits | `192311001` |
| 3 | name | free text | `Arun Kumar` |
| 4 | route | integer | `12` |
| 5 | boarding stop | free text | `Tambaram` |
| 6 | validity date | ISO `yyyy-MM-dd` (sorts and parses without ambiguity) | `2027-05-31` |
| 7 | amount paid | decimal with 2 places (rupees) | `4500.00` |
- **Delimiter `|`** rather than `,`: names and stops often contain commas ("Kumar, R."), but rarely a vertical bar.
- **A delimiter inside a field** is escaped: `|` → `\|` and `\` → `\\`. The reader splits only on an *unescaped* `|`. (Quoting with `"…"` as in CSV would also work; escaping keeps the parser to a single pass.)
- A line has **exactly 7 fields**; anything else is a damaged record. The newline is the record terminator, so a record without its final newline is incomplete (see A1.3).

**(b) Binary file written with `DataOutputStream`**
| Field | Write call | Size |
|---|---|---|
| passId | `writeUTF` | 2-byte length + UTF-8 bytes |
| regNo | `writeUTF` | 2 + 9 |
| name | `writeUTF` | 2 + n |
| route | `writeShort` | 2 |
| stop | `writeUTF` | 2 + n |
| validUntil | `writeLong(date.toEpochDay())` | 8 |
| amount | `writeLong(paise)` (integer paise, no floating-point error) | 8 |
`writeUTF` stores the **length before the text**, so a name containing `|`, `,` or a newline needs **no escaping**. The program printed the 58 bytes of one record: `00 05 50 31 30 30 31 …` = length 5, then "P1001". Records are variable-length, so they are read sequentially with `DataInputStream` in exactly the same order. A file header `int magic, short version` lets a later format change be detected.

## A1.2 Operations (text design), with stream choices
**Streams:** `BufferedWriter` over `FileWriter` (via `Files.newBufferedWriter`, in **append** mode) for add, and `BufferedReader` over `FileReader` (`Files.newBufferedReader`) for find and list. These are **character** streams because the format is text (they handle UTF-8 names such as "Ramesh Kumār"). They are **buffered** because records are short lines: `readLine()` needs a buffer, and buffering turns thousands of small reads into a few 8 KB OS reads (A2). `FileInputStream`/`FileOutputStream` are byte streams, appropriate for the binary design (b) but not for text lines.

```
ALGORITHM AddPass
INPUT  : p (passId, regNo, name, route, stop, validUntil, amount), file
OUTPUT : p appended to file, or an error
BEGIN
    IF FindPass(file, p.passId) ≠ NULL THEN
        RAISE ERROR "Pass ID already exists"
    END IF
    line ← Escape(p.passId) + "|" + p.regNo + "|" + Escape(p.name) + "|" + p.route + "|"
           + Escape(p.stop) + "|" + Format(p.validUntil, yyyy-MM-dd) + "|" + Format(p.amount, 2 decimals)
    OPEN writer ← BufferedWriter(FileWriter(file, APPEND, CREATE))       // try-with-resources
        writer.write(line); writer.newLine()
    CLOSE writer                                                         // flushes the buffer
END

ALGORITHM FindPass
INPUT  : file, id
OUTPUT : the pass with passId = id, or NULL
BEGIN
    IF file does not exist THEN RETURN NULL END IF
    OPEN reader ← BufferedReader(FileReader(file))
        lineNo ← 0
        WHILE (line ← reader.readLine()) ≠ NULL DO
            lineNo ← lineNo + 1
            fields ← SplitOnUnescapedBar(line)
            IF Valid(fields) = FALSE THEN                        // 7 fields, 9-digit regNo, numbers, date
                Report(lineNo, line, reason); CONTINUE           // damaged: skip, keep searching
            END IF
            IF fields[0] = id THEN RETURN ToPass(fields) END IF
        END WHILE
    CLOSE reader
    RETURN NULL
END

ALGORITHM ListPassesOnRoute
INPUT  : file, r
OUTPUT : list of passes whose route = r
BEGIN
    result ← empty list
    OPEN reader ← BufferedReader(FileReader(file))
        WHILE (line ← reader.readLine()) ≠ NULL DO
            fields ← SplitOnUnescapedBar(line)
            IF Valid(fields) AND ToInt(fields[3]) = r THEN APPEND ToPass(fields) TO result END IF
        END WHILE
    CLOSE reader
    RETURN result
END
```

## A1.3 Designing for damaged data
| Problem | Detection | Handling |
|---|---|---|
| **Damaged line** (wrong field count, non-numeric route or amount, bad date, 8-digit regNo, empty line) | `Valid(fields)` checks every field | the line is **skipped and reported** with its line number and reason; the search continues (one bad line never hides the other 7,999). B1 and B2 show 7 different kinds of damage handled this way. |
| **Missing file** | `NoSuchFileException` | for find/list it means "no passes issued yet", so return NULL / an empty list. For add, `CREATE` makes the file. Any other `IOException` is reported to the user. |
| **Incomplete write (power failure)** | (1) the last line has **no terminating newline**, or fails `Valid`; (2) an optional **per-line checksum** (CRC32 of the line as an 8th field) catches torn or garbled bytes | append mode means only the *last* record can be torn; it is detected and reported, and earlier records are intact. For whole-file rewrites (e.g. deleting a pass): write to `passes.txt.tmp`, `force()`/close, then **atomic rename** over the original, so the old file is valid until the new one is complete (`BusPassFile.writeAll` does this). Keep a daily backup copy. |

## Alternative concept: RandomAccessFile with fixed-length records
```
recordSize ← 4 (passNo int) + 9 (regNo) + 40 (name, padded) + 2 (route) + 20 (stop, padded) + 4 (date) + 4 (paise) + 1 (status) = 84 bytes
Find(i):  raf.seek(i × recordSize); read the fields              // pass number i is stored in slot i
Add(p):   i ← next free slot (or p's numeric id); raf.seek(i × recordSize); write the fields, padding text with zeros
Delete:   set status byte = 0 (slot can be reused)
```
**Measured** (`output/RandomAccessLab.txt`; finding the *last* pass):
| passes | text file | fixed-length file | padding wasted | find by ID: text scan | find by ID: seek |
|---|---|---|---|---|---|
| 1,000 | 57 KB | 82 KB | 51% | 1.8 ms | 40 µs |
| 10,000 | 582 KB | 820 KB | 50% | 11.9 ms | 59 µs |
| 100,000 | 5,924 KB | 8,203 KB | 49% | 20.1 ms | 84 µs |

- **Time:** text find is an **O(n)** scan. A fixed-length find is an **O(1)** seek *if the ID maps directly to a slot number* (P1001 → slot 1001). With arbitrary IDs it needs an index (HashMap or B-tree) or an O(n) scan again. The seek time is one open + seek + read, independent of n.
- **Space:** every record takes the *maximum* size (name 40 bytes, stop 20 bytes) whether it is "Arun" or a long name, so about **50% of the file is padding** here. Names longer than 40 bytes are **truncated**. It is O(n × recordSize) versus O(total actual bytes) for text.
- **Verdict:** for about 8,000 passes a year, the text file is small, readable and fast enough (≈ 2 ms per find). Fixed-length records suit lookup-heavy workloads with numeric IDs. At large scale a database (B3/B4) gives indexed lookup without the padding and truncation problems.
