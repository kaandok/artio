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
package uk.co.real_logic.fix_gateway.engine.framer;

import io.aeron.Subscription;
import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import io.aeron.logbuffer.Header;
import org.agrona.ErrorHandler;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.QueuedPipe;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.fix_gateway.engine.EngineConfiguration;
import uk.co.real_logic.fix_gateway.engine.EngineDescriptorStore;
import uk.co.real_logic.fix_gateway.engine.SessionInfo;
import uk.co.real_logic.fix_gateway.engine.logger.ReplayQuery;
import uk.co.real_logic.fix_gateway.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.fix_gateway.messages.DisconnectReason;
import uk.co.real_logic.fix_gateway.messages.GatewayError;
import uk.co.real_logic.fix_gateway.messages.LogonStatus;
import uk.co.real_logic.fix_gateway.messages.SessionState;
import uk.co.real_logic.fix_gateway.protocol.GatewayPublication;
import uk.co.real_logic.fix_gateway.replication.ClusterableStreams;
import uk.co.real_logic.fix_gateway.replication.ClusterableSubscription;
import uk.co.real_logic.fix_gateway.replication.SoloSubscription;
import uk.co.real_logic.fix_gateway.session.CompositeKey;
import uk.co.real_logic.fix_gateway.session.Session;
import uk.co.real_logic.fix_gateway.session.SessionIdStrategy;
import uk.co.real_logic.fix_gateway.timing.Timer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.aeron.Publication.BACK_PRESSURED;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.ABORT;
import static io.aeron.logbuffer.ControlledFragmentHandler.Action.CONTINUE;
import static java.util.Collections.singletonList;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.fix_gateway.engine.FixEngine.ENGINE_LIBRARY_ID;
import static uk.co.real_logic.fix_gateway.library.FixLibrary.NO_MESSAGE_REPLAY;
import static uk.co.real_logic.fix_gateway.library.SessionConfiguration.AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
import static uk.co.real_logic.fix_gateway.messages.ConnectionType.INITIATOR;
import static uk.co.real_logic.fix_gateway.messages.DisconnectReason.APPLICATION_DISCONNECT;
import static uk.co.real_logic.fix_gateway.messages.GatewayError.*;
import static uk.co.real_logic.fix_gateway.messages.SequenceNumberType.TRANSIENT;
import static uk.co.real_logic.fix_gateway.messages.SessionReplyStatus.OK;
import static uk.co.real_logic.fix_gateway.messages.SessionState.ACTIVE;
import static uk.co.real_logic.fix_gateway.messages.SessionState.CONNECTED;

public class FramerTest
{
    private static final InetSocketAddress TEST_ADDRESS = new InetSocketAddress("localhost", 9998);
    private static final InetSocketAddress FRAMER_ADDRESS = new InetSocketAddress("localhost", 9999);
    private static final int LIBRARY_ID = 3;
    private static final int REPLY_TIMEOUT_IN_MS = 10;
    private static final int HEARTBEAT_INTERVAL_IN_S = 10;
    private static final long HEARTBEAT_INTERVAL_IN_MS = TimeUnit.SECONDS.toMillis(HEARTBEAT_INTERVAL_IN_S);
    private static final int CORR_ID = 1;
    private static final long POSITION = 1024;
    private static final int AERON_SESSION_ID = 234;
    private static final long SESSION_ID = 123;

    private ServerSocketChannel server;

    private SocketChannel client;
    private ByteBuffer clientBuffer = ByteBuffer.allocate(1024);

    private SenderEndPoint mockSenderEndPoint = mock(SenderEndPoint.class);
    private ReceiverEndPoint mockReceiverEndPoint = mock(ReceiverEndPoint.class);
    private EndPointFactory mockEndPointFactory = mock(EndPointFactory.class);
    private GatewayPublication inboundPublication = mock(GatewayPublication.class);
    private SessionIdStrategy mockSessionIdStrategy = mock(SessionIdStrategy.class);
    private Header header = mock(Header.class);
    private FakeEpochClock mockClock = new FakeEpochClock();
    private SequenceNumberIndexReader sentSequenceNumberIndex = mock(SequenceNumberIndexReader.class);
    private SequenceNumberIndexReader receivedSequenceNumberIndex = mock(SequenceNumberIndexReader.class);
    private ReplayQuery replayQuery = mock(ReplayQuery.class);
    private SessionIds sessionIds = mock(SessionIds.class);
    private GatewaySessions gatewaySessions = mock(GatewaySessions.class);
    private GatewaySession gatewaySession = mock(GatewaySession.class);
    private Session session = mock(Session.class);
    private SoloSubscription outboundSubscription = mock(SoloSubscription.class);
    private ClusterableStreams node = mock(ClusterableStreams.class);

