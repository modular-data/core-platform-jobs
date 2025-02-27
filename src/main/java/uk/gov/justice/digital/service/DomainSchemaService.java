package uk.gov.justice.digital.service;

import com.amazonaws.services.glue.AWSGlue;
import com.amazonaws.services.glue.model.*;
import lombok.val;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.justice.digital.client.glue.GlueClientProvider;
import uk.gov.justice.digital.domain.model.TableIdentifier;
import uk.gov.justice.digital.exception.DomainSchemaException;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

// TODO - this should not use the glueClient directly
@Singleton
public class DomainSchemaService {

    private final static String TABLE_NAME_PATTERN = "^\\w*$";
    private final static String SCHEMA_TABLE_SEPARATOR = "_";

    private static final Logger logger = LoggerFactory.getLogger(DomainSchemaService.class);

    private final AWSGlue glueClient;

    @Inject
    public DomainSchemaService(GlueClientProvider glueClientProvider) {
        this.glueClient = glueClientProvider.getClient();
    }

    public boolean databaseExists(String databaseName) {
        GetDatabaseRequest request = new GetDatabaseRequest().withName(databaseName);
        GetDatabaseResult result = glueClient.getDatabase(request);

        return Optional.ofNullable(result.getDatabase())
                .map(Database::getName)
                .filter(n -> n.equals(databaseName))
                .isPresent();
    }

    // TODO - is the entire method only needed for testing or some condition within it?
    // This is needed only for unit testing
    public void create(TableIdentifier info, String path, Dataset<Row> dataFrame) throws DomainSchemaException {
        if (databaseExists(info.getDatabase())) {
            logger.info("Hive Schema insert started for " + info.getDatabase());
            String tableName = combineTableName(info.getSchema(), info.getTable());
            if (!tableExists(info.getDatabase(),  tableName)) {
                createTable(info.getDatabase(), tableName, path, dataFrame);
                logger.info("Creating hive schema completed:" + tableName);
            } else {
                throw new DomainSchemaException("Glue catalog table '" + tableName + "' already exists");
            }
        } else {
            logger.error("Glue catalog database '" + info.getDatabase() + "' doesn't exist");
            throw new DomainSchemaException("Glue catalog database '" + info.getDatabase() + "' doesn't exist");
        }
    }

    public void replace(TableIdentifier info, String path, Dataset<Row> dataFrame) throws DomainSchemaException {
        if (databaseExists(info.getDatabase())) {
            logger.info("Hive Schema insert started for " + info.getDatabase());
            final String tableName = combineTableName(info.getSchema(), info.getTable());
            if (tableExists(info.getDatabase(), tableName)) {
                updateTable(info.getDatabase(), tableName, path, dataFrame);
                logger.info("Replacing Hive Schema completed " + tableName);
            } else {
                logger.error("Glue catalog table '" + info.getTable() + "' doesn't exist");
                throw new DomainSchemaException("Glue catalog table '" + info.getTable() + "' doesn't exist");
            }
        } else {
            logger.error("Glue catalog database '" + info.getDatabase() + "' doesn't exist");
            throw new DomainSchemaException("Glue catalog database '" + info.getDatabase() + "' doesn't exist");
        }
    }

    public void drop(TableIdentifier info) throws DomainSchemaException {
        if (databaseExists(info.getDatabase())) {
            final String tableName = combineTableName(info.getSchema(), info.getTable());
            if (tableExists(info.getDatabase(),
                    tableName)) {
                deleteTable(info.getDatabase(), tableName);
                logger.info("Dropping Hive Schema completed " + tableName);
            } else {
                logger.warn("Couldn't remove Hive Schema " + tableName + " as it doesn't exist");
            }
        } else {
            logger.error("Glue catalog " + info.getDatabase() + " doesn't exist");
            throw new DomainSchemaException("Glue catalog " + info.getDatabase() + " doesn't exist");
        }
    }

    public boolean tableExists(String databaseName, String tableName) {
        GetTableRequest request = new GetTableRequest()
                .withDatabaseName(databaseName)
                .withName(tableName);
        try {
            glueClient.getTable(request);
            logger.info("Hive Catalog Table '" + tableName + "' found");
            return Boolean.TRUE;
        } catch (EntityNotFoundException e) {
            logger.info("Hive Catalog Table '" + tableName + "' not found");
            return Boolean.FALSE;
        }
    }

    public void updateTable(String databaseName, String tableName, String path, Dataset<Row> dataframe) {
        // TODO - what if create fails? do we have transactional semantics here?
        deleteTable(databaseName, tableName);
        createTable(databaseName, tableName, path, dataframe);
    }

    public void deleteTable(final String databaseName, final String tableName) {
        DeleteTableRequest deleteTableRequest = new DeleteTableRequest()
                .withDatabaseName(databaseName)
                .withName(tableName);
        try {
            glueClient.deleteTable(deleteTableRequest);
        } catch (Exception e) {
            logger.error("Delete failed for " + databaseName + ":" + tableName + " " + e.getMessage());
        }
    }

    public void createTable(final String databaseName, final String tableName, final String path,
                            final Dataset<Row> dataframe) {
        if(!validateTableName(tableName)) {
            logger.error("Table name " + tableName + " is not supported. Use [a-zA-Z_0-9]");
            throw new RuntimeException("Table name " + tableName + " is not supported. Use [a-zA-Z_0-9]");
        }
        // Create a CreateTableRequest
        CreateTableRequest createTableRequest = new CreateTableRequest()
                .withDatabaseName(databaseName)
                .withTableInput(new TableInput()
                        .withName(tableName)
                        .withTableType("EXTERNAL_TABLE")
                        .withParameters(Collections.singletonMap("classification", "parquet"))
                        .withStorageDescriptor(
                                new StorageDescriptor()
                                        .withColumns(getColumnsAndModifyTypes(dataframe.schema()))
                                        .withLocation(path + "/_symlink_format_manifest")
                                        .withInputFormat("org.apache.hadoop.hive.ql.io.SymlinkTextInputFormat")
                                        .withOutputFormat("org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat")
                                        .withSerdeInfo(
                                                new SerDeInfo()
                                                        .withSerializationLibrary("org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe")
                                                        .withParameters(Collections.singletonMap("serialization.format", ","))
                                        )
                                        .withCompressed(false)
                                        .withNumberOfBuckets(0)
                                        .withStoredAsSubDirectories(false)
                        )
                );

        // Create the table in the AWS Glue Data Catalog
        try {
            glueClient.createTable(createTableRequest);
            logger.info("Table created in hive : " + tableName);
        } catch (Exception e) {
            logger.error("Create table failed : Table Name " + tableName + "Reason: " + e.getMessage());
        }
    }

    private List<Column> getColumnsAndModifyTypes(StructType schema) {
        val columns = new ArrayList<Column>();
        for (StructField field : schema.fields()) {
            val col = new Column()
                    .withName(field.name())
                    .withType(field.dataType().typeName());
            // Null type not supported in AWS Glue Catalog.
            // Numerical type mappings should be explicit and not automatically selected.
            if (col.getType().equals("long")) {
                col.setType("bigint");
            } else if (col.getType().equals("short")) {
                col.setType("smallint");
            } else if (col.getType().equals("integer")) {
                col.setType("int");
            } else if (col.getType().equals("byte")) {
                col.setType("tinyint");
            }
            columns.add(col);
        }
        return columns;
    }

    private String combineTableName(String schema, String table) {
        return schema + SCHEMA_TABLE_SEPARATOR + table;
    }
    private boolean validateTableName(String tablename) {
        return Pattern.matches(TABLE_NAME_PATTERN, tablename);
    }
}
