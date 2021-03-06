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

package org.elasticsearch.gateway.local;

import com.google.common.collect.Sets;
import com.google.common.io.Closeables;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.*;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.compress.lzf.LZF;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.*;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.thread.LoggingRunnable;
import org.elasticsearch.common.xcontent.*;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.gateway.Gateway;
import org.elasticsearch.gateway.GatewayException;
import org.elasticsearch.index.gateway.local.LocalIndexGatewayModule;
import org.elasticsearch.index.shard.ShardId;

import java.io.*;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.elasticsearch.common.util.concurrent.EsExecutors.daemonThreadFactory;

/**
 *
 */
public class LocalGateway extends AbstractLifecycleComponent<Gateway> implements Gateway, ClusterStateListener {

    private boolean requiresStatePersistence;

    private final ClusterService clusterService;

    private final NodeEnvironment nodeEnv;

    private final TransportNodesListGatewayMetaState listGatewayMetaState;

    private final TransportNodesListGatewayStartedShards listGatewayStartedShards;


    private final boolean compress;
    private final boolean prettyPrint;

    private volatile LocalGatewayMetaState currentMetaState;

    private volatile LocalGatewayStartedShards currentStartedShards;

    private volatile ExecutorService executor;

    private volatile boolean initialized = false;

    private volatile boolean metaDataPersistedAtLeastOnce = false;

    @Inject
    public LocalGateway(Settings settings, ClusterService clusterService, NodeEnvironment nodeEnv,
                        TransportNodesListGatewayMetaState listGatewayMetaState, TransportNodesListGatewayStartedShards listGatewayStartedShards) {
        super(settings);
        this.clusterService = clusterService;
        this.nodeEnv = nodeEnv;
        this.listGatewayMetaState = listGatewayMetaState.initGateway(this);
        this.listGatewayStartedShards = listGatewayStartedShards.initGateway(this);

        this.compress = componentSettings.getAsBoolean("compress", true);
        this.prettyPrint = componentSettings.getAsBoolean("pretty", false);
    }

    @Override
    public String type() {
        return "local";
    }

    public LocalGatewayMetaState currentMetaState() {
        lazyInitialize();
        return this.currentMetaState;
    }

    public LocalGatewayStartedShards currentStartedShards() {
        lazyInitialize();
        return this.currentStartedShards;
    }

    @Override
    protected void doStart() throws ElasticSearchException {
        this.executor = newSingleThreadExecutor(daemonThreadFactory(settings, "gateway"));
        lazyInitialize();
        clusterService.add(this);
    }

