package apoc.periodic;

import apoc.Pools;
import apoc.util.Util;
import org.apache.commons.lang3.time.DateUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.QueryStatistics;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Pair;
import org.neo4j.logging.Log;
import org.neo4j.procedure.TerminationGuard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static apoc.periodic.Periodic.JobInfo;

public class PeriodicUtils {

    private PeriodicUtils() {

    }
    public enum ScheduleType { DEFAULT, FIXED_DELAY, FIXED_RATE }

    public static final String ERROR_DATE_BEFORE = "The provided date is before current date";

    public static Pair<String,Boolean> prepareInnerStatement(String cypherAction, BatchMode batchMode, List<String> columns, String iteratorVariableName) {
        String names = columns.stream().map(Util::quote).collect(Collectors.joining("|"));
        boolean withCheck = regNoCaseMultiLine("[{$](" + names + ")\\}?\\s+AS\\s+").matcher(cypherAction).find();
        if (withCheck) return Pair.of(cypherAction, false);

        switch(batchMode) {
            case SINGLE:
                 return Pair.of(Util.withMapping(columns.stream(), (c) ->  Util.param(c) + " AS " + Util.quote(c)) + cypherAction,false);
            case BATCH:
                if (regNoCaseMultiLine("UNWIND\\s+[{$]" + iteratorVariableName+"\\}?\\s+AS\\s+").matcher(cypherAction).find()) {
                    return Pair.of(cypherAction, true);
                }
                String with = Util.withMapping(columns.stream(), (c) -> Util.quote(iteratorVariableName) + "." + Util.quote(c) + " AS " + Util.quote(c));
                return Pair.of("UNWIND "+ Util.param(iteratorVariableName)+" AS "+ Util.quote(iteratorVariableName) + with + " " + cypherAction,true);
            case BATCH_SINGLE:
                return Pair.of(cypherAction, true);
            default:
                throw new IllegalArgumentException("Unrecognised batch mode: [" + batchMode + "]");
        }
    }
    
    public static Pattern regNoCaseMultiLine(String pattern) {
        return Pattern.compile(pattern,Pattern.CASE_INSENSITIVE|Pattern.MULTILINE|Pattern.DOTALL);
    }

    public static Stream<BatchAndTotalResult> iterateAndExecuteBatchedInSeparateThread(
            GraphDatabaseService db, TerminationGuard terminationGuard, Log log, Pools pools,
            int batchsize, boolean parallel, boolean iterateList, long retries,
            Iterator<Map<String, Object>> iterator, BiFunction<Transaction, Map<String, Object>, QueryStatistics> consumer,
            int concurrency, int failedParams, String periodicId) {

        ExecutorService pool = parallel ? pools.getDefaultExecutorService() : pools.getSingleExecutorService();
        List<Future<Long>> futures = new ArrayList<>(concurrency);
        BatchAndTotalCollector collector = new BatchAndTotalCollector(terminationGuard, failedParams);
        AtomicInteger activeFutures = new AtomicInteger(0);

        do {
            if (Util.transactionIsTerminated(terminationGuard)) break;

            if (activeFutures.get() < concurrency || !parallel) {
                // we have capacity, add a new Future to the list
                activeFutures.incrementAndGet();

                if (log.isDebugEnabled()) log.debug("Execute, in periodic iteration with id %s, no %d batch size ", periodicId, batchsize);
                List<Map<String,Object>> batch = Util.take(iterator, batchsize);
                final long currentBatchSize = batch.size();
                Periodic.ExecuteBatch executeBatch =
                        iterateList ?
                                new Periodic.ListExecuteBatch(terminationGuard, collector, batch, consumer) :
                                new Periodic.OneByOneExecuteBatch(terminationGuard, collector, batch, consumer);

                futures.add(Util.inTxFuture(log,
                        pool,
                        db,
                        executeBatch,
                        retries,
                        retryCount -> collector.incrementRetried(),
                        onComplete -> {
                            collector.incrementBatches();
                            executeBatch.release();
                            activeFutures.decrementAndGet();
                        }));
                collector.incrementCount(currentBatchSize);
                if (log.isDebugEnabled()) {
                    log.debug("Processed in periodic iteration with id %s, %d iterations of %d total", periodicId, batchsize, collector.getCount());
                }
            } else {
                // we can't block until the counter decrease as we might miss a cancellation, so
                // let this thread be preempted for a bit before we check for cancellation or
                // capacity.
                LockSupport.parkNanos(1000);
            }
        } while (iterator.hasNext());

        boolean wasTerminated = Util.transactionIsTerminated(terminationGuard);
        ToLongFunction<Future<Long>> toLongFunction = wasTerminated ?
                f -> Util.getFutureOrCancel(f, collector.getBatchErrors(), collector.getFailedBatches(), 0L) :
                f -> Util.getFuture(f, collector.getBatchErrors(), collector.getFailedBatches(), 0L);
        collector.incrementSuccesses(futures.stream().mapToLong(toLongFunction).sum());

        Util.logErrors("Error during iterate.commit:", collector.getBatchErrors(), log);
        Util.logErrors("Error during iterate.execute:", collector.getOperationErrors(), log);
        if (log.isDebugEnabled()) {
            log.debug("Terminated periodic iteration with id %s with %d executions", periodicId, collector.getCount());
        }
        return Stream.of(collector.getResult());
    }

