/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.sink.opensearch.index;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.util.StringUtils;
import org.opensearch.action.admin.indices.alias.Alias;
import org.opensearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;

import javax.ws.rs.HttpMethod;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

class IsmPolicyManagement implements IsmPolicyManagementStrategy {

    // TODO: replace with new _opensearch API
    private static final String POLICY_MANAGEMENT_ENDPOINT = "/_opendistro/_ism/policies/";
    public static final String DEFAULT_INDEX_SUFFIX = "-000001";
    private static final String POLICY_FILE_ROOT_KEY = "policy";
    private static final String POLICY_FILE_ISM_TEMPLATE_KEY = "ism_template";

    private final RestHighLevelClient restHighLevelClient;
    private final String policyName;
    private final String policyFile;
    private final String policyFileWithoutIsmTemplate;

    public IsmPolicyManagement(final RestHighLevelClient restHighLevelClient,
                               final String policyName,
                               final String policyFile,
                               final String policyFileWithoutIsmTemplate) {
        checkNotNull(restHighLevelClient);
        checkArgument(StringUtils.isNotEmpty(policyName));
        checkArgument(StringUtils.isNotEmpty(policyFile));
        checkArgument(StringUtils.isNotEmpty(policyFileWithoutIsmTemplate));
        this.restHighLevelClient = restHighLevelClient;
        this.policyName = policyName;
        this.policyFile = policyFile;
        this.policyFileWithoutIsmTemplate = policyFileWithoutIsmTemplate;
    }

    public IsmPolicyManagement(final RestHighLevelClient restHighLevelClient,
                               final String policyName,
                               final String policyFile) {
        checkNotNull(restHighLevelClient);
        checkArgument(StringUtils.isNotEmpty(policyName));
        checkArgument(StringUtils.isNotEmpty(policyFile));
        this.restHighLevelClient = restHighLevelClient;
        this.policyName = policyName;
        this.policyFile = policyFile;
        this.policyFileWithoutIsmTemplate = null;
    }

    @Override
    public Optional<String> checkAndCreatePolicy() throws IOException {
        final String policyManagementEndpoint = POLICY_MANAGEMENT_ENDPOINT + policyName;

        String policyJsonString = retrievePolicyJsonString(policyFile);
        Request request = createPolicyRequestFromFile(policyManagementEndpoint, policyJsonString);

        try {
            restHighLevelClient.getLowLevelClient().performRequest(request);
        } catch (ResponseException e1) {
            final String msg = e1.getMessage();
            if (msg.contains("Invalid field: [ism_template]")) {

                if(StringUtils.isEmpty(policyFileWithoutIsmTemplate)) {
                    policyJsonString = dropIsmTemplateFromPolicy(policyJsonString);
                } else {
                    policyJsonString = retrievePolicyJsonString(policyFileWithoutIsmTemplate);
                }

                request = createPolicyRequestFromFile(policyManagementEndpoint, policyJsonString);
                try {
                    restHighLevelClient.getLowLevelClient().performRequest(request);
                } catch (ResponseException e2) {
                    if (e2.getMessage().contains("version_conflict_engine_exception")
                            || e2.getMessage().contains("resource_already_exists_exception")) {
                        // Do nothing - likely caused by
                        // (1) a race condition where the resource was created by another host before this host's
                        // restClient made its request;
                        // (2) policy already exists in the cluster
                    } else {
                        throw e2;
                    }
                }
                return Optional.of(policyName);
            } else if (e1.getMessage().contains("version_conflict_engine_exception")
                    || e1.getMessage().contains("resource_already_exists_exception")) {
                // Do nothing - likely caused by
                // (1) a race condition where the resource was created by another host before this host's
                // restClient made its request;
                // (2) policy already exists in the cluster
            } else {
                throw e1;
            }
        }

        return Optional.empty();
    }

    @Override
    public List<String> getIndexPatterns(final String indexAlias){
        checkArgument(StringUtils.isNotEmpty(indexAlias));
        return  Collections.singletonList(indexAlias + "-*");
    }

    @Override
    public boolean checkIfIndexExistsOnServer(final String indexAlias) throws IOException {
        checkArgument(StringUtils.isNotEmpty(indexAlias));
        return restHighLevelClient.indices().existsAlias(new GetAliasesRequest().aliases(indexAlias), RequestOptions.DEFAULT);
    }

    @Override
    public CreateIndexRequest getCreateIndexRequest(final String indexAlias) {
        checkArgument(StringUtils.isNotEmpty(indexAlias));
        final String initialIndexName = indexAlias + DEFAULT_INDEX_SUFFIX;
        final CreateIndexRequest createIndexRequest = new CreateIndexRequest(initialIndexName);
        createIndexRequest.alias(new Alias(indexAlias).writeIndex(true));
        return createIndexRequest;
    }

    private String retrievePolicyJsonString(final String fileName) throws IOException {
        final File file = new File(fileName);
        final URL policyFileUrl;
        if (file.isAbsolute()) {
            policyFileUrl = file.toURI().toURL();
        } else {
            policyFileUrl = getClass().getClassLoader().getResource(fileName);
        }
        final StringBuilder policyJsonBuffer = new StringBuilder();
        try (final InputStream inputStream = policyFileUrl.openStream();
             final BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(inputStream)))) {
            reader.lines().forEach(line -> policyJsonBuffer.append(line).append("\n"));
        }
        return policyJsonBuffer.toString();
    }

    private Request createPolicyRequestFromFile(final String endPoint, final String policyJsonString) throws IOException {
        final Request request = new Request(HttpMethod.PUT, endPoint);
        request.setJsonEntity(policyJsonString);
        return request;
    }

    private String dropIsmTemplateFromPolicy(final String policyJsonString) throws JsonProcessingException {
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode jsonNode = mapper.readTree(policyJsonString);
        ((ObjectNode)jsonNode.get(POLICY_FILE_ROOT_KEY)).remove(POLICY_FILE_ISM_TEMPLATE_KEY);
        return jsonNode.toString();
    }

}
