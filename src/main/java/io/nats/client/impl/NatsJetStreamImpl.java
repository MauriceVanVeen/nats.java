// Copyright 2021 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamOptions;
import io.nats.client.Message;
import io.nats.client.NUID;
import io.nats.client.api.*;
import io.nats.client.support.NatsJetStreamConstants;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static io.nats.client.support.NatsJetStreamClientError.JsConsumerCreate290NotAvailable;
import static io.nats.client.support.NatsRequestCompletableFuture.CancelAction;

class NatsJetStreamImpl implements NatsJetStreamConstants {

    // currently the only thing we care about caching is the allowDirect setting
    static class CachedStreamInfo {
        public final boolean allowDirect;

        public CachedStreamInfo(StreamInfo si) {
            allowDirect = si.getConfiguration().getAllowDirect();
        }
    }

    private static final ConcurrentHashMap<String, CachedStreamInfo> CACHED_STREAM_INFO_MAP = new ConcurrentHashMap<>();

    final NatsConnection conn;
    final JetStreamOptions jso;
    final boolean consumerCreate290Available;

    // ----------------------------------------------------------------------------------------------------
    // Create / Init
    // ----------------------------------------------------------------------------------------------------
    NatsJetStreamImpl(NatsConnection connection, JetStreamOptions jsOptions) throws IOException {
        conn = connection;
        jso = JetStreamOptions.builder(jsOptions).build(); // builder handles null
        consumerCreate290Available = conn.getInfo().isSameOrNewerThanVersion("2.9.0") && !jso.isOptOut290ConsumerCreate();
    }

