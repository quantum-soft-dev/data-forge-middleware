package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;

import java.io.Serial;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Folds a changelog into current per-key row state (Delta Client v2 — 022, CR §8.D).
 *
 * <p>Starting from a checkpoint state, applies records in order: INSERT replaces the row by key,
 * UPDATE merges the changed columns, DELETE removes the row. Each surviving row can still be read
 * as its structured {@code key} and {@code data} (typed {@link Value}s) so the state can be
 * re-emitted as an all-INSERT checkpoint frame (see {@link CheckpointFrame}) — the basis for
 * checkpoints, snapshot reconstruction, and re-baseline.</p>
 *
 * <h2>What a row costs (issue #290)</h2>
 *
 * <p>The fold is the checkpoint build's real ceiling (issue #152): it is the one full-site copy
 * left in heap, and everything around it already streams. So the row's <em>representation</em> is
 * the ceiling, multiplied by whatever redundancy it carries. It used to carry three kinds:</p>
 *
 * <ul>
 *   <li>every row held its own {@link LinkedHashMap} of column <b>names</b> — protobuf mints a
 *       fresh {@code String} per record, so five million rows retained five million copies of the
 *       same twenty names, at a map entry plus a string header each;</li>
 *   <li>the <b>key columns</b> were held twice, once in {@code key} and once in {@code data} — a
 *       duplication the UPDATE branch already treated as given ("an existing row already carries
 *       its key columns in data from the original INSERT");</li>
 *   <li>the row's <b>identity</b> string, a third copy of the key.</li>
 * </ul>
 *
 * <p>The first two are gone. A table owns one canonical set of column names ({@link FoldedTable}),
 * and a row is a {@code Value[]} aligned with it — one reference per column instead of an entry and
 * a name. The key is read back out of those values by the table's key column names; the rare row
 * whose key column never appears in its data (a client may send it in {@code key} only) keeps a
 * small side array for exactly those columns. The identity string <b>stays as it is</b>: shortening
 * it to a hash trades a collision — two distinct rows folding into one, i.e. silent data loss — for
 * bytes, and the ticket declined that trade.</p>
 *
 * <p>This multiplies the ceiling by a constant; it does not remove it. Spilling the fold to disk
 * with an external sort is the work that removes it, and it is deliberately not done here.</p>
 *
 * @author Data Forge Team
 * @version 2.0.0
 */
public final class ChangelogFold {

    private ChangelogFold() {
    }

    private static final String[] NO_NAMES = new String[0];
    private static final Value[] NO_VALUES = new Value[0];

    /**
     * One table's rows <em>and</em> the column names they share.
     *
     * <p>It is a {@link LinkedHashMap} so the state's declared type
     * ({@code Map<String, Map<String, FoldedRow>>}) and every consumer of it are unchanged, and so
     * that two states holding equal rows still compare equal whatever built them.</p>
     *
     * <p>The layout is <b>append-only</b>: a column name keeps its index for the life of the table,
     * so a row written when the table was narrower simply holds a shorter array — its indexes stay
     * valid, and it is never rewritten to match a later record's wider shape.</p>
     */
    static final class FoldedTable extends LinkedHashMap<String, FoldedRow> {

        @Serial
        private static final long serialVersionUID = 1L;

        // Not transient: nothing on this path serializes a fold, but a layout silently lost to
        // serialization would read every column as absent, so the fields travel if it ever is.
        /** Column names in first-seen order — the array indexes a row's values are aligned with. */
        private final List<String> columns = new ArrayList<>();

        private final Map<String, Integer> index = new HashMap<>();

        /**
         * The table's key column names, as the first record named them. A record whose key names
         * differ gets an array of its own rather than being forced onto this one, so a table whose
         * key set is not constant still emits each row's own key.
         */
        private String[] keyNames = NO_NAMES;

        /** Estimated retained bytes of the shared layout — charged once, never per row. */
        private long sharedBytes;

        private int indexOf(String column) {
            Integer position = index.get(column);
            return position == null ? -1 : position;
        }

        /** Give {@code column} an index if it has none; returns the estimated bytes that cost. */
        private long declare(String column) {
            if (index.containsKey(column)) {
                return 0L;
            }
            index.put(column, columns.size());
            columns.add(column);
            long cost = COLUMN_NAME_BYTES + stringBytes(column);
            sharedBytes += cost;
            return cost;
        }

        /**
         * The shared key-name array for {@code keyMap}: the table's own when the names match it
         * (the normal case, so nothing is allocated), a fresh array otherwise.
         */
        private String[] keyNamesFor(Map<String, Value> keyMap) {
            if (matchesCanonicalKey(keyMap)) {
                return keyNames;
            }
            String[] names = keyMap.keySet().toArray(NO_NAMES);
            if (keyNames.length == 0) {
                keyNames = names;
            }
            return names;
        }

        private boolean matchesCanonicalKey(Map<String, Value> keyMap) {
            if (keyNames.length != keyMap.size()) {
                return false;
            }
            for (String name : keyNames) {
                if (!keyMap.containsKey(name)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * A folded row: its current column values, plus whatever of its key is not among them.
     *
     * <p>Values are positional — {@code values[i]} is the value of {@code table.columns.get(i)},
     * {@code null} meaning the row does not carry that column. {@link #key()} and {@link #data()}
     * are read-only views built on demand; they retain nothing, so a caller may take them per row
     * without growing the fold.</p>
     */
    public static final class FoldedRow {

        private final FoldedTable table;
        private final String[] keyNames;
        private final Value[] values;
        /**
         * Key values whose column is not carried in {@link #values} — aligned with
         * {@link #keyNames}, and {@code null} whenever the key is fully readable from the data,
         * which is what an INSERT carrying its key columns (the normal shape) gives.
         */
        private final Value[] keyOnly;

        private FoldedRow(FoldedTable table, String[] keyNames, Value[] values, Value[] keyOnly) {
            this.table = table;
            this.keyNames = keyNames;
            this.values = values;
            this.keyOnly = keyOnly;
        }

        /** The row's key columns (drives row identity), reconstructed from what the row holds. */
        public Map<String, Value> key() {
            return new KeyView();
        }

        /** The row's current column values. */
        public Map<String, Value> data() {
            return new DataView();
        }

        private Value valueAt(int position) {
            return position >= 0 && position < values.length ? values[position] : null;
        }

        private Value keyValueAt(int slot) {
            Value fromData = valueAt(table.indexOf(keyNames[slot]));
            if (fromData != null) {
                return fromData;
            }
            return keyOnly == null ? null : keyOnly[slot];
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FoldedRow row
                    && key().equals(row.key())
                    && data().equals(row.data());
        }

        @Override
        public int hashCode() {
            return 31 * key().hashCode() + data().hashCode();
        }

        @Override
        public String toString() {
            return "FoldedRow{key=" + key() + ", data=" + data() + "}";
        }

        private final class DataView extends AbstractMap<String, Value> {

            @Override
            public Value get(Object column) {
                return column instanceof String name ? valueAt(table.indexOf(name)) : null;
            }

            @Override
            public boolean containsKey(Object column) {
                return get(column) != null;
            }

            @Override
            public int size() {
                int size = 0;
                for (Value value : values) {
                    if (value != null) {
                        size++;
                    }
                }
                return size;
            }

            @Override
            public Set<Entry<String, Value>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, Value>> iterator() {
                        return new Iterator<>() {
                            private int next = advance(0);

                            private int advance(int from) {
                                int at = from;
                                while (at < values.length && values[at] == null) {
                                    at++;
                                }
                                return at;
                            }

                            @Override
                            public boolean hasNext() {
                                return next < values.length;
                            }

                            @Override
                            public Entry<String, Value> next() {
                                if (!hasNext()) {
                                    throw new NoSuchElementException();
                                }
                                int at = next;
                                next = advance(at + 1);
                                return new SimpleImmutableEntry<>(table.columns.get(at), values[at]);
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return DataView.this.size();
                    }
                };
            }
        }

        private final class KeyView extends AbstractMap<String, Value> {

            @Override
            public Value get(Object column) {
                if (!(column instanceof String name)) {
                    return null;
                }
                for (int slot = 0; slot < keyNames.length; slot++) {
                    if (keyNames[slot].equals(name)) {
                        return keyValueAt(slot);
                    }
                }
                return null;
            }

            @Override
            public boolean containsKey(Object column) {
                return get(column) != null;
            }

            @Override
            public int size() {
                int size = 0;
                for (int slot = 0; slot < keyNames.length; slot++) {
                    if (keyValueAt(slot) != null) {
                        size++;
                    }
                }
                return size;
            }

            @Override
            public Set<Entry<String, Value>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Entry<String, Value>> iterator() {
                        return new Iterator<>() {
                            private int next = advance(0);

                            private int advance(int from) {
                                int at = from;
                                while (at < keyNames.length && keyValueAt(at) == null) {
                                    at++;
                                }
                                return at;
                            }

                            @Override
                            public boolean hasNext() {
                                return next < keyNames.length;
                            }

                            @Override
                            public Entry<String, Value> next() {
                                if (!hasNext()) {
                                    throw new NoSuchElementException();
                                }
                                int at = next;
                                next = advance(at + 1);
                                return new SimpleImmutableEntry<>(keyNames[at], keyValueAt(at));
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return KeyView.this.size();
                    }
                };
            }
        }
    }

    /**
     * Apply {@code records} on top of {@code initial} (typically a checkpoint state) — a
     * <strong>test convenience</strong> since issue #152, and deliberately nothing more.
     *
     * <p>No production caller remains, and new code belongs on {@link #apply}. This shape is the one
     * the ticket removed: it copies {@code initial}, so both states exist at once and each of them
     * is a whole site, and it takes the records as a materialized {@code List}, which is a second
     * whole site again. The checkpoint build folds record by record into a state it owns instead.
     * Kept because the fold's semantics are most readable stated as a function of a starting state
     * and a record list, which is how the tests assert them.</p>
     *
     * @param initial starting state (not mutated)
     * @param records changelog records in sequence order
     * @return resulting state: table → row-identity → folded row
     */
    public static Map<String, Map<String, FoldedRow>> fold(
            Map<String, Map<String, FoldedRow>> initial, List<ChangeRecord> records) {

        Map<String, Map<String, FoldedRow>> state = deepCopy(initial);
        for (ChangeRecord record : records) {
            apply(state, record);
        }
        return state;
    }

    /**
     * Apply one record to {@code state} <b>in place</b>, and report what it did to the state's size.
     *
     * <p>The record is not retained: its column names are canonicalized onto the table and its
     * values are copied into the row's array, so a caller streaming a frame or a segment can drop
     * each record as soon as this returns.</p>
     *
     * @param state  the fold so far — mutated
     * @param record the next changelog record, in sequence order
     * @return the change in the fold's estimated retained heap, in bytes: positive for a row that
     *         grew or arrived, negative for one that shrank or was deleted, zero when nothing about
     *         the state's size changed. It includes the <em>shared</em> cost of any column name this
     *         record introduced to its table, charged once for the table rather than once per row —
     *         and never given back, because a name outlives every row that used it. See
     *         {@link #estimatedRetainedBytes} for what "estimated" is worth; summing these is how a
     *         build knows how big its fold has become (issue #152) without walking it. The cost is
     *         proportional to the width of the row this record touches — never to the size of the
     *         fold.
     */
    public static long apply(Map<String, Map<String, FoldedRow>> state, ChangeRecord record) {
        FoldedTable table = table(state, record.getTable());
        String identity = identity(record.getKeyMap());
        Map<String, Value> recordData = record.getDataMap();
        Map<String, Value> recordKey = record.getKeyMap();
        switch (record.getOp()) {
            case INSERT -> {
                long shared = declareAll(table, recordData.keySet());
                Value[] values = valuesOf(table, recordData, NO_VALUES);
                String[] keyNames = table.keyNamesFor(recordKey);
                FoldedRow row = new FoldedRow(table, keyNames, values,
                        keyOnly(table, keyNames, recordKey, values));
                FoldedRow replaced = table.put(identity, row);
                // The replaced row is weighed only when there was one: on a first INSERT — every
                // record of a seed frame — this is a single pass over the new row.
                return shared + estimatedRetainedBytes(identity, row)
                        - (replaced == null ? 0L : estimatedRetainedBytes(identity, replaced));
            }
            case UPDATE -> {
                FoldedRow existing = table.get(identity);
                if (existing == null) {
                    // Seed a brand-new row (no prior INSERT in state) with its key columns so the
                    // materialized row never loses its primary key; an existing row already carries
                    // its key columns in data from the original INSERT. Key columns are declared
                    // first, so they lead the row exactly as the pre-#290 map copy left them.
                    long shared = declareAll(table, recordKey.keySet())
                            + declareAll(table, recordData.keySet());
                    Value[] values = valuesOf(table, recordData, valuesOf(table, recordKey, NO_VALUES));
                    String[] keyNames = table.keyNamesFor(recordKey);
                    FoldedRow row = new FoldedRow(table, keyNames, values,
                            keyOnly(table, keyNames, recordKey, values));
                    table.put(identity, row);
                    return shared + estimatedRetainedBytes(identity, row);
                }
                long shared = declareAll(table, recordData.keySet());
                Value[] values = valuesOf(table, recordData, existing.values);
                // The row was already there and keeps the *same* key names and key-only values, so
                // identity and key all cancel: only the values array can have changed. Weighing the
                // whole row twice here would double the accounting's cost on the commonest record.
                FoldedRow row = new FoldedRow(table, existing.keyNames, values, existing.keyOnly);
                table.put(identity, row);
                return shared + valuesBytes(values) - valuesBytes(existing.values);
            }
            case DELETE -> {
                FoldedRow removed = table.remove(identity);
                return removed == null ? 0L : -estimatedRetainedBytes(identity, removed);
            }
            default -> {
                return 0L;
            }
        }
    }

    private static FoldedTable table(Map<String, Map<String, FoldedRow>> state, String name) {
        Map<String, FoldedRow> rows = state.get(name);
        if (rows instanceof FoldedTable folded) {
            return folded;
        }
        if (rows != null && !rows.isEmpty()) {
            // Rows are positional against their own table's layout, so they cannot be adopted by a
            // table that did not lay them out. Nothing in the application builds the inner map
            // itself; a caller that did would silently mis-read every column.
            throw new IllegalArgumentException(
                    "Fold state for table " + name + " was not built by ChangelogFold");
        }
        FoldedTable folded = new FoldedTable();
        state.put(name, folded);
        return folded;
    }

    private static long declareAll(FoldedTable table, Iterable<String> columns) {
        long bytes = 0L;
        for (String column : columns) {
            bytes += table.declare(column);
        }
        return bytes;
    }

    /**
     * {@code base} widened as needed and overwritten with {@code columns}. Absent columns of
     * {@code base} stay absent; nothing is copied out of the record but its {@link Value}
     * references.
     */
    private static Value[] valuesOf(FoldedTable table, Map<String, Value> columns, Value[] base) {
        int width = base.length;
        for (String column : columns.keySet()) {
            width = Math.max(width, table.indexOf(column) + 1);
        }
        Value[] values = new Value[width];
        System.arraycopy(base, 0, values, 0, base.length);
        for (Map.Entry<String, Value> column : columns.entrySet()) {
            int position = table.indexOf(column.getKey());
            if (position >= 0) {
                values[position] = column.getValue();
            }
        }
        return values;
    }

    /** The key values {@code values} cannot supply — {@code null} when it supplies all of them. */
    private static Value[] keyOnly(FoldedTable table, String[] keyNames,
                                   Map<String, Value> recordKey, Value[] values) {
        Value[] keyOnly = null;
        for (int slot = 0; slot < keyNames.length; slot++) {
            int position = table.indexOf(keyNames[slot]);
            if (position >= 0 && position < values.length && values[position] != null) {
                continue;
            }
            if (keyOnly == null) {
                keyOnly = new Value[keyNames.length];
            }
            keyOnly[slot] = recordKey.get(keyNames[slot]);
        }
        return keyOnly;
    }

    /**
     * A coarse estimate of how much heap one folded row occupies.
     *
     * <p><b>Estimated, and deliberately so.</b> The exact answer needs an object-graph walk per row,
     * which would cost more than the fold; this is arithmetic over the same things that make a row
     * big — its columns and their values — using flat per-object costs for a 64-bit JVM with
     * compressed ordinary object pointers. It is within a small factor of the truth rather than
     * exact, which is what a budget expressed as a fraction of the heap needs it to be. It is
     * <em>not</em> the row's serialized size: the fold keeps a Java object graph, and for a narrow
     * row that graph is an order of magnitude larger than its protobuf encoding, so a wire-byte
     * budget could not be compared against {@code -Xmx} at all.</p>
     *
     * <p>Counted: the row's identity string (the map key, built per row and retained with it), the
     * row's own object and the table entry that holds it, one array slot per column the row's array
     * spans, and each value — a string, decimal or bytes value carries its own payload, while an
     * int, double, boolean or NULL lives inside the wrapper. Column <em>names</em> are not counted
     * here at all: they belong to the table, and {@link #sharedEstimatedRetainedBytes} counts them
     * once (issue #290). Key columns are counted once — the values the row's data already carries,
     * plus the side array for any it does not.</p>
     *
     * <p><b>Where it under-counts</b>, so a deployment that matches can lower its budget rather than
     * be surprised: a character costs one byte here, which is what compact strings give for Latin-1
     * text — string data outside it (Cyrillic, CJK) is held as UTF-16 and costs twice that. The
     * per-table maps themselves are not counted either, but there are tens of those against millions
     * of rows. Everything else errs the other way: the per-object costs below are rounded up.</p>
     *
     * @param identity the row's identity string, as used for the map key
     * @param row      the folded row
     * @return estimated retained bytes
     */
    static long estimatedRetainedBytes(String identity, FoldedRow row) {
        return ROW_BYTES + stringBytes(identity) + valuesBytes(row.values) + keyOnlyBytes(row.keyOnly);
    }

    /**
     * What one table's shared column names cost — paid once for the table, whatever its row count.
     *
     * @param table one table of a fold state, as built by {@link #apply}
     * @return estimated retained bytes of the shared layout
     */
    static long sharedEstimatedRetainedBytes(Map<String, FoldedRow> table) {
        return table instanceof FoldedTable folded ? folded.sharedBytes : 0L;
    }

    /**
     * {@link FoldedRow} (four references), its values array header, and the {@code LinkedHashMap}
     * entry plus table slot that hold the row.
     */
    private static final long ROW_BYTES = 112L;

    /** One reference in a row's values array. */
    private static final long SLOT_BYTES = 4L;

    /**
     * A column name's place in the table's layout: the index map's entry and slot, the boxed
     * position, and the name list's slot. The name's own {@link String} is added on top.
     */
    private static final long COLUMN_NAME_BYTES = 80L;

    /** A {@link String} header plus its (compact, one byte per Latin-1 character) array header. */
    private static final long STRING_BYTES = 48L;

    /**
     * A protobuf {@code Value}: object header, the generated message's {@code memoizedSize} and
     * {@code unknownFields} reference, the oneof case, and the oneof's own field or reference.
     */
    private static final long VALUE_BYTES = 40L;

    /** The header of a {@code byte[]} behind a bytes value, or of the key-only side array. */
    private static final long ARRAY_BYTES = 16L;

    private static long valuesBytes(Value[] values) {
        long bytes = SLOT_BYTES * values.length;
        for (Value value : values) {
            if (value != null) {
                bytes += valueBytes(value);
            }
        }
        return bytes;
    }

    private static long keyOnlyBytes(Value[] keyOnly) {
        return keyOnly == null ? 0L : ARRAY_BYTES + valuesBytes(keyOnly);
    }

    private static long valueBytes(Value value) {
        return VALUE_BYTES + switch (value.getVCase()) {
            case STRING_VALUE -> stringBytes(value.getStringValue());
            case DECIMAL_VALUE -> stringBytes(value.getDecimalValue());
            case BYTES_VALUE -> ARRAY_BYTES + value.getBytesValue().size();
            // int, double, bool and NULL are held inside the wrapper counted above
            case INT_VALUE, DOUBLE_VALUE, BOOL_VALUE, IS_NULL, V_NOT_SET -> 0L;
        };
    }

    private static long stringBytes(String text) {
        return STRING_BYTES + text.length();
    }

    /**
     * Build a deterministic, collision-free identity string for a row's key columns.
     *
     * <p>Columns are sorted by name and each {@code name=value} pair is length-prefixed, so distinct
     * key tuples can never concatenate to the same string. Each value carries a type tag: {@code N}
     * for SQL NULL, {@code B} for bytes (hex-encoded, so equal {@code byte[]}s match), {@code V} for
     * every other scalar (its {@code toString()}). The tag keeps a NULL key distinct from the literal
     * string {@code "null"} and a bytes key distinct from a same-looking string.</p>
     *
     * <p>It is retained per row and it is the third copy of the key — issue #290 weighed shortening
     * it to a hash and declined: a hash collision folds two distinct rows into one, which is silent
     * data loss, and verifying the full key on a hit means keeping the full key anyway.</p>
     */
    private static String identity(Map<String, Value> keyMap) {
        StringBuilder sb = new StringBuilder();
        keyMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String name = entry.getKey();
                    String value = encode(entry.getValue());
                    sb.append(name.length()).append(':').append(name)
                            .append('=').append(value.length()).append(':').append(value);
                });
        return sb.toString();
    }

    /**
     * Type-tagged encoding of a key value so distinct wire types never share an identity: int
     * {@code 1}, string {@code "1"}, and bool {@code true} must address different rows (review r4).
     * Decimals are scale-normalized ({@code 1.5} == {@code 1.50}) so trailing-zero variance across
     * client code paths still addresses one row.
     */
    private static String encode(Value value) {
        return switch (value.getVCase()) {
            case INT_VALUE -> "I" + value.getIntValue();
            case DOUBLE_VALUE -> "D" + Double.doubleToLongBits(value.getDoubleValue());
            case STRING_VALUE -> "S" + value.getStringValue();
            case BOOL_VALUE -> "L" + value.getBoolValue();
            case DECIMAL_VALUE -> "M" + normalizeDecimal(value.getDecimalValue());
            case BYTES_VALUE -> "B" + HexFormat.of().formatHex(value.getBytesValue().toByteArray());
            case IS_NULL, V_NOT_SET -> "N";
        };
    }

    /**
     * The identity form of a decimal key column, so {@code 1.0} and {@code 1.00} fold as one row.
     *
     * <p>A value {@link java.math.BigDecimal} cannot parse keeps the token itself as its identity
     * (issue #215, review round 2). PostgreSQL {@code numeric} holds {@code NaN} and
     * {@code ±Infinity} and compares {@code NaN} equal to itself, so it is a usable key — and the
     * bare parse here threw on every one of them, out of {@code apply}, aborting the <b>whole
     * site's</b> fold rather than one table. That is a larger blast radius than the per-table skip
     * this ticket set out to remove, and it is deterministic: every following nightly build ends
     * the same way, with the pointer and retention frozen.</p>
     *
     * <p>The non-finite spellings are canonicalised so a client sending {@code nan} and {@code NaN}
     * for the same source row does not fold into two identities.</p>
     */
    private static String normalizeDecimal(String token) {
        try {
            return new java.math.BigDecimal(token).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            String trimmed = token.trim();
            try {
                // BigDecimal rejects surrounding whitespace, so " 1.0" reached this fallback and
                // folded as its own identity beside "1.0" -- one source row becoming two (review
                // round 3). Retried trimmed before falling back to the token.
                return new java.math.BigDecimal(trimmed).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException stillNotANumber) {
                // genuinely non-finite or malformed: the token itself is the identity
            }
            // One vocabulary, shared with ValueMapper: the duplicate copy that used to live here is
            // what issue #238 was -- the same sign-handling slip in both, with nothing making them
            // agree, so "-NaN" was reported non-finite by one and kept its own identity in the other.
            String canonical = ValueMapper.canonicalNonFinite(trimmed);
            return canonical != null ? canonical : trimmed;
        }
    }

    /**
     * Copy the state so the copy can be folded into without touching the original.
     *
     * <p>Each table gets a layout of its own, and its rows are re-homed onto it. The value arrays
     * themselves are shared, which is safe because {@link #apply} never writes into an existing
     * row's array — an UPDATE builds a new one.</p>
     */
    private static Map<String, Map<String, FoldedRow>> deepCopy(Map<String, Map<String, FoldedRow>> source) {
        Map<String, Map<String, FoldedRow>> copy = new LinkedHashMap<>();
        source.forEach((table, rows) -> {
            FoldedTable copiedTable = new FoldedTable();
            if (rows instanceof FoldedTable folded) {
                folded.columns.forEach(copiedTable::declare);
                copiedTable.keyNames = folded.keyNames;
            } else if (!rows.isEmpty()) {
                // Guarded exactly as apply() guards its own lookup, and for the same reason: rows
                // are positional against the layout that laid them out, so copying them onto a
                // layout nobody built would read every column as absent — silently, which is worse
                // than the refusal.
                throw new IllegalArgumentException(
                        "Fold state for table " + table + " was not built by ChangelogFold");
            }
            rows.forEach((identity, row) -> copiedTable.put(identity,
                    new FoldedRow(copiedTable, row.keyNames, row.values, row.keyOnly)));
            copy.put(table, copiedTable);
        });
        return copy;
    }
}
