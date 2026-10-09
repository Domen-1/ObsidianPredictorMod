package ru.domen.obsidianpredictor.world;

import ru.domen.obsidianpredictor.ObsidianPredictorMod;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One low-priority background thread for all snapshot work: encoding sections, applying block
 * changes and running predictions. Snapshots are only ever touched from this thread.
 */
public final class Worker {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Obsidian Predictor worker");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    public static final Executor EXECUTOR_SAFE = task -> EXECUTOR.execute(() -> {
        try {
            task.run();
        } catch (Throwable t) {
            ObsidianPredictorMod.LOGGER.error("Obsidian Predictor worker task failed", t);
        }
    });

    private Worker() {}

    public static void submit(Runnable task) {
        EXECUTOR_SAFE.execute(task);
    }
}
