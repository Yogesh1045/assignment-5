import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;

/** Re-maps parameter index i (column order, id first) to UPDATE order: columns 2..n become 1..n-1, the id becomes n. */
final class PreparedStatementShift {
    private final PreparedStatement target; private final int n;
    PreparedStatementShift(PreparedStatement t, int n) { target = t; this.n = n; }
    PreparedStatement proxy() {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, args) -> {
            if (m.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer i) args[0] = i == 1 ? n : i - 1;
            return m.invoke(target, args);
        });
    }
}
