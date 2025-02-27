package uk.gov.justice.digital.common;

public class CommonDataFields {

    // The operation column added by DMS. See ShortOperationCode below.
    public static final String OPERATION = "Op";
    // The timestamp column added by AWS DMS.
    public static final String TIMESTAMP = "_timestamp";
    // The error column is added to the schema by the app when writing violations to give error details.
    public static final String ERROR = "error";

    /**
     * The possible entries in the operation column
     */
    public enum ShortOperationCode {
        Insert("I"),
        Update("U"),
        Delete("D");

        private final String name;

        ShortOperationCode(String name) { this.name = name; }

        public String getName() {
            return name;
        }
    }

    private CommonDataFields() {}
}