    // ----------------------------------------------------------------------------------------------------
    // Management that is also needed by regular context
    // ----------------------------------------------------------------------------------------------------
    ConsumerInfo _getConsumerInfo(String streamName, String consumerName) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_CONSUMER_INFO, streamName, consumerName);
        Message resp = makeRequestResponseRequired(subj, null, jso.getRequestTimeout());
        return new ConsumerInfo(resp).throwOnHasError();
    }

    ConsumerInfo _createConsumer(String streamName, ConsumerConfiguration config) throws IOException, JetStreamApiException {
        // ConsumerConfiguration validates that name and durable are the same if both are supplied.
        String consumerName = config.getName();
        if (consumerName != null && !consumerCreate290Available) {
            throw JsConsumerCreate290NotAvailable.instance();
        }
        String durable = config.getDurable();

        String subj;
        if (consumerCreate290Available) {
            if (consumerName == null) {
                // if both consumerName and durable are null, generate a name
                consumerName = durable == null ? generateConsumerName() : durable;
            }
            String fs = config.getFilterSubject();
            if (fs == null || fs.equals(">")) {
                subj = String.format(JSAPI_CONSUMER_CREATE_V290, streamName, consumerName);
            }
            else {
                subj = String.format(JSAPI_CONSUMER_CREATE_V290_W_FILTER, streamName, consumerName, fs);
            }
        }
        else if (durable == null) {
            subj = String.format(JSAPI_CONSUMER_CREATE, streamName);
        }
        else {
            subj = String.format(JSAPI_DURABLE_CREATE, streamName, durable);
        }

        ConsumerCreateRequest ccr = new ConsumerCreateRequest(streamName, config);
        Message resp = makeRequestResponseRequired(subj, ccr.serialize(), conn.getOptions().getConnectionTimeout());
        return new ConsumerInfo(resp).throwOnHasError();
    }

    static String generateConsumerName() {
        return NUID.nextGlobalSequence();
    }

    void _createConsumerUnsubscribeOnException(String stream, ConsumerConfiguration cc, NatsJetStreamSubscription sub) throws IOException, JetStreamApiException {
        try {
            ConsumerInfo ci = _createConsumer(stream, cc);
            sub.setConsumerName(ci.getName());
        }
        catch (IOException | JetStreamApiException e) {
            // create consumer can fail, unsubscribe and then throw the exception to the user
            if (sub.getDispatcher() == null) {
                sub.unsubscribe();
            }
            else {
                sub.getDispatcher().unsubscribe(sub);
            }
            throw e;
        }
    }

    StreamInfo _getStreamInfo(String streamName, StreamInfoOptions options) throws IOException, JetStreamApiException {
        String subj = String.format(JSAPI_STREAM_INFO, streamName);
        StreamInfoReader sir = new StreamInfoReader();
        while (sir.hasMore()) {
            Message resp = makeRequestResponseRequired(subj, sir.nextJson(options), jso.getRequestTimeout());
            sir.process(resp);
        }
        return cacheStreamInfo(streamName, sir.getStreamInfo());
    }

    StreamInfo createAndCacheStreamInfoThrowOnError(String streamName, Message resp) throws JetStreamApiException {
        return cacheStreamInfo(streamName, new StreamInfo(resp).throwOnHasError());
    }

    StreamInfo cacheStreamInfo(String streamName, StreamInfo si) {
        CACHED_STREAM_INFO_MAP.put(streamName, new CachedStreamInfo(si));
        return si;
    }

    List<StreamInfo> cacheStreamInfo(List<StreamInfo> list) {
        list.forEach(si -> CACHED_STREAM_INFO_MAP.put(si.getConfiguration().getName(), new CachedStreamInfo(si)));
        return list;
    }

    List<String> _getStreamNames(String subjectFilter) throws IOException, JetStreamApiException {
        StreamNamesReader snr = new StreamNamesReader();
        while (snr.hasMore()) {
            Message resp = makeRequestResponseRequired(JSAPI_STREAM_NAMES, snr.nextJson(subjectFilter), jso.getRequestTimeout());
            snr.process(resp);
        }
        return snr.getStrings();
    }

    // ----------------------------------------------------------------------------------------------------
    // General Utils
    // ----------------------------------------------------------------------------------------------------
    ConsumerInfo lookupConsumerInfo(String streamName, String consumerName) throws IOException, JetStreamApiException {
        try {
            return _getConsumerInfo(streamName, consumerName);
        }
        catch (JetStreamApiException e) {
            // the right side of this condition...  ( starting here \/ ) is for backward compatibility with server versions that did not provide api error codes
            if (e.getApiErrorCode() == JS_CONSUMER_NOT_FOUND_ERR || (e.getErrorCode() == 404 && e.getErrorDescription().contains("consumer"))) {
                return null;
            }
            throw e;
        }
    }

    String lookupStreamBySubject(String subject) throws IOException, JetStreamApiException {
        List<String> list = _getStreamNames(subject);
        return list.size() == 1 ? list.get(0) : null;
    }

    // ----------------------------------------------------------------------------------------------------
    // Request Utils
    // ----------------------------------------------------------------------------------------------------
    Message makeRequestResponseRequired(String subject, byte[] bytes, Duration timeout) throws IOException {
        try {
            return responseRequired(conn.request(prependPrefix(subject), bytes, timeout));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    Message makeInternalRequestResponseRequired(String subject, Headers headers, byte[] data, Duration timeout, CancelAction cancelAction) throws IOException {
        try {
            return responseRequired(conn.requestInternal(subject, headers, data, timeout, cancelAction));
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    Message responseRequired(Message respMessage) throws IOException {
        if (respMessage == null) {
            throw new IOException("Timeout or no response waiting for NATS JetStream server");
        }
        return respMessage;
    }

    String prependPrefix(String subject) {
        return jso.getPrefix() + subject;
    }

    CachedStreamInfo getCachedStreamInfo(String streamName) throws IOException, JetStreamApiException {
        CachedStreamInfo csi = CACHED_STREAM_INFO_MAP.get(streamName);
        if (csi != null) {
            return csi;
        }
        _getStreamInfo(streamName, null);
        return CACHED_STREAM_INFO_MAP.get(streamName);
    }
}