# Task A2: Don't Read What You Don't Need (counting lines in a 50 MB attendance log)

The design is here; the implementation and measurements are in **B1** (`../B1/FileStoreLab.java`, section B1.3).

## A2.1 Predicted cost
```java
FileInputStream in = new FileInputStream("attendance.log");
int b; long lines = 0;
while ((b = in.read()) != -1) if (b == '\n') lines++;
```
- **Number of read calls:** `FileInputStream.read()` returns **one byte per call**, so a 50 MB file (≈ 50,000,000 bytes) needs **≈ 50,000,000 calls**, plus one more that returns −1.
- **Why it is slow:** `FileInputStream.read()` is a **native method**. Each call crosses from Java into the JVM's C code and makes a `read(fd, buf, 1)` **system call** into the OS kernel. That costs roughly 0.1–1 µs, compared with about 1 ns for reading a byte from memory. The data is in the OS page cache after the first run, so the time is almost entirely **call overhead, not disk**. **Prediction: about 50,000,000 × 0.3 µs ≈ 15 seconds**, compared with milliseconds if the bytes came from memory.

## A2.2 Redesign with buffering
```
ALGORITHM CountLinesBuffered
INPUT  : file
OUTPUT : number of '\n' bytes
BEGIN
    buf ← new byte[64 × 1024]                     // or: BufferedInputStream(FileInputStream(file), 8192)
    lines ← 0
    OPEN in ← FileInputStream(file)
        WHILE (n ← in.read(buf)) ≠ -1 DO          // ONE system call fills up to 65,536 bytes
            FOR i ← 0 TO n - 1 DO
                IF buf[i] = '\n' THEN lines ← lines + 1 END IF
            END FOR
        END WHILE
    CLOSE in
    RETURN lines
END
```
**How buffering changes the calls to the OS:** the operating system is asked for a *block* of bytes at a time. With a buffer of B bytes, the number of system calls falls from N to **⌈N / B⌉**: for 50 MB, 50,000,000 → **≈ 6,100** with 8 KB (`BufferedInputStream`), or **≈ 763** with 64 KB. The per-byte work becomes a plain array access in Java. `BufferedInputStream.read()` still makes one *Java method call* per byte (and it is `synchronized`), so reading into a byte array directly is faster still.

**Measured in B1 vs the prediction:**
| file | unbuffered `read()` | `BufferedInputStream` (8 KB) | `byte[64 KB]` loop | `Files.readAllLines` |
|---|---|---|---|---|
| 1 MB | 0.31 s | 0.022 s | 0.011 s | 0.020 s |
| 10 MB | 3.27 s | 0.218 s | 0.007 s | 0.064 s |
| 50 MB | **15.94 s** | 1.07 s | **0.022 s** | 0.234 s |
The prediction (≈ 15 s) was supported almost exactly. Time is **linear in file size** for every method; buffering changes the constant by **15×** (8 KB buffered stream) to **700×** (byte-array loop).

## A2.3 When buffering does not help
- When the program **already reads large blocks**: `in.read(byte[64 KB])`, `Files.readAllBytes` or `FileChannel.read(ByteBuffer)` make few system calls already. Wrapping them in a `BufferedInputStream` just copies every byte twice (OS → buffer → your array) for no gain. (Java's `BufferedInputStream` even bypasses its buffer for reads larger than the buffer.)
- When reading **one small record at a random position** (`RandomAccessFile.seek` + read 84 bytes): a buffer fills up with bytes you will not use, and the next seek throws them away.
- When the bottleneck is elsewhere: a slow network share or a CPU-heavy parse per line. Buffering reduces call overhead, not network latency or parsing time.

## Alternative concept: `Files.readAllLines()`
```java
long lines = Files.readAllLines(Path.of("attendance.log")).size();   // whole file -> List<String> in memory
```
- **Time:** still **O(file size)**, a single pass with large internal reads. Measured 0.23 s for 50 MB: fast, but slower than the byte loop, because it **decodes every byte to UTF-16 chars and creates 1,219,513 String objects**.
- **Memory:** **O(file size)** and then some. The 50 MB file became about **118 MB of heap** (UTF-16 chars plus about 40 B of object overhead per line). The buffered counter needs **O(buffer size) = 8–64 KB**, whatever the file size. A 2 GB log would make `readAllLines` throw `OutOfMemoryError`, while the streaming counter just takes longer.
- **When each is appropriate:** `readAllLines` is fine for **small files that you need in full**, more than once, or in random order (a 100-line config or the pass file at 8,000 lines). **Streaming** with a buffered reader or byte buffer suits **large or unbounded files** (logs, imports) and single-pass jobs such as counting, filtering and summing. `Files.lines()` (a lazy stream) gives the convenience of lines with streaming memory use.
