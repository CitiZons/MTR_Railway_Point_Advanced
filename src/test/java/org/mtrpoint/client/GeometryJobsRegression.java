package org.mtrpoint.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Deterministic asynchronous checks for GeometryJobs without requiring a Minecraft world. */
public final class GeometryJobsRegression {
    private static final long TIMEOUT_SECONDS = 5;
    private GeometryJobsRegression() {}

    public static void run() throws Exception {
        boundedQueueAndRetry();
        priorityEvictsTailAndRetries();
        queuedSlotCanBePromoted();
        backgroundRunsOnlyAfterForeground();
        slotAndWorldClearDiscardOldResult();
        exceptionsReachCaller();
        GeometryJobs.clear();
        require(GeometryJobs.pendingCount() == 0 && GeometryJobs.queueCount() == 0, "Geometry job cleanup left work pending");
        System.out.println("GEOMETRY_JOBS: PASS bounded worker/queue, retry, invalidation, and exception delivery");
    }

    private static void priorityEvictsTailAndRetries() throws Exception {
        GeometryJobs.clear();
        CountDownLatch workerStarted=new CountDownLatch(1),releaseWorker=new CountDownLatch(1),queuedDone=new CountDownLatch(96);
        List<Integer> order=java.util.Collections.synchronizedList(new ArrayList<>());
        var slots=new ArrayList<GeometryJobs.Slot<Integer>>();
        try {
            var blocker=new GeometryJobs.Slot<Integer>();slots.add(blocker);
            blocker.getAsync(()->{workerStarted.countDown();awaitUninterruptibly(releaseWorker);return -1;});
            require(workerStarted.await(TIMEOUT_SECONDS,TimeUnit.SECONDS),"Priority test worker did not start");
            for(int i=0;i<96;i++){
                int value=i;var slot=new GeometryJobs.Slot<Integer>();slots.add(slot);
                slot.getAsync(()->{order.add(value);queuedDone.countDown();return value;});
            }
            var urgent=new GeometryJobs.Slot<Integer>();slots.add(urgent);
            require(urgent.getAsyncPriority(()->{order.add(999);queuedDone.countDown();return 999;})==null,"Priority result should be queued");
            require(GeometryJobs.queueCount()==96&&GeometryJobs.pendingCount()==97,"Priority replacement exceeded capacity or miscounted pending work");
            require(!slots.get(96).pending(),"Evicted tail slot still reports pending");
            releaseWorker.countDown();
            require(queuedDone.await(TIMEOUT_SECONDS,TimeUnit.SECONDS),"Priority queue did not drain");
            await(()->GeometryJobs.pendingCount()==0&&GeometryJobs.queueCount()==0,"Priority counters did not return to zero");
            require(order.get(0)==999,"Priority task did not run before normal queued tasks");
            var evicted=slots.get(96);
            require(evicted.getAsync(()->95)==null,"Evicted task did not become retryable");
            require(awaitValue(evicted,()->95)==95,"Retried evicted task did not publish");
        } finally {releaseWorker.countDown();GeometryJobs.clear();}
    }

