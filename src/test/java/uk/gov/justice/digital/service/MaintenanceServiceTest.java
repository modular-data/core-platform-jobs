package uk.gov.justice.digital.service;

import io.delta.exceptions.ConcurrentDeleteReadException;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.justice.digital.exception.DataStorageException;
import uk.gov.justice.digital.exception.MaintenanceOperationFailedException;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MaintenanceServiceTest {

    private static final String rootPath = "s3:///some/path";
    private static final String table1 = rootPath + "/table1";
    private static final String table2 = rootPath + "/table2";
    private static final String table3 = rootPath + "/table3";
    private static final List<String> deltaTablePaths = Arrays.asList(table1, table2, table3);

    private MaintenanceService underTest;
    @Mock
    private DataStorageService mockDataStorageService;
    @Mock
    private SparkSession mockSparkSession;

    @BeforeEach
    public void setup() {
        underTest = new MaintenanceService(mockDataStorageService);
    }

    @Test
    public void shouldCompactEachTable() throws Exception {
        when(mockDataStorageService.listDeltaTablePaths(mockSparkSession, rootPath)).thenReturn(deltaTablePaths);
        underTest.compactDeltaTables(mockSparkSession, rootPath);
        verify(mockDataStorageService).compactDeltaTable(mockSparkSession, table1);
        verify(mockDataStorageService).compactDeltaTable(mockSparkSession, table2);
        verify(mockDataStorageService).compactDeltaTable(mockSparkSession, table3);
    }

    @Test
    public void shouldTryToCompactAllTablesWhenCompactionFailsWithConcurrentDeleteReadException() throws Exception {
        when(mockDataStorageService.listDeltaTablePaths(mockSparkSession, rootPath)).thenReturn(deltaTablePaths);

        // This is a specific Exception we have seen and want to handle
        doThrow(new ConcurrentDeleteReadException("Failed compaction"))
                .when(mockDataStorageService).compactDeltaTable(eq(mockSparkSession), anyString());

        assertThrows(MaintenanceOperationFailedException.class, () -> underTest.compactDeltaTables(mockSparkSession, rootPath));
        verify(mockDataStorageService).compactDeltaTable(mockSparkSession, table1);
        verify(mockDataStorageService).compactDeltaTable(mockSparkSession, table2);
        verify(mockDataStorageService).compactDeltaTable(mockSparkSession, table3);
    }

    @Test
    public void shouldTryToCompactAllTablesWhenCompactionFailsWithException() throws Exception {
        when(mockDataStorageService.listDeltaTablePaths(mockSparkSession, rootPath)).thenReturn(deltaTablePaths);

        // make sure we handle checked and unchecked exceptions in general
        doThrow(new DataStorageException("Failed compaction"))
                .when(mockDataStorageService).compactDeltaTable(eq(mockSparkSession), eq(table1));

        doThrow(new RuntimeException(
                "Failed compaction"))
                .when(mockDataStorageService).compactDeltaTable(eq(mockSparkSession), eq(table2));

        assertThrows(MaintenanceOperationFailedException.class, () -> underTest.compactDeltaTables(mockSparkSession, rootPath));
        verify(mockDataStorageService).compactDeltaTable(mockSparkSession, table1);
        verify(mockDataStorageService).compactDeltaTable(mockSparkSession, table2);
        verify(mockDataStorageService).compactDeltaTable(mockSparkSession, table3);
    }

    @Test
    public void shouldVacuumEachTable() throws Exception {
        when(mockDataStorageService.listDeltaTablePaths(mockSparkSession, rootPath)).thenReturn(deltaTablePaths);
        underTest.vacuumDeltaTables(mockSparkSession, rootPath);
        verify(mockDataStorageService).vacuum(mockSparkSession, table1);
        verify(mockDataStorageService).vacuum(mockSparkSession, table2);
        verify(mockDataStorageService).vacuum(mockSparkSession, table3);
    }

    @Test
    public void shouldTryToVacuumAllTablesWhenVacuumFailsWithException() throws Exception {
        when(mockDataStorageService.listDeltaTablePaths(mockSparkSession, rootPath)).thenReturn(deltaTablePaths);

        doThrow(new DataStorageException("Failed vacuum"))
                .when(mockDataStorageService).vacuum(eq(mockSparkSession), eq(table1));

        doThrow(new RuntimeException(
                "Failed vacuum"))
                .when(mockDataStorageService).vacuum(eq(mockSparkSession), eq(table2));

        assertThrows(MaintenanceOperationFailedException.class, () -> underTest.vacuumDeltaTables(mockSparkSession, rootPath));
        verify(mockDataStorageService).vacuum(mockSparkSession, table1);
        verify(mockDataStorageService).vacuum(mockSparkSession, table2);
        verify(mockDataStorageService).vacuum(mockSparkSession, table3);
    }

}