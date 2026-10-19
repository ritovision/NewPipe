/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.cloud;

import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Test-only streaming digest of every native table, cell type and exact stored value. */
public final class CloudRoomContentDigest {
    private CloudRoomContentDigest() {
    }

    public static String of(final SupportSQLiteDatabase database) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
        final List<String> tables = new ArrayList<>();
        try (Cursor names = database.query(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (names.moveToNext()) {
                tables.add(names.getString(0));
            }
        }
        for (final String table : tables) {
            token(digest, table.getBytes(StandardCharsets.UTF_8));
            final String escaped = table.replace("\"", "\"\"");
            final int columns;
            try (Cursor empty = database.query("SELECT * FROM \"" + escaped + "\" LIMIT 0")) {
                columns = empty.getColumnCount();
            }
            final StringBuilder query = new StringBuilder("SELECT * FROM \"")
                    .append(escaped).append("\" ORDER BY ");
            for (int i = 1; i <= columns; i++) {
                if (i > 1) {
                    query.append(',');
                }
                query.append(i);
            }
            try (Cursor rows = database.query(query.toString())) {
                while (rows.moveToNext()) {
                    digest.update((byte) 0x7f);
                    for (int i = 0; i < columns; i++) {
                        final int type = rows.getType(i);
                        digest.update((byte) type);
                        if (type == Cursor.FIELD_TYPE_NULL) {
                            token(digest, new byte[0]);
                        } else if (type == Cursor.FIELD_TYPE_BLOB) {
                            token(digest, rows.getBlob(i));
                        } else if (type == Cursor.FIELD_TYPE_FLOAT) {
                            token(digest, ByteBuffer.allocate(Long.BYTES)
                                    .putLong(Double.doubleToRawLongBits(rows.getDouble(i)))
                                    .array());
                        } else {
                            token(digest, rows.getString(i).getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        }
        final StringBuilder hex = new StringBuilder(64);
        for (final byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static void token(final MessageDigest digest, final byte[] bytes) {
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }
}
