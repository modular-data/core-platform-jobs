package uk.gov.justice.digital.config;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.CommandLinePropertySource;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.context.env.PropertySource;
import lombok.val;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.justice.digital.client.glue.JobClient;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@Singleton
public class JobParameters {

    private static final Logger logger = LoggerFactory.getLogger(JobParameters.class);

    private final Map<String, String> config;

    @Inject
    public JobParameters(ApplicationContext context) {
        this(getCommandLineArgumentsFromContext(context));
    }

    public JobParameters(Map<String, String> config) {
        this.config = config.entrySet()
                .stream()
                .map(this::cleanEntryKey)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        logger.info("Job initialised with parameters: {}", config);
    }

    public String getAwsRegion() {
        return getMandatoryProperty("dpr.aws.region");
    }

    public String getAwsKinesisEndpointUrl() {
        return getMandatoryProperty("dpr.aws.kinesis.endpointUrl");
    }

    public String getAwsDynamoDBEndpointUrl() {
        return getMandatoryProperty("dpr.aws.dynamodb.endpointUrl");
    }

    public String getKinesisReaderStreamName() {
        return getMandatoryProperty("dpr.kinesis.reader.streamName");
    }

    public Duration getKinesisReaderBatchDuration() {
        String durationSeconds = getMandatoryProperty("dpr.kinesis.reader.batchDurationSeconds");
        long parsedDuration = Long.parseLong(durationSeconds);
        return Durations.seconds(parsedDuration);
    }

    public Optional<String> getRawS3Path() {
        return getOptionalProperty("dpr.raw.s3.path");
    }

    public Optional<String> getStructuredS3Path() {
        return getOptionalProperty("dpr.structured.s3.path");
    }

    public Optional<String> getViolationsS3Path() {
        return getOptionalProperty("dpr.violations.s3.path");
    }

    public String getCuratedS3Path() {
        return getMandatoryProperty("dpr.curated.s3.path");
    }

    public String getDomainTargetPath() {
        return getMandatoryProperty("dpr.domain.target.path");
    }

    public String getDomainName() {
        return getMandatoryProperty("dpr.domain.name");
    }

    public String getDomainTableName() {
        return getMandatoryProperty("dpr.domain.table.name");
    }

    public String getDomainRegistry() {
        return getMandatoryProperty("dpr.domain.registry");
    }

    public String getDomainOperation() {
        return getMandatoryProperty("dpr.domain.operation");
    }

    public Optional<String> getCatalogDatabase() {
        return getOptionalProperty("dpr.domain.catalog.db");
    }

    private String getMandatoryProperty(String jobParameter) {
        return Optional
                .ofNullable(config.get(jobParameter))
                .orElseThrow(() -> new IllegalStateException("Job Parameter: " + jobParameter + " is not set"));
    }

    // TODO - consider supporting a default value where if no value is provided we throw an exception if there is no
    //        value at all
    private Optional<String> getOptionalProperty(String jobParameter) {
        return Optional
                .ofNullable(config.get(jobParameter));
    }

    // We expect job parameters to be specified with a leading -- prefix e.g. --some.job.setting consistent with how
    // AWS glue specifies job parameters. The prefix is removed to clean up code handling parameters by name.
    private Map.Entry<String, String> cleanEntryKey(Map.Entry<String, String> entry) {
        // TODO - check this - we may not need this
        val cleanedKey = entry.getKey().replaceFirst("--", "");
        return new SimpleEntry<>(cleanedKey, entry.getValue());
    }

    private static Map<String, String> getCommandLineArgumentsFromContext(ApplicationContext context) {
        return context.getEnvironment()
                .getPropertySources()
                .stream()
                .filter(JobParameters::isCommandLinePropertySource)
                .findFirst()
                .flatMap(JobParameters::castToCommandLinePropertySource)
                .map(MapPropertySource::asMap)
                .map(JobParameters::convertArgumentValuesToString)
                .orElseGet(Collections::emptyMap);
    }

    private static boolean isCommandLinePropertySource(PropertySource p) {
        return p.getName().equals(CommandLinePropertySource.NAME);
    }

    private static Optional<CommandLinePropertySource> castToCommandLinePropertySource(PropertySource p) {
        return (p instanceof CommandLinePropertySource)
                ? Optional.of((CommandLinePropertySource) p)
                : Optional.empty();
    }

    private static Map<String, String> convertArgumentValuesToString(Map<String, Object> m) {
        return m.entrySet()
                .stream()
                .map(e -> new SimpleEntry<>(e.getKey(), e.getValue().toString()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

}