    @Override
    protected void doStop() throws ElasticSearchException {
        clusterService.remove(this);
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    @Override
    protected void doClose() throws ElasticSearchException {
    }

    @Override
    public void performStateRecovery(final GatewayStateRecoveredListener listener) throws GatewayException {
        Set<String> nodesIds = Sets.newHashSet();
        nodesIds.addAll(clusterService.state().nodes().masterNodes().keySet());
        TransportNodesListGatewayMetaState.NodesLocalGatewayMetaState nodesState = listGatewayMetaState.list(nodesIds, null).actionGet();

        if (nodesState.failures().length > 0) {
            for (FailedNodeException failedNodeException : nodesState.failures()) {
                logger.warn("failed to fetch state from node", failedNodeException);
            }
        }

        TransportNodesListGatewayMetaState.NodeLocalGatewayMetaState electedState = null;
        for (TransportNodesListGatewayMetaState.NodeLocalGatewayMetaState nodeState : nodesState) {
            if (nodeState.state() == null) {
                continue;
            }
            if (electedState == null) {
                electedState = nodeState;
            } else if (nodeState.state().version() > electedState.state().version()) {
                electedState = nodeState;
            }
        }
        if (electedState == null) {
            logger.debug("no state elected");
            listener.onSuccess(ClusterState.builder().build());
        } else {
            logger.debug("elected state from [{}]", electedState.node());
            ClusterState.Builder builder = ClusterState.builder().version(electedState.state().version());
            builder.metaData(MetaData.builder().metaData(electedState.state().metaData()).version(electedState.state().version()));
            listener.onSuccess(builder.build());
        }
    }

    @Override
    public Class<? extends Module> suggestIndexGateway() {
        return LocalIndexGatewayModule.class;
    }

    @Override
    public void reset() throws Exception {
        FileSystemUtils.deleteRecursively(nodeEnv.nodeDataLocations());
    }

    @Override
    public void clusterChanged(final ClusterChangedEvent event) {
        if (!requiresStatePersistence) {
            return;
        }

        // nothing to do until we actually recover from the gateway or any other block indicates we need to disable persistency
        if (event.state().blocks().disableStatePersistence()) {
            return;
        }

        // we only write the local metadata if this is a possible master node
        if (event.state().nodes().localNode().masterNode() && (event.metaDataChanged() || !metaDataPersistedAtLeastOnce)) {
            executor.execute(new LoggingRunnable(logger, new PersistMetaData(event)));
        }

        if (event.state().nodes().localNode().dataNode() && event.routingTableChanged()) {
            LocalGatewayStartedShards.Builder builder = LocalGatewayStartedShards.builder();
            if (currentStartedShards != null) {
                builder.state(currentStartedShards);
            }
            builder.version(event.state().version());

            boolean changed = false;

            // remove from the current state all the shards that are primary and started somewhere, we won't need them anymore
            // and if they are still here, we will add them in the next phase

            // Also note, this works well when closing an index, since a closed index will have no routing shards entries
            // so they won't get removed (we want to keep the fact that those shards are allocated on this node if needed)
            for (IndexRoutingTable indexRoutingTable : event.state().routingTable()) {
                for (IndexShardRoutingTable indexShardRoutingTable : indexRoutingTable) {
                    if (indexShardRoutingTable.countWithState(ShardRoutingState.STARTED) == indexShardRoutingTable.size()) {
                        changed |= builder.remove(indexShardRoutingTable.shardId());
                    }
                }
            }
            // remove deleted indices from the started shards
            for (ShardId shardId : builder.build().shards().keySet()) {
                if (!event.state().metaData().hasIndex(shardId.index().name())) {
                    changed |= builder.remove(shardId);
                }
            }
            // now, add all the ones that are active and on this node
            RoutingNode routingNode = event.state().readOnlyRoutingNodes().node(event.state().nodes().localNodeId());
            if (routingNode != null) {
                // out node is not in play yet...
                for (MutableShardRouting shardRouting : routingNode) {
                    if (shardRouting.active()) {
                        changed |= builder.put(shardRouting.shardId(), shardRouting.version());
                    }
                }
            }

            // only write if something changed...
            if (changed) {
                final LocalGatewayStartedShards stateToWrite = builder.build();
                executor.execute(new LoggingRunnable(logger, new PersistShards(event, stateToWrite)));
            }
        }
    }

    /**
     * We do here lazy initialization on not only on start(), since we might be called before start by another node (really will
     * happen in term of timing in testing, but still), and we want to return the cluster state when we can.
     * <p/>
     * It is synchronized since we want to wait for it to be loaded if called concurrently. There should really be a nicer
     * solution here, but for now, its good enough.
     */
    private synchronized void lazyInitialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        // if this is not a possible master node or data node, bail, we won't save anything here...
        if (!clusterService.localNode().masterNode() && !clusterService.localNode().dataNode()) {
            requiresStatePersistence = false;
        } else {
            // create the location where the state will be stored
            // TODO: we might want to persist states on all data locations
            requiresStatePersistence = true;

            if (clusterService.localNode().masterNode()) {
                try {
                    File latest = findLatestMetaStateVersion();
                    if (latest != null) {
                        logger.debug("[find_latest_state]: loading metadata from [{}]", latest.getAbsolutePath());
                        this.currentMetaState = readMetaState(Streams.copyToByteArray(new FileInputStream(latest)));
                    } else {
                        logger.debug("[find_latest_state]: no metadata state loaded");
                    }
                } catch (Exception e) {
                    logger.warn("failed to read local state (metadata)", e);
                }
            }

            if (clusterService.localNode().dataNode()) {
                try {
                    File latest = findLatestStartedShardsVersion();
                    if (latest != null) {
                        logger.debug("[find_latest_state]: loading started shards from [{}]", latest.getAbsolutePath());
                        this.currentStartedShards = readStartedShards(Streams.copyToByteArray(new FileInputStream(latest)));
                    } else {
                        logger.debug("[find_latest_state]: no started shards loaded");
                    }
                } catch (Exception e) {
                    logger.warn("failed to read local state (started shards)", e);
                }
            }
        }
    }

