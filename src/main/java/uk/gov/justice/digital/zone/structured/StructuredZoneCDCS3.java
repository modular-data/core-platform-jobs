package uk.gov.justice.digital.zone.structured;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.val;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.justice.digital.config.JobArguments;
import uk.gov.justice.digital.domain.model.SourceReference;
import uk.gov.justice.digital.exception.DataStorageRetriesExhaustedException;
import uk.gov.justice.digital.service.DataStorageService;
import uk.gov.justice.digital.service.ViolationService;

import static uk.gov.justice.digital.common.ResourcePath.tablePath;
import static uk.gov.justice.digital.service.ViolationService.ZoneName.STRUCTURED_CDC;

@Singleton
public class StructuredZoneCDCS3 {

    private static final Logger logger = LoggerFactory.getLogger(StructuredZoneCDCS3.class);

    private final String structuredZoneRootPath;
    private final ViolationService violationService;
    private final DataStorageService storage;

    @Inject
    public StructuredZoneCDCS3(
            JobArguments arguments,
            ViolationService violationService,
            DataStorageService storage) {
        this.structuredZoneRootPath = arguments.getStructuredS3Path();
        this.violationService = violationService;
        this.storage = storage;
    }


    public void process(SparkSession spark, Dataset<Row> dataFrame, SourceReference sourceReference) {
        val startTime = System.currentTimeMillis();
        String sourceName = sourceReference.getSource();
        String tableName = sourceReference.getTable();
        String structuredTablePath = tablePath(structuredZoneRootPath, sourceName, tableName);
        logger.debug("Processing records for structured {}/{} {}", sourceName, tableName, structuredTablePath);

        try {
            logger.debug("Merging {} records to deltalake table: {}", dataFrame.count(), structuredTablePath);
            storage.mergeRecordsCdc(spark, structuredTablePath, dataFrame, sourceReference.getPrimaryKey());
            logger.debug("Merge completed successfully to table: {}", structuredTablePath);
            logger.info("Processed batch for structured {}/{} in {}ms", sourceName, tableName, System.currentTimeMillis() - startTime);
        } catch (DataStorageRetriesExhaustedException e) {
            logger.warn("Structured zone cdc retries exhausted", e);
            violationService.handleRetriesExhaustedS3(spark, dataFrame, sourceName, tableName, e, STRUCTURED_CDC);
        }
    }


}
