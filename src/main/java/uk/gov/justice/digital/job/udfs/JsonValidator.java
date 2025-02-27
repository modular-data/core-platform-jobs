package uk.gov.justice.digital.job.udfs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Maps;
import lombok.val;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.api.java.UDF2;
import org.apache.spark.sql.expressions.UserDefinedFunction;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.justice.digital.job.filter.NomisDataFilter;

import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.spark.sql.functions.udf;

/**
 * JSON validator that is intended to be invoked *after* from_json is used to parse a field containing a JSON string.
 * <p>>
 * Data is filtered prior to validation to ensure that the raw data matches the expected spark representation so that
 * validation passes.
 * <p>
 * By design, from_json will forcibly enable nullable on all fields declared in the schema. This resolves potential
 * downstream issues with parquet but makes it harder for us to validate JSON principally because from_json
 * o silently allows fields declared notNull to be null
 * o silently converts fields with incompatible values to null
 * <p>
 * For this reason this validator *must* be used *after* first parsing a JSON string with from_json.
 * <p>
 * This validator performs the following checks
 * o original and parsed json *must* be equal - if there is a difference this indicates a bad value in the source data
 * e.g. a String value when a Numeric value was expected
 * o all fields declared notNull *must* have a value
 * o handling of dates represented in the incoming raw data as an ISO 8601 datetime string with the time values all
 * set to zero
 * <p>
 * and can be used as follows within Spark SQL
 * <p>
 * StructType schema = .... // Some schema defining the format of the JSON being processed
 * <p>
 * UserDefinedFunction jsonValidator = JsonValidator.createAndRegister(
 * schema,
 * someDataFrame.sparkSession(),
 * sourceName,
 * tableName
 * );
 * <p>
 * // dataframe with a single string column 'rawJson' containing JSON to parse and validate against a schema
 * someDataFrame
 * .withColumn("parsedJson", from_json(col("rawJson"), schema))
 * .withColumn("valid", jsonValidator.apply(col("rawData"), to_json("parsedJson")))
 * <p>
 * The dataframe can then be filtered on the value of the boolean valid column where
 * o true -> the JSON has passed validation
 * o false -> the JSON has not passed validation
 */
public class JsonValidator implements Serializable {

    private static final long serialVersionUID = 3626262733334508950L;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Logger logger = LoggerFactory.getLogger(JsonValidator.class);

    public static UserDefinedFunction createAndRegister(
            StructType schema,
            SparkSession sparkSession,
            String schemaName,
            String tableName) {

        return sparkSession
                .udf()
                .register(
                        String.format("udfValidatorFor%s%s", schemaName, tableName),
                        udf((UDF2<String, String, String>) (String originalJson, String parsedJson) ->
                                        validate(originalJson, parsedJson, schema),
                                DataTypes.StringType
                        )
                ).asNondeterministic();
    }

    public static String validate(
            String originalJson,
            String parsedJson,
            StructType schema
    ) throws JsonProcessingException {

        // null content is still valid
        if (originalJson == null || parsedJson == null) return "Json data was parsed as null";

        TypeReference<Map<String, Object>> mapTypeReference = new TypeReference<Map<String, Object>>() {};

        val originalData = objectMapper.readValue(originalJson, mapTypeReference);
        val parsedDataWithNullColumnsDropped = removeNullValues(objectMapper.readValue(parsedJson, mapTypeReference));

        val nomisFilter = new NomisDataFilter(schema);

        // Apply data filters. See NomisDataFilter.
        val filteredDataWithNullColumnsDropped = removeNullValues(nomisFilter.apply(originalData));

        // Check that
        //  o the original and parsed data match using a simple equality check
        //  o any fields declared not-nullable have a value
        val result = filteredDataWithNullColumnsDropped.equals(parsedDataWithNullColumnsDropped) &&
                allNotNullFieldsHaveValues(schema, objectMapper.readTree(originalJson));

        logger.debug("JSON validation result - json valid: {}", result);

        if (!result) {
            // We treat null fields the same as missing fields
            val difference = Maps.difference(filteredDataWithNullColumnsDropped, parsedDataWithNullColumnsDropped);

            val errorMessage = String.format("JSON validation failed. Parsed and Raw JSON have the following differences: %s", difference);
            logger.error(errorMessage);
            return errorMessage;
        } else return "";

    }

    private static boolean allNotNullFieldsHaveValues(StructType schema, JsonNode json) {
        for (StructField structField : schema.fields()) {
            // Skip fields that are declared nullable in the table schema.
            if (structField.nullable()) continue;

            val jsonField = Optional.ofNullable(json.get(structField.name()));

            val jsonFieldIsNull = jsonField
                    .map(JsonNode::isNull)  // Field present in JSON but with no value
                    .orElse(true);          // If no field found then it's null by default.

            // We fail immediately if the field is null since it's declared as not-nullable.
            if (jsonFieldIsNull) {
                logger.error("JSON validation failed. Not null field {} is null", structField);
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> removeNullValues(Map<String, Object> map) {
        return map
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

}
