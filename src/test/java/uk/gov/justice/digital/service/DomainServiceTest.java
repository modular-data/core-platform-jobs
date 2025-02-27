package uk.gov.justice.digital.service;

import lombok.val;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.justice.digital.client.dynamodb.DomainDefinitionClient;
import uk.gov.justice.digital.config.BaseSparkTest;
import uk.gov.justice.digital.config.JobArguments;
import uk.gov.justice.digital.converter.dms.DMS_3_4_7;
import uk.gov.justice.digital.domain.DomainExecutor;
import uk.gov.justice.digital.domain.model.DomainDefinition;
import uk.gov.justice.digital.domain.model.TableDefinition;

import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static uk.gov.justice.digital.converter.dms.DMS_3_4_7.ParsedDataFields.*;
import static uk.gov.justice.digital.test.Fixtures.getAllCapturedRecords;

@ExtendWith(MockitoExtension.class)
public class DomainServiceTest extends BaseSparkTest {

    private static final String domainName = "SomeDomain";
    private static final String relevantDomainTableName = "RelevantDomainTable";
    private static final String irrelevantDomainTableName = "IrrelevantDomainTable";
    private static final String source = "source";

    @Mock
    private JobArguments mockJobArguments;
    @Mock
    private DomainDefinitionClient mockDomainDefinitionClient;
    @Mock
    private DomainExecutor mockDomainExecutor;
    @Mock
    private DomainDefinition mockDomainDefinition;

    @Captor
    ArgumentCaptor<Dataset<Row>> dataframeCaptor;

    private DomainService underTest;

    @BeforeEach
    public void setup() {
        underTest = new DomainService(mockJobArguments, mockDomainDefinitionClient, mockDomainExecutor);
    }

    @Test
    public void shouldRunDeleteWhenOperationIsDelete() throws Exception {
        givenJobArgumentsWithOperation("delete");

        underTest.run(spark);

        verify(mockDomainExecutor).doDomainDelete(spark, domainName, relevantDomainTableName);
    }

    @Test
    public void shouldRunFullRefreshWhenOperationIsInsert() throws Exception {
        givenJobArgumentsWithOperation("insert");
        givenTheClientReturnsADomainDefinition();

        underTest.run(spark);

        verify(mockDomainExecutor).doFullDomainRefresh(spark, mockDomainDefinition, relevantDomainTableName, "insert");
    }

    @ParameterizedTest
    @EnumSource(value = DMS_3_4_7.Operation.class, names = {"Insert", "Update", "Delete"})
    public void shouldIncrementallyRefreshRelevantDomainWhenGivenRecordsForCDCOperations(DMS_3_4_7.Operation operation) throws Exception {
        val recordsToInsert = createInputDataFrame(operation);
        val domainDefinition = createDomainDefinition();
        val domainDefinitions = Collections.singletonList(domainDefinition);

        val transformedDataFrame1 = createTransformedDataFrame("1");
        val transformedDataFrame2 = createTransformedDataFrame("2");
        val transformedDataFrame3 = createTransformedDataFrame("3");

        val transformedDataFrames = new ArrayList<Dataset<Row>>();
        transformedDataFrames.add(transformedDataFrame1);
        transformedDataFrames.add(transformedDataFrame2);
        transformedDataFrames.add(transformedDataFrame3);

        val expectedCapturedRecords = transformedDataFrames
                .stream()
                .flatMap(df -> df.collectAsList().stream())
                .collect(Collectors.toList());

        when(mockDomainDefinitionClient.getDomainDefinitions()).thenReturn(domainDefinitions);
        when(mockDomainExecutor.applyTransform(eq(spark), any(), any()))
                .thenReturn(
                        transformedDataFrame1,
                        transformedDataFrame2,
                        transformedDataFrame3
                );

        doNothing()
                .when(mockDomainExecutor)
                .applyDomain(
                        eq(spark),
                        dataframeCaptor.capture(),
                        eq(domainName),
                        eq(domainDefinition.getTables().get(0)),
                        eq(operation)
                );

        underTest.refreshDomainUsingDataFrame(spark, recordsToInsert, source, relevantDomainTableName);

        assertIterableEquals(
                expectedCapturedRecords,
                getAllCapturedRecords(dataframeCaptor)
        );
    }

    @ParameterizedTest
    @EnumSource(value = DMS_3_4_7.Operation.class, names = {"Insert", "Update", "Delete", "Load"})
    public void shouldSkipAndContinueWhenNoMatchingDomainDefinitionIsFound(DMS_3_4_7.Operation operation) throws Exception {
        val recordsToInsert = createInputDataFrame(operation);
        val domainDefinition = createDomainDefinition();
        val domainDefinitions = Collections.singletonList(domainDefinition);

        when(mockDomainDefinitionClient.getDomainDefinitions()).thenReturn(domainDefinitions);

        assertDoesNotThrow(() -> underTest.refreshDomainUsingDataFrame(spark, recordsToInsert, source, "otherTable"));

        verifyNoInteractions(mockDomainExecutor);
    }

