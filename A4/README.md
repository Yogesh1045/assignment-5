# Task A4: What Should Be Serialized? (Hostel management state)

**Supporting program:** `HostelSerialization.java` (transient fields, object graph, versioning experiment, size/time comparison)
**Run:** `javac HostelSerialization.java && java HostelSerialization` · output: `output/HostelSerialization.txt`

## A4.1 What to persist
```java
class Hostel   implements Serializable { String name; List<Room> rooms; }
class Room     implements Serializable { String number; List<Resident> residents; Hostel hostel; }
class Resident implements Serializable {
    String name, regNo;                 // persisted: the data the hostel actually owns
    transient String password;          // NOT persisted: a secret
    transient Connection db;            // NOT persisted: a live resource
    Room room;
}
```
| Field | transient? | Reason |
|---|---|---|
| `name`, `regNo` | no | the state that must survive a restart |
| **`password`** | **yes** | Serialized files are **not encrypted**: anyone who can read `hostel.ser` (a backup, a USB copy) would read every password. The output confirms the password text is **absent** from the bytes after marking it transient. It should not be in the object at all; store a salted hash in the database. |
| **`db : Connection`** | **yes** | (1) A JDBC `Connection` does **not implement Serializable**, so without `transient` the write fails with `NotSerializableException` (reproduced). (2) Even if it could be written, a socket to a database is **meaningless after a restart**; it must be reopened. After reading, transient fields hold their **default values** (`null`), so the program must re-create the connection (e.g. in `readObject` or lazily). |

**What happens to the object graph:** `writeObject(hostel)` performs a **deep traversal**. It writes the Hostel, then every object reachable from it through non-transient fields: the list, each Room, each Room's list, each Resident, and the back-references `room.hostel` and `resident.room`. Each object is written **once**: the stream keeps a table of objects already written, and a second reference is stored as a *handle* to the first. That is why **cycles do not loop forever**, and why **shared references are preserved**: after reading, `resident.room.hostel == hostel` is `true` (the same object, not a copy; verified). Serializing the root saves the whole reachable graph, so a stray reference to something large or non-serializable drags it in, or fails.

## A4.2 Planning for change (new field `phone`)
Experiment: a file was written by `Resident` **v1** (name, regNo) and read by `Resident` **v2** (name, regNo, **phone**), compiled and run in separate JVMs:
| Case | Result |
|---|---|
| (a) **no `serialVersionUID` declared** | `InvalidClassException: local class incompatible: stream classdesc serialVersionUID = -1485896733400315031, local class serialVersionUID = -4704594282173425439`. **The old file cannot be read.** |
| (b) **`serialVersionUID = 1L` in both versions** | read OK: `name=Arun, regNo=192311001, phone=null` |
**Explanation:** the stream stores each class's `serialVersionUID`. If the class does not declare one, Java **computes** it from the class's structure (name, fields, methods), so adding a field changes the number, and every old file is rejected. When it is declared and kept the same, Java uses its **compatible-change rules**: a field present in the class but missing from the stream (here `phone`) gets its default (`null`); a field in the stream but no longer in the class is ignored. **Rule:** always declare `private static final long serialVersionUID = 1L;`, and change it only for an *incompatible* change (such as a changed field type), where old files really cannot be read.

## Alternative concept: CSV or JSON instead of Java serialization
```
CSV : name,regNo,room            JSON: [{"name":"Arun","regNo":"192311001","room":"L-101"}, ...]
      Arun,192311001,L-101
```
**Measured, n residents (save + load, in memory, median of 5):**
| n | serialized | CSV | JSON | serialize ms | CSV ms | JSON ms |
|---|---|---|---|---|---|---|
| 1,000 | 39 KB | 28 KB | 57 KB | 3.9 | 1.1 | 7.6 |
| 10,000 | 407 KB | 289 KB | 582 KB | 18.5 | 2.6 | 35.4 |
| 100,000 | 4,167 KB | **2,995 KB** | 5,924 KB | 162 | **48** | 263 |
- **Size:** all are **O(n)**. CSV is smallest: no field names, no type metadata. Serialization adds class descriptors (written once) plus per-object handles and headers. JSON repeats every field name in every record.
- **Time:** all **O(n)**. Simple CSV was fastest here. Serialization uses reflection per object. The hand-written JSON code was slowest; a library such as Jackson is faster, but still parses text.
- **Coping with a new field:** CSV needs a **header row** and readers that find columns by name. An old file simply lacks the `phone` column, so it defaults to empty; an old *reader* given a new file must ignore extra columns. JSON copes best: a missing key becomes null and an unknown key is ignored, with no version number needed. Serialization copes **only if `serialVersionUID` is fixed** (case b), otherwise it fails completely (case a).
- **Other differences:** text formats are human-readable, editable, language-neutral (Python and Excel can read them) and safe to load. **Deserializing untrusted bytes is a known security risk** (gadget-chain attacks). Serialization saves an arbitrary object graph with cycles and shared references automatically; CSV/JSON need IDs (`"room":"L-101"`) to rebuild references.
