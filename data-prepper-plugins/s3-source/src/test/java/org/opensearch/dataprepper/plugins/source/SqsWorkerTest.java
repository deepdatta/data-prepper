/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.source;

import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.plugins.source.configuration.AwsAuthenticationOptions;
import org.opensearch.dataprepper.plugins.source.configuration.OnErrorOption;
import org.opensearch.dataprepper.plugins.source.configuration.SqsOptions;
import org.opensearch.dataprepper.plugins.source.filter.ObjectCreatedFilter;
import org.opensearch.dataprepper.plugins.source.filter.S3EventFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResultEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.UUID;

import static org.opensearch.dataprepper.plugins.source.SqsWorker.SQS_MESSAGES_DELETED_METRIC_NAME;
import static org.opensearch.dataprepper.plugins.source.SqsWorker.SQS_MESSAGES_FAILED_METRIC_NAME;
import static org.opensearch.dataprepper.plugins.source.SqsWorker.SQS_MESSAGES_RECEIVED_METRIC_NAME;
import static org.opensearch.dataprepper.plugins.source.SqsWorker.SQS_MESSAGE_DELAY_METRIC_NAME;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SqsWorkerTest {
    private SqsWorker sqsWorker;
    private SqsClient sqsClient;
    private S3Service s3Service;
    private S3SourceConfig s3SourceConfig;
    private S3EventFilter objectCreatedFilter;
    private PluginMetrics pluginMetrics;
    private Counter sqsMessagesReceivedCounter;
    private Counter sqsMessagesDeletedCounter;
    private Counter sqsMessagesFailedCounter;
    private Timer sqsMessageDelayTimer;

    @BeforeEach
    void setUp() {
        sqsClient = mock(SqsClient.class);
        s3Service = mock(S3Service.class);
        s3SourceConfig = mock(S3SourceConfig.class);
        objectCreatedFilter = new ObjectCreatedFilter();

        AwsAuthenticationOptions awsAuthenticationOptions = mock(AwsAuthenticationOptions.class);
        when(awsAuthenticationOptions.getAwsRegion()).thenReturn(Region.US_EAST_1);

        SqsOptions sqsOptions = mock(SqsOptions.class);
        when(sqsOptions.getSqsUrl()).thenReturn("https://sqs.us-east-2.amazonaws.com/123456789012/MyQueue");

        when(s3SourceConfig.getAwsAuthenticationOptions()).thenReturn(awsAuthenticationOptions);
        when(s3SourceConfig.getSqsOptions()).thenReturn(sqsOptions);
        when(s3SourceConfig.getOnErrorOption()).thenReturn(OnErrorOption.RETAIN_MESSAGES);

        final DeleteMessageBatchResponse deleteMessageBatchResponse = mock(DeleteMessageBatchResponse.class);
        when(deleteMessageBatchResponse.hasSuccessful()).thenReturn(true);
        when(deleteMessageBatchResponse.successful()).thenReturn(Collections.singletonList(mock(DeleteMessageBatchResultEntry.class)));
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class))).thenReturn(deleteMessageBatchResponse);

        pluginMetrics = mock(PluginMetrics.class);
        sqsMessagesReceivedCounter = mock(Counter.class);
        sqsMessagesDeletedCounter = mock(Counter.class);
        sqsMessagesFailedCounter = mock(Counter.class);
        sqsMessageDelayTimer = mock(Timer.class);
        when(pluginMetrics.counter(SQS_MESSAGES_RECEIVED_METRIC_NAME)).thenReturn(sqsMessagesReceivedCounter);
        when(pluginMetrics.counter(SQS_MESSAGES_DELETED_METRIC_NAME)).thenReturn(sqsMessagesDeletedCounter);
        when(pluginMetrics.counter(SQS_MESSAGES_FAILED_METRIC_NAME)).thenReturn(sqsMessagesFailedCounter);
        when(pluginMetrics.timer(SQS_MESSAGE_DELAY_METRIC_NAME)).thenReturn(sqsMessageDelayTimer);

        sqsWorker = new SqsWorker(sqsClient, s3Service, s3SourceConfig, pluginMetrics);
    }

    @AfterEach
    void cleanup() {
        verifyNoMoreInteractions(sqsMessagesReceivedCounter);
        verifyNoMoreInteractions(sqsMessagesDeletedCounter);
        verifyNoMoreInteractions(sqsMessagesFailedCounter);
        verifyNoMoreInteractions(sqsMessageDelayTimer);
    }

    @Test
    void processSqsMessages_should_return_number_of_messages_processed() {
        Instant startTime = Instant.now().minus(1, ChronoUnit.HOURS);
        final Message message = mock(Message.class);
        when(message.body()).thenReturn("{\"Records\":[{\"eventVersion\":\"2.1\",\"eventSource\":\"aws:s3\",\"awsRegion\":\"us-east-1\"," +
                "\"eventTime\":\"" + startTime + "\",\"eventName\":\"ObjectCreated:Put\",\"userIdentity\":{\"principalId\":\"AWS:AROAX:xxxxxx\"}," +
                "\"requestParameters\":{\"sourceIPAddress\":\"99.99.999.99\"},\"responseElements\":{\"x-amz-request-id\":\"ABCD\"," +
                "\"x-amz-id-2\":\"abcd\"},\"s3\":{\"s3SchemaVersion\":\"1.0\",\"configurationId\":\"s3SourceEventNotification\"," +
                "\"bucket\":{\"name\":\"bucketName\",\"ownerIdentity\":{\"principalId\":\"ID\"},\"arn\":\"arn:aws:s3:::bucketName\"}," +
                "\"object\":{\"key\":\"File.gz\",\"size\":72,\"eTag\":\"abcd\",\"sequencer\":\"ABCD\"}}}]}");
        final String testReceiptHandle = UUID.randomUUID().toString();
        when(message.messageId()).thenReturn(testReceiptHandle);
        when(message.receiptHandle()).thenReturn(testReceiptHandle);

        final ReceiveMessageResponse receiveMessageResponse = mock(ReceiveMessageResponse.class);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(receiveMessageResponse);
        when(receiveMessageResponse.messages()).thenReturn(Collections.singletonList(message));

        final int messagesProcessed = sqsWorker.processSqsMessages();
        final ArgumentCaptor<DeleteMessageBatchRequest> deleteMessageBatchRequestArgumentCaptor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        verify(sqsClient).deleteMessageBatch(deleteMessageBatchRequestArgumentCaptor.capture());
        final DeleteMessageBatchRequest actualDeleteMessageBatchRequest = deleteMessageBatchRequestArgumentCaptor.getValue();

        final ArgumentCaptor<Duration> durationArgumentCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(sqsMessageDelayTimer).record(durationArgumentCaptor.capture());
        Duration actualDelay = durationArgumentCaptor.getValue();

        assertThat(actualDeleteMessageBatchRequest, notNullValue());
        assertThat(actualDeleteMessageBatchRequest.entries().size(), equalTo(1));
        assertThat(actualDeleteMessageBatchRequest.queueUrl(), equalTo(s3SourceConfig.getSqsOptions().getSqsUrl()));
        assertThat(actualDeleteMessageBatchRequest.entries().get(0).id(), equalTo(message.messageId()));
        assertThat(actualDeleteMessageBatchRequest.entries().get(0).receiptHandle(), equalTo(message.receiptHandle()));
        assertThat(messagesProcessed, equalTo(1));
        verify(s3Service).addS3Object(any(S3ObjectReference.class));
        verify(sqsClient).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        verify(sqsMessagesReceivedCounter).increment(1);
        verify(sqsMessagesDeletedCounter).increment(1);
        assertThat(actualDelay, lessThanOrEqualTo(Duration.ofHours(1).plus(Duration.ofSeconds(5))));
        assertThat(actualDelay, greaterThanOrEqualTo(Duration.ofHours(1).minus(Duration.ofSeconds(5))));
    }


    @Test
    void processSqsMessages_with_object_deleted_should_return_number_of_messages_processed_without_s3service_interactions() {
        final Message message = mock(Message.class);
        when(message.body()).thenReturn("{\"Records\":[{\"eventVersion\":\"2.1\",\"eventSource\":\"aws:s3\",\"awsRegion\":\"us-east-1\"," +
                "\"eventTime\":\"2022-06-06T18:02:33.495Z\",\"eventName\":\"ObjectRemoved:Delete\",\"userIdentity\":{\"principalId\":\"AWS:AROAX:xxxxxx\"}," +
                "\"requestParameters\":{\"sourceIPAddress\":\"99.99.999.99\"},\"responseElements\":{\"x-amz-request-id\":\"ABCD\"," +
                "\"x-amz-id-2\":\"abcd\"},\"s3\":{\"s3SchemaVersion\":\"1.0\",\"configurationId\":\"s3SourceEventNotification\"," +
                "\"bucket\":{\"name\":\"bucketName\",\"ownerIdentity\":{\"principalId\":\"ID\"},\"arn\":\"arn:aws:s3:::bucketName\"}," +
                "\"object\":{\"key\":\"File.gz\",\"size\":72,\"eTag\":\"abcd\",\"sequencer\":\"ABCD\"}}}]}");

        final ReceiveMessageResponse receiveMessageResponse = mock(ReceiveMessageResponse.class);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(receiveMessageResponse);
        when(receiveMessageResponse.messages()).thenReturn(Collections.singletonList(message));

        final int messagesProcessed = sqsWorker.processSqsMessages();

        assertThat(messagesProcessed, equalTo(1));
        verifyNoInteractions(s3Service);
        verify(sqsMessagesReceivedCounter).increment(1);
        verify(sqsMessagesDeletedCounter).increment(1);
    }

    @Test
    void processSqsMessages_should_return_zero_messages_when_a_SqsException_is_thrown() {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(SqsException.class);
        final int messagesProcessed = sqsWorker.processSqsMessages();
        assertThat(messagesProcessed, equalTo(0));
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"foo\": \"bar\""})
    void processSqsMessages_should_not_interact_with_S3Service_if_input_is_not_valid_JSON(String inputString) {
        final Message message = mock(Message.class);
        when(message.body()).thenReturn(inputString);

        final ReceiveMessageResponse receiveMessageResponse = mock(ReceiveMessageResponse.class);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(receiveMessageResponse);
        when(receiveMessageResponse.messages()).thenReturn(Collections.singletonList(message));

        final int messagesProcessed = sqsWorker.processSqsMessages();
        assertThat(messagesProcessed, equalTo(1));
        verifyNoInteractions(s3Service);
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        verify(sqsMessagesReceivedCounter).increment(1);
        verify(sqsMessagesFailedCounter).increment();
    }

    @Test
    void processSqsMessages_should_not_interact_with_S3Service_and_delete_message_if_TestEvent() {
        final String messageId = UUID.randomUUID().toString();
        final String receiptHandle = UUID.randomUUID().toString();
        final Message message = mock(Message.class);
        when(message.body()).thenReturn("{\"Service\":\"Amazon S3\",\"Event\":\"s3:TestEvent\",\"Time\":\"2022-10-15T16:36:25.510Z\"," +
                "\"Bucket\":\"bucketname\",\"RequestId\":\"abcdefg\",\"HostId\":\"hijklm\"}");
        when(message.messageId()).thenReturn(messageId);
        when(message.receiptHandle()).thenReturn(receiptHandle);

        final ReceiveMessageResponse receiveMessageResponse = mock(ReceiveMessageResponse.class);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(receiveMessageResponse);
        when(receiveMessageResponse.messages()).thenReturn(Collections.singletonList(message));

        final int messagesProcessed = sqsWorker.processSqsMessages();
        assertThat(messagesProcessed, equalTo(1));
        verifyNoInteractions(s3Service);

        final ArgumentCaptor<DeleteMessageBatchRequest> deleteMessageBatchRequestArgumentCaptor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        verify(sqsClient).deleteMessageBatch(deleteMessageBatchRequestArgumentCaptor.capture());
        final DeleteMessageBatchRequest actualDeleteMessageBatchRequest = deleteMessageBatchRequestArgumentCaptor.getValue();

        assertThat(actualDeleteMessageBatchRequest, notNullValue());
        assertThat(actualDeleteMessageBatchRequest.entries().size(), equalTo(1));
        assertThat(actualDeleteMessageBatchRequest.queueUrl(), equalTo(s3SourceConfig.getSqsOptions().getSqsUrl()));
        assertThat(actualDeleteMessageBatchRequest.entries().get(0).id(), equalTo(messageId));
        assertThat(actualDeleteMessageBatchRequest.entries().get(0).receiptHandle(), equalTo(receiptHandle));
        assertThat(messagesProcessed, equalTo(1));

        verify(sqsMessagesReceivedCounter).increment(1);
        verify(sqsMessagesDeletedCounter).increment(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"foo\": \"bar\"}", "{}"})
    void processSqsMessages_should_return_zero_messages_when_messages_are_not_S3EventsNotificationRecords(String inputString) {
        final Message message = mock(Message.class);
        when(message.body()).thenReturn(inputString);

        final ReceiveMessageResponse receiveMessageResponse = mock(ReceiveMessageResponse.class);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(receiveMessageResponse);
        when(receiveMessageResponse.messages()).thenReturn(Collections.singletonList(message));

        final int messagesProcessed = sqsWorker.processSqsMessages();
        assertThat(messagesProcessed, equalTo(1));
        verifyNoInteractions(s3Service);
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        verify(sqsMessagesReceivedCounter).increment(1);
        verify(sqsMessagesFailedCounter).increment();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"foo\": \"bar\""})
    void processSqsMessages_should_invoke_delete_if_input_is_not_valid_JSON_and_delete_on_error(String inputString) {
        when(s3SourceConfig.getOnErrorOption()).thenReturn(OnErrorOption.DELETE_MESSAGES);
        final Message message = mock(Message.class);
        when(message.body()).thenReturn(inputString);

        final String testReceiptHandle = UUID.randomUUID().toString();
        when(message.messageId()).thenReturn(testReceiptHandle);
        when(message.receiptHandle()).thenReturn(testReceiptHandle);

        final ReceiveMessageResponse receiveMessageResponse = mock(ReceiveMessageResponse.class);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(receiveMessageResponse);
        when(receiveMessageResponse.messages()).thenReturn(Collections.singletonList(message));

        final int messagesProcessed = sqsWorker.processSqsMessages();
        final ArgumentCaptor<DeleteMessageBatchRequest> deleteMessageBatchRequestArgumentCaptor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        verify(sqsClient).deleteMessageBatch(deleteMessageBatchRequestArgumentCaptor.capture());
        final DeleteMessageBatchRequest actualDeleteMessageBatchRequest = deleteMessageBatchRequestArgumentCaptor.getValue();

        assertThat(actualDeleteMessageBatchRequest, notNullValue());
        assertThat(actualDeleteMessageBatchRequest.entries().size(), equalTo(1));
        assertThat(actualDeleteMessageBatchRequest.queueUrl(), equalTo(s3SourceConfig.getSqsOptions().getSqsUrl()));
        assertThat(actualDeleteMessageBatchRequest.entries().get(0).id(), equalTo(message.messageId()));
        assertThat(actualDeleteMessageBatchRequest.entries().get(0).receiptHandle(), equalTo(message.receiptHandle()));
        assertThat(messagesProcessed, equalTo(1));
        verifyNoInteractions(s3Service);
        verify(sqsClient, times(1)).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        verify(sqsMessagesReceivedCounter).increment(1);
        verify(sqsMessagesDeletedCounter).increment(1);
        verify(sqsMessagesFailedCounter).increment();
    }

    @Test
    void populateS3Reference_should_interact_with_getUrlDecodedKey() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        // Using reflection to unit test a private method as part of bug fix.
        final Method method = SqsWorker.class.getDeclaredMethod("populateS3Reference", S3EventNotification.S3EventNotificationRecord.class);
        method.setAccessible(true);

        final S3EventNotification.S3EventNotificationRecord s3EventNotificationRecord = mock(S3EventNotification.S3EventNotificationRecord.class);
        final S3EventNotification.S3Entity s3Entity = mock(S3EventNotification.S3Entity.class);
        final S3EventNotification.S3ObjectEntity s3ObjectEntity = mock(S3EventNotification.S3ObjectEntity.class);
        final S3EventNotification.S3BucketEntity s3BucketEntity = mock(S3EventNotification.S3BucketEntity.class);

        when(s3EventNotificationRecord.getS3()).thenReturn(s3Entity);
        when(s3Entity.getBucket()).thenReturn(s3BucketEntity);
        when(s3Entity.getObject()).thenReturn(s3ObjectEntity);
        when(s3BucketEntity.getName()).thenReturn("test-bucket-name");
        when(s3ObjectEntity.getUrlDecodedKey()).thenReturn("test-key");

        final S3ObjectReference s3ObjectReference = (S3ObjectReference) method.invoke(sqsWorker, s3EventNotificationRecord);

        assertThat(s3ObjectReference, notNullValue());
        assertThat(s3ObjectReference.getBucketName(), equalTo("test-bucket-name"));
        assertThat(s3ObjectReference.getKey(), equalTo("test-key"));
        verify(s3ObjectEntity).getUrlDecodedKey();
        verifyNoMoreInteractions(s3ObjectEntity);
    }
}