    private static void queuedSlotCanBePromoted() throws Exception {
        GeometryJobs.clear();
        CountDownLatch workerStarted=new CountDownLatch(1),releaseWorker=new CountDownLatch(1),done=new CountDownLatch(2);
        List<Integer> order=java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            var blocker=new GeometryJobs.Slot<Integer>();
            blocker.getAsync(()->{workerStarted.countDown();awaitUninterruptibly(releaseWorker);return -1;});
            require(workerStarted.await(TIMEOUT_SECONDS,TimeUnit.SECONDS),"Promotion test worker did not start");
            var first=new GeometryJobs.Slot<Integer>();var second=new GeometryJobs.Slot<Integer>();
            first.getAsync(()->{order.add(1);done.countDown();return 1;});
            second.getAsync(()->{order.add(2);done.countDown();return 2;});
            require(first.getAsyncPriority(()->-1)==null,"Promoted slot should remain pending");
            require(GeometryJobs.cacheJobCount()==3,"Promoting an existing job submitted a duplicate");
            releaseWorker.countDown();
            require(done.await(TIMEOUT_SECONDS,TimeUnit.SECONDS),"Promoted queue did not drain");
            require(order.equals(List.of(1,2)),"Existing normal job was not promoted ahead of queued work");
        } finally {releaseWorker.countDown();GeometryJobs.clear();}
    }

    private static void backgroundRunsOnlyAfterForeground() throws Exception {
        GeometryJobs.clear();
        CountDownLatch workerStarted=new CountDownLatch(1),releaseWorker=new CountDownLatch(1),done=new CountDownLatch(3);
        List<Integer> order=java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            var blocker=new GeometryJobs.Slot<Integer>();
            blocker.getAsync(()->{workerStarted.countDown();awaitUninterruptibly(releaseWorker);return -1;});
            require(workerStarted.await(TIMEOUT_SECONDS,TimeUnit.SECONDS),"Background test worker did not start");
            var background=new GeometryJobs.Slot<Integer>();var normal=new GeometryJobs.Slot<Integer>();var urgent=new GeometryJobs.Slot<Integer>();
            background.getAsyncBackground(()->{order.add(3);done.countDown();return 3;});
            normal.getAsync(()->{order.add(2);done.countDown();return 2;});
            urgent.getAsyncPriority(()->{order.add(1);done.countDown();return 1;});
            releaseWorker.countDown();
            require(done.await(TIMEOUT_SECONDS,TimeUnit.SECONDS),"Three-tier queue did not drain");
            require(order.equals(List.of(1,2,3)),"Background work ran ahead of urgent or normal geometry: "+order);
            require(awaitValue(background,()->3)==3,"Background result was not published");
        } finally {releaseWorker.countDown();GeometryJobs.clear();}
    }

    private static void boundedQueueAndRetry() throws Exception {
        GeometryJobs.clear();
        CountDownLatch workerStarted = new CountDownLatch(1), releaseWorker = new CountDownLatch(1), queuedDone = new CountDownLatch(96);
        AtomicInteger active = new AtomicInteger(), maximumActive = new AtomicInteger();
        var slots = new ArrayList<GeometryJobs.Slot<Integer>>();
        var rejected = new GeometryJobs.Slot<Integer>();
        try {
            var blocker = new GeometryJobs.Slot<Integer>(); slots.add(blocker);
            require(blocker.getAsync(() -> {
                enter(active, maximumActive); workerStarted.countDown(); awaitUninterruptibly(releaseWorker);
                active.decrementAndGet(); return -1;
            }) == null, "Worker job should be asynchronous");
            require(workerStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Single worker did not start");

            for (int i = 0; i < 96; i++) {
                int value = i; var slot = new GeometryJobs.Slot<Integer>(); slots.add(slot);
                require(slot.getAsync(() -> {
                    enter(active, maximumActive);
                    try { queuedDone.countDown(); return value; }
                    finally { active.decrementAndGet(); }
                }) == null, "Queued result must be pending");
            }
            require(GeometryJobs.queueCount() == 96, "Bounded worker queue did not reach its 96-job capacity");
            require(GeometryJobs.pendingCount() == 97, "Pending count should include one running and 96 queued jobs");
            require(rejected.getAsync(() -> 999) == null && !rejected.pending(), "Full queue should reject without blocking or claiming the slot");
            require(GeometryJobs.queueCount() == 96 && GeometryJobs.pendingCount() == 97, "Rejected work changed queue counters");

            releaseWorker.countDown();
            require(queuedDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Accepted queue did not drain");
            await(() -> GeometryJobs.pendingCount() == 0 && GeometryJobs.queueCount() == 0, "Queue counters did not return to zero");
            require(maximumActive.get() == 1, "More than one geometry worker ran at once");

            require(rejected.getAsync(() -> 999) == null, "Rejected job was not retried after capacity became available");
            require(awaitValue(rejected, () -> 999) == 999, "Retried job did not publish its result");
            require(GeometryJobs.cacheJobCount() == 98, "Accepted cache-job count missed a retried submission");
        } finally {
            releaseWorker.countDown();
            GeometryJobs.clear();
        }
    }

    private static void slotAndWorldClearDiscardOldResult() throws Exception {
        staleSlotClear();
        worldClear();
    }

    private static void staleSlotClear() throws Exception {
        GeometryJobs.clear();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        var slot = new GeometryJobs.Slot<String>();
        try {
            slot.getAsync(() -> {
                entered.countDown(); awaitUninterruptibly(release); finished.countDown(); return "stale";
            });
            require(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Old slot job did not start");
            slot.clear(); release.countDown();
            require(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Cancelled slot supplier did not stop");
            require(slot.getAsync(() -> "fresh") == null, "Cleared slot exposed a stale result");
            require("fresh".equals(awaitValue(slot, () -> "fresh")), "Cleared slot failed to accept a fresh job");
        } finally {
            release.countDown(); GeometryJobs.clear();
        }
    }

    private static void worldClear() throws Exception {
        GeometryJobs.clear();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        var slot = new GeometryJobs.Slot<String>();
        try {
            slot.getAsync(() -> {
                entered.countDown(); awaitUninterruptibly(release); finished.countDown(); return "old-world";
            });
            require(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "World job did not start");
            GeometryJobs.clear();
            require(GeometryJobs.pendingCount() == 0 && GeometryJobs.queueCount() == 0, "World clear retained queued work");
            release.countDown();
            require(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Cancelled world supplier did not finish");
            require(slot.getAsync(() -> "new-world") == null, "World clear left a stale result in its slot");
            require("new-world".equals(awaitValue(slot, () -> "new-world")), "World-cleared slot failed to install fresh work");
        } finally {
            release.countDown(); GeometryJobs.clear();
        }
    }

    private static void exceptionsReachCaller() throws Exception {
        GeometryJobs.clear();
        var slot = new GeometryJobs.Slot<Integer>();
        try {
            require(slot.getAsync(() -> { throw new IllegalArgumentException("geometry failure"); }) == null, "Failing job should first report pending");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                try { slot.getAsync(() -> 0); }
                catch (IllegalArgumentException expected) {
                    require("geometry failure".equals(expected.getMessage()), "Wrong exception reached the caller");
                    return;
                }
                Thread.sleep(5);
            }
            throw new AssertionError("Async build exception was swallowed or not delivered");
        } finally {
            GeometryJobs.clear();
        }
    }

    private static <T> T awaitValue(GeometryJobs.Slot<T> slot, java.util.function.Supplier<T> build) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            T value = slot.getAsync(build);
            if (value != null) return value;
            Thread.sleep(5);
        }
        throw new AssertionError("Async result did not become available");
    }

    private static void await(BooleanSupplier ready, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        require(ready.getAsBoolean(), message);
    }

    private static void enter(AtomicInteger active, AtomicInteger maximum) {
        int now = active.incrementAndGet(); maximum.accumulateAndGet(now, Math::max);
        require(Thread.currentThread().getName().equals("MTR Point geometry preparation"), "Geometry work ran off the preparation worker");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
