package org.telegram.messenger.semantic;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.telegram.messenger.ApplicationLoader;
import java.io.File;
import java.util.ArrayList;

/** Private, account-identity-scoped derived store; source messages stay authoritative. */
final class SemanticIndex implements AutoCloseable {
    final SQLiteDatabase db;
    static File file(long owner) { return new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "semantic-index-" + owner + ".db"); }
    SemanticIndex(long owner) {
        db = SQLiteDatabase.openOrCreateDatabase(file(owner), null);
        db.execSQL("PRAGMA secure_delete=ON");
        db.execSQL("CREATE TABLE IF NOT EXISTS items(dialog INTEGER,mid INTEGER,part INTEGER,date INTEGER,hash TEXT,vector BLOB,text TEXT,seen INTEGER,PRIMARY KEY(dialog,mid,part))");
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata(version TEXT)");
        String version = SemanticModel.HASH + ":256:text-v1";
        try (Cursor c = db.rawQuery("SELECT version FROM metadata LIMIT 1", null)) {
            if (!c.moveToFirst() || !version.equals(c.getString(0))) {
                db.execSQL("DELETE FROM items"); db.execSQL("DELETE FROM metadata");
                ContentValues v = new ContentValues(); v.put("version", version); db.insert("metadata", null, v);
            }
        }
    }
    static final class Item {
        long dialog; int mid, date, part; String hash, text; float[] vector; double score;
    }
    ArrayList<Item> all() {
        ArrayList<Item> items = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT dialog,mid,part,date,hash,vector,text FROM items LIMIT 10000", null)) {
            while (c.moveToNext()) {
                Item i = new Item(); i.dialog = c.getLong(0); i.mid = c.getInt(1); i.part = c.getInt(2); i.date = c.getInt(3); i.hash = c.getString(4); i.vector = SemanticText.decode(c.getBlob(5)); i.text = c.getString(6); items.add(i);
            }
        }
        return items;
    }
    String hash(long dialog, int mid) {
        try (Cursor c = db.rawQuery("SELECT hash FROM items WHERE dialog=? AND mid=? LIMIT 1", new String[]{"" + dialog, "" + mid})) { return c.moveToFirst() ? c.getString(0) : null; }
    }
    void keep(long dialog, int mid, long seen) { ContentValues v = new ContentValues(); v.put("seen", seen); db.update("items", v, "dialog=? AND mid=?", new String[]{"" + dialog, "" + mid}); }
    int count() { try (Cursor c = db.rawQuery("SELECT count(*) FROM items", null)) { c.moveToFirst(); return c.getInt(0); } }
    void put(SemanticMessages.Message m, ArrayList<String> chunks, ArrayList<float[]> vectors, long seen) {
        db.beginTransaction();
        try {
            remove(m.dialog, m.id);
            for (int part = 0; part < chunks.size(); part++) {
                ContentValues v = new ContentValues(); v.put("dialog", m.dialog); v.put("mid", m.id); v.put("part", part); v.put("date", m.date); v.put("hash", m.hash); v.put("text", chunks.get(part)); v.put("vector", SemanticText.encode(vectors.get(part))); v.put("seen", seen);
                db.insertOrThrow("items", null, v);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    void remove(long dialog, int mid) { db.delete("items", "dialog=? AND mid=?", new String[]{"" + dialog, "" + mid}); }
    void removeDialog(long dialog) { db.delete("items", "dialog=?", new String[]{"" + dialog}); }
    void clear() { db.delete("items", null, null); db.execSQL("VACUUM"); }
    void finish(long dialog, long seen) { db.delete("items", "dialog=? AND seen<>?", new String[]{"" + dialog, "" + seen}); }
    @Override public void close() { db.close(); }
}
