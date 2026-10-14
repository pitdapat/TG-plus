package org.telegram.messenger.semantic;

import java.util.function.Consumer;

/** Contain recoverable failures from derived-data work before they reach Looper. */
public final class SemanticTasks {
    private SemanticTasks() {}
    public static void run(Runnable task, Consumer<Throwable> failure) {
        try { task.run(); }
        catch (Exception | LinkageError error) { failure.accept(error); }
    }
}
