/*
 * Copyright 2015-2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.system_tests;

import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver;
import org.agrona.CloseHelper;
import org.agrona.concurrent.NoOpIdleStrategy;
import uk.co.real_logic.fix_gateway.engine.EngineConfiguration;
import uk.co.real_logic.fix_gateway.engine.FixEngine;
import uk.co.real_logic.fix_gateway.engine.SessionInfo;
import uk.co.real_logic.fix_gateway.replication.FrameDropper;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static uk.co.real_logic.fix_gateway.TestFixtures.*;
import static uk.co.real_logic.fix_gateway.system_tests.SystemTestUtil.*;

public class FixEngineRunner
{
    private static final String CLUSTER_AERON_CHANNEL = clusteredAeronChannel();

    private final int tcpPort;
    private final int libraryPort;
    private final String libraryChannel;
    private final MediaDriver mediaDriver;
    private final FixEngine engine;

    private final DebugTcpChannelSupplier tcpChannelSupplier;
    private final FrameDropper frameDropper;
    private final int nodeId;

    public FixEngineRunner(final int ourId, final IntStream ids)
    {
        nodeId = ourId;
        tcpPort = unusedPort();
        libraryPort = unusedPort();
        libraryChannel = "aeron:udp?endpoint=224.0.1.1:" + libraryPort;
        frameDropper = new FrameDropper(ourId);

        final MediaDriver.Context context =
            mediaDriverContext(TERM_BUFFER_LENGTH)
                .aeronDirectoryName(aeronDirName(ourId))
                .termBufferSparseFile(true)
                .publicationUnblockTimeoutNs(TimeUnit.SECONDS.toNanos(100))
                .receiveChannelEndpointSupplier(frameDropper.newReceiveChannelEndpointSupplier())
                .sendChannelEndpointSupplier(frameDropper.newSendChannelEndpointSupplier());

        mediaDriver = MediaDriver.launch(context);

        final String acceptorLogs = ACCEPTOR_LOGS + ourId;
        delete(acceptorLogs);
        final EngineConfiguration configuration = new EngineConfiguration();

        setupAuthentication(ACCEPTOR_ID, INITIATOR_ID, configuration);

        configuration
            .bindTo("localhost", tcpPort)
            .libraryAeronChannel(libraryChannel)
            .monitoringFile(acceptorMonitoringFile("engineCounters" + ourId))
            .logFileDir(acceptorLogs)
            .clusterAeronChannel(CLUSTER_AERON_CHANNEL)
            .nodeId((short) ourId)
            .addOtherNodes(ids.filter(id -> id != ourId).toArray());

        tcpChannelSupplier = new DebugTcpChannelSupplier(configuration);
        configuration.channelSupplierFactory(config -> tcpChannelSupplier);

        configuration.aeronContext().aeronDirectoryName(aeronDirName(ourId));

        engine = FixEngine.launch(configuration);
    }

    private String aeronDirName(final int id)
    {
        return CommonContext.AERON_DIR_PROP_DEFAULT + id;
    }

    public String libraryChannel()
    {
        return libraryChannel;
    }

    public int tcpPort()
    {
        return tcpPort;
    }

    public EngineConfiguration configuration()
    {
        return engine.configuration();
    }

    public void disable()
    {
        tcpChannelSupplier.disable();
        frameDropper.dropFrames(true);
    }

    public void enable()
    {
        tcpChannelSupplier.enable();
        frameDropper.dropFrames(false);
    }

    public boolean isLeader()
    {
        return engine.isLeader();
    }

    public void close()
    {
        CloseHelper.close(engine);
        CloseHelper.close(mediaDriver);
    }

    public int nodeId()
    {
        return nodeId;
    }

    public List<SessionInfo> sessions()
    {
        return engine.gatewaySessions(new NoOpIdleStrategy());
    }
}