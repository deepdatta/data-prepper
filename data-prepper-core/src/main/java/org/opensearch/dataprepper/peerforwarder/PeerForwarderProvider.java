/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.peerforwarder;

import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.peerforwarder.client.PeerForwarderClient;
import org.opensearch.dataprepper.peerforwarder.discovery.DiscoveryMode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class PeerForwarderProvider {

    private final PeerForwarderClientFactory peerForwarderClientFactory;
    private final PeerForwarderClient peerForwarderClient;
    private final PeerForwarderConfiguration peerForwarderConfiguration;
    private final PluginMetrics pluginMetrics;
    private final Map<String, Map<String, PeerForwarderReceiveBuffer<Record<Event>>>> pipelinePeerForwarderReceiveBufferMap = new HashMap<>();
    private HashRing hashRing;

    PeerForwarderProvider(final PeerForwarderClientFactory peerForwarderClientFactory,
                          final PeerForwarderClient peerForwarderClient,
                          final PeerForwarderConfiguration peerForwarderConfiguration,
                          final PluginMetrics pluginMetrics) {
        this.peerForwarderClientFactory = peerForwarderClientFactory;
        this.peerForwarderClient = peerForwarderClient;
        this.peerForwarderConfiguration = peerForwarderConfiguration;
        this.pluginMetrics = pluginMetrics;
    }

    public PeerForwarder register(final String pipelineName, final String pluginId, final Set<String> identificationKeys) {
        if (pipelinePeerForwarderReceiveBufferMap.containsKey(pipelineName) &&
                pipelinePeerForwarderReceiveBufferMap.get(pipelineName).containsKey(pluginId)) {
            throw new RuntimeException("Data Prepper 2.0 will only support a single peer-forwarder per pipeline/plugin type");
        }

        final PeerForwarderReceiveBuffer<Record<Event>> peerForwarderReceiveBuffer = createBufferPerPipelineProcessor(pipelineName, pluginId);

        if (isPeerForwardingRequired()) {
            if (hashRing == null) {
                hashRing = peerForwarderClientFactory.createHashRing();
            }
            return new RemotePeerForwarder(
                    peerForwarderClient, hashRing, peerForwarderReceiveBuffer, pipelineName, pluginId, identificationKeys, pluginMetrics
            );
        }
        else {
            return new LocalPeerForwarder();
        }
    }

    private PeerForwarderReceiveBuffer<Record<Event>> createBufferPerPipelineProcessor(final String pipelineName, final String pluginId) {
        final PeerForwarderReceiveBuffer<Record<Event>> peerForwarderReceiveBuffer = new
                PeerForwarderReceiveBuffer<>(peerForwarderConfiguration.getBufferSize(), peerForwarderConfiguration.getBatchSize());

        final Map<String, PeerForwarderReceiveBuffer<Record<Event>>> pluginsBufferMap =
                pipelinePeerForwarderReceiveBufferMap.computeIfAbsent(pipelineName, k -> new HashMap<>());

        pluginsBufferMap.put(pluginId, peerForwarderReceiveBuffer);

        return peerForwarderReceiveBuffer;
    }

    public boolean isPeerForwardingRequired() {
        return arePeersConfigured() && pipelinePeerForwarderReceiveBufferMap.size() > 0;
    }

    private boolean arePeersConfigured() {
        final DiscoveryMode discoveryMode = peerForwarderConfiguration.getDiscoveryMode();
        if (discoveryMode.equals(DiscoveryMode.LOCAL_NODE)) {
            return false;
        }
        else if (discoveryMode.equals(DiscoveryMode.STATIC) && peerForwarderConfiguration.getStaticEndpoints().size() <= 1) {
            return false;
        }
        return true;
    }

    public Map<String, Map<String, PeerForwarderReceiveBuffer<Record<Event>>>> getPipelinePeerForwarderReceiveBufferMap() {
        return pipelinePeerForwarderReceiveBufferMap;
    }
}
