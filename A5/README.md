# Task A5: Design for Correctness and Safety

**Supporting program:** `WalletTransfer.java` (the transaction pseudocode implemented, with a crash injected) · injection experiments are in **B2**
**Run:** `javac -cp ../lib/mariadb-java-client-2.7.6.jar *.java && java -cp .:../lib/mariadb-java-client-2.7.6.jar WalletTransfer` · output: `output/WalletTransfer.txt`

## A5.1 Transaction design: transfer ₹500 hostel → canteen
```
ALGORITHM TransferWallet
INPUT  : con (JDBC connection), from ← "HOSTEL", to ← "CANTEEN", amt ← 500.00
OUTPUT : both balances changed, or neither
BEGIN
    old ← con.getAutoCommit()
    con.setAutoCommit(FALSE)                                    // start ONE transaction
    TRY
        bal ← SELECT balance FROM wallet WHERE owner = from FOR UPDATE   // lock the source row
        IF bal < amt THEN RAISE ERROR "Insufficient balance" END IF
        n1 ← UPDATE wallet SET balance = balance - amt WHERE owner = from
        n2 ← UPDATE wallet SET balance = balance + amt WHERE owner = to
        IF n1 ≠ 1 OR n2 ≠ 1 THEN RAISE ERROR "Wallet not found" END IF
        con.commit()                                            // both changes become permanent together
    CATCH any SQLException or RuntimeException
        con.rollback()                                          // undo BOTH updates: no money lost or created
        RE-RAISE the error                                      // caller shows "Transfer failed"
    FINALLY
        con.setAutoCommit(old)
    END TRY
END
```
All statements are `PreparedStatement`s with `?` parameters, and money is held as `DECIMAL(10,2)` / `BigDecimal`, never `double`. The `CHECK (balance >= 0)` constraint is a second safety net.

**Executed** (`output/WalletTransfer.txt`):
```
Start                                   : HOSTEL=2000.00  CANTEEN=100.00  TOTAL=2100.00
Transaction, success                    : HOSTEL=1500.00  CANTEEN=600.00  TOTAL=2100.00
Transaction, crash between updates      : HOSTEL=1500.00  CANTEEN=600.00  TOTAL=2100.00   <- rolled back, nothing lost
Transaction, insufficient balance       : HOSTEL=1500.00  CANTEEN=600.00  TOTAL=2100.00
Two auto-commits, crash between updates : HOSTEL=1500.00  CANTEEN=100.00  TOTAL=1600.00   <- Rs 500 VANISHED
```

## A5.2 Injection analysis
```java
String sql = "SELECT * FROM users WHERE id='" + id + "' AND pwd='" + pwd + "'";
```
The user's input becomes **part of the SQL code**. With `id = admin` and `pwd = ' OR '1'='1` the database receives:
```sql
SELECT * FROM users WHERE id='admin' AND pwd='' OR '1'='1'
--  AND binds tighter than OR:  (id='admin' AND pwd='')  OR  ('1'='1')   → TRUE for every row
```
The query returns rows, `rs.next()` is true, and the attacker is **logged in without the password**. Other inputs work too: `id = admin'-- ` (the rest of the line, including the password check, becomes a comment) or `admin'#` on MySQL. B2 shows all of them succeeding against the unsafe version on the test database.

**Redesign with `PreparedStatement`:**
```java
boolean login(Connection c, String id, String pwd) throws SQLException {
    String sql = "SELECT pwd_hash, salt FROM users WHERE id = ?";       // SQL text fixed at compile time
    try (PreparedStatement ps = c.prepareStatement(sql)) {
        ps.setString(1, id);                                            // sent as DATA, never parsed as SQL
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() && PasswordHasher.matches(pwd, rs.getString("salt"), rs.getString("pwd_hash"));
        }
    }
}
```
The SQL structure is sent and parsed **before** any input exists. The values are transmitted separately as typed parameters, so `' OR '1'='1` is just a strange password string that matches nothing. (Passwords should also be stored as salted hashes, e.g. PBKDF2 or bcrypt, and compared in Java, not stored in plain text as in the demo table.)

## Alternative concept: two separate auto-commit statements
```java
con.setAutoCommit(true);                                    // JDBC default
update("UPDATE wallet SET balance = balance - 500 WHERE owner = 'HOSTEL'");   // COMMITTED immediately
// <-- crash, exception, power cut, network drop here
update("UPDATE wallet SET balance = balance + 500 WHERE owner = 'CANTEEN'");
```
- **Outcome on failure between them:** the debit is already permanent and the credit never happens, so **₹500 disappears** (measured: total 2,100 → 1,600). In the reverse order, money would be **created**. Nothing records that a transfer was half done, so finding and repairing it needs a manual audit.
- **Time:** both designs do the same 2 updates, which is **O(1)** work. The transaction adds a `SELECT … FOR UPDATE`, one `COMMIT`, and holds row locks until commit. Each auto-commit statement also pays its own commit (a log flush). **Measured: 489 µs (two auto-commits) vs 529 µs (transaction)**, about 8% more for full safety. Under heavy contention, transactions on the *same* wallet wait for each other's locks, which is exactly what prevents two concurrent transfers from both spending the same ₹500.
- **Risk:** auto-commit pairs give a **non-zero probability of permanent inconsistency** for every failure. A transaction gives **atomicity** (all or nothing) and, with `FOR UPDATE`, **isolation** from concurrent transfers. For money, the 40 µs is always worth it.
