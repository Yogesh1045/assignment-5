import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Everything specific to the student table - about 20 lines; no connection or close code. */
public class StudentDao extends JdbcDao<Student, String> {
    @Override protected String table() { return "student"; }
    @Override protected String idColumn() { return "reg_no"; }
    @Override protected String[] columns() { return new String[]{"reg_no", "full_name", "dept_code", "study_year", "email"}; }
    @Override protected String idOf(Student s) { return s.regNo(); }
    @Override protected void bind(PreparedStatement ps, Student s) throws SQLException {
        ps.setString(1, s.regNo()); ps.setString(2, s.name()); ps.setString(3, s.department()); ps.setInt(4, s.year()); ps.setString(5, s.email());
    }
    @Override protected Student map(ResultSet rs) throws SQLException {
        return new Student(rs.getString("reg_no"), rs.getString("full_name"), rs.getString("dept_code"), rs.getInt("study_year"), rs.getString("email"));
    }
}
