package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical integrity hash over a session's change records (Delta Client v2 — 022, CR §10).
 *
 * <p>The client sends {@code SessionEnd.content_hash}; the server recomputes the same hash over the
 * records it accepted and rejects the session on mismatch. The encoding is language-neutral and does
 * <b>not</b> depend on protobuf wire ordering (map field order is unspecified across languages):</p>
 *
 * <ul>
 *   <li>records are hashed in sequence order;</li>
 *   <li>each record contributes {@code op US table US seq US key-cols US data-cols RS}
 *       (US = 0x1F, RS = 0x1E);</li>
 *   <li>columns are sorted by name; each is {@code name=<tagged-value>} joined by GS (0x1D);</li>
 *   <li>a value is type-tagged: {@code I}nt, {@code D}ouble, {@code S}tring, boo{@code L}ean,
 *       deci{@code M}al, {@code B}ytes (hex), {@code N}ull — so {@code 1}/{@code "1"}/{@code true}
 *       never collide.</li>
 * </ul>
 *
 * <p>Result is lowercase hex SHA-256. Clients implementing this algorithm get end-to-end integrity;
 * a client that cannot yet compute it may send an empty {@code content_hash} (see
 * {@link #matches(List, String)}).</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ChangelogContentHash {

    private static final char UNIT_SEPARATOR = '\u001F';
    private static final char RECORD_SEPARATOR = '\u001E';
    private static final char GROUP_SEPARATOR = '\u001D';

    private ChangelogContentHash() {
    }

    /**
     * @return whether the declared hash matches the records; a blank {@code declaredHex} (client did
     *         not provide one) is treated as a match so optional integrity is not a hard requirement
     */
    public static boolean matches(List<ChangeRecord> records, String declaredHex) {
        if (declaredHex == null || declaredHex.isBlank()) {
            return true;
        }
        return compute(records).equalsIgnoreCase(declaredHex.trim());
    }

    /**
     * @return the lowercase-hex SHA-256 over the canonical serialization of {@code records}
     */
    public static String compute(List<ChangeRecord> records) {
        MessageDigest digest = sha256();
        StringBuilder sb = new StringBuilder();
        for (ChangeRecord record : records) {
            sb.setLength(0);
            sb.append(record.getOp().name()).append(UNIT_SEPARATOR)
                    .append(record.getTable()).append(UNIT_SEPARATOR)
                    .append(record.getSeq()).append(UNIT_SEPARATOR);
            appendColumns(sb, record.getKeyMap());
            sb.append(UNIT_SEPARATOR);
            appendColumns(sb, record.getDataMap());
            sb.append(RECORD_SEPARATOR);
            digest.update(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void appendColumns(StringBuilder sb, Map<String, Value> columns) {
        boolean first = true;
        for (Map.Entry<String, Value> entry : new TreeMap<>(columns).entrySet()) {
            if (!first) {
                sb.append(GROUP_SEPARATOR);
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(encode(entry.getValue()));
        }
    }

    private static String encode(Value value) {
        return switch (value.getVCase()) {
            case INT_VALUE -> "I" + value.getIntValue();
            case DOUBLE_VALUE -> "D" + value.getDoubleValue();
            case STRING_VALUE -> "S" + value.getStringValue();
            case BOOL_VALUE -> "L" + value.getBoolValue();
            case DECIMAL_VALUE -> "M" + value.getDecimalValue();
            case BYTES_VALUE -> "B" + HexFormat.of().formatHex(value.getBytesValue().toByteArray());
            case IS_NULL, V_NOT_SET -> "N";
        };
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
