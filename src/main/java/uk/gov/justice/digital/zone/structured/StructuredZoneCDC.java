package uk.gov.justice.digital.zone.structured;

import lombok.val;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.justice.digital.config.JobArguments;
import uk.gov.justice.digital.exception.DataStorageException;
import uk.gov.justice.digital.service.DataStorageService;
import uk.gov.justice.digital.service.SourceReferenceService;
import uk.gov.justice.digital.writer.Writer;
import uk.gov.justice.digital.writer.structured.StructuredZoneCdcWriter;

import javax.inject.Inject;

import static org.apache.spark.sql.functions.col;
import static uk.gov.justice.digital.converter.dms.DMS_3_4_6.Operation.cdcOperations;
import static uk.gov.justice.digital.converter.dms.DMS_3_4_6.ParsedDataFields.*;

public class StructuredZoneCDC extends StructuredZone {

    private static final Logger logger = LoggerFactory.getLogger(StructuredZoneCDC.class);


    @Inject
    public StructuredZoneCDC(
            JobArguments jobArguments,
            DataStorageService storage,
            SourceReferenceService sourceReference
    ) {
        this(jobArguments, sourceReference, createWriter(storage));
    }

    private StructuredZoneCDC(
            JobArguments jobArguments,
            SourceReferenceService sourceReference,
            Writer writer
    ) {
        super(jobArguments, sourceReference, writer);
    }

    private static Writer createWriter(DataStorageService storage) {
        return new StructuredZoneCdcWriter(storage);
    }

    @Override
    public Dataset<Row> process(SparkSession spark, Dataset<Row> dataFrame, Row table) throws DataStorageException {
        val filteredRecords = dataFrame.filter(col(OPERATION).isin(cdcOperations));

        val rowCount = filteredRecords.count();
        String sourceName = table.getAs(SOURCE);
        String tableName = table.getAs(TABLE);

        val startTime = System.currentTimeMillis();

        logger.info("Processing {} records for {}/{}", rowCount, sourceName, tableName);
        val result = super.process(spark, filteredRecords, table);
        logger.info("Processed batch with {} rows in {}ms", rowCount, System.currentTimeMillis() - startTime);

        return result;
    }

}
