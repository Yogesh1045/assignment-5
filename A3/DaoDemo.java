import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** A3 evidence: StudentDao CRUD on a real table, and the cost of the DAO layer vs SQL written inline. */
public class DaoDemo {
    public static void main(String[] args) throws Exception {
        try (Connection c = Db.connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS student");
            s.execute("CREATE TABLE student (reg_no CHAR(9) PRIMARY KEY, full_name VARCHAR(60) NOT NULL, dept_code VARCHAR(6) NOT NULL, study_year INT NOT NULL, email VARCHAR(100) UNIQUE)");
        }
        Dao<Student, String> dao = new StudentDao();
        dao.insert(new Student("192311001", "Arun Kumar", "CSE", 4, "arun@simats.edu"));
        dao.insert(new Student("192311002", "Divya R", "IT", 4, "divya@simats.edu"));
        System.out.println("findAll           -> " + dao.findAll());
        System.out.println("findById(...002)  -> " + dao.findById("192311002").orElse(null));
        System.out.println("update year to 3  -> " + dao.update(new Student("192311002", "Divya R", "IT", 3, "divya@simats.edu")) + " : " + dao.findById("192311002").orElse(null));
        System.out.println("delete(...001)    -> " + dao.delete("192311001") + ", remaining " + dao.findAll().size());
        System.out.println("findById(missing) -> " + dao.findById("999999999"));
        try { dao.insert(new Student("192311002", "Dup", "IT", 1, "x@y.z")); } catch (Dao.DataAccessException e) { System.out.println("duplicate insert  -> DataAccessException: " + e.getMessage()); }

        System.out.println("\nCost per findById: DAO vs the same SQL written inline (median of 5 x 1,000):");
        double[] td = new double[5], ti = new double[5];
        for (int r = -1; r < 5; r++) {
            long a = System.nanoTime();
            for (int i = 0; i < 1000; i++) dao.findById("192311002");
            long b = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement("SELECT reg_no, full_name, dept_code, study_year, email FROM student WHERE reg_no = ?")) {
                    ps.setString(1, "192311002");
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) new Student(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)); }
                }
            }
            long c = System.nanoTime();
            if (r >= 0) { td[r] = (b - a) / 1000e3; ti[r] = (c - b) / 1000e3; }
        }
        java.util.Arrays.sort(td); java.util.Arrays.sort(ti);
        System.out.printf("   DAO       : %.0f us%n   inline SQL: %.0f us   (both dominated by opening a connection + the round trip)%n", td[2], ti[2]);
    }
}
