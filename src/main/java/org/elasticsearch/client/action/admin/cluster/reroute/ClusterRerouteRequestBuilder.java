/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.client.action.admin.cluster.reroute;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteRequest;
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteResponse;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.client.action.admin.cluster.support.BaseClusterRequestBuilder;
import org.elasticsearch.common.unit.TimeValue;

/**
 */
public class ClusterRerouteRequestBuilder extends BaseClusterRequestBuilder<ClusterRerouteRequest, ClusterRerouteResponse> {

    public ClusterRerouteRequestBuilder(ClusterAdminClient clusterClient) {
        super(clusterClient, new ClusterRerouteRequest());
    }

    /**
     * Sets the master node timeout in case the master has not yet been discovered.
     */
    public ClusterRerouteRequestBuilder setMasterNodeTimeout(TimeValue timeout) {
        request.masterNodeTimeout(timeout);
        return this;
    }

    /**
     * Sets the master node timeout in case the master has not yet been discovered.
     */
    public ClusterRerouteRequestBuilder setMasterNodeTimeout(String timeout) {
        request.masterNodeTimeout(timeout);
        return this;
    }

    @Override
    protected void doExecute(ActionListener<ClusterRerouteResponse> listener) {
        client.reroute(request, listener);
    }
}
