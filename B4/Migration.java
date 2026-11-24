import java.io.BufferedReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * B4 - migrate the pass file into the database (batches + one transaction + failure report),
 * DatabaseMetaData report, concurrent counters on the DB, and the 12-campus serialized-file alternative.
 */
public class Migration {

    record Row(int lineNo, String passId, String regNo, String name, int route, double amount, String campus) { }
    record Reject(int lineNo, String line, String reason) { }

    static final String[] CAMPUSES = {"MAIN", "NORTH", "SOUTH", "EAST", "WEST", "CITY", "HILL", "LAKE", "RIVER", "PARK", "VALLEY", "COAST"};

    /** Writes a realistic legacy file: 7-field lines, about 1% damaged in different ways. */
    static Path makeLegacyFile(Path f, int n) throws IOException {
        Random r = new Random(42);
        try (var w = Files.newBufferedWriter(f)) {
            for (int i = 0; i < n; i++) {
                String line = String.format("P%07d|1923%05d|Student %d|%d|Stop%d|2027-05-31|%d.00", i, i % 100000, i, 1 + i % 40, i % 9, 3000 + i % 20 * 100);
                int k = r.nextInt(400);
                if (k == 0) line = line.substring(0, line.lastIndexOf('|'));                  // missing field
                else if (k == 1) line = line.replace(".00", "x00");                          // non-numeric amount
                else if (k == 2) line = "";                                                    // empty line
                else if (k == 3) line = line.replaceFirst("\\|1923\\d{5}\\|", "|12345|");     // bad register number
                w.write(line); w.newLine();
            }
            w.write(String.format("P%07d|192300001|Duplicate|1|Stop1|2027-05-31|3000.00", 5)); w.newLine();   // duplicate pass ID
        }
        return f;
    }

    static Row parse(int lineNo, String line, String campus) {
        if (line.isBlank()) throw new IllegalArgumentException("empty line");
        String[] f = line.split("\\|", -1);
        if (f.length != 7) throw new IllegalArgumentException("expected 7 fields, found " + f.length);
        if (!f[1].matches("\\d{9}")) throw new IllegalArgumentException("bad register number '" + f[1] + "'");
        try { return new Row(lineNo, f[0], f[1], f[2], Integer.parseInt(f[3]), Double.parseDouble(f[6]), campus); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("non-numeric route/amount"); }
    }