    private EngineConfiguration engineConfiguration = new EngineConfiguration()
        .bindTo(FRAMER_ADDRESS.getHostName(), FRAMER_ADDRESS.getPort())
        .replyTimeoutInMs(REPLY_TIMEOUT_IN_MS);

    private Framer framer;

    private ArgumentCaptor<Long> connectionId = ArgumentCaptor.forClass(Long.class);
    private ErrorHandler errorHandler = mock(ErrorHandler.class);

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws IOException
    {
        when(mockEndPointFactory.inboundPublication()).thenReturn(inboundPublication);

        server = ServerSocketChannel.open().bind(TEST_ADDRESS);
        server.configureBlocking(false);

        clientBuffer.putInt(10, 5);

        when(mockEndPointFactory
            .receiverEndPoint(any(), connectionId.capture(), anyLong(), anyInt(), any(),
                eq(sentSequenceNumberIndex), eq(receivedSequenceNumberIndex), any(), any()))
            .thenReturn(mockReceiverEndPoint);

        when(mockEndPointFactory.senderEndPoint(any(), anyLong(), anyInt(), any()))
            .thenReturn(mockSenderEndPoint);

        when(mockReceiverEndPoint.connectionId()).then(inv -> connectionId.getValue());

        when(mockSenderEndPoint.connectionId()).then(inv -> connectionId.getValue());

        when(mockReceiverEndPoint.libraryId()).thenReturn(LIBRARY_ID);

        isLeader(true);

        framer = new Framer(
            mockClock,
            mock(Timer.class),
            mock(Timer.class),
            engineConfiguration,
            mockEndPointFactory,
            mock(ClusterableSubscription.class),
            outboundSubscription,
            mock(ClusterableSubscription.class),
            mock(Subscription.class),
            mock(QueuedPipe.class),
            mockSessionIdStrategy,
            sessionIds,
            sentSequenceNumberIndex,
            receivedSequenceNumberIndex,
            gatewaySessions,
            replayQuery,
            errorHandler,
            mock(GatewayPublication.class),
            mock(GatewayPublication.class),
            node,
            mock(EngineDescriptorStore.class),
            new LongHashSet(SessionIds.MISSING));

        when(sessionIds.onLogon(any())).thenReturn(SESSION_ID);
    }

    private void isLeader(final boolean value)
    {
        when(node.isLeader()).thenReturn(value);
    }

    @After
    public void tearDown() throws IOException
    {
        framer.onClose();
        server.close();
        if (client != null)
        {
            client.close();
        }
    }

    @Test
    public void shouldListenOnSpecifiedPort() throws IOException
    {
        aClientConnects();

        assertTrue("Client has failed to connect", client.finishConnect());
    }

    @Test
    public void shouldCreateEndPointWhenClientConnects() throws Exception
    {
        aClientConnects();

        framer.doWork();

        verifyEndpointsCreated();
    }

    @Test
    public void shouldPassDataToEndPointWhenSent() throws Exception
    {
        aClientConnects();
        framer.doWork();

        aClientSendsData();
        framer.doWork();

        verify(mockReceiverEndPoint).pollForData();
    }

    @Test
    public void shouldCloseSocketUponDisconnect() throws Exception
    {
        aClientConnects();
        framer.doWork();

        framer.onDisconnect(LIBRARY_ID, connectionId.getValue(), APPLICATION_DISCONNECT);
        framer.doWork();

        verifyEndPointsDisconnected(APPLICATION_DISCONNECT);
    }

    @Test
    public void shouldConnectToAddress() throws Exception
    {
        initiateConnection();

        assertNotNull("Sender hasn't connected to server", server.accept());
    }

    @Test
    public void shouldNotConnectIfLibraryUnknown() throws Exception
    {
        onInitiateConnection();

        framer.doWork();

        assertNull("Sender has connected to server", server.accept());
        verifyErrorPublished(UNKNOWN_LIBRARY);
    }

    @Test
    public void shouldNotifyLibraryOfInitiatedConnection() throws Exception
    {
        initiateConnection();

        notifyLibraryOfConnection();
    }

    @Test
    public void shouldReplyWithSocketConnectionError() throws Exception
    {
        server.close();

        initiateConnection();

        verifyErrorPublished(UNABLE_TO_CONNECT);
    }

    @Test
    public void shouldIdentifyDuplicateInitiatedSessions() throws Exception
    {
        initiateConnection();

        notifyLibraryOfConnection();

        when(sessionIds.onLogon(any())).thenReturn(SessionIds.DUPLICATE_SESSION);

        initiateConnection();

        verifyErrorPublished(DUPLICATE_SESSION);
    }

    @Test
    public void shouldAcquireInitiatedClientsWhenLibraryDisconnects() throws Exception
    {
        initiateConnection();

        timeoutLibrary();

        framer.doWork();

        verifySessionsAcquired(ACTIVE);
        verifyLibraryTimeout();
    }

