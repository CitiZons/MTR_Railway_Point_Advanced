package org.mtrpoint.client;

import net.minecraft.client.Minecraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Bounded background preparation for immutable geometry snapshots. Jobs must not access Minecraft state. */
final class GeometryJobs {
    private static final int QUEUE_CAPACITY = 96;
    private static final Object LOCK = new Object();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final AtomicLong CACHE_JOBS = new AtomicLong();
    private static final Set<Slot<?>> SLOTS = ConcurrentHashMap.newKeySet();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<>(QUEUE_CAPACITY),
        new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "MTR Point geometry preparation");
                thread.setDaemon(true);
                return thread;
            }
        }, new ThreadPoolExecutor.AbortPolicy());

    private GeometryJobs() {}

    static int pendingCount() { return PENDING.get(); }
    static int queueCount() { return WORKER.getQueue().size(); }
    static long cacheJobCount() { return CACHE_JOBS.get(); }

    /** Cancel queued work and invalidate all results without waiting for the worker thread. */
    static void clear() {
        synchronized (LOCK) {
            epoch++;
            for (Slot<?> slot : SLOTS) slot.reset();
            SLOTS.clear();
            List<Runnable> queued = new ArrayList<>();
            WORKER.getQueue().drainTo(queued);
            for (Runnable job : queued) if (job instanceof Future<?> future) future.cancel(false);
            WORKER.purge();
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
                    if (job != null) { job.future.cancel(true); WORKER.remove(job.future); }
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
                        if (priority.rank != job.future.priority.rank) reprioritize(job.future, priority);
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

                if(priority==JobPriority.BACKGROUND&&WORKER.getQueue().remainingCapacity()==0)return null;
                long submittedEpoch = epoch;
                JobFuture<T> future = new JobFuture<>(this, priority, build);
                PENDING.incrementAndGet();
                if (!enqueue(future)) { future.cancel(false); return null; }
                CACHE_JOBS.incrementAndGet();
                job = new Job<>(submittedEpoch, future);
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
            if (job != null) { job.future.cancel(true); WORKER.remove(job.future); }
            job = null;
            value = null;
            ready = false;
        }
    }

    private static long epoch;
    private record Job<T>(long epoch, JobFuture<T> future) {}

    private enum JobPriority { BACKGROUND(0), NORMAL(1), URGENT(2); final int rank; JobPriority(int rank){this.rank=rank;} }

    private static final class JobFuture<T> extends FutureTask<T> {
        private final Slot<T> slot;
        private volatile JobPriority priority;
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

    /** Producers serialize through LOCK; the single worker can only improve available capacity. */
    private static boolean enqueue(JobFuture<?> future) {
        if (WORKER.getPoolSize() == 0) {
            try { WORKER.execute(future); return true; }
            catch (RejectedExecutionException stopped) { return false; }
        }
        LinkedBlockingDeque<Runnable> queue=(LinkedBlockingDeque<Runnable>)WORKER.getQueue();
        if(queue.remainingCapacity()==0){
            Runnable tail=queue.peekLast();
            if(!(tail instanceof JobFuture<?> queued)||queued.priority.rank>=future.priority.rank||!queue.removeLastOccurrence(tail))return false;
            queued.evict();
        }
        insertOrdered(queue,future);
        return true;
    }

    private static void reprioritize(JobFuture<?> future,JobPriority priority) {
        LinkedBlockingDeque<Runnable> queue=(LinkedBlockingDeque<Runnable>)WORKER.getQueue();
        if(queue.remove(future)){future.priority=priority;insertOrdered(queue,future);}
    }

    /** Stable ordering keeps FIFO within each tier and always puts normal work ahead of background. */
    private static void insertOrdered(LinkedBlockingDeque<Runnable> queue,JobFuture<?> future){
        List<Runnable> jobs=new ArrayList<>();queue.drainTo(jobs);jobs.add(future);
        jobs.sort((a,b)->Integer.compare(((JobFuture<?>)b).priority.rank,((JobFuture<?>)a).priority.rank));
        for(Runnable job:jobs)if(!queue.offerLast(job))throw new IllegalStateException("Geometry job queue capacity changed while locked");
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