    @ParameterizedTest
    @EnumSource(value = DMS_3_4_7.Operation.class, names = {"Insert", "Update", "Delete", "Load"})
    public void shouldFailWhenThereAreNoDomainDefinitions(DMS_3_4_7.Operation operation) throws Exception {
        val recordsToInsert = createInputDataFrame(operation);
        List<DomainDefinition> domainDefinitions = Collections.emptyList();

        when(mockDomainDefinitionClient.getDomainDefinitions()).thenReturn(domainDefinitions);

        assertThrows(
                RuntimeException.class,
                () -> underTest.refreshDomainUsingDataFrame(spark, recordsToInsert, source, relevantDomainTableName)
        );

        verifyNoInteractions(mockDomainExecutor);
    }

    @ParameterizedTest
    @EnumSource(value = DMS_3_4_7.Operation.class, names = {"Load"})
    public void shouldNotIncrementallyRefreshRecordsForLoadOperations(DMS_3_4_7.Operation operation) throws Exception {
        val recordsToInsert = createInputDataFrame(operation);
        val domainDefinition = createDomainDefinition();

        when(mockDomainDefinitionClient.getDomainDefinitions()).thenReturn(Collections.singletonList(domainDefinition));

        assertDoesNotThrow(() -> underTest.refreshDomainUsingDataFrame(spark, recordsToInsert, source, relevantDomainTableName));

        verifyNoInteractions(mockDomainExecutor);
    }

    private void givenJobArgumentsWithOperation(String operation) {
        when(mockJobArguments.getDomainTableName()).thenReturn(relevantDomainTableName);
        when(mockJobArguments.getDomainName()).thenReturn(domainName);
        when(mockJobArguments.getDomainOperation()).thenReturn(operation);
    }

    private void givenTheClientReturnsADomainDefinition() throws Exception {
        when(mockDomainDefinition.getName()).thenReturn(domainName);
        when(mockDomainDefinitionClient.getDomainDefinition(domainName, relevantDomainTableName)).thenReturn(mockDomainDefinition);
    }

    private Dataset<Row> createTransformedDataFrame(String id) {
        val tableSchema = new StructType()
                .add("id", DataTypes.StringType)
                .add("description", DataTypes.StringType);

        val records = new ArrayList<Row>();
        records.add(RowFactory.create(id, "some description 1"));

        return spark.createDataFrame(records, tableSchema);
    }

    private Dataset<Row> createInputDataFrame(DMS_3_4_7.Operation operation) {
        val tableSchema = new StructType()
                .add("table_id", DataTypes.StringType)
                .add("description", DataTypes.StringType)
                .add("column_1", DataTypes.StringType)
                .add("column_2", DataTypes.StringType)
                .add(OPERATION, DataTypes.StringType);

        val records = new ArrayList<Row>();
        records.add(RowFactory.create("1", "description 1", "column_1_1", "column_2_1", operation.getName()));
        records.add(RowFactory.create("2", "description 2", "column_1_2", "column_2_2", operation.getName()));
        records.add(RowFactory.create("3", "description 3", "column_1_3", "column_2_3", operation.getName()));

        return spark.createDataFrame(records, tableSchema);
    }

    private DomainDefinition createDomainDefinition() {
        val tableDefinition = createDomainTableDefinition(relevantDomainTableName);
        val irrelevantDomainTableDefinition = createDomainTableDefinition(irrelevantDomainTableName);

        ArrayList<TableDefinition> tables = new ArrayList<>();
        tables.add(tableDefinition);
        tables.add(irrelevantDomainTableDefinition);

        DomainDefinition domainDefinition = new DomainDefinition();
        domainDefinition.setName(domainName);
        domainDefinition.setTables(tables);

        return domainDefinition;
    }

    @NotNull
    private static TableDefinition createDomainTableDefinition(String domainTableName) {
        val tableDefinition = new TableDefinition();
        tableDefinition.setName(domainTableName);
        tableDefinition.setPrimaryKey("table_id");
        tableDefinition.setViolations(new ArrayList<>());

        TableDefinition.TransformDefinition transform = new TableDefinition.TransformDefinition();

        val sources = Collections.singletonList(source + "." + domainTableName);

        transform.setSources(sources);
        transform.setViewText(
                "SELECT " +
                        source + "." + domainTableName + ".table_id as id, " +
                        source + "." + domainTableName + ".table_description as description " +
                        "from " + source + "." + domainTableName
        );

        tableDefinition.setTransform(transform);
        return tableDefinition;
    }

}
