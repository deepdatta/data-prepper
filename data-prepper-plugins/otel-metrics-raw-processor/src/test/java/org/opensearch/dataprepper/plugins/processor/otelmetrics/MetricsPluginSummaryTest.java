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
import io.opentelemetry.proto.metrics.v1.InstrumentationLibraryMetrics;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.Summary;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;
import io.opentelemetry.proto.resource.v1.Resource;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.opensearch.dataprepper.model.configuration.PluginSetting;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.record.Record;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

@RunWith(MockitoJUnitRunner.class)
public class MetricsPluginSummaryTest {

    private OTelMetricsRawProcessor rawProcessor;

    @Before
    public void init() {
        PluginSetting testsettings = new PluginSetting("testsettings", Collections.emptyMap());
        testsettings.setPipelineName("testpipeline");
        rawProcessor = new OTelMetricsRawProcessor(testsettings);
    }

    @Test
    public void testSummaryProcessing() throws JsonProcessingException {
        SummaryDataPoint dataPoint = SummaryDataPoint.newBuilder()
                .addQuantileValues(SummaryDataPoint.ValueAtQuantile.newBuilder()
                        .setQuantile(0.5)
                        .setValue(100)
                        .build())
                .addQuantileValues(SummaryDataPoint.ValueAtQuantile.newBuilder()
                        .setQuantile(0.7)
                        .setValue(250)
                        .build())
                .build();
        Summary summary = Summary.newBuilder().addDataPoints(dataPoint).build();
        Metric metric = Metric.newBuilder()
                .setSummary(summary)
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

        ExportMetricsServiceRequest exportMetricRequest = ExportMetricsServiceRequest.newBuilder().addResourceMetrics(resourceMetrics).build();

        Record record = new Record<>(exportMetricRequest);

        List<Record<Event>> rec =  (List<Record<Event>>) rawProcessor.doExecute(Arrays.asList(record));
        Record<Event> firstRecord = rec.get(0);

        ObjectMapper objectMapper = new ObjectMapper();
        Map map = objectMapper.readValue(firstRecord.getData().toJsonString(), Map.class);
        assertSumProcessing(map);
    }

    private void assertSumProcessing(Map map) {
        assertThat(map).contains(entry("kind", org.opensearch.dataprepper.model.metric.Metric.KIND.SUMMARY.toString()));
        assertThat(map).contains(entry("unit", "seconds"));
        assertThat(map).contains(entry("description", "description"));
        assertThat(map).contains(entry("name", "name"));
        assertThat(map).contains(entry("serviceName", "service"));
        assertThat(map).contains(entry("quantileValuesCount", 2));

        List<Map<String, Object>> quantileValues = (List<Map<String, Object>>) map.get("quantiles");
        assertThat(quantileValues).hasSize(2);
        Map<String, Object> q1 = quantileValues.get(0);
        Map<String, Object> q2 = quantileValues.get(1);
        assertThat(q1).contains(entry("quantile", 0.5));
        assertThat(q1).contains(entry("value", 100.0));
        assertThat(q2).contains(entry("quantile", 0.7));
        assertThat(q2).contains(entry("value", 250.0));
    }
}
