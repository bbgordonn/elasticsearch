/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
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

package org.elasticsearch.cluster.metadata;

import java.util.HashMap;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.exists.IndicesExistsResponse;
import org.elasticsearch.action.admin.indices.settings.UpdateSettingsResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.action.admin.cluster.settings.ClusterUpdateSettingsRequestBuilder;
import org.elasticsearch.client.action.admin.indices.settings.UpdateSettingsRequestBuilder;
import org.elasticsearch.client.action.index.IndexRequestBuilder;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.testng.annotations.Test;

import static org.elasticsearch.node.NodeBuilder.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

@Test
public class ClusterBlockTests {
    @Test public void testClusterReadOnly() throws Exception {
        Node node = newNode();
        try {
            Client client = node.client();
            try {
                // cluster.read_only = null: write and metadata not blocked
                canCreateIndex(client, "test1");
                canIndexDocument(client, "test1");
                setIndexReadOnly(client, "test1", "false");
                canIndexExists(client, "test1");

                // cluster.read_only = true: block write and metadata
                setClusterReadOnly(client, "true");
                canNotCreateIndex(client, "test2");
                // even if index has index.read_only = false
                canNotIndexDocument(client, "test1");
                canNotIndexExists(client, "test1");

                // cluster.read_only = false: removes the block
                setClusterReadOnly(client, "false");
                canCreateIndex(client, "test2");
                canIndexDocument(client, "test2");
                canIndexDocument(client, "test1");
                canIndexExists(client, "test1");
            }
            finally {
                client.close();
            }
        }
        finally {
            node.close();
        }
    }

    @Test public void testIndexReadOnly() throws Exception {
        Node node = newNode();
        try {
            Client client = node.client();
            try {
                // newly created an index has no blocks
                canCreateIndex(client, "ro");
                canIndexDocument(client, "ro");
                canIndexExists(client, "ro");

                // adds index write and metadata block
                setIndexReadOnly(client, "ro", "true");
                canNotIndexDocument(client, "ro");
                canNotIndexExists(client, "ro");

                // other indices not blocked
                canCreateIndex(client, "rw");
                canIndexDocument(client, "rw");
                canIndexExists(client, "rw");

                // blocks can be removed
                setIndexReadOnly(client, "ro", "false");
                canIndexDocument(client, "ro");
                canIndexExists(client, "ro");
            }
            finally {
                client.close();
            }
        }
        finally {
            node.close();
        }
    }

    private Node newNode() {
        ImmutableSettings.Builder settingsBuilder = ImmutableSettings.settingsBuilder().put("gateway.type", "none");
        NodeBuilder nodeBuilder = nodeBuilder().local(true).loadConfigSettings(false).clusterName("ClusterBlockTests").settings(settingsBuilder);
        return nodeBuilder.node();
    }

    private void canCreateIndex(Client client, String index) {
        try {
            CreateIndexResponse r = client.admin().indices().prepareCreate(index).execute().actionGet();
            assertThat(r, notNullValue());
        }
        catch (ClusterBlockException e) {
            assert false;
        }
    }

    private void canNotCreateIndex(Client client, String index) {
        try {
            client.admin().indices().prepareCreate(index).execute().actionGet();
            assert false;
        }
        catch (ClusterBlockException e) {
            // all is well
        }
    }

    private void canIndexDocument(Client client, String index) {
        try {
            IndexRequestBuilder builder = client.prepareIndex(index, "zzz");
            builder.setSource("foo", "bar");
            IndexResponse r = builder.execute().actionGet();
            assertThat(r, notNullValue());
        }
        catch (ClusterBlockException e) {
            assert false;
        }
    }

    private void canNotIndexDocument(Client client, String index) {
        try {
            IndexRequestBuilder builder = client.prepareIndex(index, "zzz");
            builder.setSource("foo", "bar");
            builder.execute().actionGet();
            assert false;
        }
        catch (ClusterBlockException e) {
            // all is well
        }
    }

    private void canIndexExists(Client client, String index) {
        try {
            IndicesExistsResponse r = client.admin().indices().prepareExists(index).execute().actionGet();
            assertThat(r, notNullValue());
        }
        catch (ClusterBlockException e) {
            assert false;
        }
    }

    private void canNotIndexExists(Client client, String index) {
        try {
            IndicesExistsResponse r = client.admin().indices().prepareExists(index).execute().actionGet();
            assert false;
        }
        catch (ClusterBlockException e) {
            // all is well
        }
    }

    private void setClusterReadOnly(Client client, String value) {
        HashMap<String, Object> newSettings = new HashMap<String, Object>();
        newSettings.put(MetaData.SETTING_READ_ONLY, value);

        ClusterUpdateSettingsRequestBuilder settingsRequest = client.admin().cluster().prepareUpdateSettings();
        settingsRequest.setTransientSettings(newSettings);
        ClusterUpdateSettingsResponse settingsResponse = settingsRequest.execute().actionGet();
        assertThat(settingsResponse, notNullValue());
    }

    private void setIndexReadOnly(Client client, String index, Object value) {
        HashMap<String, Object> newSettings = new HashMap<String, Object>();
        newSettings.put(IndexMetaData.SETTING_READ_ONLY, value);

        UpdateSettingsRequestBuilder settingsRequest = client.admin().indices().prepareUpdateSettings(index);
        settingsRequest.setSettings(newSettings);
        UpdateSettingsResponse settingsResponse = settingsRequest.execute().actionGet();
        assertThat(settingsResponse, notNullValue());
    }
}