    /** Validates every line first (report), then loads the valid rows in batches inside ONE transaction. */
    static int migrate(Connection c, Path file, String campus, List<Reject> rejects) throws Exception {
        List<Row> valid = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line; int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                try {
                    Row row = parse(n, line, campus);
                    Integer first = seen.putIfAbsent(row.passId(), n);
                    if (first != null) throw new IllegalArgumentException("duplicate pass ID (first on line " + first + ")");
                    valid.add(row);
                } catch (IllegalArgumentException e) { rejects.add(new Reject(n, line, e.getMessage())); }
            }
        }
        boolean old = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount, campus) VALUES (?,?,?,?,?,?)")) {
            int k = 0;
            for (Row row : valid) {
                ps.setString(1, row.passId()); ps.setString(2, row.regNo()); ps.setString(3, row.name());
                ps.setInt(4, row.route()); ps.setDouble(5, row.amount()); ps.setString(6, row.campus());
                ps.addBatch();
                if (++k % 1000 == 0) ps.executeBatch();
            }
            ps.executeBatch();
            c.commit();
            return valid.size();
        } catch (SQLException e) {
            c.rollback();                                   // all or nothing: rerun after fixing the cause
            throw e;
        } finally { c.setAutoCommit(old); }
    }

    public static void main(String[] args) throws Exception {
        Db.reset();
        String url = System.getProperty("db.url", "jdbc:mariadb://localhost:3306/campus?allowLocalInfile=true");
        System.setProperty("db.url", url + (url.contains("?") ? "&" : "?") + "rewriteBatchedStatements=true");
        Path dir = Path.of("work"); Files.createDirectories(dir);

        System.out.println("== B4.1 migration of the legacy pass file ==");
        Path legacy = makeLegacyFile(dir.resolve("passes_legacy.txt"), 100_000);
        List<Reject> rejects = new ArrayList<>();
        try (Connection c = Db.connect()) {
            long t0 = System.nanoTime();
            int loaded = migrate(c, legacy, "MAIN", rejects);
            long t = System.nanoTime() - t0;
            int lines = (int) Files.lines(legacy).count();
            System.out.printf("Lines read %,d | loaded %,d | rejected %,d | loaded+rejected = %,d | %.1f s%n", lines, loaded, rejects.size(), loaded + rejects.size(), t / 1e9);
            Map<String, Integer> byReason = new HashMap<>();
            for (Reject r : rejects) byReason.merge(r.reason().replaceAll("'.*'|\\(first.*\\)", "").trim(), 1, Integer::sum);
            System.out.println("Failure report by reason: " + byReason);
            Path report = dir.resolve("migration_failures.csv");
            try (var w = Files.newBufferedWriter(report)) {
                w.write("line,reason,content\n");
                for (Reject r : rejects) w.write(r.lineNo() + ",\"" + r.reason() + "\",\"" + r.line().replace("\"", "\"\"") + "\"\n");
            }
            System.out.println("First 5 rejected lines (full list in work/migration_failures.csv):");
            for (Reject r : rejects.subList(0, 5)) System.out.printf("   line %6d  %-40s %s%n", r.lineNo(), r.reason(), r.line());
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*), SUM(amount) FROM bus_pass")) {
                rs.next(); System.out.printf("Database now holds %,d passes, total amount Rs %,.2f%n", rs.getInt(1), rs.getDouble(2));
            }

            System.out.println("\n== B4.2 DatabaseMetaData ==");
            DatabaseMetaData md = c.getMetaData();
            System.out.println("Database product : " + md.getDatabaseProductName() + " " + md.getDatabaseProductVersion());
            System.out.println("JDBC driver      : " + md.getDriverName() + " " + md.getDriverVersion() + " (JDBC " + md.getJDBCMajorVersion() + "." + md.getJDBCMinorVersion() + ")");
            System.out.println("URL / user       : " + md.getURL().replaceAll("password=[^&]*", "password=***") + " / " + md.getUserName());
            System.out.println("Transactions     : supported=" + md.supportsTransactions() + ", default isolation=" + md.getDefaultTransactionIsolation()
                    + ", batch updates=" + md.supportsBatchUpdates());
            System.out.println("Tables in catalog 'campus':");
            try (ResultSet tb = md.getTables("campus", null, "%", new String[]{"TABLE"})) {
                while (tb.next()) {
                    String name = tb.getString("TABLE_NAME");
                    StringBuilder cols = new StringBuilder();
                    try (ResultSet col = md.getColumns("campus", null, name, "%")) { while (col.next()) cols.append(col.getString("COLUMN_NAME")).append(':').append(col.getString("TYPE_NAME")).append(' '); }
                    StringBuilder pk = new StringBuilder();
                    try (ResultSet p = md.getPrimaryKeys("campus", null, name)) { while (p.next()) pk.append(p.getString("COLUMN_NAME")).append(' '); }
                    System.out.printf("   %-14s PK=[%s] %s%n", name, pk.toString().trim(), cols);
                }
            }

            counters(c);
        }
        alternative(dir);
    }

    /** Several counters issuing passes at the same time: the database serialises them safely. */
    static void counters(Connection unused) throws Exception {
        System.out.println("\n== Concurrent counters on the shared database: 12 counters x 200 new passes, plus 12 attempts at the SAME pass ID ==");
        AtomicInteger ok = new AtomicInteger(), dup = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int k = 0; k < 12; k++) {
            final int counter = k;
            Thread t = new Thread(() -> {
                try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement("INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount, campus) VALUES (?,?,?,?,?,?)")) {
                    go.await();
                    for (int i = 0; i <= 200; i++) {
                        String id = i == 200 ? "PSAME" : String.format("C%02d%05d", counter, i);       // last one: everyone tries the same ID
                        ps.setString(1, id); ps.setString(2, "192399999"); ps.setString(3, "Counter " + counter); ps.setInt(4, 1); ps.setDouble(5, 3000); ps.setString(6, CAMPUSES[counter]);
                        try { ps.executeUpdate(); ok.incrementAndGet(); } catch (SQLException e) { dup.incrementAndGet(); }
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            ts.add(t); t.start();
        }
        go.countDown();
        for (Thread t : ts) t.join();
        System.out.printf("   inserted %,d, rejected as duplicates %d (expected 2,401 and 11) in %.0f ms%n", ok.get(), dup.get(), (System.nanoTime() - t0) / 1e6);
    }

    // ---------------- alternative: one serialized file per campus ----------------
    record SPass(String passId, String regNo, String name, int route, double amount) implements Serializable { }

    static void alternative(Path dir) throws Exception {
        System.out.println("\n== Alternative: 12 campuses, each with its own serialized file (100x scale ~ 800,000 passes in total) ==");
        int perCampus = 800_000 / 12;
        long totalBytes = 0;
        long w0 = System.nanoTime();
        for (int k = 0; k < 12; k++) {
            List<SPass> l = new ArrayList<>(perCampus);
            for (int i = 0; i < perCampus; i++) l.add(new SPass(String.format("%s%06d", CAMPUSES[k].charAt(0), i), "192300001", "Student " + i, 1 + i % 40, 3000));
            Path f = dir.resolve("campus_" + CAMPUSES[k] + ".ser");
            try (ObjectOutputStream o = new ObjectOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(f)))) { o.writeObject(l); }
            totalBytes += Files.size(f);
        }
        System.out.printf("   write 12 files: %.1f s, total size %,d MB%n", (System.nanoTime() - w0) / 1e9, totalBytes >> 20);
        long s0 = System.nanoTime(); int found = 0;
        for (int k = 0; k < 12; k++) {                                  // search ALL campuses for one register number
            try (ObjectInputStream in = new ObjectInputStream(new java.io.BufferedInputStream(Files.newInputStream(dir.resolve("campus_" + CAMPUSES[k] + ".ser"))))) {
                @SuppressWarnings("unchecked") List<SPass> l = (List<SPass>) in.readObject();
                for (SPass p : l) if (p.passId().equals("W012345")) found++;
            }
        }
        System.out.printf("   find one pass across all campuses = deserialize all 12 files: %.1f s (found %d)%n", (System.nanoTime() - s0) / 1e9, found);
        try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement("SELECT * FROM bus_pass WHERE pass_id = ?")) {
            ps.setString(1, "P0050000");
            long q0 = System.nanoTime(); try (ResultSet rs = ps.executeQuery()) { rs.next(); } long q = System.nanoTime() - q0;
            ps.setString(1, "P0050001"); q0 = System.nanoTime(); try (ResultSet rs = ps.executeQuery()) { rs.next(); } q = System.nanoTime() - q0;
            System.out.printf("   same lookup in the shared database (primary key): %.2f ms%n", q / 1e6);
        }
        for (String k : CAMPUSES) Files.deleteIfExists(dir.resolve("campus_" + k + ".ser"));
    }
}
