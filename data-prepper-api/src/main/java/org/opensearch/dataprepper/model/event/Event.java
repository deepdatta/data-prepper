/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.model.event;

import java.util.List;
import java.util.Map;

/**
 * All data flowing through Data Prepper will be represented as events. An event is the base representation of data.
 * An event can be defined as a collection of key-value pairs and the following interface represents the contract with this model.
 * <p>
 * Data Prepper will be migrating away from the original use of {@link org.opensearch.dataprepper.model.record.Record}s.
 * Internal interfaces for {@link org.opensearch.dataprepper.model.processor.Processor}, {@link org.opensearch.dataprepper.model.buffer.Buffer},
 * {@link org.opensearch.dataprepper.model.sink.Sink} and {@link org.opensearch.dataprepper.model.source.Source} will be extended to support
 * the new internal model. The use of {@link org.opensearch.dataprepper.model.record.Record}s will be deprecated in 2.0.
 * <p>
 * @since 1.2
 */
public interface Event {

    /**
     * Adds or updates the key with a given value in the Event
     *
     * @param key where the value will be set
     * @param value value to set the key to
     * @since 1.2
     */
    void put(String key, Object value);

    /**
     * Retrieves the given key from the Event
     *
     * @param key the value to retrieve from
     * @param clazz the return type of the value
     * @return T a clazz object from the key
     * @since 1.2
     */
    <T> T get(String key, Class<T> clazz);

    /**
     * Retrieves the given key from the Event as a List
     *
     * @param key the value to retrieve from
     * @param clazz the return type of elements in the list
     * @return {@literal List<T>} a list of clazz elements
     * @since 1.2
     */
    <T> List<T> getList(String key, Class<T> clazz);

    /**
     * Deletes the given key from the Event
     * @param key the field to be deleted
     * @since 1.2
     */
    void delete(String key);

    /**
     * Generates a serialized Json string of the entire Event
     * @return Json string of the event
     * @since 1.2
     */
    String toJsonString();

    /**
     * Retrieves the EventMetadata
     * @return EventMetadata for the event
     * @since 1.2
     */
    EventMetadata getMetadata();

    /**
     * Checks if the key exists.
     * @param key
     * @return returns true if the key exists, otherwise false
     * @since 1.2
     */
    boolean containsKey(String key);

    /**
     * Checks if the value stored for the key is list
     * @param key
     * @return returns true if the key is a list, otherwise false
     * @since 1.2
     */
    boolean isValueAList(String key);

    /**
     * @return a Map representation of the Event
     * @since 1.3
     */
    Map<String, Object> toMap();
}
