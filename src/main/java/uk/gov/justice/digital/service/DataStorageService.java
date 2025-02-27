package uk.gov.justice.digital.service;

import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import dev.failsafe.RetryPolicyBuilder;
import dev.failsafe.function.CheckedRunnable;
import io.delta.tables.DeltaTable;
import jakarta.inject.Inject;
import lombok.val;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.delta.DeltaConcurrentModificationException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.justice.digital.common.CommonDataFields;
import uk.gov.justice.digital.config.JobArguments;
import uk.gov.justice.digital.domain.model.SourceReference;
import uk.gov.justice.digital.domain.model.TableIdentifier;
import uk.gov.justice.digital.exception.DataStorageException;
import uk.gov.justice.digital.exception.DataStorageRetriesExhaustedException;

import javax.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static uk.gov.justice.digital.common.CommonDataFields.OPERATION;
import static uk.gov.justice.digital.common.CommonDataFields.ShortOperationCode.Delete;
import static uk.gov.justice.digital.common.CommonDataFields.ShortOperationCode.Insert;
import static uk.gov.justice.digital.common.CommonDataFields.ShortOperationCode.Update;

@Singleton
public class DataStorageService {

    private static final String SOURCE = "source";
    private static final String TARGET = "target";
    private static final Logger logger = LoggerFactory.getLogger(DataStorageService.class);

    // Retry policy used on operations that are at risk of concurrent modification exceptions
    private final RetryPolicy<Void> retryPolicy;

    private final String insertMatchCondition = createMatchExpression(Insert);
    private final String updateMatchCondition = createMatchExpression(Update);
    private final String deleteMatchCondition = createMatchExpression(Delete);
    private final String notDeleteMatchCondition = String.format("%s.%s<>'%s'", TARGET, OPERATION, Delete.getName());

    @Inject
    public DataStorageService(JobArguments jobArguments) {
        this.retryPolicy = buildRetryPolicy(jobArguments);
    }

    public boolean exists(SparkSession spark, TableIdentifier tableId) {
        val exists = DeltaTable.isDeltaTable(spark, tableId.toPath());
        logger.info("Delta table path {} {}", tableId.toPath(), (exists) ? "exists" : "does not exist");
        return exists;
    }


    public boolean hasRecords(SparkSession spark, TableIdentifier tableId) throws DataStorageException {
        val hasRecords = exists(spark, tableId) &&
                Optional.ofNullable(get(spark, tableId))
                        .map(df -> !df.isEmpty())
                        .orElse(false);

        logger.info("Delta table {} {}", tableId.getSchema(), (hasRecords) ? "contains data" : "is empty");

        return hasRecords;
    }

    public void append(String tablePath, Dataset<Row> df) throws DataStorageException {
        logger.debug("Appending schema and data to " + tablePath);
        if (tablePath != null && df != null) {
            doWithRetryOnConcurrentModification(() ->
                    df.write()
                            .format("delta")
                            .mode("append")
                            .option("path", tablePath)
                            .save()
            );
        } else {
            val errorMessage = "Path " + tablePath + " is not set or dataframe is null";
            logger.error(errorMessage);
            throw new DataStorageException(errorMessage);
        }
    }

    public void appendDistinct(String tablePath, Dataset<Row> df, SourceReference.PrimaryKey primaryKey) throws DataStorageException {
        if(!df.isEmpty()) {
            val dt = getTable(df.sparkSession(), tablePath);
            if(dt.isPresent()) {
                val condition = primaryKey.getSparkCondition(SOURCE, TARGET);
                doWithRetryOnConcurrentModification(() ->
                        dt.get().as(SOURCE)
                                .merge(df.as(TARGET), condition)
                                .whenNotMatched().insertAll()
                                .execute()
                );
            } else {
                append(tablePath, df);
            }
        }
    }
    public void mergeRecords(
            SparkSession spark,
            String tablePath,
            Dataset<Row> dataFrame,
            SourceReference.PrimaryKey primaryKey, List<String> columnsToExclude) throws DataStorageRetriesExhaustedException {
        val dt = getTable(spark, tablePath);

        val expression = createMergeExpression(dataFrame, columnsToExclude);

        try {
            if (dt.isPresent()) {
                val condition = primaryKey.getSparkCondition(SOURCE, TARGET);
                logger.debug("Upsert records from {} using condition: {}", tablePath, condition);
                doWithRetryOnConcurrentModification(() ->
                        dt.get().as(SOURCE)
                                .merge(dataFrame.as(TARGET), condition)
                                // Multiple whenMatched clauses are evaluated in the order they are specified.
                                // As such the Delete needs to be specified after the update
                                .whenMatched(insertMatchCondition)
                                .updateExpr(expression)
                                .whenMatched(updateMatchCondition)
                                .updateExpr(expression)
                                .whenMatched(deleteMatchCondition)
                                .delete()
                                .whenNotMatched()
                                .insertExpr(expression)
                                .execute()
                );
            } else {
                logger.error("Failed to upsert table {}. Delta table is not present", tablePath);
            }
        } catch (DataStorageRetriesExhaustedException e) {
            // We don't want to catch this particular Exception so rethrow before the next catch block
            throw e;
        } catch (Exception e) {
            val errorMessage = String.format("Failed to upsert table %s", tablePath);
            logger.error(errorMessage, e);
        }
    }

