import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A3.1 - the steps that were copied in StudentDB, CourseDB and MarksDB, written ONCE:
 *   open connection -> prepare SQL -> bind parameters -> execute -> map ResultSet rows -> close everything.
 * Subclasses supply only what DIFFERS: table, columns, how to bind an entity, how to map a row.
 */
public abstract class JdbcDao<T, K> implements Dao<T, K> {
    protected abstract String table();
    protected abstract String idColumn();
    protected abstract String[] columns();                                   // all columns, id first
    protected abstract void bind(PreparedStatement ps, T e) throws SQLException;   // in columns() order
    protected abstract T map(ResultSet rs) throws SQLException;
    protected abstract K idOf(T e);

    interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private <R> R query(String sql, Binder b, boolean many, List<T> out) {
        try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            b.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { out.add(map(rs)); if (!many) break; }
            }
            return null;
        } catch (SQLException e) { throw new DataAccessException(sql.split(" ")[0], e); }
    }

    private int update(String sql, Binder b) {
        try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            b.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new DataAccessException(sql.split(" ")[0], e); }
    }

    @Override public void insert(T e) {
        String cols = String.join(", ", columns()), qs = "?" + ", ?".repeat(columns().length - 1);
        update("INSERT INTO " + table() + " (" + cols + ") VALUES (" + qs + ")", ps -> bind(ps, e));
    }
    @Override public Optional<T> findById(K id) {
        List<T> out = new ArrayList<>();
        query("SELECT " + String.join(", ", columns()) + " FROM " + table() + " WHERE " + idColumn() + " = ?", ps -> ps.setObject(1, id), false, out);
        return out.stream().findFirst();
    }
    @Override public List<T> findAll() {
        List<T> out = new ArrayList<>();
        query("SELECT " + String.join(", ", columns()) + " FROM " + table() + " ORDER BY " + idColumn(), ps -> { }, true, out);
        return out;
    }
    @Override public boolean update(T e) {
        StringBuilder set = new StringBuilder();
        for (int i = 1; i < columns().length; i++) set.append(i > 1 ? ", " : "").append(columns()[i]).append(" = ?");
        int n = columns().length;
        return update("UPDATE " + table() + " SET " + set + " WHERE " + idColumn() + " = ?", ps -> {
            // bind() fills 1..n in column order (id first); rebind for SET order (columns 2..n) then id last
            PreparedStatementShift shifted = new PreparedStatementShift(ps, n);
            bind(shifted.proxy(), e);
        }) == 1;
    }
    @Override public boolean delete(K id) {
        return update("DELETE FROM " + table() + " WHERE " + idColumn() + " = ?", ps -> ps.setObject(1, id)) == 1;
    }
}
