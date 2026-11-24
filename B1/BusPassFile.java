import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B1 - the bus-pass text store designed in A1 (full record: 7 fields).
 * Line format (UTF-8, one record per line):
 *   passId|regNo|name|route|stop|validUntil(yyyy-MM-dd)|amount
 * '|' and '\' inside a text field are escaped as '\|' and '\\'.
 */
public class BusPassFile {

    public record Pass(String passId, String regNo, String name, int route, String stop, LocalDate validUntil, double amount) { }

    /** A line that could not be parsed, with its line number and reason - collected, never fatal. */
    public record Damaged(int lineNo, String line, String reason) { }

    private final Path file;
    public final List<Damaged> damaged = new ArrayList<>();

    public BusPassFile(Path file) { this.file = file; }

    // ---------------------------------------------------------------- encoding
    static String esc(String s) { return s.replace("\\", "\\\\").replace("|", "\\|"); }

    /** Splits on unescaped '|' and un-escapes. */
    static List<String> splitLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) { cur.append(line.charAt(++i)); }
            else if (c == '|') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    static String encode(Pass p) {
        return String.join("|", esc(p.passId()), p.regNo(), esc(p.name()), String.valueOf(p.route()), esc(p.stop()),
                p.validUntil().toString(), String.format("%.2f", p.amount()));
    }

    /** Parses one line or throws IllegalArgumentException with the reason. */
    static Pass decode(String line) {
        if (line.isBlank()) throw new IllegalArgumentException("empty line");
        List<String> f = splitLine(line);
        if (f.size() != 7) throw new IllegalArgumentException("expected 7 fields, found " + f.size());
        if (!f.get(1).matches("\\d{9}")) throw new IllegalArgumentException("bad register number '" + f.get(1) + "'");
        int route; double amount; LocalDate valid;
        try { route = Integer.parseInt(f.get(3)); } catch (NumberFormatException e) { throw new IllegalArgumentException("route not a number '" + f.get(3) + "'"); }
        try { amount = Double.parseDouble(f.get(6)); } catch (NumberFormatException e) { throw new IllegalArgumentException("amount not a number '" + f.get(6) + "'"); }
        try { valid = LocalDate.parse(f.get(5)); } catch (DateTimeParseException e) { throw new IllegalArgumentException("bad date '" + f.get(5) + "'"); }
        if (amount < 0) throw new IllegalArgumentException("negative amount");
        return new Pass(f.get(0), f.get(1), f.get(2), route, f.get(4), valid, amount);
    }

    // ---------------------------------------------------------------- operations
    /** (i) add: append one line; flush+close in try-with-resources so the line is complete on disk. */
    public void add(Pass p) throws IOException {
        if (find(p.passId()) != null) throw new IllegalArgumentException("Pass ID already exists: " + p.passId());
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(encode(p));
            w.newLine();
        }
    }

    /** (ii) find by pass ID: sequential scan, damaged lines recorded and skipped. */
    public Pass find(String passId) throws IOException {
        damaged.clear();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line; int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                try {
                    Pass p = decode(line);
                    if (p.passId().equals(passId)) return p;
                } catch (IllegalArgumentException e) { damaged.add(new Damaged(n, line, e.getMessage())); }
            }
        } catch (NoSuchFileException e) {
            return null;                                   // no file yet = no passes issued
        }
        return null;
    }

    /** (iii) list all passes on a route. */
    public List<Pass> listByRoute(int route) throws IOException {
        damaged.clear();
        List<Pass> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line; int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                try { Pass p = decode(line); if (p.route() == route) out.add(p); }
                catch (IllegalArgumentException e) { damaged.add(new Damaged(n, line, e.getMessage())); }
            }
        } catch (NoSuchFileException e) { return out; }
        return out;
    }

    /** Bulk write used for large test files: write to .tmp then atomic rename (no half-written file). */
    public static void writeAll(Path file, List<Pass> passes) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp)) { for (Pass p : passes) { w.write(encode(p)); w.newLine(); } }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Alternative: load once into a HashMap keyed by pass ID. */
    public Map<String, Pass> loadIndex() throws IOException {
        Map<String, Pass> m = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line;
            while ((line = r.readLine()) != null) { try { Pass p = decode(line); m.put(p.passId(), p); } catch (IllegalArgumentException ignored) { } }
        }
        return m;
    }
}
