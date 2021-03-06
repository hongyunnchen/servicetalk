/*
 * Copyright © 2018-2019 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.buffer.api.Buffer;
import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.client.api.MaxRequestLimitExceededException;
import io.servicetalk.concurrent.CompletableSource.Processor;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.concurrent.api.TestPublisher;
import io.servicetalk.concurrent.internal.ServiceTalkTestTimeout;
import io.servicetalk.http.api.DefaultHttpHeadersFactory;
import io.servicetalk.http.api.DefaultStreamingHttpRequestResponseFactory;
import io.servicetalk.http.api.HttpClient;
import io.servicetalk.http.api.HttpConnection;
import io.servicetalk.http.api.HttpExecutionContext;
import io.servicetalk.http.api.HttpExecutionStrategy;
import io.servicetalk.http.api.HttpHeaderNames;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.api.StreamingHttpConnection;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpRequestResponseFactory;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.http.api.TestStreamingHttpConnection;
import io.servicetalk.transport.api.RetryableException;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.netty.internal.FlushStrategy;
import io.servicetalk.transport.netty.internal.NettyConnection;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.net.StandardSocketOptions;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.servicetalk.buffer.api.EmptyBuffer.EMPTY_BUFFER;
import static io.servicetalk.buffer.netty.BufferAllocators.DEFAULT_ALLOCATOR;
import static io.servicetalk.concurrent.api.BlockingTestUtils.awaitIndefinitelyNonNull;
import static io.servicetalk.concurrent.api.Completable.never;
import static io.servicetalk.concurrent.api.Executors.immediate;
import static io.servicetalk.concurrent.api.Processors.newCompletableProcessor;
import static io.servicetalk.concurrent.api.Publisher.empty;
import static io.servicetalk.concurrent.api.Single.failed;
import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.concurrent.api.SourceAdapters.fromSource;
import static io.servicetalk.http.api.HttpExecutionStrategies.customStrategyBuilder;
import static io.servicetalk.http.api.HttpProtocolVersion.HTTP_1_1;
import static io.servicetalk.http.netty.HttpClients.forResolvedAddress;
import static io.servicetalk.http.netty.HttpProtocolConfigs.h1;
import static io.servicetalk.transport.netty.internal.AddressUtils.localAddress;
import static io.servicetalk.transport.netty.internal.AddressUtils.serverHostAndPort;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ConcurrentRequestsHttpConnectionFilterTest {

    private static final BufferAllocator allocator = DEFAULT_ALLOCATOR;
    private static final StreamingHttpRequestResponseFactory reqRespFactory =
            new DefaultStreamingHttpRequestResponseFactory(allocator, DefaultHttpHeadersFactory.INSTANCE, HTTP_1_1);

    @Rule
    public final MockitoRule rule = MockitoJUnit.rule();
    @Mock
    private HttpExecutionContext executionContext;
    @Rule
    public final Timeout timeout = new ServiceTalkTestTimeout();

    private final TestPublisher<Buffer> response1Publisher = new TestPublisher<>();
    private final TestPublisher<Buffer> response2Publisher = new TestPublisher<>();
    private final TestPublisher<Buffer> response3Publisher = new TestPublisher<>();

    // TODO(jayv) Temporary workaround until DefaultNettyConnection leverages strategy.offloadReceive()
    private static final HttpExecutionStrategy FULLY_NO_OFFLOAD_STRATEGY =
            customStrategyBuilder().executor(immediate()).build();

    @Test
    public void decrementWaitsUntilResponsePayloadIsComplete() throws Exception {
        @SuppressWarnings("unchecked")
        Function<Publisher<Object>, Publisher<Object>> reqResp = mock(Function.class);
        final int maxPipelinedReqeusts = 2;
        NettyConnection conn = mock(NettyConnection.class);
        when(conn.onClose()).thenReturn(never());
        when(conn.onClosing()).thenReturn(never());
        when(conn.transportError()).thenReturn(Single.never());
        AbstractStreamingHttpConnection<NettyConnection> mockConnection =
                new AbstractStreamingHttpConnection<NettyConnection>(conn,
                        maxPipelinedReqeusts, executionContext, reqRespFactory, DefaultHttpHeadersFactory.INSTANCE) {
                    private final AtomicInteger reqCount = new AtomicInteger(0);

                    @Override
                    public Single<StreamingHttpResponse> request(final HttpExecutionStrategy strategy,
                                                                 final StreamingHttpRequest request) {
                        switch (reqCount.incrementAndGet()) {
                            case 1: return succeeded(reqRespFactory.ok().payloadBody(response1Publisher));
                            case 2: return succeeded(reqRespFactory.ok().payloadBody(response2Publisher));
                            case 3: return succeeded(reqRespFactory.ok().payloadBody(response3Publisher));
                            default: return failed(new UnsupportedOperationException());
                        }
                    }

            @Override
            protected Publisher<Object> writeAndRead(final Publisher<Object> stream,
                                                     final FlushStrategy flushStrategy) {
                return reqResp.apply(stream);
            }
        };

        StreamingHttpConnection limitedConnection = TestStreamingHttpConnection.from(
                new ConcurrentRequestsHttpConnectionFilter(mockConnection, maxPipelinedReqeusts));

        StreamingHttpResponse resp1 = awaitIndefinitelyNonNull(
                limitedConnection.request(limitedConnection.get("/foo")));
        awaitIndefinitelyNonNull(limitedConnection.request(limitedConnection.get("/bar")));
        try {
            limitedConnection.request(limitedConnection.get("/baz")).toFuture().get();
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), is(instanceOf(MaxRequestLimitExceededException.class)));
        }

        // Consume the first response payload and ignore the content.
        resp1.payloadBody().forEach(chunk -> { });
        response1Publisher.onNext(EMPTY_BUFFER);
        response1Publisher.onComplete();

        // Verify that a new request can be made after the first request completed.
        awaitIndefinitelyNonNull(limitedConnection.request(limitedConnection.get("/baz")));
    }

    @Ignore("reserveConnection does not apply connection limits.")
    @Test
    public void throwMaxConcurrencyExceededOnOversubscribedConnection() throws Exception {
        final Processor lastRequestFinished = newCompletableProcessor();

        try (ServerContext serverContext = HttpServers.forAddress(localAddress(0))
                .listenStreamingAndAwait((ctx, request, responseFactory) -> {
                    Publisher<Buffer> deferredPayload = fromSource(lastRequestFinished).concat(empty());
                    return request.payloadBody().ignoreElements()
                            .concat(Single.succeeded(responseFactory.ok().payloadBody(deferredPayload)));
                });

             StreamingHttpClient client = forResolvedAddress(serverHostAndPort(serverContext))
                     .protocols(h1().maxPipelinedRequests(2).build())
                     .buildStreaming();

             StreamingHttpConnection connection = client.reserveConnection(client.get("/")).toFuture().get()) {

            Single<StreamingHttpResponse> resp1 = connection.request(connection.get("/one"));
            Single<StreamingHttpResponse> resp2 = connection.request(connection.get("/two"));
            Single<StreamingHttpResponse> resp3 = connection.request(connection.get("/three"));

            try {
                Publisher.from(resp1, resp2, resp3) // Don't consume payloads to build up concurrency
                        .flatMapMergeSingle(Function.identity())
                        .toFuture().get();

                fail("Should not allow three concurrent requests to complete normally");
            } catch (ExecutionException e) {
                assertThat(e.getCause(), instanceOf(MaxRequestLimitExceededException.class));
            } finally {
                lastRequestFinished.onComplete();
            }
        }
    }

    @Test
    public void throwConnectionClosedOnConnectionClose() throws Exception {

        try (ServerContext serverContext = HttpServers.forAddress(localAddress(0))
                .listenStreamingAndAwait((ctx, request, responseFactory) ->
                        request.payloadBody().ignoreElements().concat(
                        Single.succeeded(responseFactory.ok()
                                .setHeader(HttpHeaderNames.CONNECTION, "close"))));

             HttpClient client = forResolvedAddress(serverHostAndPort(serverContext))
                     .protocols(h1().maxPipelinedRequests(99).build())
                     .executionStrategy(FULLY_NO_OFFLOAD_STRATEGY)
                     .build();

             HttpConnection connection = client.reserveConnection(client.get("/")).toFuture().get()) {

            Single<? extends HttpResponse> resp1 = connection.request(connection.get("/one"));
            Single<? extends HttpResponse> resp2 = connection.request(connection.get("/two"));

            resp1.toFuture().get();

            try {
                connection.onClose().concat(resp2).toFuture().get();
                fail("Should not allow request to complete normally on a closed connection");
            } catch (ExecutionException e) {
                assertThat(e.getCause(), both(instanceOf(ClosedChannelException.class))
                        .and(instanceOf(RetryableException.class)));
                assertThat(e.getCause().getCause(), instanceOf(ClosedChannelException.class));
                assertThat(e.getCause().getCause().getMessage(), startsWith("PROTOCOL_CLOSING_INBOUND"));
            }
        }
    }

    @Test
    public void throwConnectionClosedWithCauseOnUnexpectedConnectionClose() throws Exception {

        try (ServerContext serverContext = HttpServers.forAddress(localAddress(0))
                .socketOption(StandardSocketOptions.SO_LINGER, 0) // Force connection reset on close
                .listenStreamingAndAwait((ctx, request, responseFactory) ->
                        request.payloadBody().ignoreElements()
                                .concat(ctx.closeAsync()) // trigger reset after client is done writing
                                .concat(Single.never()));
             HttpClient client = forResolvedAddress(serverHostAndPort(serverContext))
                     .protocols(h1().maxPipelinedRequests(99).build())
                     .executionStrategy(FULLY_NO_OFFLOAD_STRATEGY)
                     .build();

             HttpConnection connection = client.reserveConnection(client.get("/")).toFuture().get()) {

            Single<? extends HttpResponse> resp1 = connection.request(connection.get("/one"));
            Single<? extends HttpResponse> resp2 = connection.request(connection.get("/two"));

            Publisher.empty()
                    .concat(resp1).recoverWith(reset -> Publisher.empty())
                    .toFuture().get();

            final Processor closedFinally = newCompletableProcessor();
            connection.onClose().afterFinally(closedFinally::onComplete).subscribe();

            try {
                fromSource(closedFinally).concat(resp2).toFuture().get();
                fail("Should not allow request to complete normally on a closed connection");
            } catch (ExecutionException e) {
                assertThat(e.getCause(), both(instanceOf(ClosedChannelException.class))
                        .and(instanceOf(RetryableException.class)));
                assertThat(e.getCause().getCause(), instanceOf(ClosedChannelException.class));
                assertThat(e.getCause().getCause().getMessage(), startsWith("CHANNEL_CLOSED_INBOUND"));
            }
        }
    }
}
