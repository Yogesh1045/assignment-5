import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Same line format and validation as ../B1/BusPassFile (copied so B2 compiles on its own). */
public class BusPassRecord {
    public record Pass(String passId, String regNo, String name, int route, String stop, LocalDate validUntil, double amount) { }
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

}
