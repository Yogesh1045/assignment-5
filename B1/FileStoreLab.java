import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** B1 - tests of the store, buffering measurements (A2 prediction check), growth of find, HashMap alternative. */
public class FileStoreLab {

    static BusPassFile.Pass pass(int i) {
        String[] stops = {"Poonamallee", "Tambaram", "Guindy", "Avadi", "Porur"};
        return new BusPassFile.Pass(String.format("P%06d", i), String.format("1923%05d", i % 100000), "Student " + i,
                1 + i % 40, stops[i % stops.length], LocalDate.of(2027, 5, 31), 3000 + (i % 20) * 100);
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of("work"); Files.createDirectories(work);
        tests(work);
        buffering(work);
        growth(work);
    }

    // ------------------------------------------------------------ B1.1
    static void tests(Path work) throws IOException {
        System.out.println("== B1.1 tests on the full 7-field store ==");
        Path f = work.resolve("passes.txt"); Files.deleteIfExists(f);
        BusPassFile s = new BusPassFile(f);
        System.out.println("T0 find on a missing file           -> " + s.find("P1") + "  (no exception: no file = no passes)");
        s.add(new BusPassFile.Pass("P1001", "192311001", "Arun", 12, "Tambaram", LocalDate.of(2027, 5, 31), 4500));
        s.add(new BusPassFile.Pass("P1002", "192311002", "Mary | Ann", 12, "Guindy", LocalDate.of(2027, 5, 31), 4500));   // delimiter in name
        Files.writeString(f, "P1003|192311003|Damaged line with too few fields\n", java.nio.file.StandardOpenOption.APPEND);
        s.add(new BusPassFile.Pass("P1004", "192311004", "Divya", 7, "Avadi", LocalDate.of(2027, 5, 31), 3800));
        System.out.println("File contents:\n" + Files.readString(f).indent(4));
        System.out.println("T1 normal find P1004                -> " + s.find("P1004"));
        System.out.println("T2 name containing the delimiter    -> " + s.find("P1002").name() + "   (escaped as \\| on disk)");
        System.out.println("T3 pass ID that does not exist P9999 -> " + s.find("P9999") + ", damaged lines seen: " + s.damaged);
        System.out.println("T4 find the DAMAGED record P1003     -> " + s.find("P1003") + "  (skipped and reported, search continued)");
        System.out.println("T5 list route 12                    -> " + s.listByRoute(12).stream().map(BusPassFile.Pass::passId).toList());
        System.out.println("T6 list route 99 (none)             -> " + s.listByRoute(99));
        try { s.add(new BusPassFile.Pass("P1001", "192311009", "Dup", 1, "X", LocalDate.of(2027, 5, 31), 1)); }
        catch (IllegalArgumentException e) { System.out.println("T7 duplicate pass ID on add         -> rejected: " + e.getMessage()); }
    }

    // ------------------------------------------------------------ B1.3 + A2
    static long countUnbuffered(Path p) throws IOException {
        long lines = 0, calls = 0;
        try (InputStream in = new FileInputStream(p.toFile())) {
            int b;
            while ((b = in.read()) != -1) { calls++; if (b == '\n') lines++; }   // one system call per byte
        }
        return lines;
    }

