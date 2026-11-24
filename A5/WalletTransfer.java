import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A5 evidence: transfer Rs 500 hostel -> canteen as ONE transaction vs two auto-commit updates,
 * with a crash injected between the two updates; then the time cost of each.
 */
public class WalletTransfer {
    static final String FROM = "192311001-HOSTEL", TO = "192311001-CANTEEN";

    static void update(Connection c, String owner, BigDecimal delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE wallet SET balance = balance + ? WHERE owner = ?")) {
            ps.setBigDecimal(1, delta); ps.setString(2, owner);
            if (ps.executeUpdate() != 1) throw new SQLException("wallet not found: " + owner);
        }
    }

    /** Transactional transfer, exactly as the A5.1 pseudocode. crash=true simulates a failure between the updates. */
    static void transferTx(Connection c, BigDecimal amt, boolean crash) throws SQLException {
        boolean old = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM wallet WHERE owner = ? FOR UPDATE")) {   // lock the source row
                ps.setString(1, FROM);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getBigDecimal(1).compareTo(amt) < 0) throw new SQLException("Insufficient balance");
                }
            }
            update(c, FROM, amt.negate());
            if (crash) throw new RuntimeException("simulated failure between the two updates");
            update(c, TO, amt);
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(old);
        }
    }

    /** Two independent auto-commit statements. */
    static void transferAuto(Connection c, BigDecimal amt, boolean crash) throws SQLException {
        update(c, FROM, amt.negate());                     // committed immediately
        if (crash) throw new RuntimeException("simulated failure between the two updates");
        update(c, TO, amt);
    }

    static String balances(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT owner, balance FROM wallet ORDER BY owner DESC"); ResultSet rs = ps.executeQuery()) {
            StringBuilder sb = new StringBuilder(); BigDecimal total = BigDecimal.ZERO;
            while (rs.next()) { sb.append(rs.getString(1).replace("192311001-", "")).append('=').append(rs.getBigDecimal(2)).append("  "); total = total.add(rs.getBigDecimal(2)); }
            return sb.append("TOTAL=").append(total).toString();
        }
    }

    public static void main(String[] args) throws Exception {
        BigDecimal five = new BigDecimal("500.00");
        Db.reset();
        try (Connection c = Db.connect()) {
            System.out.println("Start                                   : " + balances(c));
            transferTx(c, five, false);
            System.out.println("Transaction, success                    : " + balances(c));
            try { transferTx(c, five, true); } catch (RuntimeException e) { System.out.println("Transaction, crash between updates      : " + balances(c) + "   <- rolled back, nothing lost"); }
            try { transferTx(c, new BigDecimal("99999"), false); } catch (SQLException e) { System.out.println("Transaction, insufficient balance       : " + balances(c) + "   (" + e.getMessage() + ")"); }
            Db.reset();
            try { transferAuto(c, five, true); } catch (RuntimeException e) { System.out.println("Two auto-commits, crash between updates : " + balances(c) + "   <- Rs 500 VANISHED"); }

            System.out.println("\nTime per transfer (median of 5 runs x 500 transfers of Rs 1):");
            Db.reset();
            for (String mode : new String[]{"two auto-commit UPDATEs", "one transaction (SELECT..FOR UPDATE + 2 UPDATEs + COMMIT)"}) {
                double[] t = new double[5];
                for (int r = -1; r < 5; r++) {
                    long a = System.nanoTime();
                    Db.reset();                                   // same starting balances for every run
                    BigDecimal one = new BigDecimal("1.00");
                    a = System.nanoTime();
                    for (int i = 0; i < 500; i++) {             // 500 transfers of Rs 1 hostel -> canteen
                        if (mode.startsWith("two")) transferAuto(c, one, false); else transferTx(c, one, false);
                    }
                    if (r >= 0) t[r] = (System.nanoTime() - a) / 500e3;
                }
                java.util.Arrays.sort(t);
                System.out.printf("   %-58s %8.0f us%n", mode, t[2]);
            }
            System.out.println("Final                                   : " + balances(c));
        }
    }
}