    private File findLatestStartedShardsVersion() throws IOException {
        long index = -1;
        File latest = null;
        for (File dataLocation : nodeEnv.nodeDataLocations()) {
            File stateLocation = new File(dataLocation, "_state");
            if (!stateLocation.exists()) {
                continue;
            }
            File[] stateFiles = stateLocation.listFiles();
            if (stateFiles == null) {
                continue;
            }
            for (File stateFile : stateFiles) {
                if (logger.isTraceEnabled()) {
                    logger.trace("[find_latest_state]: processing [" + stateFile.getName() + "]");
                }
                String name = stateFile.getName();
                if (!name.startsWith("shards-")) {
                    continue;
                }
                long fileIndex = Long.parseLong(name.substring(name.indexOf('-') + 1));
                if (fileIndex >= index) {
                    // try and read the meta data
                    try {
                        byte[] data = Streams.copyToByteArray(new FileInputStream(stateFile));
                        if (data.length == 0) {
                            logger.debug("[find_latest_state]: not data for [" + name + "], ignoring...");
                        }
                        readStartedShards(data);
                        index = fileIndex;
                        latest = stateFile;
                    } catch (IOException e) {
                        logger.warn("[find_latest_state]: failed to read state from [" + name + "], ignoring...", e);
                    }
                }
            }
        }
        return latest;
    }

    private File findLatestMetaStateVersion() throws IOException {
        long index = -1;
        File latest = null;
        for (File dataLocation : nodeEnv.nodeDataLocations()) {
            File stateLocation = new File(dataLocation, "_state");
            if (!stateLocation.exists()) {
                continue;
            }
            File[] stateFiles = stateLocation.listFiles();
            if (stateFiles == null) {
                continue;
            }
            for (File stateFile : stateFiles) {
                if (logger.isTraceEnabled()) {
                    logger.trace("[find_latest_state]: processing [" + stateFile.getName() + "]");
                }
                String name = stateFile.getName();
                if (!name.startsWith("metadata-")) {
                    continue;
                }
                long fileIndex = Long.parseLong(name.substring(name.indexOf('-') + 1));
                if (fileIndex >= index) {
                    // try and read the meta data
                    try {
                        byte[] data = Streams.copyToByteArray(new FileInputStream(stateFile));
                        if (data.length == 0) {
                            logger.debug("[find_latest_state]: not data for [" + name + "], ignoring...");
                            continue;
                        }
                        readMetaState(data);
                        index = fileIndex;
                        latest = stateFile;
                    } catch (IOException e) {
                        logger.warn("[find_latest_state]: failed to read state from [" + name + "], ignoring...", e);
                    }
                }
            }
        }
        return latest;
    }

