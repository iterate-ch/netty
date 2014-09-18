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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderUtil;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2Headers.HeaderVisitor;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * This adapter provides just header/data events from the HTTP message flow defined
 * here <a href="http://tools.ietf.org/html/draft-ietf-httpbis-http2-14#section-8.1.">HTTP/2 Spec Message Flow</a>
 */
public class InboundHttp2ToHttpAdapter extends Http2EventAdapter {
    private final int maxContentLength;
    protected final Http2Connection connection;
    protected final boolean validateHttpHeaders;
    private final ImmediateSendDetector sendDetector;
    protected final IntObjectMap<FullHttpMessage> messageMap;

    private static final Set<String> HEADERS_TO_EXCLUDE;
    private static final Map<String, String> HEADER_NAME_TRANSLATIONS_REQUEST;
    private static final Map<String, String> HEADER_NAME_TRANSLATIONS_RESPONSE;

    static {
        HEADERS_TO_EXCLUDE = new HashSet<String>();
        HEADER_NAME_TRANSLATIONS_REQUEST = new HashMap<String, String>();
        HEADER_NAME_TRANSLATIONS_RESPONSE = new HashMap<String, String>();
        for (Http2Headers.PseudoHeaderName http2HeaderName : Http2Headers.PseudoHeaderName.values()) {
            HEADERS_TO_EXCLUDE.add(http2HeaderName.value());
        }

        HEADER_NAME_TRANSLATIONS_RESPONSE.put(Http2Headers.PseudoHeaderName.AUTHORITY.value(),
                        HttpUtil.ExtensionHeaders.Names.AUTHORITY.toString());
        HEADER_NAME_TRANSLATIONS_RESPONSE.put(Http2Headers.PseudoHeaderName.SCHEME.value(),
                        HttpUtil.ExtensionHeaders.Names.SCHEME.toString());
        HEADER_NAME_TRANSLATIONS_REQUEST.putAll(HEADER_NAME_TRANSLATIONS_RESPONSE);
        HEADER_NAME_TRANSLATIONS_RESPONSE.put(Http2Headers.PseudoHeaderName.PATH.value(),
                        HttpUtil.ExtensionHeaders.Names.PATH.toString());
    }

    /**
     * Creates a new instance
     *
     * @param connection The object which will provide connection notification events for the current connection
     * @param maxContentLength the maximum length of the message content. If the length of the message content exceeds
     *        this value, a {@link TooLongFrameException} will be raised.
     * @throws NullPointerException If {@code connection} is null
     * @throws IllegalArgumentException If {@code maxContentLength} is less than or equal to {@code 0}
     */
    public static InboundHttp2ToHttpAdapter newInstance(Http2Connection connection, int maxContentLength) {
        InboundHttp2ToHttpAdapter instance = new InboundHttp2ToHttpAdapter(connection, maxContentLength);
        connection.addListener(instance);
        return instance;
    }

    /**
     * Creates a new instance
     *
     * @param connection The object which will provide connection notification events for the current connection
     * @param maxContentLength the maximum length of the message content. If the length of the message content exceeds
     *        this value, a {@link TooLongFrameException} will be raised.
     * @param validateHttpHeaders
     * <ul>
     * <li>{@code true} to validate HTTP headers in the http-codec</li>
     * <li>{@code false} not to validate HTTP headers in the http-codec</li>
     * </ul>
     * @throws NullPointerException If {@code connection} is null
     * @throws IllegalArgumentException If {@code maxContentLength} is less than or equal to {@code 0}
     */
    public static InboundHttp2ToHttpAdapter newInstance(Http2Connection connection, int maxContentLength,
                    boolean validateHttpHeaders) {
        InboundHttp2ToHttpAdapter instance = new InboundHttp2ToHttpAdapter(connection,
                        maxContentLength, validateHttpHeaders);
        connection.addListener(instance);
        return instance;
    }