    public void mergeRecordsCdc(
            SparkSession spark,
            String tablePath,
            Dataset<Row> dataFrame,
            SourceReference.PrimaryKey primaryKey) throws DataStorageRetriesExhaustedException {
        val dt = DeltaTable
                .createIfNotExists(spark)
                .addColumns(dataFrame.schema())
                .location(tablePath)
                .execute();

        val expression = createMergeExpression(dataFrame, Collections.emptyList());
        val condition = primaryKey.getSparkCondition(SOURCE, TARGET);
        logger.info("Upsert records from {} using condition: {}", tablePath, condition);
        doWithRetryOnConcurrentModification(() ->
                dt.as(SOURCE)
                        .merge(dataFrame.as(TARGET), condition)
                        // Multiple whenMatched clauses are evaluated in the order they are specified.
                        // As such the Delete needs to be specified after the update
                        .whenMatched(insertMatchCondition)
                        .updateExpr(expression)
                        .whenMatched(updateMatchCondition)
                        .updateExpr(expression)
                        .whenMatched(deleteMatchCondition)
                        .delete()
                        // If the PKs don't match then insert anything that isn't a delete
                        .whenNotMatched(notDeleteMatchCondition)
                        .insertExpr(expression)
                        .execute()
        );
    }

    public void updateRecords(
            SparkSession spark,
            String tablePath,
            Dataset<Row> dataFrame,
            SourceReference.PrimaryKey primaryKey) throws DataStorageRetriesExhaustedException {
        val dt = getTable(spark, tablePath);

        try {
            if (dt.isPresent()) {
                val condition = primaryKey.getSparkCondition(SOURCE, TARGET);
                logger.debug("Updating records from {} using condition: {}", tablePath, condition);
                doWithRetryOnConcurrentModification(() ->
                        dt.get().as(SOURCE)
                                .merge(dataFrame.as(TARGET), condition)
                                .whenMatched()
                                .updateAll()
                                .execute()
                );
            } else {
                logger.error("Failed to update table {}. Delta table is not present", tablePath);
            }
        } catch (DataStorageRetriesExhaustedException e) {
            // We don't want to catch this particular Exception so rethrow before the next catch block
            throw e;
        } catch (Exception e) {
            val errorMessage = String.format("Failed to update table %s", tablePath);
            logger.error(errorMessage, e);
        }
    }

    public void deleteRecords(
            SparkSession spark,
            String tablePath,
            Dataset<Row> dataFrame,
            SourceReference.PrimaryKey primaryKey) throws DataStorageRetriesExhaustedException {
        val dt = getTable(spark, tablePath);

        try {
            if (dt.isPresent()) {
                val condition = primaryKey.getSparkCondition(SOURCE, TARGET);
                logger.debug("Deleting records from {} using condition: {}", tablePath, condition);
                doWithRetryOnConcurrentModification(() ->
                        dt.get().as(SOURCE)
                                .merge(dataFrame.as(TARGET), condition)
                                .whenMatched()
                                .delete()
                                .execute()
                );
            } else {
                logger.error("Failed to delete table {}. Delta table is not present", tablePath);
            }
        } catch (DataStorageRetriesExhaustedException e) {
            // We don't want to catch this particular Exception so rethrow before the next catch block
            throw e;
        } catch (Exception e) {
            val errorMessage = String.format("Failed to delete table %s", tablePath);
            logger.error(errorMessage, e);
        }
    }

    public void create(String tablePath, Dataset<Row> df) throws DataStorageException {
        logger.info("Inserting schema and data to " + tablePath);
        if (tablePath != null && df != null)
            df.write()
                    .format("delta")
                    .option("path", tablePath)
                    .save();
        else {
            val errorMessage = "Path " + tablePath + " is not set or dataframe is null";
            logger.error(errorMessage);
            throw new DataStorageException(errorMessage);
        }
    }