    private LocalGatewayMetaState readMetaState(byte[] data) throws IOException {
        XContentParser parser = null;
        try {
            if (LZF.isCompressed(data)) {
                BytesStreamInput siBytes = new BytesStreamInput(data);
                LZFStreamInput siLzf = CachedStreamInput.cachedLzf(siBytes);
                parser = XContentFactory.xContent(XContentType.JSON).createParser(siLzf);
            } else {
                parser = XContentFactory.xContent(XContentType.JSON).createParser(data);
            }
            return LocalGatewayMetaState.Builder.fromXContent(parser);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    private LocalGatewayStartedShards readStartedShards(byte[] data) throws IOException {
        XContentParser parser = null;
        try {
            if (LZF.isCompressed(data)) {
                BytesStreamInput siBytes = new BytesStreamInput(data);
                LZFStreamInput siLzf = CachedStreamInput.cachedLzf(siBytes);
                parser = XContentFactory.xContent(XContentType.JSON).createParser(siLzf);
            } else {
                parser = XContentFactory.xContent(XContentType.JSON).createParser(data);
            }
            return LocalGatewayStartedShards.Builder.fromXContent(parser);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    class PersistMetaData implements Runnable {
        private final ClusterChangedEvent event;

        public PersistMetaData(ClusterChangedEvent event) {
            this.event = event;
        }

        @Override
        public void run() {
            LocalGatewayMetaState.Builder builder = LocalGatewayMetaState.builder();
            if (currentMetaState != null) {
                builder.state(currentMetaState);
            }
            final long version = event.state().metaData().version();
            builder.version(version);
            builder.metaData(event.state().metaData());
            LocalGatewayMetaState stateToWrite = builder.build();

            CachedStreamOutput.Entry cachedEntry = CachedStreamOutput.popEntry();
            StreamOutput streamOutput;
            try {
                try {
                    if (compress) {
                        streamOutput = cachedEntry.cachedLZFBytes();
                    } else {
                        streamOutput = cachedEntry.cachedBytes();
                    }
                    XContentBuilder xContentBuilder = XContentFactory.contentBuilder(XContentType.JSON, streamOutput);
                    if (prettyPrint) {
                        xContentBuilder.prettyPrint();
                    }
                    xContentBuilder.startObject();
                    LocalGatewayMetaState.Builder.toXContent(stateToWrite, xContentBuilder, ToXContent.EMPTY_PARAMS);
                    xContentBuilder.endObject();
                    xContentBuilder.close();
                } catch (Exception e) {
                    logger.warn("failed to serialize local gateway state", e);
                    return;
                }

                boolean serializedAtLeastOnce = false;
                for (File dataLocation : nodeEnv.nodeDataLocations()) {
                    File stateLocation = new File(dataLocation, "_state");
                    if (!stateLocation.exists()) {
                        FileSystemUtils.mkdirs(stateLocation);
                    }
                    File stateFile = new File(stateLocation, "metadata-" + version);
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(stateFile);
                        fos.write(cachedEntry.bytes().underlyingBytes(), 0, cachedEntry.bytes().size());
                        fos.getChannel().force(true);
                        serializedAtLeastOnce = true;
                    } catch (Exception e) {
                        logger.warn("failed to write local gateway state to {}", e, stateFile);
                    } finally {
                        Closeables.closeQuietly(fos);
                    }
                }
                if (serializedAtLeastOnce) {
                    currentMetaState = stateToWrite;
                    metaDataPersistedAtLeastOnce = true;

                    // delete all the other files
                    for (File dataLocation : nodeEnv.nodeDataLocations()) {
                        File stateLocation = new File(dataLocation, "_state");
                        if (!stateLocation.exists()) {
                            continue;
                        }
                        File[] files = stateLocation.listFiles(new FilenameFilter() {
                            @Override
                            public boolean accept(File dir, String name) {
                                return name.startsWith("metadata-") && !name.equals("metadata-" + version);
                            }
                        });
                        if (files != null) {
                            for (File file : files) {
                                file.delete();
                            }
                        }
                    }
                }
            } finally {
                CachedStreamOutput.pushEntry(cachedEntry);
            }
        }
    }

    class PersistShards implements Runnable {
        private final ClusterChangedEvent event;
        private final LocalGatewayStartedShards stateToWrite;

        public PersistShards(ClusterChangedEvent event, LocalGatewayStartedShards stateToWrite) {
            this.event = event;
            this.stateToWrite = stateToWrite;
        }

        @Override
        public void run() {

            CachedStreamOutput.Entry cachedEntry = CachedStreamOutput.popEntry();
            try {
                StreamOutput streamOutput;
                try {
                    if (compress) {
                        streamOutput = cachedEntry.cachedLZFBytes();
                    } else {
                        streamOutput = cachedEntry.cachedBytes();
                    }
                    XContentBuilder xContentBuilder = XContentFactory.contentBuilder(XContentType.JSON, streamOutput);
                    if (prettyPrint) {
                        xContentBuilder.prettyPrint();
                    }
                    xContentBuilder.startObject();
                    LocalGatewayStartedShards.Builder.toXContent(stateToWrite, xContentBuilder, ToXContent.EMPTY_PARAMS);
                    xContentBuilder.endObject();
                    xContentBuilder.close();
                } catch (Exception e) {
                    logger.warn("failed to serialize local gateway shard states", e);
                    return;
                }

                boolean serializedAtLeastOnce = false;
                for (File dataLocation : nodeEnv.nodeDataLocations()) {
                    File stateLocation = new File(dataLocation, "_state");
                    if (!stateLocation.exists()) {
                        FileSystemUtils.mkdirs(stateLocation);
                    }
                    File stateFile = new File(stateLocation, "shards-" + event.state().version());
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(stateFile);
                        fos.write(cachedEntry.bytes().underlyingBytes(), 0, cachedEntry.bytes().size());
                        fos.getChannel().force(true);
                        serializedAtLeastOnce = true;
                    } catch (Exception e) {
                        logger.warn("failed to write local gateway shards state to {}", e, stateFile);
                    } finally {
                        Closeables.closeQuietly(fos);
                    }
                }

                if (serializedAtLeastOnce) {
                    currentStartedShards = stateToWrite;

                    // delete all the other files
                    for (File dataLocation : nodeEnv.nodeDataLocations()) {
                        File stateLocation = new File(dataLocation, "_state");
                        if (!stateLocation.exists()) {
                            continue;
                        }
                        File[] files = stateLocation.listFiles(new FilenameFilter() {
                            @Override
                            public boolean accept(File dir, String name) {
                                return name.startsWith("shards-") && !name.equals("shards-" + event.state().version());
                            }
                        });
                        if (files != null) {
                            for (File file : files) {
                                file.delete();
                            }
                        }
                    }
                }
            } finally {
                CachedStreamOutput.pushEntry(cachedEntry);
            }
        }
    }
}
