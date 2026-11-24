import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * A4 evidence: transient fields, the object graph, serialVersionUID and versioning,
 * and Java serialization vs CSV vs JSON for n residents.
 * Versioning is shown by compiling an OLD Resident (v1) and a NEW Resident (v2 with phone) in a child JVM.
 */
public class HostelSerialization {

    static class Hostel implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        final String name; final List<Room> rooms = new ArrayList<>();
        Hostel(String n) { name = n; }
    }
    static class Room implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        final String number; final List<Resident> residents = new ArrayList<>();
        Hostel hostel;                                       // back-reference: a cycle in the graph
        Room(String n) { number = n; }
    }
    static class Resident implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        final String name, regNo;
        transient String password;                           // secret: never written to disk
        transient Connection db;                             // live resource: not serializable, meaningless after restart
        Room room;
        Resident(String n, String r, String p) { name = n; regNo = r; password = p; }
    }

    public static void main(String[] args) throws Exception {
        Hostel h = new Hostel("Lotus Block");
        for (int r = 1; r <= 2; r++) {
            Room room = new Room("L-10" + r); room.hostel = h; h.rooms.add(room);
            for (int k = 1; k <= 2; k++) { Resident x = new Resident("Resident " + r + k, "19231100" + r + k, "secret" + r + k); x.room = room; room.residents.add(x); }
        }
        byte[] bytes = serialize(h);
        Hostel back;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) { back = (Hostel) in.readObject(); }
        Resident first = back.rooms.get(0).residents.get(0);
        System.out.println("Serialized Hostel graph: " + bytes.length + " bytes");
        System.out.println("After reading back: name=" + first.name + ", regNo=" + first.regNo + ", password=" + first.password + ", db=" + first.db);
        System.out.println("Back-references preserved (same object, not a copy): " + (first.room.hostel == back) + ", " + (back.rooms.get(0).hostel == back));
        System.out.println("Password text present in the file bytes? " + new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("secret11"));

        // What happens WITHOUT transient on the Connection field: NotSerializableException
        class BadResident implements Serializable { Object db = new Object(); }   // Object is not Serializable, like Connection
        try { serialize(new BadResident()); } catch (java.io.NotSerializableException e) { System.out.println("Without transient on the connection: " + e); }

        compare();
        versioning();
    }

    static byte[] serialize(Object o) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(b)) { out.writeObject(o); }
        return b.toByteArray();
    }

    /** Size and time for n residents: Java serialization vs CSV vs JSON (hand-written, no library). */
    record R(String name, String regNo, String room) implements Serializable { }

    static void compare() throws Exception {
        System.out.println("\nn residents: size and save+load time (median of 5)");
        System.out.printf("%9s %12s %10s %10s %14s %12s %12s%n", "n", "serial KB", "CSV KB", "JSON KB", "serial ms", "CSV ms", "JSON ms");
        for (int n : new int[]{1_000, 10_000, 100_000}) {
            List<R> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(new R("Resident " + i, String.format("1923%05d", i % 100000), "L-" + (i % 500)));
            double[] ts = new double[5], tc = new double[5], tj = new double[5]; int ss = 0, sc = 0, sj = 0;
            for (int r = -1; r < 5; r++) {
                long a = System.nanoTime();
                byte[] s = serialize(new ArrayList<>(list));
                try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(s))) { in.readObject(); }
                long b = System.nanoTime();
                StringBuilder csv = new StringBuilder("name,regNo,room\n");
                for (R x : list) csv.append(x.name()).append(',').append(x.regNo()).append(',').append(x.room()).append('\n');
                byte[] c = csv.toString().getBytes();
                List<R> csvBack = new ArrayList<>(n);
                for (String line : new String(c).split("\n")) { if (line.startsWith("name,")) continue; String[] f = line.split(","); csvBack.add(new R(f[0], f[1], f[2])); }
                long d = System.nanoTime();
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < list.size(); i++) { R x = list.get(i); json.append(i == 0 ? "" : ",").append("{\"name\":\"").append(x.name()).append("\",\"regNo\":\"").append(x.regNo()).append("\",\"room\":\"").append(x.room()).append("\"}"); }
                json.append(']');
                byte[] j = json.toString().getBytes();
                List<R> jsonBack = new ArrayList<>(n);
                for (String obj : new String(j).split("\\},\\{")) { String[] kv = obj.replaceAll("[\\[\\]{}\"]", "").split(","); jsonBack.add(new R(kv[0].split(":")[1], kv[1].split(":")[1], kv[2].split(":")[1])); }
                long e = System.nanoTime();
                if (r >= 0) { ts[r] = (b - a) / 1e6; tc[r] = (d - b) / 1e6; tj[r] = (e - d) / 1e6; }
                ss = s.length; sc = c.length; sj = j.length;
            }
            java.util.Arrays.sort(ts); java.util.Arrays.sort(tc); java.util.Arrays.sort(tj);
            System.out.printf("%9d %12d %10d %10d %14.1f %12.1f %12.1f%n", n, ss / 1024, sc / 1024, sj / 1024, ts[2], tc[2], tj[2]);
        }
    }

    /** Old file + new class, with and without an explicit serialVersionUID - run in a child JVM. */
    static void versioning() throws Exception {
        System.out.println("\nVersioning: file written by Resident v1, read by Resident v2 (new field 'phone')");
        Path d = Files.createTempDirectory("ver");
        String v1 = "public class Resident implements java.io.Serializable { %s String name = \"Arun\"; String regNo = \"192311001\"; }";
        String v2 = "public class Resident implements java.io.Serializable { %s String name; String regNo; String phone; }";
        String writer = "public class W { public static void main(String[] a) throws Exception { try (var o = new java.io.ObjectOutputStream(new java.io.FileOutputStream(a[0]))) { o.writeObject(new Resident()); } } }";
        String reader = "public class R { public static void main(String[] a) throws Exception { try (var i = new java.io.ObjectInputStream(new java.io.FileInputStream(a[0]))) { Resident r = (Resident) i.readObject(); System.out.println(\"   read OK: name=\" + r.name + \", regNo=\" + r.regNo + \", phone=\" + r.phone); } catch (java.io.InvalidClassException e) { System.out.println(\"   FAILED: \" + e.getMessage()); } } }";
        for (String uid : new String[]{"", "private static final long serialVersionUID = 1L;"}) {
            System.out.println(uid.isEmpty() ? " (a) no serialVersionUID declared (computed from the class shape):" : " (b) serialVersionUID = 1L declared in both versions:");
            Path old = Files.createDirectories(d.resolve("old" + uid.length())), neu = Files.createDirectories(d.resolve("new" + uid.length()));
            Files.writeString(old.resolve("Resident.java"), String.format(v1, uid)); Files.writeString(old.resolve("W.java"), writer);
            Files.writeString(neu.resolve("Resident.java"), String.format(v2, uid)); Files.writeString(neu.resolve("R.java"), reader);
            run(old, "javac", "Resident.java", "W.java"); run(old, "java", "-cp", ".", "W", d.resolve("res.ser").toString());
            run(neu, "javac", "Resident.java", "R.java"); System.out.print(run(neu, "java", "-cp", ".", "R", d.resolve("res.ser").toString()));
        }
    }

    static String run(Path dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().remove("JAVA_TOOL_OPTIONS"); pb.environment().remove("_JAVA_OPTIONS");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }
}
