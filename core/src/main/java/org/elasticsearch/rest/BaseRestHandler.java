/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.rest;

import org.apache.lucene.search.spell.LevensteinDistance;
import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.ActionPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Base handler for REST requests.
 * <p>
 * This handler makes sure that the headers &amp; context of the handled {@link RestRequest requests} are copied over to
 * the transport requests executed by the associated client. While the context is fully copied over, not all the headers
 * are copied, but a selected few. It is possible to control what headers are copied over by returning them in
 * {@link ActionPlugin#getRestHeaders()}.
 */
public abstract class BaseRestHandler extends AbstractComponent implements RestHandler {

    public static final Setting<Boolean> MULTI_ALLOW_EXPLICIT_INDEX =
        Setting.boolSetting("rest.action.multi.allow_explicit_index", true, Property.NodeScope);
    protected final ParseFieldMatcher parseFieldMatcher;

    protected BaseRestHandler(Settings settings) {
        super(settings);
        this.parseFieldMatcher = new ParseFieldMatcher(settings);
    }

    @Override
    public final void handleRequest(RestRequest request, RestChannel channel, NodeClient client) throws Exception {
        // prepare the request for execution; has the side effect of touching the request parameters
        final RestChannelConsumer action = prepareRequest(request, client);

        // validate unconsumed params, but we must exclude params used to format the response
        // use a sorted set so the unconsumed parameters appear in a reliable sorted order
        final SortedSet<String> unconsumedParams =
            request.unconsumedParams().stream().filter(p -> !responseParams().contains(p)).collect(Collectors.toCollection(TreeSet::new));

        // validate the non-response params
        if (!unconsumedParams.isEmpty()) {
            String message = String.format(
                Locale.ROOT,
                "request [%s] contains unrecognized parameter%s: ",
                request.path(),
                unconsumedParams.size() > 1 ? "s" : "");
            boolean first = true;
            for (final String unconsumedParam : unconsumedParams) {
                final LevensteinDistance ld = new LevensteinDistance();
                final List<Tuple<Float, String>> scoredParams = new ArrayList<>();
                for (String consumedParam : request.consumedParams()) {
                    final float distance = ld.getDistance(unconsumedParam, consumedParam);
                    if (distance > 0.5f) {
                        scoredParams.add(new Tuple<>(distance, consumedParam));
                    }
                }
                CollectionUtil.timSort(scoredParams, (a, b) -> {
                    // sort by distance in reverse order, then parameter name for equal distances
                    int compare = a.v1().compareTo(b.v1());
                    if (compare != 0) return -compare;
                    else return a.v2().compareTo(b.v2());
                });
                if (first == false) {
                    message += ", ";
                }
                message += "[" + unconsumedParam + "]";
                final List<String> keys = scoredParams.stream().map(Tuple::v2).collect(Collectors.toList());
                if (keys.isEmpty() == false) {
                    message += " -> did you mean " + (keys.size() == 1 ? "[" + keys.get(0) + "]": "any of " + keys.toString()) + "?";
                }
                first = false;
            }

            throw new IllegalArgumentException(message);
        }

        // execute the action
        action.accept(channel);
    }

    /**
     * REST requests are handled by preparing a channel consumer that represents the execution of
     * the request against a channel.
     */
    @FunctionalInterface
    protected interface RestChannelConsumer {
        /**
         * Executes a request against the given channel.
         *
         * @param channel the channel for sending the response
         * @throws Exception if an exception occurred executing the request
         */
        void accept(RestChannel channel) throws Exception;
    }

    /**
     * Prepare the request for execution. Implementations should consume all request params before
     * returning the runnable for actual execution. Unconsumed params will immediately terminate
     * execution of the request. However, some params are only used in processing the response;
     * implementations can override {@link BaseRestHandler#responseParams()} to indicate such
     * params.
     *
     * @param request the request to execute
     * @param client  client for executing actions on the local node
     * @return the action to execute
     * @throws IOException if an I/O exception occurred parsing the request and preparing for
     *                     execution
     */
    protected abstract RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException;

    /**
     * Parameters used for controlling the response and thus might not be consumed during
     * preparation of the request execution in
     * {@link BaseRestHandler#prepareRequest(RestRequest, NodeClient)}.
     *
     * @return a set of parameters used to control the response and thus should not trip strict
     * URL parameter checks.
     */
    protected Set<String> responseParams() {
        return Collections.emptySet();
    }

}
