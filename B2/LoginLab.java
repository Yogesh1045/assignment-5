import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * B2 - adversarial inputs against (1) the unsafe string-built login check, (2) the PreparedStatement version,
 * (3) the escaping / regex alternatives, and (4) the bus-pass file store.
 * Uses ONLY the local test database created by ../sql/test_db.sql.
 */
public class LoginLab {

    /** The unsafe check from A5.2, exactly as given. */
    static boolean unsafeLogin(Connection c, String id, String pwd) throws Exception {
        String sql = "SELECT * FROM users WHERE id='" + id + "' AND pwd='" + pwd + "'";
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next();
        }
    }

    /** Safe: the SQL text is fixed; id and pwd travel as DATA, never parsed as SQL. */
    static boolean safeLogin(Connection c, String id, String pwd) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM users WHERE id = ? AND pwd = ?")) {
            ps.setString(1, id); ps.setString(2, pwd);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /** Alternative 1: escape single quotes by doubling them. */
    static boolean escapedLogin(Connection c, String id, String pwd) throws Exception {
        String sql = "SELECT * FROM users WHERE id='" + id.replace("'", "''") + "' AND pwd='" + pwd.replace("'", "''") + "'";
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { return rs.next(); }
    }

    /** Alternative 2: allow-list validation with a regex before building the string. */
    static final Pattern SAFE = Pattern.compile("[A-Za-z0-9@!._-]{1,30}");
    static boolean regexLogin(Connection c, String id, String pwd) throws Exception {
        if (!SAFE.matcher(id).matches() || !SAFE.matcher(pwd).matches()) return false;
        return unsafeLogin(c, id, pwd);
    }

    public static void main(String[] args) throws Exception {
        Db.reset();
        try (Connection c = Db.connect()) {
            String[][] inputs = {
                {"valid user, right password",            "192311001", "arun@123"},
                {"valid user, WRONG password",            "192311001", "guess"},
                {"tautology in pwd:  ' OR '1'='1",          "admin", "' OR '1'='1"},
                {"comment out the check:  admin'-- ",     "admin'-- ", "anything"},
                {"comment out (MySQL #):  admin'#",       "admin'#", "anything"},
                {"first user:  ' OR 1=1 LIMIT 1-- ",      "' OR 1=1 LIMIT 1-- ", "x"},
                {"backslash trick vs escaping:  \\'",     "admin\\'", " OR 1=1-- "},
            };
            System.out.printf("%-42s %-22s %-10s %-10s %-10s %-10s%n", "attack / input", "id", "unsafe", "Prepared", "escaped", "regex");
            for (String[] in : inputs) {
                System.out.printf("%-42s %-22s %-10s %-10s %-10s %-10s%n", in[0], in[1],
                        res(() -> unsafeLogin(c, in[1], in[2])), res(() -> safeLogin(c, in[1], in[2])),
                        res(() -> escapedLogin(c, in[1], in[2])), res(() -> regexLogin(c, in[1], in[2])));
            }
            String id = "admin", pwd = "' OR '1'='1";
            System.out.println("\nSQL actually executed by the unsafe version for the tautology attack:");
            System.out.println("  SELECT * FROM users WHERE id='" + id + "' AND pwd='" + pwd + "'");
            System.out.println("  -> WHERE (id='admin' AND pwd='') OR ('1'='1')   -> true for EVERY row");
            System.out.println("A legitimate password containing a quote, e.g. O'Brien#1, with the regex version: "
                    + res(() -> regexLogin(c, "192311001", "O'Brien#1")) + " (rejected before reaching the DB: false negatives for real users)");

            timing(c);
        }
        damagedFile();
    }

    interface Check { boolean run() throws Exception; }
    static String res(Check k) {
        try { return k.run() ? "ACCEPTED" : "rejected"; }
        catch (Exception e) { return "SQL ERROR"; }
    }

    static void timing(Connection c) throws Exception {
        System.out.println("\nCost per login check (median of 5 x 2,000 checks, valid user):");
        String[] names = {"unsafe Statement", "PreparedStatement (new each time)", "PreparedStatement (reused)", "escape + Statement", "regex + Statement"};
        PreparedStatement reused = c.prepareStatement("SELECT 1 FROM users WHERE id = ? AND pwd = ?");
        Check[] checks = {
            () -> unsafeLogin(c, "192311001", "arun@123"),
            () -> safeLogin(c, "192311001", "arun@123"),
            () -> { reused.setString(1, "192311001"); reused.setString(2, "arun@123"); try (ResultSet rs = reused.executeQuery()) { return rs.next(); } },
            () -> escapedLogin(c, "192311001", "arun@123"),
            () -> regexLogin(c, "192311001", "arun@123"),
        };
        for (int k = 0; k < checks.length; k++) {
            double[] t = new double[5];
            for (int r = -1; r < 5; r++) { long a = System.nanoTime(); for (int i = 0; i < 2000; i++) checks[k].run(); if (r >= 0) t[r] = (System.nanoTime() - a) / 2000e3; }
            Arrays.sort(t);
            System.out.printf("   %-36s %8.1f us%n", names[k], t[2]);
        }
        long a = System.nanoTime(); for (int i = 0; i < 1_000_000; i++) SAFE.matcher("192311001").matches();
        System.out.printf("   (the regex check alone: %.2f us; the escape alone: ", (System.nanoTime() - a) / 1e9);
        a = System.nanoTime(); for (int i = 0; i < 1_000_000; i++) "arun@123".replace("'", "''");
        System.out.printf("%.2f us)%n", (System.nanoTime() - a) / 1e9);
        reused.close();
    }

    /** Adversarial bus-pass file for the B1 store (7-field format). */
    static void damagedFile() throws IOException {
        System.out.println("\nDamaged bus-pass file fed to the B1 store logic:");
        Path f = Files.createTempFile("adversarial", ".txt");
        Files.writeString(f, String.join("\n",
                "P2001|192311001|Arun|12|Tambaram|2027-05-31|4500.00",
                "P2002|192311002|Too|Few|Fields",
                "P2003|192311003|Bala|7|Avadi|2027-05-31|45OO.00",           // letter O in the amount
                "",                                                         // empty line
                "P2004|192311004|Chitra|seven|Guindy|2027-05-31|3000",     // route not a number
                "P2005|19231|Dev|3|Porur|2027-05-31|3000",                  // short register number
                "P2006|192311006|Esha|3|Porur|31-05-2027|3000",             // wrong date format
                "P2007|192311007|Farah|3|Porur|2027-05-31|-50",             // negative amount
                "P2008|192311008|Gopi \\| Raj|9|Guindy|2027-05-31|3900.00", // escaped delimiter - valid
                "P2009|192311009|Hari|9|Guindy|2027-05-31|3900.00") + "\n");
        int ok = 0, n = 0;
        for (String line : Files.readAllLines(f)) {
            n++;
            try { BusPassRecord.decode(line); ok++; System.out.printf("   line %2d OK      %s%n", n, line); }
            catch (IllegalArgumentException e) { System.out.printf("   line %2d SKIPPED (%s)%n", n, e.getMessage()); }
        }
        System.out.println("   -> " + ok + " valid records loaded, " + (n - ok) + " damaged lines reported; no crash.");
        Files.delete(f);
    }
}