    public void replace(String tablePath, Dataset<Row> df) throws DataStorageException {
        logger.info("Overwriting schema and data to " + tablePath);
        if (tablePath != null && df != null)
            df.write()
                    .format("delta")
                    .mode("overwrite")
                    .option("overwriteSchema", true)
                    .option("path", tablePath)
                    .save();
        else {
            val errorMessage = "Path " + tablePath + " is not set or dataframe is null";
            logger.error(errorMessage);
            throw new DataStorageException(errorMessage);
        }
    }

    public void resync(String tablePath, Dataset<Row> df) throws DataStorageException {
        logger.info("Syncing data to " + tablePath);
        if (tablePath != null && df != null)
            df.write()
                    .format("delta")
                    .mode("overwrite")
                    .option("path", tablePath)
                    .save();
        else {
            val errorMessage = "Path " + tablePath + " is not set or dataframe is null";
            logger.error(errorMessage);
            throw new DataStorageException(errorMessage);
        }

    }

    public void delete(SparkSession spark, TableIdentifier tableId) throws DataStorageException {
        logger.info("Deleting Delta table {}.{}", tableId.getSchema(), tableId.getTable());

        getTable(spark, tableId.toPath())
                .orElseThrow(() -> new DataStorageException("Failed to delete table. Table does not exist"))
                .delete();
    }

    public void vacuum(SparkSession spark, TableIdentifier tableId) throws DataStorageException {
        logger.info("Vacuuming Delta table {}.{}", tableId.getSchema(), tableId.getTable());
        vacuum(spark, tableId.toPath());
    }

    /**
     * Run a delta lake vacuum operation on the delta table at the given tablePath.
     */
    public void vacuum(SparkSession spark, String tablePath) throws DataStorageException {
        logger.info("Vacuuming Delta table at {}", tablePath);
        val deltaTable = getTable(spark, tablePath)
                .orElseThrow(() -> new DataStorageException("Failed to vacuum table. Table does not exist"));

        doWithRetryOnConcurrentModification(deltaTable::vacuum);

        updateManifest(deltaTable);
        logger.info("Finished vacuuming Delta table at {}", tablePath);
    }

    public Dataset<Row> get(SparkSession spark, TableIdentifier tableId) {
        return get(spark, tableId.toPath());
    }

    public Dataset<Row> get(SparkSession spark, String tablePath) {
        return getTable(spark, tablePath).map(DeltaTable::toDF).orElse(spark.emptyDataFrame());
    }

    protected Optional<DeltaTable> getTable(SparkSession spark, String tablePath) {
        if (DeltaTable.isDeltaTable(spark, tablePath))
            return Optional.of(DeltaTable.forPath(spark, tablePath));
        else {
            logger.warn("No valid table found for path: {} - Not a delta table", tablePath);
            return Optional.empty();
        }
    }

    public void endTableUpdates(SparkSession spark, TableIdentifier tableId) throws DataStorageException {
        updateDeltaManifestForTable(spark, tableId.toPath());
    }

    protected void updateManifest(DeltaTable dt) {
        dt.generate("symlink_format_manifest");
    }

    public void updateDeltaManifestForTable(SparkSession spark, String tablePath) {
        logger.info("Updating manifest for table: {}", tablePath);

        val deltaTable = getTable(spark, tablePath);

        if (deltaTable.isPresent()) updateManifest(deltaTable.get());
        else logger.warn("Unable to update manifest for table: {} Not a delta table", tablePath);
    }

    /**
     * Runs a delta lake compaction on the Delta table at the given tablePath
     */

    public void compactDeltaTable(SparkSession spark, String tablePath) throws DataStorageException {
        logger.info("Compacting table at path: {}", tablePath);
        val deltaTable = getTable(spark, tablePath)
                .orElseThrow(() -> new DataStorageException(format("Failed to compact table at path %s. Table does not exist", tablePath)));
        doWithRetryOnConcurrentModification(() -> deltaTable.optimize().executeCompaction());
        updateManifest(deltaTable);
        logger.info("Finished compacting table at path: {}", tablePath);
    }

    /**
     * List all Delta table paths below rootPath recursively to the provided depth limit
     * @return The list of delta table paths (including hadoop filesystem prefix, e.g. s3://)
     */
    public List<String> listDeltaTablePaths(SparkSession spark, String rootPath, int depthLimit) throws DataStorageException {
        logger.info("Listing all delta table paths below: {}", rootPath);
        try {
            val fs = FileSystem.get(new URI(rootPath), spark.sparkContext().hadoopConfiguration());
            List<String> deltaTablePathAccumulator = new ArrayList<>();
            collectDeltaTablePaths(spark, fs, rootPath, depthLimit, deltaTablePathAccumulator);
            return deltaTablePathAccumulator;
        } catch (URISyntaxException e) {
            val errorMessage = format("Badly formatted root path when listing delta tables: %s", rootPath);
            logger.error(errorMessage);
            throw new DataStorageException(errorMessage, e);
        } catch (IOException e) {
            val errorMessage = format("Exception encountered when listing delta tables at root path: %s", rootPath);
            logger.error(errorMessage);
            throw new DataStorageException(errorMessage, e);
        }
    }