    /**
     * Creates a new instance
     *
     * @param connection The object which will provide connection notification events for the current connection
     * @param maxContentLength the maximum length of the message content. If the length of the message content exceeds
     *        this value, a {@link TooLongFrameException} will be raised.
     * @throws NullPointerException If {@code connection} is null
     * @throws IllegalArgumentException If {@code maxContentLength} is less than or equal to {@code 0}
     */
    protected InboundHttp2ToHttpAdapter(Http2Connection connection, int maxContentLength) {
        this(connection, maxContentLength, true);
    }

    /**
     * Creates a new instance
     *
     * @param connection The object which will provide connection notification events for the current connection
     * @param maxContentLength the maximum length of the message content. If the length of the message content exceeds
     *        this value, a {@link TooLongFrameException} will be raised.
     * @param validateHttpHeaders
     * <ul>
     * <li>{@code true} to validate HTTP headers in the http-codec</li>
     * <li>{@code false} not to validate HTTP headers in the http-codec</li>
     * </ul>
     * @throws NullPointerException If {@code connection} is null
     * @throws IllegalArgumentException If {@code maxContentLength} is less than or equal to {@code 0}
     */
    protected InboundHttp2ToHttpAdapter(Http2Connection connection, int maxContentLength,
                    boolean validateHttpHeaders) {
        if (connection == null) {
            throw new NullPointerException("connection");
        }
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException("maxContentLength must be a positive integer: " + maxContentLength);
        }
        this.maxContentLength = maxContentLength;
        this.validateHttpHeaders = validateHttpHeaders;
        this.connection = connection;
        sendDetector = DefaultImmediateSendDetector.getInstance();
        messageMap = new IntObjectHashMap<FullHttpMessage>();
    }

    /**
     * The streamId is out of scope for the HTTP message flow and will no longer be tracked
     * @param streamId The stream id to remove associated state with
     */
    protected void removeMessage(int streamId) {
        messageMap.remove(streamId);
    }

    @Override
    public void streamRemoved(Http2Stream stream) {
        removeMessage(stream.id());
    }

    /**
     * Set final headers and fire a channel read event
     *
     * @param ctx The context to fire the event on
     * @param msg The message to send
     * @param streamId the streamId of the message which is being fired
     */
    protected void fireChannelRead(ChannelHandlerContext ctx, FullHttpMessage msg, int streamId) {
        removeMessage(streamId);
        HttpHeaderUtil.setContentLength(msg, msg.content().readableBytes());
        ctx.fireChannelRead(msg);
    }

    /**
     * Create a new {@link FullHttpMessage} based upon the current connection parameters
     *
     * @param streamId The stream id to create a message for
     * @param headers The headers associated with {@code streamId}
     * @param validateHttpHeaders
     * <ul>
     * <li>{@code true} to validate HTTP headers in the http-codec</li>
     * <li>{@code false} not to validate HTTP headers in the http-codec</li>
     * </ul>
     * @return
     * @throws Http2Exception
     */
    protected FullHttpMessage newMessage(int streamId, Http2Headers headers, boolean validateHttpHeaders)
            throws Http2Exception {
        return connection.isServer() ? newHttpRequest(streamId, headers, validateHttpHeaders) :
                                       newHttpResponse(streamId, headers, validateHttpHeaders);
    }

    /**
     * Provides translation between HTTP/2 and HTTP header objects while ensuring the stream
     * is in a valid state for additional headers.
     *
     * @param ctx The context for which this message has been received.
     * Used to send informational header if detected.
     * @param streamId The stream id the {@code headers} apply to
     * @param headers The headers to process
     * @param endOfStream {@code true} if the {@code streamId} has received the end of stream flag
     * @param allowAppend
     * <ul>
     * <li>{@code true} if headers will be appended if the stream already exists.</li>
     * <li>if {@code false} and the stream already exists this method returns {@code null}.</li>
     * </ul>
     * @param appendToTrailer
     * <ul>
     * <li>{@code true} if a message {@code streamId} already exists then the headers
     * should be added to the trailing headers.</li>
     * <li>{@code false} then appends will be done to the initial headers.</li>
     * </ul>
     * @return The object used to track the stream corresponding to {@code streamId}. {@code null} if
     *         {@code allowAppend} is {@code false} and the stream already exists.
     * @throws Http2Exception If the stream id is not in the correct state to process the headers request
     */
    protected FullHttpMessage processHeadersBegin(ChannelHandlerContext ctx, int streamId, Http2Headers headers,
                boolean endOfStream, boolean allowAppend, boolean appendToTrailer) throws Http2Exception {
        FullHttpMessage msg = messageMap.get(streamId);
        if (msg == null) {
            msg = newMessage(streamId, headers, validateHttpHeaders);
        } else if (allowAppend) {
            try {
                addHttp2ToHttpHeaders(streamId, headers, msg, appendToTrailer);
            } catch (Http2Exception e) {
                removeMessage(streamId);
                throw e;
            }
        } else {
            msg = null;
        }

        if (sendDetector.mustSendImmediately(msg)) {
            fireChannelRead(ctx, msg, streamId);
            return endOfStream ? null : sendDetector.copyIfNeeded(msg);
        }

        return msg;
    }

    /**
     * After HTTP/2 headers have been processed by {@link #processHeadersBegin} this method either
     * sends the result up the pipeline or retains the message for future processing.
     *
     * @param ctx The context for which this message has been received
     * @param streamId The stream id the {@code objAccumulator} corresponds to
     * @param msg The object which represents all headers/data for corresponding to {@code streamId}
     * @param endOfStream {@code true} if this is the last event for the stream
     */
    private void processHeadersEnd(ChannelHandlerContext ctx, int streamId,
            FullHttpMessage msg, boolean endOfStream) {
        if (endOfStream) {
            fireChannelRead(ctx, msg, streamId);
        } else {
            messageMap.put(streamId, msg);
        }
    }

    @Override
    public void onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream)
                    throws Http2Exception {
        FullHttpMessage msg = messageMap.get(streamId);
        if (msg == null) {
            throw Http2Exception.protocolError("Data Frame recieved for unknown stream id %d", streamId);
        }

        ByteBuf content = msg.content();
        if (content.readableBytes() > maxContentLength - data.readableBytes()) {
            throw Http2Exception.format(Http2Error.INTERNAL_ERROR,
                            "Content length exceeded max of %d for stream id %d", maxContentLength, streamId);
        }

        content.writeBytes(data, data.readerIndex(), data.readableBytes());

        if (endOfStream) {
            fireChannelRead(ctx, msg, streamId);
        }
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding,
                    boolean endOfStream) throws Http2Exception {
        FullHttpMessage msg = processHeadersBegin(ctx, streamId, headers, endOfStream, true, true);
        if (msg != null) {
            processHeadersEnd(ctx, streamId, msg, endOfStream);
        }
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int streamDependency,
                    short weight, boolean exclusive, int padding, boolean endOfStream) throws Http2Exception {
        FullHttpMessage msg = processHeadersBegin(ctx, streamId, headers, endOfStream, true, true);
        if (msg != null) {
            processHeadersEnd(ctx, streamId, msg, endOfStream);
        }
    }

    @Override
    public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception {
        FullHttpMessage msg = messageMap.get(streamId);
        if (msg != null) {
            fireChannelRead(ctx, msg, streamId);
        }
    }

    @Override
    public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
            Http2Headers headers, int padding) throws Http2Exception {
        // A push promise should not be allowed to add headers to an existing stream
        FullHttpMessage msg = processHeadersBegin(ctx, promisedStreamId, headers, false, false, false);
        if (msg == null) {
            throw Http2Exception.protocolError("Push Promise Frame recieved for pre-existing stream id %d",
                            promisedStreamId);
        }

        msg.headers().set(HttpUtil.ExtensionHeaders.Names.STREAM_PROMISE_ID, streamId);

        processHeadersEnd(ctx, promisedStreamId, msg, false);
    }

    /**
     * Allows messages to be sent up the pipeline before the next phase in the
     * HTTP message flow is detected.
     */
    private interface ImmediateSendDetector {
        /**
         * Determine if the response should be sent immediately, or wait for the end of the stream
         *
         * @param msg The response to test
         * @return {@code true} if the message should be sent immediately
         *         {@code false) if we should wait for the end of the stream
         */
        boolean mustSendImmediately(FullHttpMessage msg);

        /**
         * Determine if a copy must be made after an immediate send happens.
         * <p>
         * An example of this use case is if a request is received
         * with a 'Expect: 100-continue' header. The message will be sent immediately,
         * and the data will be queued and sent at the end of the stream.
         *
         * @param msg The message which has just been sent due to {@link #mustSendImmediatley}
         * @return A modified copy of the {@code msg} or {@code null} if a copy is not needed.
         */
        FullHttpMessage copyIfNeeded(FullHttpMessage msg);
    }

    /**
     * Default implementation of {@link ImmediateSendDetector}
     */
    private static final class DefaultImmediateSendDetector implements ImmediateSendDetector {
        private static DefaultImmediateSendDetector instance;

        private DefaultImmediateSendDetector() {
        }

        public static DefaultImmediateSendDetector getInstance() {
            if (instance == null) {
                instance = new DefaultImmediateSendDetector();
            }
            return instance;
        }

        @Override
        public boolean mustSendImmediately(FullHttpMessage msg) {
            if (msg instanceof FullHttpResponse) {
                return ((FullHttpResponse) msg).status().isInformational();
            } else if (msg instanceof FullHttpRequest) {
                return ((FullHttpRequest) msg).headers().contains(HttpHeaders.Names.EXPECT);
            }
            return false;
        }

        @Override
        public FullHttpMessage copyIfNeeded(FullHttpMessage msg) {
            if (msg instanceof FullHttpRequest) {
                FullHttpRequest copy = ((FullHttpRequest) msg).copy(null);
                copy.headers().remove(HttpHeaders.Names.EXPECT);
                return copy;
            }
            return null;
        }
    }

    /**
     * Create a new object to contain the response data
     *
     * @param streamId The stream associated with the response
     * @param http2Headers The initial set of HTTP/2 headers to create the response with
     * @param validateHttpHeaders
     * <ul>
     * <li>{@code true} to validate HTTP headers in the http-codec</li>
     * <li>{@code false} not to validate HTTP headers in the http-codec</li>
     * </ul>
     * @return A new response object which represents headers/data
     * @throws Http2Exception see {@link #addHttp2ToHttpHeaders(int, Http2Headers, FullHttpMessage, Map)}
     */
    private static FullHttpMessage newHttpResponse(int streamId, Http2Headers http2Headers, boolean validateHttpHeaders)
                    throws Http2Exception {
        HttpResponseStatus status = HttpUtil.parseStatus(http2Headers.status());
        // HTTP/2 does not define a way to carry the version or reason phrase that is included in an HTTP/1.1
        // status line.
        FullHttpMessage msg = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, validateHttpHeaders);
        addHttp2ToHttpHeaders(streamId, http2Headers, msg, false, HEADER_NAME_TRANSLATIONS_RESPONSE);
        return msg;
    }

    /**
     * Create a new object to contain the request data
     *
     * @param streamId The stream associated with the request
     * @param http2Headers The initial set of HTTP/2 headers to create the request with
     * @param validateHttpHeaders
     * <ul>
     * <li>{@code true} to validate HTTP headers in the http-codec</li>
     * <li>{@code false} not to validate HTTP headers in the http-codec</li>
     * </ul>
     * @return A new request object which represents headers/data
     * @throws Http2Exception see {@link #addHttp2ToHttpHeaders(int, Http2Headers, FullHttpMessage, Map)}
     */
    private static FullHttpMessage newHttpRequest(int streamId, Http2Headers http2Headers, boolean validateHttpHeaders)
                    throws Http2Exception {
        // HTTP/2 does not define a way to carry the version identifier that is
        // included in the HTTP/1.1 request line.
        FullHttpMessage msg = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                        HttpMethod.valueOf(http2Headers.method()), http2Headers.path(), validateHttpHeaders);
        addHttp2ToHttpHeaders(streamId, http2Headers, msg, false, HEADER_NAME_TRANSLATIONS_REQUEST);
        return msg;
    }

    /**
     * Translate and add HTTP/2 headers to HTTP/1.x headers
     *
     * @param streamId The stream associated with {@code sourceHeaders}
     * @param sourceHeaders The HTTP/2 headers to convert
     * @param destinationMessage The object which will contain the resulting HTTP/1.x headers
     * @param addToTrailer {@code true} to add to trailing headers. {@code false} to add to initial headers.
     * @throws Http2Exception see {@link #addHttp2ToHttpHeaders(int, Http2Headers, FullHttpMessage, Map)}
     */
    private static void addHttp2ToHttpHeaders(int streamId, Http2Headers sourceHeaders,
                    FullHttpMessage destinationMessage, boolean addToTrailer) throws Http2Exception {
        addHttp2ToHttpHeaders(streamId, sourceHeaders, destinationMessage, addToTrailer,
                        (destinationMessage instanceof FullHttpRequest) ? HEADER_NAME_TRANSLATIONS_REQUEST
                                        : HEADER_NAME_TRANSLATIONS_RESPONSE);
    }

    /**
     * Translate and add HTTP/2 headers to HTTP/1.x headers
     *
     * @param streamId The stream associated with {@code sourceHeaders}
     * @param sourceHeaders The HTTP/2 headers to convert
     * @param destinationMessage The object which will contain the resulting HTTP/1.x headers
     * @param addToTrailer {@code true} to add to trailing headers. {@code false} to add to initial headers.
     * @param translations A map used to help translate HTTP/2 headers to HTTP/1.x headers
     * @throws Http2Exception If not all HTTP/2 headers can be translated to HTTP/1.x
     */
    private static void addHttp2ToHttpHeaders(int streamId, Http2Headers sourceHeaders,
                    FullHttpMessage destinationMessage, boolean addToTrailer, Map<String, String> translations)
                            throws Http2Exception {
        HttpHeaders headers = addToTrailer ? destinationMessage.trailingHeaders() : destinationMessage.headers();
        HttpAdapterVisitor visitor = new HttpAdapterVisitor(headers, translations);
        sourceHeaders.forEach(visitor);
        if (visitor.exception() != null) {
            throw visitor.exception();
        }

        headers.remove(HttpHeaders.Names.TRANSFER_ENCODING);
        headers.remove(HttpHeaders.Names.TRAILER);
        if (!addToTrailer) {
            headers.set(HttpUtil.ExtensionHeaders.Names.STREAM_ID, streamId);
            HttpHeaderUtil.setKeepAlive(destinationMessage, true);
        }
    }

    /**
     * A visitor which translates HTTP/2 headers to HTTP/1 headers
     */
    private static final class HttpAdapterVisitor implements HeaderVisitor {
        private Map<String, String> translations;
        private HttpHeaders headers;
        private Http2Exception e;

        /**
         * Create a new instance
         *
         * @param headers The HTTP/1.x headers object to store the results of the translation
         * @param translations A map used to help translate HTTP/2 headers to HTTP/1.x headers
         */
        public HttpAdapterVisitor(HttpHeaders headers, Map<String, String> translations) {
            this.translations = translations;
            this.headers = headers;
            this.e = null;
        }

        @Override
        public boolean visit(Entry<String, String> entry) {
            String translatedName = translations.get(entry.getKey());
            if (translatedName != null || !HEADERS_TO_EXCLUDE.contains(entry.getKey())) {
                if (translatedName == null) {
                    translatedName = entry.getKey();
                }

                // http://tools.ietf.org/html/draft-ietf-httpbis-http2-14#section-8.1.2.3
                // All headers that start with ':' are only valid in HTTP/2 context
                if (translatedName.isEmpty() || translatedName.charAt(0) == ':') {
                    e = Http2Exception
                            .protocolError("Unknown HTTP/2 header '%s' encountered in translation to HTTP/1.x",
                                            translatedName);
                    return false;
                } else {
                    headers.add(translatedName, entry.getValue());
                }
            }
            return true;
        }

        /**
         * Get any exceptions encountered while translating HTTP/2 headers to HTTP/1.x headers
         *
         * @return
         * <ul>
         * <li>{@code null} if no exceptions where encountered</li>
         * <li>Otherwise an exception describing what went wrong</li>
         * </ul>
         */
        public Http2Exception exception() {
            return e;
        }
    }
}
