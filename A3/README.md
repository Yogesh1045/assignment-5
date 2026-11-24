# Task A3: Discover the Data-Access Design Pattern

**Files:** `Dao.java` (interface), `JdbcDao.java` (the shared steps, written once), `StudentDao.java` (only the student-specific parts), `Student.java`, `DaoDemo.java` (CRUD test + cost comparison)
**Run:** `javac -cp ../lib/mariadb-java-client-2.7.6.jar *.java && java -cp .:../lib/mariadb-java-client-2.7.6.jar DaoDemo` · output: `output/DaoDemo.txt`

## A3.1 The common pattern
Every method in `StudentDB`, `CourseDB` and `MarksDB` repeats the same skeleton:
| Step | Same in all three | Differs |
|---|---|---|
| 1. Get a connection (`DriverManager.getConnection(url, user, pwd)`) | ✔ | |
| 2. Build the SQL | the *shape* (`SELECT cols FROM t WHERE key = ?`, `INSERT INTO t (cols) VALUES (?, …)`) | **table name, column names, key column** |
| 3. Create a (Prepared)Statement and **bind parameters** | ✔ the calls | **which field goes into which `?`** |
| 4. Execute (`executeQuery` / `executeUpdate`) | ✔ | |
| 5. Loop over the `ResultSet`, **map each row to an object** | ✔ the loop | **which column becomes which field** (`new Student(rs.getString("reg_no"), …)`) |
| 6. Handle `SQLException` | ✔ | |
| 7. Close ResultSet, Statement, Connection (often forgotten, a leak) | ✔ | |

So the parts that **differ** are only: the **table**, the **columns**, the **key**, **entity → parameters** (binding) and **row → entity** (mapping). Everything else is duplicated boilerplate, so a bug fix (such as closing the connection) must be made three times.

## A3.2 Generic data-access interface
```java
public interface Dao<T, K> {                 // T = entity, K = primary-key type
    void insert(T entity);
    Optional<T> findById(K id);              // Optional: "not found" is a normal result, not null
    List<T> findAll();
    boolean update(T entity);                // true if a row was changed
    boolean delete(K id);
}
```
The shared steps go into an abstract `JdbcDao<T, K> implements Dao<T, K>`, which opens the connection, prepares, binds, executes, maps and closes **in one place** with try-with-resources, and wraps `SQLException` in one `DataAccessException`. A concrete DAO supplies only the five varying parts:
```java
public class StudentDao extends JdbcDao<Student, String> {
    protected String table()    { return "student"; }
    protected String idColumn() { return "reg_no"; }
    protected String[] columns(){ return new String[]{"reg_no", "full_name", "dept_code", "study_year", "email"}; }
    protected void bind(PreparedStatement ps, Student s) throws SQLException {
        ps.setString(1, s.regNo()); ps.setString(2, s.name()); ps.setString(3, s.department()); ps.setInt(4, s.year()); ps.setString(5, s.email());
    }
    protected Student map(ResultSet rs) throws SQLException {
        return new Student(rs.getString("reg_no"), rs.getString("full_name"), rs.getString("dept_code"), rs.getInt("study_year"), rs.getString("email"));
    }
    protected String idOf(Student s) { return s.regNo(); }
}
```
`CourseDao extends JdbcDao<Course, String>` and `MarksDao extends JdbcDao<Marks, Long>` follow the same pattern. Each is about 20 lines, with no connection or close code.

**Verified on a real table** (`output/DaoDemo.txt`): insert 2 → `findAll` returns both; `findById` works; `update` (year 4 → 3) returns `true` and is visible; `delete` returns `true`, leaving 1; a missing ID gives `Optional.empty`; a duplicate insert becomes `DataAccessException: INSERT failed: Duplicate entry '192311002' for key 'PRIMARY'`.

## A3.3 Object–table mapping
| `Student` field (Java) | Column (`student` table) | SQL type | Constraint |
|---|---|---|---|
| `regNo : String` | `reg_no` | `CHAR(9)` | **PRIMARY KEY** (the `K` of `Dao<Student, String>`) |
| `name : String` | `full_name` | `VARCHAR(60)` | NOT NULL |
| `department : String` | `dept_code` | `VARCHAR(6)` | NOT NULL (could be a FOREIGN KEY to `department`) |
| `year : int` | `study_year` | `INT` | NOT NULL |
| `email : String` | `email` | `VARCHAR(100)` | UNIQUE |
The names deliberately differ (`name` ↔ `full_name`), to show that the mapping lives in exactly two methods: `bind()` (object → columns) and `map()` (columns → object).

## Alternative concept: SQL written inside the service or GUI classes
```java
// in RegistrationPanel.onSave(), ReportService.monthly(), AdminScreen.search(), ...
try (Connection c = DriverManager.getConnection(...);
     PreparedStatement ps = c.prepareStatement("SELECT reg_no, full_name, ... FROM student WHERE reg_no = ?")) { ... }
```
- **Places to change when a column is renamed** (e.g. `full_name` → `student_name`): with a DAO, **1 class** (`StudentDao`: `columns()` and `map()`). With inline SQL, **every class that mentions the column**: if student data is used in s screens or services, that is O(s) edits, and a missed one fails only at **run time** (`Unknown column`). The same applies to changing databases, adding caching or fixing a connection leak.
- **Time per operation:** essentially **the same**. Both do one connection, one prepare and one round trip. The DAO adds a virtual call, building the SQL string from `columns()` (could be cached) and a lambda: a few microseconds against a database round trip of hundreds of microseconds. **Measured:** `findById` via DAO **≈ 457 µs**, inline **≈ 439 µs** (both dominated by opening a connection; a connection pool would cut both equally).
- **Space:** the DAO adds one small object per entity type (O(number of tables)), shared by every caller. Per operation, both allocate the same Statement, ResultSet and entity objects. **The difference is maintainability, not performance.**
