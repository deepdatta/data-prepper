/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.processor.otelmetrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.InstrumentationLibraryMetrics;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.opensearch.dataprepper.model.configuration.PluginSetting;
import org.opensearch.dataprepper.model.metric.Bucket;
import org.opensearch.dataprepper.model.metric.DefaultBucket;
import org.opensearch.dataprepper.model.metric.Metric;
import org.opensearch.dataprepper.model.record.Record;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

@RunWith(MockitoJUnitRunner.class)
public class MetricsPluginHistogramTest {

    private OTelMetricsRawProcessor rawProcessor;

    @Before
    public void init() {
        PluginSetting testsettings = new PluginSetting("testsettings", Collections.emptyMap());
        testsettings.setPipelineName("testpipeline");
        rawProcessor = new OTelMetricsRawProcessor(testsettings);
    }

    @Test
    public void test() throws JsonProcessingException {

        final double bound_0 = 10.0;
        final double bound_1 = 100.0;
        final double bound_2 = 1000.0;
        HistogramDataPoint dp = HistogramDataPoint.newBuilder()
                .addBucketCounts(0)
                .addBucketCounts(5)
                .addBucketCounts(17)
                .addBucketCounts(33)
                .addExplicitBounds(bound_0)
                .addExplicitBounds(bound_1)
                .addExplicitBounds(bound_2)
                .setCount(4)
                .setSum(1d / 3d)
                .build();

        Histogram histogram = Histogram.newBuilder().addDataPoints(dp).build();

        List<Record<? extends Metric>> processedRecords = (List<Record<? extends Metric>>) rawProcessor.doExecute(Collections.singletonList(new Record<>(fillServiceRequest(histogram))));
        Record<? extends Metric> record = processedRecords.get(0);
        ObjectMapper objectMapper = new ObjectMapper();
        Map<Object, Object> map = objectMapper.readValue(record.getData().toJsonString(), Map.class);

        DefaultBucket bucket_0 = new DefaultBucket((double) -Float.MAX_VALUE, bound_0, 0L);
        DefaultBucket bucket_1 = new DefaultBucket(bound_0, bound_1, 5L);
        DefaultBucket bucket_2 = new DefaultBucket(bound_1, bound_2, 17L);
        DefaultBucket bucket_3 = new DefaultBucket(bound_2, (double) Float.MAX_VALUE, 33L);
        assertHistogramProcessing(map, Arrays.asList(bucket_0, bucket_1, bucket_2, bucket_3));
    }

    private ExportMetricsServiceRequest fillServiceRequest(Histogram histogram) {
        io.opentelemetry.proto.metrics.v1.Metric metric = io.opentelemetry.proto.metrics.v1.Metric.newBuilder()
                .setHistogram(histogram)
                .setUnit("seconds")
                .setName("name")
                .setDescription("description")
                .build();
        InstrumentationLibraryMetrics instLib = InstrumentationLibraryMetrics.newBuilder()
                .addMetrics(metric).build();

        Resource resource = Resource.newBuilder()
                .addAttributes(KeyValue.newBuilder()
                        .setKey("service.name")
                        .setValue(AnyValue.newBuilder().setStringValue("service").build())
                ).build();
        ResourceMetrics resourceMetrics = ResourceMetrics.newBuilder()
                .setResource(resource)
                .addInstrumentationLibraryMetrics(instLib)
                .build();
        return ExportMetricsServiceRequest.newBuilder().addResourceMetrics(resourceMetrics).build();
    }

    private void assertHistogramProcessing(Map<Object, Object> map, List<DefaultBucket> expectedBuckets) {
        assertThat(map).contains(entry("kind", Metric.KIND.HISTOGRAM.toString()));
        assertThat(map).contains(entry("unit", "seconds"));
        assertThat(map).contains(entry("description", "description"));
        assertThat(map).contains(entry("name", "name"));
        assertThat(map).contains(entry("bucketCounts", 4));
        assertThat(map).contains(entry("sum", (1d / 3d)));
        assertThat(map).contains(entry("count", 4));
        assertThat(map).contains(entry("serviceName", "service"));
        assertThat(map).contains(entry("aggregationTemporality", "AGGREGATION_TEMPORALITY_UNSPECIFIED"));

        assertThat(map).containsKey("buckets");

        List<Map> listOfMaps = (List<Map>) map.get("buckets");
        assertThat(listOfMaps).hasSize(expectedBuckets.size());

        for (int i = 0; i < expectedBuckets.size(); i++) {
            Bucket expectedBucket = expectedBuckets.get(i);
            Map<Object, Object> actualBucket = listOfMaps.get(i);

            assertThat(actualBucket)
                    .contains(entry("min", expectedBucket.getMin()))
                    .contains(entry("max", expectedBucket.getMax()))
                    .contains(entry("count", expectedBucket.getCount().intValue()));

        }
    }
}
