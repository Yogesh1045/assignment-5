import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** B3 - Statement vs PreparedStatement vs batch; auto-commit vs transaction; failure half-way; LOAD DATA / multi-row INSERT. */
public class InsertLab {

    static List<String[]> passes(int n) {
        List<String[]> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            l.add(new String[]{String.format("P%07d", i), String.format("1923%05d", i % 100000), "Student " + i, String.valueOf(1 + i % 40), String.valueOf(3000 + (i % 20) * 100)});
        return l;
    }

    static void clear(Connection c) throws SQLException { try (Statement s = c.createStatement()) { s.execute("TRUNCATE TABLE bus_pass"); } }
    static int count(Connection c) throws SQLException { try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM bus_pass")) { rs.next(); return rs.getInt(1); } }

    // ---------------- the three approaches (auto-commit ON unless the caller changes it) ----------------
    static void withStatement(Connection c, List<String[]> ps) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String[] p : ps)
                s.executeUpdate("INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount) VALUES ('" + p[0] + "','" + p[1] + "','" + p[2] + "'," + p[3] + "," + p[4] + ")");
        }
    }
    static void withPrepared(Connection c, List<String[]> ps) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount) VALUES (?,?,?,?,?)")) {
            for (String[] p : ps) { bind(s, p); s.executeUpdate(); }
        }
    }
    static void withBatch(Connection c, List<String[]> ps) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount) VALUES (?,?,?,?,?)")) {
            int n = 0;
            for (String[] p : ps) { bind(s, p); s.addBatch(); if (++n % 1000 == 0) s.executeBatch(); }
            s.executeBatch();
        }
    }
    static void bind(PreparedStatement s, String[] p) throws SQLException {
        s.setString(1, p[0]); s.setString(2, p[1]); s.setString(3, p[2]); s.setInt(4, Integer.parseInt(p[3])); s.setDouble(5, Double.parseDouble(p[4]));
    }

    // ---------------- alternatives ----------------
    static void multiRowInsert(Connection c, List<String[]> ps, int rowsPerStatement) throws SQLException {
        try (Statement s = c.createStatement()) {
            StringBuilder sb = new StringBuilder();
            int k = 0;
            for (String[] p : ps) {
                if (k == 0) sb.setLength(0);
                sb.append(k == 0 ? "INSERT INTO bus_pass(pass_id, reg_no, name, route_no, amount) VALUES " : ",")
                  .append("('").append(p[0]).append("','").append(p[1]).append("','").append(p[2]).append("',").append(p[3]).append(',').append(p[4]).append(')');
                if (++k == rowsPerStatement) { s.executeUpdate(sb.toString()); k = 0; }
            }
            if (k > 0) s.executeUpdate(sb.toString());
        }
    }
    static void loadData(Connection c, List<String[]> ps) throws Exception {
        Path csv = Files.createTempFile("bus_pass", ".csv");
        try (var w = Files.newBufferedWriter(csv)) { for (String[] p : ps) { w.write(String.join(",", p)); w.newLine(); } }
        try (Statement s = c.createStatement()) {
            s.execute("LOAD DATA LOCAL INFILE '" + csv.toAbsolutePath() + "' INTO TABLE bus_pass FIELDS TERMINATED BY ',' (pass_id, reg_no, name, route_no, amount)");
        } finally { Files.delete(csv); }
    }

    interface Job { void run(Connection c, List<String[]> p) throws Exception; }

    static double timeIt(Connection c, Job j, List<String[]> data, boolean autoCommit) throws Exception {
        clear(c);
        c.setAutoCommit(autoCommit);
        long t0 = System.nanoTime();
        j.run(c, data);
        if (!autoCommit) c.commit();
        long t = System.nanoTime() - t0;
        c.setAutoCommit(true);
        if (count(c) != data.size()) throw new IllegalStateException("row count mismatch");
        return t / 1e6;
    }

    public static void main(String[] args) throws Exception {
        Db.reset();
        try (Connection c = Db.connect()) {
            // ---------------- B3.1 correctness on 10 rows ----------------
            System.out.println("== B3.1 10 rows with each approach, verified with SELECT + ResultSetMetaData ==");
            List<String[]> ten = passes(10);
            for (Object[] a : new Object[][]{{"Statement", (Job) InsertLab::withStatement}, {"PreparedStatement", (Job) InsertLab::withPrepared}, {"Batch", (Job) InsertLab::withBatch}}) {
                clear(c); ((Job) a[1]).run(c, ten);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT pass_id, reg_no, name, route_no, amount FROM bus_pass ORDER BY pass_id")) {
                    int rows = 0; boolean same = true;
                    while (rs.next()) {
                        String[] e = ten.get(rows++);
                        same &= rs.getString(1).equals(e[0]) && rs.getString(2).equals(e[1]) && rs.getString(3).equals(e[2])
                                && rs.getInt(4) == Integer.parseInt(e[3]) && rs.getBigDecimal(5).doubleValue() == Double.parseDouble(e[4]);
                    }
                    System.out.printf("   %-18s rows=%d  every value equal to the input: %s%n", a[0], rows, same);
                }
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM bus_pass LIMIT 3")) {
                ResultSetMetaData md = rs.getMetaData();
                System.out.println("   ResultSetMetaData of bus_pass:");
                for (int i = 1; i <= md.getColumnCount(); i++)
                    System.out.printf("     %-9s %-8s size=%-3d scale=%d nullable=%s%n", md.getColumnName(i), md.getColumnTypeName(i), md.getPrecision(i), md.getScale(i),
                            md.isNullable(i) == ResultSetMetaData.columnNoNulls ? "NO" : "YES");
                System.out.println("   first rows:");
                while (rs.next()) System.out.printf("     %s %s %-10s %3d %8.2f %s%n", rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5), rs.getString(6));
            }

            // ---------------- B3.2 sizes ----------------
            System.out.println("\n== B3.2 insert time (ms), auto-commit ON (the JDBC default) ==");
            System.out.printf("   %9s %12s %18s %12s%n", "rows", "Statement", "PreparedStatement", "Batch(1000)");
            for (int n : new int[]{1_000, 10_000, 100_000}) {
                List<String[]> d = passes(n);
                double a = n <= 10_000 ? timeIt(c, InsertLab::withStatement, d, true) : timeIt(c, InsertLab::withStatement, d, true);
                double b = timeIt(c, InsertLab::withPrepared, d, true);
                double e = timeIt(c, InsertLab::withBatch, d, true);
                System.out.printf("   %,9d %12.0f %18.0f %12.0f%n", n, a, b, e);
            }

            // ---------------- B3.3 auto-commit vs one transaction ----------------
            System.out.println("\n== B3.3 batch insert: auto-commit ON vs one transaction (ms) ==");
            System.out.printf("   %9s %14s %16s %14s %16s%n", "rows", "Stmt autocommit", "Stmt 1 txn", "Batch autocommit", "Batch 1 txn");
            for (int n : new int[]{1_000, 10_000, 100_000}) {
                List<String[]> d = passes(n);
                System.out.printf("   %,9d %14.0f %16.0f %14.0f %16.0f%n", n, timeIt(c, InsertLab::withPrepared, d, true), timeIt(c, InsertLab::withPrepared, d, false),
                        timeIt(c, InsertLab::withBatch, d, true), timeIt(c, InsertLab::withBatch, d, false));
            }

            System.out.println("\n   Failure half-way: 10,000 rows, row 5,001 has amount 'abc'");
            List<String[]> bad = passes(10_000);
            bad.get(5000)[4] = "abc";
            clear(c);
            try { withBatch(c, bad); } catch (NumberFormatException e) { System.out.println("   auto-commit ON : failed with " + e.getClass().getSimpleName() + " -> rows left in table = " + count(c)); }
            clear(c);
            try { PassLoader.loadAll(c, bad); } catch (NumberFormatException e) { System.out.println("   PassLoader (1 txn): failed with " + e.getClass().getSimpleName() + " -> rows left in table = " + count(c)); }

            // ---------------- Complete the Code: loadAll at three sizes ----------------
            System.out.println("\n== PassLoader.loadAll (Complete the Code) ==");
            for (int n : new int[]{1_000, 10_000, 100_000}) {
                clear(c);
                long t0 = System.nanoTime(); int loaded = PassLoader.loadAll(c, passes(n)); long t = System.nanoTime() - t0;
                System.out.printf("   loadAll(%,7d passes) returned %,7d in %6.0f ms; rows in table = %,d%n", n, loaded, t / 1e6, count(c));
            }

            // ---------------- alternatives ----------------
            System.out.println("\n== Alternatives for 100,000 rows (ms) ==");
            List<String[]> big = passes(100_000);
            System.out.printf("   PreparedStatement batch, 1 txn     : %6.0f%n", timeIt(c, InsertLab::withBatch, big, false));
            System.out.printf("   multi-row INSERT, 1,000 rows each  : %6.0f%n", timeIt(c, (cc, p) -> multiRowInsert(cc, p, 1000), big, false));
            try { System.out.printf("   LOAD DATA LOCAL INFILE (CSV)       : %6.0f   (includes writing the CSV)%n", timeIt(c, InsertLab::loadData, big, false)); }
            catch (Exception e) { System.out.println("   LOAD DATA LOCAL INFILE             : not permitted on this server (" + e.getMessage() + ")"); }
            Runtime rt = Runtime.getRuntime();
            System.gc(); long m0 = rt.totalMemory() - rt.freeMemory();
            StringBuilder sb = new StringBuilder();
            for (String[] p : big.subList(0, 1000)) sb.append("('").append(p[0]).append("','").append(p[1]).append("','").append(p[2]).append("',").append(p[3]).append(',').append(p[4]).append("),");
            String url = System.getProperty("db.url", "jdbc:mariadb://localhost:3306/campus?allowLocalInfile=true");
            try (Connection rw = java.sql.DriverManager.getConnection(url + (url.contains("?") ? "&" : "?") + "rewriteBatchedStatements=true",
                    System.getProperty("db.user", "student"), System.getProperty("db.password", "student123"))) {
                System.out.printf("   batch, 1 txn, rewriteBatchedStatements=true: %6.0f   (driver rewrites each batch into multi-row INSERTs)%n",
                        timeIt(rw, InsertLab::withBatch, big, false));
            }
            System.out.printf("   one 1,000-row multi-row INSERT string = %,d chars (~%d KB as UTF-16 in the JVM)%n", sb.length(), sb.length() * 2 / 1024);
            clear(c);
        }
    }
}
