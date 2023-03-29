package uk.gov.justice.digital.config;

import lombok.val;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.Durations;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JobParametersTest {

    private static final String AWS_REGION_KEY = "dpr.aws.region";
    private static final String AWS_KINESIS_ENDPOINT_URL_KEY = "dpr.aws.kinesis.endpointUrl";
    private static final String KINESIS_READER_STREAM_NAME_KEY = "dpr.kinesis.reader.streamName";
    private static final String KINESIS_READER_BATCH_DURATION_SECONDS_KEY = "dpr.kinesis.reader.batchDurationSeconds";
    private static final String RAW_S3_PATH_KEY = "dpr.raw.s3.path";
    private static final String STRUCTURED_S3_PATH_KEY = "dpr.structured.s3.path";
    private static final String VIOLATIONS_S3_PATH_KEY = "dpr.violations.s3.path";
    private static final String CURATED_S3_PATH_KEY = "dpr.curated.s3.path";

    private static final String AWS_REGION = "test-region";
    private static final String AWS_KINESIS_ENDPOINT_URL = "https://kinesis.example.com";
    private static final String KINESIS_READER_STREAM_NAME = "some-kinesis-stream";
    private static final String KINESIS_READER_BATCH_DURATION_SECONDS = "5";
    private static final String RAW_S3_PATH = "s3://somepath/raw";
    private static final String STRUCTURED_S3_PATH = "s3://somepath/structured";
    private static final String VIOLATIONS_S3_PATH = "s3://somepath/violations";
    private static final String CURATED_S3_PATH = "s3://somepath/curated";

    private static final Map<String, String> testConfig;

    static {
        testConfig = new HashMap<>();
        testConfig.put(AWS_REGION_KEY, AWS_REGION);
        testConfig.put(AWS_KINESIS_ENDPOINT_URL_KEY, AWS_KINESIS_ENDPOINT_URL);
        testConfig.put(KINESIS_READER_STREAM_NAME_KEY, KINESIS_READER_STREAM_NAME);
        testConfig.put(KINESIS_READER_BATCH_DURATION_SECONDS_KEY, KINESIS_READER_BATCH_DURATION_SECONDS);
        testConfig.put(RAW_S3_PATH_KEY, RAW_S3_PATH);
        testConfig.put(STRUCTURED_S3_PATH_KEY, STRUCTURED_S3_PATH);
        testConfig.put(VIOLATIONS_S3_PATH_KEY, VIOLATIONS_S3_PATH);
        testConfig.put(CURATED_S3_PATH_KEY, CURATED_S3_PATH);
    }

    private static final JobParameters validJobParameters = new JobParameters(testConfig);
    private static final JobParameters emptyJobParameters = new JobParameters(Collections.emptyMap());

    @Test
    public void shouldRemoveLeadingHyphensFromParameterNames() {
        val jobParameters = new JobParameters(
            Collections.singletonMap("--" + AWS_REGION_KEY, AWS_REGION)
        );
        assertEquals(AWS_REGION, jobParameters.getAwsRegion());
    }

    @Test
    public void shouldReturnAwsRegionWhenSet() {
        assertEquals(AWS_REGION, validJobParameters.getAwsRegion());
    }

    @Test
    public void shouldThrowExceptionWhenAwsRegionNotSet() {
        assertThrows(IllegalStateException.class, emptyJobParameters::getAwsRegion);
    }

    @Test
    public void shouldReturnAwsKinesisEndpointUrlWhenSet() {
        assertEquals(AWS_KINESIS_ENDPOINT_URL, validJobParameters.getAwsKinesisEndpointUrl());
    }

    @Test
    public void shouldThrowExceptionWhenKinesisEndpointUrlNotSet() {
        assertThrows(IllegalStateException.class, emptyJobParameters::getAwsKinesisEndpointUrl);
    }

    @Test
    public void shouldReturnKinesisReaderStreamNameWhenSet() {
        assertEquals(KINESIS_READER_STREAM_NAME, validJobParameters.getKinesisReaderStreamName());
    }

    @Test
    public void shouldThrowExceptionWhenKinesisReaderStreamNameNotSet() {
        assertThrows(IllegalStateException.class, emptyJobParameters::getKinesisReaderStreamName);
    }

    @Test
    public void shouldReturnKinesisReaderBatchDurationWhenSet() {
        Duration expectedDuration = Durations.seconds(Long.parseLong(KINESIS_READER_BATCH_DURATION_SECONDS));
        assertEquals(expectedDuration, validJobParameters.getKinesisReaderBatchDuration());
    }

    @Test
    public void shouldThrowExceptionWhenKinesisReaderBatchDurationNotSet() {
        assertThrows(IllegalStateException.class, emptyJobParameters::getKinesisReaderBatchDuration);
    }

    @Test
    public void shouldThrowExceptionWhenKinesisReaderBatchDurationInvalid() {
        JobParameters jobParameters = new JobParameters(
            Collections.singletonMap(KINESIS_READER_BATCH_DURATION_SECONDS_KEY, "this is not a number")
        );
        assertThrows(NumberFormatException.class, jobParameters::getKinesisReaderBatchDuration);
    }

    @Test
    public void shouldReturnOptionalWithRawPathWhenSet() {
        assertEquals(Optional.of(RAW_S3_PATH), validJobParameters.getRawS3Path());
    }

    @Test
    public void shouldReturnEmptyOptionalWhenRawPathNotSet() {
        assertEquals(Optional.empty(), emptyJobParameters.getRawS3Path());
    }

    @Test
    public void shouldReturnOptionalWithStructuredPathWhenSet() {
        assertEquals(Optional.of(STRUCTURED_S3_PATH), validJobParameters.getStructuredS3Path());
    }

    @Test
    public void shouldReturnEmptyOptionalWhenStructuredPathNotSet() {
        assertEquals(Optional.empty(), emptyJobParameters.getStructuredS3Path());
    }

    @Test
    public void shouldReturnOptionalWithViolationsPathWhenSet() {
        assertEquals(Optional.of(VIOLATIONS_S3_PATH), validJobParameters.getViolationsS3Path());
    }

    @Test
    public void shouldReturnEmptyOptionalWhenViolationsPathNotSet() {
        assertEquals(Optional.empty(), emptyJobParameters.getViolationsS3Path());
    }

    @Test
    public void shouldReturnOptionalWithCuratedPathWhenSet() {
        assertEquals(Optional.of(CURATED_S3_PATH), validJobParameters.getCuratedS3Path());
    }
}