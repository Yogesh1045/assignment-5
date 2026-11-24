import java.util.List;
import java.util.Optional;

/** A3.2 - generic data-access interface: T = entity type, K = primary-key type. */
public interface Dao<T, K> {
    void insert(T entity) throws DataAccessException;
    Optional<T> findById(K id) throws DataAccessException;
    List<T> findAll() throws DataAccessException;
    boolean update(T entity) throws DataAccessException;
    boolean delete(K id) throws DataAccessException;

    /** One unchecked exception type for every DAO, wrapping SQLException with the operation that failed. */
    class DataAccessException extends RuntimeException {
        public DataAccessException(String op, Throwable cause) { super(op + " failed: " + cause.getMessage(), cause); }
    }
}
