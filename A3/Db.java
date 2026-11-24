import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Connection settings from -Ddb.url / -Ddb.user / -Ddb.password (defaults: local MariaDB test database). */
final class Db {
    private Db() { }
    static Connection connect() throws SQLException {
        return DriverManager.getConnection(System.getProperty("db.url", "jdbc:mariadb://localhost:3306/campus?allowLocalInfile=true"),
                System.getProperty("db.user", "student"), System.getProperty("db.password", "student123"));
    }
    /** Runs ../sql/test_db.sql so every program starts from the same known state. */
    static void reset() throws Exception {
        String script = Files.readString(Path.of("../sql/test_db.sql")).replaceAll("(?m)^--.*$", "");
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String stmt : script.split(";")) {
                String t = stmt.trim();
                if (!t.isEmpty() && !t.toUpperCase().startsWith("CREATE DATABASE") && !t.toUpperCase().startsWith("USE ")) s.execute(t);
            }
        }
    }
}
