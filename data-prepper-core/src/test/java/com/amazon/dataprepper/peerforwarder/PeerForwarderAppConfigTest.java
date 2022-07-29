/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.peerforwarder;

import com.amazon.dataprepper.parser.model.DataPrepperConfiguration;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeerForwarderAppConfigTest {

    private static final PeerForwarderAppConfig peerForwarderAppConfig = new PeerForwarderAppConfig();

    @Test
    void peerForwarderConfiguration_with_non_null_DataPrepperConfiguration_should_return_PeerForwarderConfiguration() {
        final DataPrepperConfiguration dataPrepperConfiguration = mock(DataPrepperConfiguration.class);
        when(dataPrepperConfiguration.getPeerForwarderConfiguration()).thenReturn(mock(PeerForwarderConfiguration.class));

        final PeerForwarderConfiguration peerForwarderConfiguration = peerForwarderAppConfig.peerForwarderConfiguration(dataPrepperConfiguration);

        verify(dataPrepperConfiguration, times(2)).getPeerForwarderConfiguration();
        assertThat(peerForwarderConfiguration, notNullValue());
    }

    @Test
    void peerForwarderConfiguration_with_null_DataPrepperConfiguration_should_return_default_PeerForwarderConfiguration() {
        final DataPrepperConfiguration dataPrepperConfiguration = null;
        final PeerForwarderConfiguration peerForwarderConfiguration = peerForwarderAppConfig.peerForwarderConfiguration(dataPrepperConfiguration);

        assertThat(peerForwarderConfiguration, notNullValue());
    }

}