    public static Stream<JobInfo> submitProc(String name, String statement, Map<String, Object> config, GraphDatabaseService db, Log log, Pools pools) {
        Map<String,Object> params = (Map)config.getOrDefault("params", Collections.emptyMap());

        final Temporal atTime = (Temporal) (config.get("atTime"));

        final Runnable task = () -> {
            try {
                db.executeTransactionally(statement, params);
            } catch (Exception e) {
                log.warn("in background task via submit", e);
                throw new RuntimeException(e);
            }
        };

        JobInfo info = atTime != null
                ? getJobInfo(name, atTime, task, log, pools, ScheduleType.DEFAULT)
                : submitJob(name, task, log, pools);

        return Stream.of(info);
    }

    public static JobInfo getJobInfo(String name, Temporal atTime, Runnable task, Log log, Pools pools, ScheduleType scheduleType) {
        if (atTime instanceof LocalDate) {
            atTime = ((LocalDate) atTime).atStartOfDay();
        }
        final boolean isTime = atTime instanceof OffsetTime || atTime instanceof LocalTime;
        Temporal now = isTime
                ? LocalTime.now()
                : LocalDateTime.now();

        final long secPerDay = DateUtils.MILLIS_PER_DAY / 1000L;
        long delay = now.until(atTime, ChronoUnit.SECONDS);
        if (isTime && delay < 0) {
            // we consider the day after
            delay = delay + secPerDay;
        }
        if (delay < 0) {
            throw new RuntimeException(ERROR_DATE_BEFORE);
        }
        return schedule(name, task, delay, secPerDay, log, pools, scheduleType);
    }

    /**
     * Call from a procedure that gets a <code>@Context GraphDatbaseAPI db;</code> injected and provide that db to the runnable.
     */
    public static <T> JobInfo submitJob(String name, Runnable task, Log log, Pools pools) {
        JobInfo info = new JobInfo(name);
        Future<T> future = pools.getJobList().remove(info);
        if (future != null && !future.isDone()) future.cancel(false);

        Runnable wrappingTask = wrapTask(name, task, log);
        Future newFuture = pools.getScheduledExecutorService().submit(wrappingTask);
        pools.getJobList().put(info,newFuture);
        return info;
    }
    
    public static JobInfo schedule(String name, Runnable task, long delay, long repeat, Log log, Pools pools) {
        return schedule(name, task, delay, repeat, log, pools, ScheduleType.FIXED_DELAY);
    }

    /**
     * Call from a procedure that gets a <code>@Context GraphDatbaseAPI db;</code> injected and provide that db to the runnable.
     */
    public static JobInfo schedule(String name, Runnable task, long delay, long repeat, Log log, Pools pools, ScheduleType isFixedDelay) {
        JobInfo info = new JobInfo(name, delay, isFixedDelay.equals(ScheduleType.DEFAULT) ? 0 : repeat);
        Future future = pools.getJobList().remove(info);
        if (future != null && !future.isDone()) future.cancel(false);

        Runnable wrappingTask = wrapTask(name, task, log);
        ScheduledFuture<?> newFuture = getScheduledFuture(wrappingTask, delay, repeat, pools, isFixedDelay);
        pools.getJobList().put(info,newFuture);
        return info;
    }
    
    private static ScheduledFuture<?> getScheduledFuture(Runnable wrappingTask, long delay, long repeat, Pools pools, ScheduleType isFixedDelay) {
        final ScheduledExecutorService service = pools.getScheduledExecutorService();
        final TimeUnit timeUnit = TimeUnit.SECONDS;
        switch (isFixedDelay) {
            case FIXED_DELAY:
                return service.scheduleWithFixedDelay(wrappingTask, delay, repeat, timeUnit);
            case FIXED_RATE:
                return service.scheduleAtFixedRate(wrappingTask, delay, repeat, timeUnit);
            default:
                return service.schedule(wrappingTask, delay, timeUnit);
        }
    }

    public static Runnable wrapTask(String name, Runnable task, Log log) {
        return () -> {
            log.debug("Executing task " + name);
            try {
                task.run();
            } catch (Exception e) {
                log.error("Error while executing task " + name + " because of the following exception (the task will be killed):", e);
                throw e;
            }
            log.debug("Executed task " + name);
        };
    }
}

/*
a batchMode variable where:
* single -> call 2nd statement individually but in one tx (currently iterateList: false)
* batch -> prepend UNWIND _batch to 2nd statement (currently iterateList: true)
* batch_single -> pass _batch through to 2nd statement
 */

