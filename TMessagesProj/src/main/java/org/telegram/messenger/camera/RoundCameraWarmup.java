package org.telegram.messenger.camera;

/** UI-thread ownership of a camera prepared for a pending hold gesture. */
public final class RoundCameraWarmup<T> {
    public interface Factory<T> { T create(); }
    public interface Closer<T> { void close(T resource); }
    private final Closer<T> closer;
    private T resource;

    public RoundCameraWarmup(Closer<T> closer) { this.closer = closer; }

    public void prepare(Factory<T> factory) {
        if (resource == null) resource = factory.create();
    }

    /** Transfer ownership to the recorder, so a later cancel cannot close it. */
    public T take() {
        T result = resource;
        resource = null;
        return result;
    }

    public void cancel() {
        T result = take();
        if (result != null) closer.close(result);
    }
}