    @Test
    public void shouldAcquireAcceptedClientsWhenLibraryDisconnects() throws Exception
    {
        aClientConnects();

        timeoutLibrary();

        framer.doWork();

        verifySessionsAcquired(CONNECTED);
        verifyLibraryTimeout();
    }

    @Test
    public void shouldRetryNotifyingLibraryOfInitiateWhenBackPressured() throws Exception
    {
        backPressureFirstSaveAttempts();

        libraryConnects();

        assertEquals(ABORT, onInitiateConnection());

        assertEquals(ABORT, onInitiateConnection());

        assertEquals(CONTINUE, onInitiateConnection());

        notifyLibraryOfConnection(times(2));
    }

    @Test
    public void shouldManageGatewaySessions() throws Exception
    {
        openSocket();

        framer.doWork();

        verifyEndpointsCreated();

        verifySessionsAcquired(CONNECTED);
    }

    @Test
    public void shouldNotifyLibraryOfAuthenticatedGatewaySessions() throws Exception
    {
        shouldManageGatewaySessions();

        givenAGatewayToManage();

        libraryConnects();

        verifyLogonSaved(times(1), LogonStatus.LIBRARY_NOTIFICATION);
    }

    @Test
    public void shouldRetryNotifyingLibraryOfAuthenticatedGatewaySessionsWhenBackPressured() throws Exception
    {
        shouldManageGatewaySessions();

        givenAGatewayToManage();

        backpressureSaveLogon();

        assertEquals(ABORT, onLibraryConnect());

        libraryConnects();

        verifyLogonSaved(times(2), LogonStatus.LIBRARY_NOTIFICATION);
    }

    @Test
    public void shouldAcquireInitiatedClientsUponReleased() throws Exception
    {
        initiateConnection();

        releaseConnection(CONTINUE);

        verifySessionsAcquired(ACTIVE);
    }

    @Test
    public void shouldRetryAcquiringInitiatedClientsUponReleasedWhenBackPressured() throws Exception
    {
        initiateConnection();

        when(inboundPublication.saveReleaseSessionReply(OK, CORR_ID))
            .thenReturn(BACK_PRESSURED, POSITION);

        releaseConnection(ABORT);

        releaseConnection(CONTINUE);

        verifySessionsAcquired(ACTIVE);
    }

    @Test
    public void shouldDisconnectConnectionsToFollowers() throws Exception
    {
        isLeader(false);

        openSocket();

        framer.doWork();

        verifyClientDisconnected();
        verify(errorHandler).onError(any(IllegalStateException.class));
    }

    @Test
    public void shouldHandoverSessionToLibraryUponRequest() throws IOException
    {
        aClientConnects();

        handoverSessionToLibrary();
    }

    @Test
    public void shouldNotifyLibraryOfControlledSessionsUponDuplicateConnect() throws IOException
    {
        aClientConnects();

        handoverSessionToLibrary();

        // Duplicate connect
        framer.onLibraryConnect(LIBRARY_ID, CORR_ID + 1, AERON_SESSION_ID);

        verifyLibraryControlNotified();
    }

    // TODO: check sessions
    // TODO: heartbeat again after timeout

    @SuppressWarnings("unchecked")
    private void verifyLibraryControlNotified()
    {
        final ArgumentCaptor<List> sessionCaptor = ArgumentCaptor.forClass(List.class);
        verify(inboundPublication).saveApplicationHeartbeat(LIBRARY_ID);
        verify(inboundPublication).saveControlNotification(eq(LIBRARY_ID), sessionCaptor.capture());

        final List<SessionInfo> sessions = sessionCaptor.getValue();
        assertThat(sessions, Matchers.contains(gatewaySession));
    }

    private void verifyClientDisconnected()
    {
        final int bytesToSend = 1;
        final ByteBuffer buffer = ByteBuffer.allocate(bytesToSend);
        while (true)
        {
            try
            {
                if (client.write(buffer) < bytesToSend)
                {
                    return;
                }
            }
            catch (IOException e)
            {
                return;
            }
        }
    }

    private void handoverSessionToLibrary()
    {
        sessionIsActive();

        assertEquals(CONTINUE, framer.onRequestSession(LIBRARY_ID, SESSION_ID, CORR_ID, NO_MESSAGE_REPLAY));

        verify(inboundPublication).saveRequestSessionReply(OK, CORR_ID);
    }

    private void sessionIsActive()
    {
        when(gatewaySessions.releaseBySessionId(SESSION_ID)).thenReturn(gatewaySession);
        when(gatewaySession.session()).thenReturn(session);
        when(session.isActive()).thenReturn(true);
    }

    private void verifyErrorPublished(final GatewayError error)
    {
        verify(inboundPublication).saveError(eq(error), eq(LIBRARY_ID), anyLong(), anyString());
    }

