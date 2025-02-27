package uk.gov.justice.digital.test;

import lombok.val;
import org.apache.commons.io.FileUtils;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import scala.collection.JavaConverters;
import scala.collection.Seq;
import uk.gov.justice.digital.domain.model.TableIdentifier;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class SparkTestHelpers {

    private final SparkSession spark;

    public SparkTestHelpers(SparkSession spark) {
        this.spark = spark;
    }

    public Dataset<Row> getOffenders(Path folder) {
        return loadParquetDataframe("/sample/events/nomis/offenders/offenders.parquet",
                folder.toFile().getAbsolutePath() + "offenders.parquet");

    }

    public Dataset<Row> getOffenderBookings(Path folder) {
        return loadParquetDataframe("/sample/events/nomis/offender_bookings/offender-bookings.parquet",
                folder.toFile().getAbsolutePath() + "offender-bookings.parquet");
    }

    public Dataset<Row> getInternalAgencyLocations(Path folder) {
        return loadParquetDataframe("/sample/events/nomis/internal-locations/" +
                        "sample-nomis.agency_internal_locations.parquet",
                folder.toFile().getAbsolutePath() + "sample-nomis.agency_internal_locations.parquet");
    }

    public Dataset<Row> getAgencyLocations(Path folder) {
        return loadParquetDataframe("/sample/events/nomis/agency-locations/" +
                        "sample-nomis.agency_locations.parquet",
                folder.toFile().getAbsolutePath() + "sample-nomis.agency_locations.parquet");
    }

    public void persistDataset(TableIdentifier location, Dataset<Row> df) {
        df.write()
                .format("delta")
                .mode("overwrite")
                .option("overwriteSchema", true)
                .option("path", location.toPath())
                .save();
    }

    public Dataset<Row> createIncidentDomainDataframe() {
        List<StructField> fields = new ArrayList<>(2);
        fields.add(DataTypes.createStructField("id", DataTypes.LongType, true));
        fields.add(DataTypes.createStructField("birth_date", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("living_unit_id", DataTypes.LongType, true));
        fields.add(DataTypes.createStructField("first_name", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("last_name", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("offender_no", DataTypes.StringType, true));
        StructType schema = DataTypes.createStructType(fields);
        return spark.read().schema(schema).json(
                spark.emptyDataset(Encoders.STRING()));
    }

    public Dataset<Row> createViolationsDomainDataframe() {
        List<StructField> fields = new ArrayList<>(2);
        fields.add(DataTypes.createStructField("AGE", DataTypes.LongType, true));
        fields.add(DataTypes.createStructField("AUDIT_TIMESTAMP", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("BIRTH_DATE", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("CREATE_DATE", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("CREATE_DATETIME", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("CREATE_USER_ID", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("FIRST_NAME", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("ID_SOURCE_CODE", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("LAST_NAME", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("LAST_NAME_SOUNDEX", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("MIDDLE_NAME", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("MODIFY_DATETIME", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("NAME_TYPE", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("OFFENDER_ID", DataTypes.LongType, true));
        fields.add(DataTypes.createStructField("OFFENDER_ID_DISPLAY", DataTypes.StringType, true));
        fields.add(DataTypes.createStructField("OFFENDER_NAME_SEQ", DataTypes.LongType, true));
        fields.add(DataTypes.createStructField("SEX_CODE", DataTypes.StringType, true));
        StructType schema = DataTypes.createStructType(fields);
        return spark.read().schema(schema).json(
                spark.emptyDataset(Encoders.STRING()));
    }

    public static <T> Seq<T> convertListToSeq(List<T> inputList) {
        return JavaConverters.asScalaIteratorConverter(inputList.iterator()).asScala().toSeq();
    }

    private Dataset<Row> loadParquetDataframe(final String resource, final String filename) {
        try {
            return spark.read().parquet(createFileFromResource(resource, filename).toString());
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Path createFileFromResource(final String resource, final String filename) throws IOException {
        val f = Paths.get(filename).toFile();
        FileUtils.copyInputStreamToFile(System.class.getResourceAsStream(resource), f);
        return Paths.get(f.getAbsolutePath());
    }

}