    static long countBuffered(Path p) throws IOException {
        long lines = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(p.toFile()), 8192)) {
            int b;
            while ((b = in.read()) != -1) if (b == '\n') lines++;                // read() served from the 8 KB buffer
        }
        return lines;
    }

    static long countByteArray(Path p) throws IOException {
        long lines = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(p.toFile())) {
            int n;
            while ((n = in.read(buf)) != -1) for (int i = 0; i < n; i++) if (buf[i] == '\n') lines++;
        }
        return lines;
    }

    static long countReadAllLines(Path p) throws IOException { return Files.readAllLines(p).size(); }

    static void buffering(Path work) throws IOException {
        System.out.println("\n== B1.3 counting lines: unbuffered vs buffered (attendance log, ~52-byte lines) ==");
        System.out.printf("%7s %12s %16s %14s %14s %16s %14s%n", "size", "lines", "read() calls*", "unbuffered s", "Buffered 8KB s", "byte[64KB] s", "readAllLines s");
        for (int mb : new int[]{1, 10, 50}) {
            Path f = work.resolve("attendance_" + mb + "MB.log");
            if (!Files.exists(f) || Files.size(f) < mb * 1_000_000L) {
                try (var w = Files.newBufferedWriter(f)) {
                    long written = 0; int i = 0;
                    while (written < mb * 1_000_000L) {
                        String l = String.format("2026-11-%02d,1923%05d,CSA09,PRESENT,Lab-3%n", 1 + i % 28, i % 100000);
                        w.write(l); written += l.length(); i++;
                    }
                }
            }
            long size = Files.size(f);
            double tu = time(() -> countUnbuffered(f)), tb = time(() -> countBuffered(f)), ta = time(() -> countByteArray(f)), tr = time(() -> countReadAllLines(f));
            long lines = countBuffered(f);
            System.out.printf("%5dMB %,12d %,16d %14.3f %14.3f %16.3f %14.3f%n", mb, lines, size + 1, tu, tb, ta, tr);
        }
        System.out.println("* unbuffered: one FileInputStream.read() native call per byte (+1 for EOF). Buffered 8 KB: about size/8192 OS reads.");
        Runtime rt = Runtime.getRuntime();
        System.gc(); long m0 = rt.totalMemory() - rt.freeMemory();
        List<String> all = Files.readAllLines(work.resolve("attendance_50MB.log"));
        long m1 = rt.totalMemory() - rt.freeMemory();
        System.out.printf("Memory: Files.readAllLines(50 MB) holds %,d Strings, heap grew ~%,d MB; buffered counter needs 8 KB%n", all.size(), (m1 - m0) >> 20);
    }

    interface IoJob { long run() throws IOException; }
    static double time(IoJob j) throws IOException {
        j.run();                                          // warm-up (also warms the OS file cache)
        double[] t = new double[3];
        for (int i = 0; i < 3; i++) { long a = System.nanoTime(); j.run(); t[i] = (System.nanoTime() - a) / 1e9; }
        Arrays.sort(t); return t[1];
    }

    // ------------------------------------------------------------ B1.4 + HashMap alternative
    static void growth(Path work) throws IOException {
        System.out.println("\n== B1.4 find() time as the file grows (median of 21 random finds; worst = last record) ==");
        System.out.printf("%9s %12s %14s %16s %18s %16s%n", "records", "file KB", "avg find ms", "worst-case ms", "HashMap load ms", "HashMap get us");
        for (int n : new int[]{1_000, 10_000, 100_000}) {
            Path f = work.resolve("passes_" + n + ".txt");
            List<BusPassFile.Pass> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(pass(i));
            BusPassFile.writeAll(f, list);
            BusPassFile s = new BusPassFile(f);
            Random r = new Random(1);
            for (int i = 0; i < 5; i++) s.find(String.format("P%06d", r.nextInt(n)));
            double[] t = new double[21];
            for (int i = 0; i < 21; i++) { String id = String.format("P%06d", r.nextInt(n)); long a = System.nanoTime(); s.find(id); t[i] = (System.nanoTime() - a) / 1e6; }
            Arrays.sort(t);
            long a = System.nanoTime(); s.find(String.format("P%06d", n - 1)); double worst = (System.nanoTime() - a) / 1e6;
            long l0 = System.nanoTime(); Map<String, BusPassFile.Pass> idx = s.loadIndex(); double load = (System.nanoTime() - l0) / 1e6;
            long g0 = System.nanoTime(); for (int i = 0; i < 100_000; i++) idx.get(String.format("P%06d", i % n)); double get = (System.nanoTime() - g0) / 1e3 / 100_000;
            System.out.printf("%9d %12d %14.2f %16.2f %18.1f %16.3f%n", n, Files.size(f) / 1024, t[10], worst, load, get);
        }
        System.out.println("q searches on n passes: scan = q x (avg find); HashMap = one load + q x get.");
    }
}
