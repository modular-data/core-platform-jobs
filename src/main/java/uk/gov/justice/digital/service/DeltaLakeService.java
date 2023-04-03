package uk.gov.justice.digital.service;

import io.delta.tables.DeltaTable;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;

public class DeltaLakeService {

    public boolean exists(final String prefix, final String schema, final String table) {
        return DeltaTable.isDeltaTable(getTablePath(prefix, schema, table));
    }

    private String getTablePath(final String prefix, final String schema, final String table) {
        return String.join("/", prefix, schema, table);
    }

    public void append(final String prefix, final String schema, final String table, final Dataset<Row> df) {
        df.write()
                .format("delta")
                .mode("append")
                .option("path", getTablePath(prefix, schema, table))
                .save();
    }

    public void replace(final String prefix, final String schema, final String table, final Dataset<Row> df) {
        df.write()
                .format("delta")
                .mode("overwrite")
                .option("overwriteSchema", true)
                .option("path", getTablePath(prefix, schema, table))
                .save();
    }

    public Dataset<Row> load(final String prefix, final String schema, final String table) {
        final DeltaTable dt = getTable(prefix, schema, table);
        return dt == null ? null : dt.toDF();
    }

    protected DeltaTable getTable(final String prefix, final String schema, final String table) {
        if(DeltaTable.isDeltaTable(getTablePath(prefix, schema, table)))
            return DeltaTable.forPath(getTablePath(prefix, schema, table));
        else
            return null;
    }

    public void endTableUpdates(final String prefix, final String schema, final String table) {
        final DeltaTable dt = getTable(prefix, schema, table);
        updateManifest(dt);
    }

    protected void updateManifest(final DeltaTable dt) {
        try {
            dt.generate("symlink_format_manifest");
        } catch(Exception e) {
            // TODO log error message
            // why are we here
        }
    }

}
