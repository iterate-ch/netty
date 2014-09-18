/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.http2;

import static io.netty.handler.codec.http2.Http2TestUtil.runInChannel;
import static io.netty.util.CharsetUtil.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2TestUtil.Http2Runnable;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests encoding/decoding each HTTP2 frame type.
 */
public class Http2FrameRoundtripTest {

    @Mock
    private Http2FrameListener serverListener;

    private ArgumentCaptor<ByteBuf> dataCaptor;
    private Http2FrameWriter frameWriter;
    private ServerBootstrap sb;
    private Bootstrap cb;
    private Channel serverChannel;
    private Channel clientChannel;
    private CountDownLatch requestLatch;
    private Http2TestUtil.FrameAdapter serverAdapter;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        serverLatch(new CountDownLatch(1));
        frameWriter = new DefaultHttp2FrameWriter();
        dataCaptor = ArgumentCaptor.forClass(ByteBuf.class);

        sb = new ServerBootstrap();
        cb = new Bootstrap();

        sb.group(new NioEventLoopGroup(), new NioEventLoopGroup());
        sb.channel(NioServerSocketChannel.class);
        sb.childHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                serverAdapter = new Http2TestUtil.FrameAdapter(serverListener, requestLatch, true);
                p.addLast("reader", serverAdapter);
                p.addLast(Http2CodecUtil.ignoreSettingsHandler());
            }
        });

        cb.group(new NioEventLoopGroup());
        cb.channel(NioSocketChannel.class);
        cb.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline p = ch.pipeline();
                p.addLast("reader", new Http2TestUtil.FrameAdapter(null, null, true));
                p.addLast(Http2CodecUtil.ignoreSettingsHandler());
            }
        });

        serverChannel = sb.bind(new InetSocketAddress(0)).sync().channel();
        int port = ((InetSocketAddress) serverChannel.localAddress()).getPort();

        ChannelFuture ccf = cb.connect(new InetSocketAddress(NetUtil.LOCALHOST, port));
        assertTrue(ccf.awaitUninterruptibly().isSuccess());
        clientChannel = ccf.channel();
    }

    @After
    public void teardown() throws Exception {
        List<ByteBuf> capturedData = dataCaptor.getAllValues();
        for (int i = 0; i < capturedData.size(); ++i) {
            capturedData.get(i).release();
        }
        serverChannel.close().sync();
        Future<?> serverGroup = sb.group().shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        Future<?> serverChildGroup = sb.childGroup().shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        Future<?> clientGroup = cb.group().shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
        serverGroup.sync();
        serverChildGroup.sync();
        clientGroup.sync();
        serverAdapter = null;
    }

    @Test
    public void dataFrameShouldMatch() throws Exception {
        final String text = "hello world";
        final ByteBuf data = Unpooled.copiedBuffer(text.getBytes());
        try {
            runInChannel(clientChannel, new Http2Runnable() {
                @Override
                public void run() {
                    frameWriter.writeData(ctx(), 0x7FFFFFFF, data.retain(), 100, true, newPromise());
                    ctx().flush();
                }
            });
            awaitRequests();
            verify(serverListener).onDataRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                    dataCaptor.capture(), eq(100), eq(true));
            List<ByteBuf> capturedData = dataCaptor.getAllValues();
            assertEquals(data, capturedData.get(0));
        } finally {
            data.release();
        }
    }

    @Test
    public void headersFrameWithoutPriorityShouldMatch() throws Exception {
        final Http2Headers headers =
                new DefaultHttp2Headers.Builder().method("GET").scheme("https")
                        .authority("example.org").path("/some/path/resource2").build();
        runInChannel(clientChannel, new Http2Runnable() {
            @Override
            public void run() {
                frameWriter.writeHeaders(ctx(), 0x7FFFFFFF, headers, 0, true, newPromise());
                ctx().flush();
            }
        });
        awaitRequests();
        verify(serverListener).onHeadersRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(headers), eq(0), eq(true));
    }

    @Test
    public void headersFrameWithPriorityShouldMatch() throws Exception {
        final Http2Headers headers =
                new DefaultHttp2Headers.Builder().method("GET").scheme("https")
                        .authority("example.org").path("/some/path/resource2").build();
        runInChannel(clientChannel, new Http2Runnable() {
            @Override
            public void run() {
                frameWriter.writeHeaders(ctx(), 0x7FFFFFFF, headers, 4, (short) 255,
                        true, 0, true, newPromise());
                ctx().flush();
            }
        });
        awaitRequests();
        verify(serverListener).onHeadersRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(headers), eq(4), eq((short) 255), eq(true), eq(0), eq(true));
    }

    @Test
    public void goAwayFrameShouldMatch() throws Exception {
        final String text = "test";
        final ByteBuf data = Unpooled.copiedBuffer(text.getBytes());
        try {
            runInChannel(clientChannel, new Http2Runnable() {
                @Override
                public void run() {
                    frameWriter.writeGoAway(ctx(), 0x7FFFFFFF, 0xFFFFFFFFL, data.retain(), newPromise());
                    ctx().flush();
                }
            });
            awaitRequests();
            verify(serverListener).onGoAwayRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                    eq(0xFFFFFFFFL), dataCaptor.capture());
            List<ByteBuf> capturedData = dataCaptor.getAllValues();
            assertEquals(data, capturedData.get(0));
        } finally {
            data.release();
        }
    }

    @Test
    public void pingFrameShouldMatch() throws Exception {
        final ByteBuf data = Unpooled.copiedBuffer("01234567", UTF_8);
        try {
            runInChannel(clientChannel, new Http2Runnable() {
                @Override
                public void run() {
                    frameWriter.writePing(ctx(), true, data.retain(), newPromise());
                    ctx().flush();
                }
            });
            awaitRequests();
            verify(serverListener).onPingAckRead(any(ChannelHandlerContext.class), dataCaptor.capture());
            List<ByteBuf> capturedData = dataCaptor.getAllValues();
            assertEquals(data, capturedData.get(0));
        } finally {
            data.release();
        }
    }

    @Test
    public void priorityFrameShouldMatch() throws Exception {
        runInChannel(clientChannel, new Http2Runnable() {
            @Override
            public void run() {
                frameWriter.writePriority(ctx(), 0x7FFFFFFF, 1, (short) 1, true, newPromise());
                ctx().flush();
            }
        });
        awaitRequests();
        verify(serverListener).onPriorityRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(1), eq((short) 1), eq(true));
    }

    @Test
    public void pushPromiseFrameShouldMatch() throws Exception {
        final Http2Headers headers =
                new DefaultHttp2Headers.Builder().method("GET").scheme("https")
                        .authority("example.org").path("/some/path/resource2").build();
        runInChannel(clientChannel, new Http2Runnable() {
            @Override
            public void run() {
                frameWriter.writePushPromise(ctx(), 0x7FFFFFFF, 1, headers, 5, newPromise());
                ctx().flush();
            }
        });
        awaitRequests();
        verify(serverListener).onPushPromiseRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(1), eq(headers), eq(5));
    }

    @Test
    public void rstStreamFrameShouldMatch() throws Exception {
        runInChannel(clientChannel, new Http2Runnable() {
            @Override
            public void run() {
                frameWriter.writeRstStream(ctx(), 0x7FFFFFFF, 0xFFFFFFFFL, newPromise());
                ctx().flush();
            }
        });
        awaitRequests();
        verify(serverListener).onRstStreamRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(0xFFFFFFFFL));
    }

    @Test
    public void settingsFrameShouldMatch() throws Exception {
        final Http2Settings settings = new Http2Settings();
        settings.initialWindowSize(10);
        settings.maxConcurrentStreams(1000);
        settings.headerTableSize(4096);
        runInChannel(clientChannel, new Http2Runnable() {
            @Override
            public void run() {
                frameWriter.writeSettings(ctx(), settings, newPromise());
                ctx().flush();
            }
        });
        awaitRequests();
        verify(serverListener).onSettingsRead(any(ChannelHandlerContext.class), eq(settings));
    }

    @Test
    public void windowUpdateFrameShouldMatch() throws Exception {
        runInChannel(clientChannel, new Http2Runnable() {
            @Override
            public void run() {
                frameWriter.writeWindowUpdate(ctx(), 0x7FFFFFFF, 0x7FFFFFFF, newPromise());
                ctx().flush();
            }
        });
        awaitRequests();
        verify(serverListener).onWindowUpdateRead(any(ChannelHandlerContext.class), eq(0x7FFFFFFF),
                eq(0x7FFFFFFF));
    }

    @Test
    public void stressTest() throws Exception {
        final Http2Headers headers =
                new DefaultHttp2Headers.Builder().method("GET").scheme("https")
                        .authority("example.org").path("/some/path/resource2").build();
        final String text = "hello world";
        final ByteBuf data = Unpooled.copiedBuffer(text.getBytes());
        try {
            final int numStreams = 10000;
            final int expectedFrames = numStreams * 2;
            serverLatch(new CountDownLatch(expectedFrames));
            runInChannel(clientChannel, new Http2Runnable() {
                @Override
                public void run() {
                    for (int i = 1; i < numStreams + 1; ++i) {
                        frameWriter.writeHeaders(ctx(), i, headers, 0, (short) 16, false, 0, false, newPromise());
                        frameWriter.writeData(ctx(), i, data.retain(), 0, true, newPromise());
                        ctx().flush();
                    }
                }
            });
            awaitRequests(30);
            verify(serverListener, times(numStreams)).onDataRead(any(ChannelHandlerContext.class), anyInt(),
                    dataCaptor.capture(), eq(0), eq(true));
            List<ByteBuf> capturedData = dataCaptor.getAllValues();
            for (int i = 0; i < capturedData.size(); ++i) {
                assertEquals(data, capturedData.get(i));
            }
        } finally {
            data.release();
        }
    }

    private void awaitRequests(long seconds) throws InterruptedException {
        requestLatch.await(seconds, SECONDS);
    }

    private void awaitRequests() throws InterruptedException {
        awaitRequests(5);
    }

    private void serverLatch(CountDownLatch latch) {
        requestLatch = latch;
        if (serverAdapter != null) {
            serverAdapter.latch(latch);
        }
    }

    private ChannelHandlerContext ctx() {
        return clientChannel.pipeline().firstContext();
    }

    private ChannelPromise newPromise() {
        return ctx().newPromise();
    }
}
