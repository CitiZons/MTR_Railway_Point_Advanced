package org.mtrpoint.client;

import net.minecraft.client.Minecraft;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Bounded background preparation for immutable geometry snapshots. Jobs must not access Minecraft state. */
final class GeometryJobs {
    private static final int QUEUE_CAPACITY = 96;
    private static final int WORKER_COUNT = 2;
    private static final Object LOCK = new Object();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final AtomicLong CACHE_JOBS = new AtomicLong();
    private static final Set<Slot<?>> SLOTS = new HashSet<>();
    // Each tier is FIFO. Producers and the workers share one lock for admission and dequeue.
    private static final List<ArrayDeque<JobFuture<?>>> QUEUES = List.of(new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
    private static int queued;
    private static final List<Thread> WORKERS = new ArrayList<>();

    private GeometryJobs() {}

    static int pendingCount() { return PENDING.get(); }
    static int queueCount() { synchronized (LOCK) { return queued; } }
    static long cacheJobCount() { return CACHE_JOBS.get(); }

    /** Cancel queued work and invalidate all results without waiting for the worker thread. */
    static void clear() {
        synchronized (LOCK) {
            epoch++;
            for (Slot<?> slot : SLOTS) slot.reset();
            SLOTS.clear();
            for (ArrayDeque<JobFuture<?>> queue : QUEUES) {
                for (JobFuture<?> future : queue) future.cancel(false);
                queue.clear();
            }
            queued=0;
            LOCK.notifyAll();
            CACHE_JOBS.set(0);
        }
    }

    static final class Slot<T> {
        private T value;
        private boolean ready;
        private Job<T> job;

        T get(Supplier<T> build) {
            return get(build, JobPriority.NORMAL, false);
        }

        /** Queue time-critical immutable preparation ahead of ordinary queued geometry. */
        T getPriority(Supplier<T> build) {
            return get(build, JobPriority.URGENT, false);
        }

        /** Queue background-only geometry such as turnout prewarming behind all normal work. */
        T getBackground(Supplier<T> build) {
            return get(build, JobPriority.BACKGROUND, false);
        }

        private T get(Supplier<T> build, JobPriority priority, boolean forceAsync) {
            synchronized (LOCK) {
                SLOTS.add(this);
                if (ready) return value;
                Minecraft minecraft = forceAsync ? null : Minecraft.getInstance();
                if (!forceAsync && (minecraft == null || minecraft.level == null)) {
                    if (job != null) { job.future.cancel(true); removeQueued(job.future); }
                    job = null;
                    value = build.get();
                    ready = true;
                    return value;
                }
                return getAsyncLocked(build, priority);
            }
        }

        /** Exercise the same bounded worker path even when Minecraft has no loaded world. */
        T getAsync(Supplier<T> build) {
            return getAsync(build, JobPriority.NORMAL);
        }

        /** Test hook for exercising priority ordering without a Minecraft world. */
        T getAsyncPriority(Supplier<T> build) {
            return getAsync(build, JobPriority.URGENT);
        }

        /** Test hook for the background-only queue path, even without a Minecraft world. */
        T getAsyncBackground(Supplier<T> build) {
            return getAsync(build, JobPriority.BACKGROUND);
        }

        private T getAsync(Supplier<T> build, JobPriority priority) {
            synchronized (LOCK) {
                SLOTS.add(this);
                if (ready) return value;
                return getAsyncLocked(build, priority);
            }
        }

        private T getAsyncLocked(Supplier<T> build, JobPriority priority) {
                if (job != null) {
                    if (job.epoch != epoch) job = null;
                    else if (!job.future.isDone()) {
                        if (priority != job.future.priority) reprioritize(job.future, priority);
                        return null;
                    }
                    else {
                        Job<T> completed = job;
                        job = null;
                        value = result(completed.future);
                        ready = true;
                        return value;
                    }
                }

                if (!admit(priority)) return null;
                JobFuture<T> future = new JobFuture<>(this, priority, build);
                PENDING.incrementAndGet();
                CACHE_JOBS.incrementAndGet();
                job = new Job<>(epoch, future);
                QUEUES.get(priority.ordinal()).addLast(future);
                queued++;
                WORKERS.removeIf(worker->!worker.isAlive());
                while (WORKERS.size()<WORKER_COUNT) {
                    Thread worker=new Thread(GeometryJobs::work, "MTR Point geometry preparation "+(WORKERS.size()+1));
                    worker.setDaemon(true);
                    worker.setPriority(Math.max(Thread.MIN_PRIORITY,Thread.NORM_PRIORITY-1));
                    WORKERS.add(worker);
                    worker.start();
                }
                LOCK.notifyAll();
                return null;
        }

        boolean pending() {
            synchronized (LOCK) {
                if (job == null) return false;
                if (job.epoch != epoch) { job = null; return false; }
                return !job.future.isDone();
            }
        }

        void clear() {
            synchronized (LOCK) {
                reset();
                SLOTS.remove(this);
            }
        }

        private void reset() {
            if (job != null) { job.future.cancel(true); removeQueued(job.future); }
            job = null;
            value = null;
            ready = false;
        }
    }

    private static long epoch;
    private record Job<T>(long epoch, JobFuture<T> future) {}

    private enum JobPriority { BACKGROUND, NORMAL, URGENT }

    private static final class JobFuture<T> extends FutureTask<T> {
        private final Slot<T> slot;
        private JobPriority priority;
        private final AtomicBoolean accounted = new AtomicBoolean();
        JobFuture(Slot<T> slot, JobPriority priority, Supplier<T> build) {
            super(build::get); this.slot=slot; this.priority=priority;
        }
        @Override protected void done() { if(accounted.compareAndSet(false,true))PENDING.decrementAndGet(); }
        void evict() {
            if(slot.job!=null&&slot.job.future==this)slot.job=null;
            cancel(false);
        }
    }

    private static boolean admit(JobPriority priority) {
        if (queued < QUEUE_CAPACITY) return true;
        for (int tier = 0; tier < priority.ordinal(); tier++) {
            JobFuture<?> tail = QUEUES.get(tier).pollLast();
            if (tail == null) continue;
            queued--;
            tail.evict();
            return true;
        }
        return false;
    }

    private static boolean removeQueued(JobFuture<?> future) {
        if (!QUEUES.get(future.priority.ordinal()).remove(future)) return false;
        queued--;
        return true;
    }

    private static void reprioritize(JobFuture<?> future, JobPriority priority) {
        if (!removeQueued(future)) return;
        future.priority = priority;
        QUEUES.get(priority.ordinal()).addLast(future);
        queued++;
    }

    private static void work() {
        while (true) {
            JobFuture<?> next = null;
            synchronized (LOCK) {
                while (queued == 0) {
                    try { LOCK.wait(); }
                    catch (InterruptedException cancelled) { /* A cleared task may interrupt the worker. */ }
                }
                for (int tier = QUEUES.size() - 1; tier >= 0 && next == null; tier--) {
                    next = QUEUES.get(tier).pollFirst();
                }
                queued--;
            }
            Thread.interrupted();
            try { next.run(); }
            catch (Throwable ignored) { /* FutureTask normally captures failures, keep a worker alive if a task escapes. */ }
        }
    }

    private static <T> T result(Future<T> future) {
        try {
            return future.get();
        } catch (java.util.concurrent.CancellationException cancelled) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while collecting prepared geometry", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Geometry preparation failed", cause);
        }
    }
}