    /**
     * Helper to recurse all delta table paths depth-first to given depth.
     * Uses an accumulator rather than keeping intermediate results on the stack.
     */
    private void collectDeltaTablePaths(SparkSession spark, FileSystem fs, String rootPath, int depthLimit, List<String> accumulator) throws IOException {
        logger.debug("Listing all delta table paths below: {}", rootPath);
        val path = new Path(rootPath);

        Map<Boolean, List<String>> deltaTablesAndOtherDirs = Arrays.stream(fs.listStatus(path))
                .filter(FileStatus::isDirectory)
                .map(FileStatus::getPath)
                .map(Path::toUri)
                .map(URI::toString)
                .collect(Collectors.partitioningBy(tablePath -> DeltaTable.isDeltaTable(spark, tablePath)));

        val deltaTables = deltaTablesAndOtherDirs.get(true);
        accumulator.addAll(deltaTables);
        logger.debug("Found {} delta tables under {}", deltaTables.size(), rootPath);

        if(depthLimit > 1) {
            // We can keep recursing so let's look in all the directories at this level
            val otherDirs = deltaTablesAndOtherDirs.get(false);
            logger.debug("Found {} directories that are not delta tables to recurse under {}", otherDirs.size(), rootPath);
            for(String dir: otherDirs) {
                collectDeltaTablePaths(spark, fs, dir, depthLimit - 1, accumulator);
            }
        } else {
            logger.debug("Reached depth limit. Skipping recursing non-delta table directories under path {}", rootPath);
        }

    }

    private void doWithRetryOnConcurrentModification(CheckedRunnable runnable) throws DataStorageRetriesExhaustedException {
        try {
            Failsafe.with(retryPolicy).run(runnable);
        } catch (DeltaConcurrentModificationException e) {
            throw new DataStorageRetriesExhaustedException(e);
        }
    }

    private static RetryPolicy<Void> buildRetryPolicy(JobArguments jobArguments) {
        long minWaitMillis = jobArguments.getDataStorageRetryMinWaitMillis();
        long maxWaitMillis = jobArguments.getDataStorageRetryMaxWaitMillis();
        double jitterFactor = jobArguments.getDataStorageRetryJitterFactor();
        // You can turn off retries by setting max attempts to 1
        int maxAttempts = jobArguments.getDataStorageRetryMaxAttempts();
        logger.info("Retry Policy Settings: max attempts: {}, min wait: {}ms, max wait: {}ms, jitter factor: {}", maxAttempts, minWaitMillis, maxWaitMillis, jitterFactor);
        RetryPolicyBuilder<Void> builder = RetryPolicy.builder();
        // Specify the Throwables we will retry
        builder.handle(DeltaConcurrentModificationException.class)
                // Exponential backoff
                .withBackoff(minWaitMillis, maxWaitMillis, ChronoUnit.MILLIS)
                .withJitter(jitterFactor)
                .withMaxAttempts(maxAttempts)
                .onFailedAttempt(e -> {
                    Throwable lastException = e.getLastException();
                    int thisAttempt = e.getAttemptCount();
                    String msg = format("Failed attempt %,d.", thisAttempt);
                    logger.debug(msg, lastException);
                })
                .onRetry(e -> {
                    int lastAttempt = e.getAttemptCount();
                    long elapsedTimeTotal = e.getElapsedTime().toMillis();
                    String msg = format("Retrying after attempt %,d. Elapsed time total: %,dms.", lastAttempt, elapsedTimeTotal);
                    logger.debug(msg);
                })
                .onRetriesExceeded(e -> {
                    Throwable lastException = e.getException();
                    int thisAttempt = e.getAttemptCount();
                    long elapsedTimeTotal = e.getElapsedTime().toMillis();
                    String msg = format("Retries exceeded on attempt %,d. Elapsed time total: %,dms.", thisAttempt, elapsedTimeTotal);
                    logger.error(msg, lastException);
                });
        return builder.build();
    }

    @NotNull
    private static Map<String, String> createMergeExpression(Dataset<Row> dataFrame, List<String> columnsToExclude) {
        return Arrays
                .stream(dataFrame.columns())
                .filter(column -> !columnsToExclude.contains(column))
                .collect(Collectors.toMap(column -> SOURCE + "." + column, column -> TARGET + "." + column));
    }

    private String createMatchExpression(CommonDataFields.ShortOperationCode operation) {
        return String.format("%s.%s=='%s'", TARGET, OPERATION, operation.getName());
    }

}
