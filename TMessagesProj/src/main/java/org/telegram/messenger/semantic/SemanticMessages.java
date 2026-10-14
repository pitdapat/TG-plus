package org.telegram.messenger.semantic;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Downloaded-message access only. Never invokes a Telegram network method. */
public final class SemanticMessages {
    public static final class Message {
        public final long dialog; public final int id, date; public final String text, hash;
        Message(long d, int i, int when, String body) throws Exception {
            dialog = d; id = i; date = when; text = body; hash = SemanticText.fingerprint(body);
        }
    }
    private interface Read<T> { T run() throws Exception; }
    private static <T> T storage(int account, Read<T> read) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Exception> error = new AtomicReference<>();
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            try { result.set(read.run()); } catch (Exception e) { error.set(e); } finally { latch.countDown(); }
        });
        if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Message storage is busy; retry");
        if (error.get() != null) throw error.get(); return result.get();
    }
    private static Message decode(long dialog, NativeByteBuffer buffer) throws Exception {
        if (buffer == null) return null;
        TLRPC.Message message;
        try { message = TLRPC.Message.TLdeserialize(buffer, buffer.readInt32(false), false); }
        finally { buffer.reuse(); }
        if (!eligible(message)) return null;
        return new Message(dialog, message.id, message.date, message.message);
    }
    public static boolean eligible(TLRPC.Message message) {
        return message!=null && SemanticPolicy.eligible(message.id,message.message,message.noforwards,
                message.expire_date>0 || message.ttl>0 || message.ttl_period>0 || (message.media!=null && message.media.ttl_seconds>0),
                message.action!=null && !(message.action instanceof TLRPC.TL_messageActionEmpty));
    }
    public static final class Page { public final ArrayList<Message> items = new ArrayList<>(); public int before; public int scanned; }
    public static Page page(int account, long owner, long dialog, int since, int before) throws Exception {
        if (DialogObject.isEncryptedDialog(dialog)) return new Page();
        return storage(account, () -> {
            Page page = new Page();
            if (UserConfig.getInstance(account).getClientUserId() != owner) return page;
            SQLiteCursor c = MessagesStorage.getInstance(account).getDatabase().queryFinalized("SELECT mid,data FROM messages_v2 m WHERE uid = " + dialog + " AND mid > 0 AND mid < " + before + " AND date >= " + since + " AND COALESCE(ttl,0)=0 AND NOT EXISTS(SELECT 1 FROM enc_tasks_v4 t WHERE t.uid=m.uid AND t.mid=m.mid) ORDER BY mid DESC LIMIT 100");
            try { while (c.next()) { page.scanned++; page.before = c.intValue(0); Message m = decode(dialog, c.byteBufferValue(1)); if (m != null) page.items.add(m); } }
            finally { c.dispose(); }
            return page;
        });
    }
    public static Message resolve(int account, long owner, long dialog, int id) throws Exception {
        return storage(account, () -> {
            if (!UserConfig.getInstance(account).isClientActivated() || UserConfig.getInstance(account).getClientUserId() != owner) return null;
            SQLiteCursor c = MessagesStorage.getInstance(account).getDatabase().queryFinalized("SELECT data FROM messages_v2 m WHERE uid = " + dialog + " AND mid = " + id + " AND COALESCE(ttl,0)=0 AND NOT EXISTS(SELECT 1 FROM enc_tasks_v4 t WHERE t.uid=m.uid AND t.mid=m.mid)");
            try { return c.next() ? decode(dialog, c.byteBufferValue(0)) : null; } finally { c.dispose(); }
        });
    }
}