    private void releaseConnection(final Action expectedResult)
    {
        assertEquals(expectedResult, framer.onReleaseSession(
            LIBRARY_ID, connectionId.getValue(), CORR_ID, ACTIVE, HEARTBEAT_INTERVAL_IN_MS, 0, 0, "", "", header));
    }

    private Action onLibraryConnect()
    {
        return framer.onLibraryConnect(LIBRARY_ID, CORR_ID, AERON_SESSION_ID);
    }

    private void givenAGatewayToManage()
    {
        when(gatewaySession.connectionId()).thenReturn(connectionId.getValue());
        when(gatewaySession.sessionKey()).thenReturn(mock(CompositeKey.class));
        when(gatewaySessions.sessions()).thenReturn(singletonList(gatewaySession));
    }

    private void backPressureFirstSaveAttempts()
    {
        when(inboundPublication.saveManageConnection(
            anyLong(),
            anyLong(),
            anyString(),
            eq(LIBRARY_ID),
            eq(INITIATOR),
            anyInt(),
            anyInt(),
            any(),
            anyInt(),
            anyLong()))
            .thenReturn(BACK_PRESSURED, POSITION);
        backpressureSaveLogon();
    }

    private void backpressureSaveLogon()
    {
        when(inboundPublication.saveLogon(
            eq(LIBRARY_ID), anyLong(), anyLong(),
            anyInt(), anyInt(),
            any(), any(), any(), any(),
            any(), any(), any()))
            .thenReturn(BACK_PRESSURED, POSITION);
    }

    private void verifySessionsAcquired(final SessionState state)
    {
        verify(gatewaySessions, times(1)).acquire(
            any(),
            eq(state),
            eq(HEARTBEAT_INTERVAL_IN_S),
            anyInt(),
            anyInt(),
            any(),
            any()
        );
    }

    private void verifyEndPointsDisconnected(final DisconnectReason reason)
    {
        verify(mockReceiverEndPoint).close(reason);
        verify(mockSenderEndPoint).close();
    }

    private void timeoutLibrary()
    {
        mockClock.advanceMilliSeconds(REPLY_TIMEOUT_IN_MS * 2);
    }

    private void libraryConnects()
    {
        assertEquals(Action.CONTINUE, onLibraryConnect());
    }

    private void initiateConnection() throws Exception
    {
        libraryConnects();

        assertEquals(CONTINUE, onInitiateConnection());
    }

    private Action onInitiateConnection()
    {
        return framer.onInitiateConnection(
            LIBRARY_ID, TEST_ADDRESS.getPort(), TEST_ADDRESS.getHostName(), "LEH_LZJ02", null, null, "CCG",
            TRANSIENT, AUTOMATIC_INITIAL_SEQUENCE_NUMBER, "", "", HEARTBEAT_INTERVAL_IN_S, CORR_ID, header);
    }

    private void aClientConnects() throws IOException
    {
        libraryConnects();

        openSocket();
    }

    private void openSocket() throws IOException
    {
        client = SocketChannel.open(FRAMER_ADDRESS);
    }

    private void notifyLibraryOfConnection()
    {
        notifyLibraryOfConnection(times(1));
    }

    private void notifyLibraryOfConnection(final VerificationMode times)
    {
        verify(inboundPublication, times).saveManageConnection(
            eq(connectionId.getValue()),
            anyLong(),
            anyString(),
            eq(LIBRARY_ID),
            eq(INITIATOR),
            anyInt(),
            anyInt(),
            any(),
            anyInt(),
            anyLong());
        verifyLogonSaved(times, LogonStatus.NEW);
    }

    private void verifyLogonSaved(final VerificationMode times, final LogonStatus status)
    {
        verify(inboundPublication, times).saveLogon(
            eq(LIBRARY_ID), eq(connectionId.getValue()), anyLong(),
            anyInt(), anyInt(),
            any(), any(), any(), any(),
            any(), any(), eq(status));
    }

    private void aClientSendsData() throws IOException
    {
        clientBuffer.position(0);
        assertEquals("Has written bytes", clientBuffer.remaining(), client.write(clientBuffer));
    }

    private void verifyEndpointsCreated() throws IOException
    {
        verify(mockEndPointFactory).receiverEndPoint(
            notNull(TcpChannel.class), anyLong(), anyLong(), eq(ENGINE_LIBRARY_ID), eq(framer),
            eq(sentSequenceNumberIndex), eq(receivedSequenceNumberIndex), any(), any());

        verify(mockEndPointFactory).senderEndPoint(
            notNull(TcpChannel.class), anyLong(), eq(ENGINE_LIBRARY_ID), eq(framer));
    }

    private void verifyLibraryTimeout()
    {
        verify(inboundPublication).saveLibraryTimeout(LIBRARY_ID, 0);
    }
}
