import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * A1 alternative concept: fixed-length records in a RandomAccessFile (record i at offset i x RECORD)
 * vs a delimited text file scanned line by line. Also writes one record in the DataOutputStream
 * binary layout designed in A1.1(b) and prints its bytes.
 */
public class RandomAccessLab {
    // Fixed-length layout (bytes): passNo int 4 | regNo 9 | name 40 | route short 2 | stop 20 | validUntil (epochDay) int 4 | amountPaise int 4 | status 1
    static final int NAME = 40, STOP = 20, RECORD = 4 + 9 + NAME + 2 + STOP + 4 + 4 + 1;   // 84 bytes

    static byte[] fixed(String s, int len) {
        byte[] out = new byte[len];                                  // zero padding
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(b, 0, out, 0, Math.min(len, b.length));
        return out;
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("work"); Files.createDirectories(dir);
        System.out.println("Fixed record size = " + RECORD + " bytes");
        System.out.printf("%9s %12s %14s %16s %18s %14s%n", "passes", "text KB", "fixed KB", "padding wasted", "text scan find ms", "RAF seek find us");
        for (int n : new int[]{1_000, 10_000, 100_000}) {
            Path text = dir.resolve("passes_" + n + ".txt"), bin = dir.resolve("passes_" + n + ".dat");
            long usefulBytes = 0;
            try (var w = Files.newBufferedWriter(text);
                 RandomAccessFile raf = new RandomAccessFile(bin.toFile(), "rw")) {
                raf.setLength(0);
                for (int i = 0; i < n; i++) {
                    String name = "Student " + i, stop = new String[]{"Tambaram", "Guindy", "Avadi", "Porur"}[i % 4];
                    w.write(String.format("P%06d|1923%05d|%s|%d|%s|2027-05-31|%d.00%n", i, i % 100000, name, 1 + i % 40, stop, 3000 + i % 20 * 100));
                    raf.seek((long) i * RECORD);                            // pass P<i> lives in slot i
                    raf.writeInt(i); raf.write(fixed(String.format("1923%05d", i % 100000), 9)); raf.write(fixed(name, NAME));
                    raf.writeShort(1 + i % 40); raf.write(fixed(stop, STOP)); raf.writeInt(20604); raf.writeInt((3000 + i % 20 * 100) * 100); raf.writeByte(1);
                    usefulBytes += 4 + 9 + name.length() + 2 + stop.length() + 4 + 4 + 1;
                }
            }
            String target = String.format("P%06d", n - 1);                  // worst case for the scan: last record
            double[] ts = new double[7], tr = new double[7];
            for (int k = -2; k < 7; k++) {
                long a = System.nanoTime();
                try (BufferedReader r = Files.newBufferedReader(text)) { String l; while ((l = r.readLine()) != null) if (l.startsWith(target + "|")) break; }
                long b = System.nanoTime();
                try (RandomAccessFile raf = new RandomAccessFile(bin.toFile(), "r")) { raf.seek((long) (n - 1) * RECORD); int id = raf.readInt(); if (id != n - 1) throw new IllegalStateException(); }
                long c = System.nanoTime();
                if (k >= 0) { ts[k] = (b - a) / 1e6; tr[k] = (c - b) / 1e3; }
            }
            Arrays.sort(ts); Arrays.sort(tr);
            long fixedBytes = Files.size(bin);
            System.out.printf("%9d %12d %14d %15.0f%% %18.2f %14.1f%n", n, Files.size(text) / 1024, fixedBytes / 1024,
                    100.0 * (fixedBytes - usefulBytes) / fixedBytes, ts[3], tr[3]);
        }

        // A1.1(b): one record written with DataOutputStream, shown as bytes
        Path one = dir.resolve("one_record.bin");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(one))) {
            out.writeUTF("P1001"); out.writeUTF("192311001"); out.writeUTF("Mary | Ann"); out.writeShort(12);
            out.writeUTF("Tambaram"); out.writeLong(java.time.LocalDate.of(2027, 5, 31).toEpochDay()); out.writeLong(450000);
        }
        byte[] b = Files.readAllBytes(one);
        StringBuilder hex = new StringBuilder();
        for (byte x : b) hex.append(String.format("%02x ", x));
        System.out.println("\nDataOutputStream record (" + b.length + " bytes): " + hex.toString().trim());
        System.out.println("writeUTF = 2-byte length + UTF-8 bytes, so '|' inside a name needs no escaping.");
    }
}
