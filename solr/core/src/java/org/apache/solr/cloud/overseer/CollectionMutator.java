/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud.overseer;

import static org.apache.solr.common.cloud.ZkStateReader.COLLECTION_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CONFIGNAME_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.NRT_REPLICAS;
import static org.apache.solr.common.cloud.ZkStateReader.REPLICATION_FACTOR;
import static org.apache.solr.common.params.CollectionAdminParams.COLL_CONF;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.solr.client.solrj.cloud.DistribStateManager;
import org.apache.solr.client.solrj.cloud.SolrCloudManager;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocCollection.CollectionStateProps;
import org.apache.solr.common.cloud.PerReplicaStates;
import org.apache.solr.common.cloud.PerReplicaStatesFetcher;
import org.apache.solr.common.cloud.PerReplicaStatesOps;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.Slice.SliceStateProps;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CollectionMutator {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected final SolrCloudManager cloudManager;
  protected final DistribStateManager stateManager;
  protected final SolrZkClient zkClient;

  public CollectionMutator(SolrCloudManager cloudManager) {
    this.cloudManager = cloudManager;
    this.stateManager = cloudManager.getDistribStateManager();
    this.zkClient = SliceMutator.getZkClient(cloudManager);
  }

  public ZkWriteCommand createShard(final ClusterState clusterState, ZkNodeProps message) {
    String collectionName = message.getStr(ZkStateReader.COLLECTION_PROP);
    if (!checkCollectionKeyExistence(message)) return ZkStateWriter.NO_OP;
    String shardId = message.getStr(ZkStateReader.SHARD_ID_PROP);
    DocCollection collection = clusterState.getCollection(collectionName);
    Slice slice = collection.getSlice(shardId);
    if (slice == null) {
      Map<String, Replica> replicas = Collections.emptyMap();
      Map<String, Object> sliceProps = new HashMap<>();
      String shardRange = message.getStr(ZkStateReader.SHARD_RANGE_PROP);
      String shardState = message.getStr(ZkStateReader.SHARD_STATE_PROP);
      String shardParent = message.getStr(ZkStateReader.SHARD_PARENT_PROP);
      String shardParentZkSession = message.getStr("shard_parent_zk_session");
      String shardParentNode = message.getStr("shard_parent_node");
      sliceProps.put(SliceStateProps.RANGE, shardRange);
      sliceProps.put(ZkStateReader.STATE_PROP, shardState);
      if (shardParent != null) {
        sliceProps.put(SliceStateProps.PARENT, shardParent);
      }
      if (shardParentZkSession != null) {
        sliceProps.put("shard_parent_zk_session", shardParentZkSession);
      }
      if (shardParentNode != null) {
        sliceProps.put("shard_parent_node", shardParentNode);
      }
      collection =
          updateSlice(
              collectionName, collection, new Slice(shardId, replicas, sliceProps, collectionName));
      return new ZkWriteCommand(collectionName, collection);
    } else {
      log.error(
          "Unable to create Shard: {} because it already exists in collection: {}",
          shardId,
          collectionName);
      return ZkStateWriter.NO_OP;
    }
  }

  public ZkWriteCommand deleteShard(final ClusterState clusterState, ZkNodeProps message) {
    final String sliceId = message.getStr(ZkStateReader.SHARD_ID_PROP);
    final String collection = message.getStr(ZkStateReader.COLLECTION_PROP);
    if (!checkCollectionKeyExistence(message)) return ZkStateWriter.NO_OP;

    log.info("Removing collection: {} shard: {}  from clusterstate", collection, sliceId);

    DocCollection coll = clusterState.getCollection(collection);

    Map<String, Slice> newSlices = new LinkedHashMap<>(coll.getSlicesMap());
    newSlices.remove(sliceId);

    DocCollection newCollection = coll.copyWithSlices(newSlices);
    return new ZkWriteCommand(collection, newCollection);
  }

  public ZkWriteCommand modifyCollection(final ClusterState clusterState, ZkNodeProps message) {
    if (!checkCollectionKeyExistence(message)) return ZkStateWriter.NO_OP;
    DocCollection coll = clusterState.getCollection(message.getStr(COLLECTION_PROP));
    Map<String, Object> props = coll.shallowCopy();
    boolean hasAnyOps = false;
    PerReplicaStatesOps replicaOps = null;
    for (String prop : CollectionAdminRequest.MODIFIABLE_COLLECTION_PROPERTIES) {
      if (prop.equals(CollectionStateProps.PER_REPLICA_STATE)) {
        String val = message.getStr(CollectionStateProps.PER_REPLICA_STATE);
        if (val == null) continue;
        boolean enable = Boolean.parseBoolean(val);
        if (enable == coll.isPerReplicaState()) {
          // already enabled
          log.error("trying to set perReplicaState to {} from {}", val, coll.isPerReplicaState());
          continue;
        }
        PerReplicaStates prs = PerReplicaStatesFetcher.fetch(coll.getZNode(), zkClient, null);
        replicaOps =
            enable ? PerReplicaStatesOps.enable(coll, prs) : PerReplicaStatesOps.disable(prs);
        if (!enable) {
          coll = updateReplicas(coll, prs);
        }
      }

      if (message.containsKey(prop)) {
        hasAnyOps = true;
        if (message.get(prop) == null) {
          props.remove(prop);
        } else {
          // rename key from collection.configName to configName
          if (prop.equals(COLL_CONF)) {
            props.put(CONFIGNAME_PROP, message.get(prop));
          } else {
            props.put(prop, message.get(prop));
          }
        }
        // SOLR-11676 : keep NRT_REPLICAS and REPLICATION_FACTOR in sync
        if (prop.equals(REPLICATION_FACTOR)) {
          props.put(NRT_REPLICAS, message.get(REPLICATION_FACTOR));
        }
      }
    }
    // other aux properties are also modifiable
    for (String prop : message.keySet()) {
      if (prop.startsWith(CollectionAdminRequest.PROPERTY_PREFIX)) {
        hasAnyOps = true;
        if (message.get(prop) == null) {
          props.remove(prop);
        } else {
          props.put(prop, message.get(prop));
        }
      }
    }

    if (!hasAnyOps) {
      return ZkStateWriter.NO_OP;
    }

    DocCollection collection =
        DocCollection.create(
            coll.getName(),
            coll.getSlicesMap(),
            props,
            coll.getRouter(),
            coll.getZNodeVersion(),
            stateManager.getPrsSupplier(coll.getName()));
    if (replicaOps == null) {
      return new ZkWriteCommand(coll.getName(), collection);
    } else {
      return new ZkWriteCommand(coll.getName(), collection, replicaOps, true);
    }
  }

  public static DocCollection updateReplicas(DocCollection coll, PerReplicaStates prs) {
    // we are disabling PRS. Update the replica states
    Map<String, Slice> modifiedSlices = new LinkedHashMap<>();
    coll.forEachReplica(
        (s, replica) -> {
          PerReplicaStates.State prsState = prs.states.get(replica.getName());
          if (prsState != null) {
            if (prsState.state != replica.getState()) {
              Slice slice =
                  modifiedSlices.getOrDefault(
                      replica.getShard(), coll.getSlice(replica.getShard()));
              replica = ReplicaMutator.setState(replica, prsState.state.toString());
              modifiedSlices.put(replica.getShard(), slice.copyWith(replica));
            }
            if (prsState.isLeader != replica.isLeader()) {
              Slice slice =
                  modifiedSlices.getOrDefault(
                      replica.getShard(), coll.getSlice(replica.getShard()));
              replica =
                  prsState.isLeader
                      ? ReplicaMutator.setLeader(replica)
                      : ReplicaMutator.unsetLeader(replica);
              modifiedSlices.put(replica.getShard(), slice.copyWith(replica));
            }
          }
        });

    if (!modifiedSlices.isEmpty()) {
      Map<String, Slice> slices = new LinkedHashMap<>(coll.getSlicesMap());
      slices.putAll(modifiedSlices);
      return coll.copyWithSlices(slices);
    }
    return coll;
  }

  public static DocCollection updateSlice(
      String collectionName, DocCollection collection, Slice slice) {
    Map<String, Slice> slices =
        new LinkedHashMap<>(collection.getSlicesMap()); // make a shallow copy
    slices.put(slice.getName(), slice);
    return collection.copyWithSlices(slices);
  }

  static boolean checkCollectionKeyExistence(ZkNodeProps message) {
    return checkKeyExistence(message, ZkStateReader.COLLECTION_PROP);
  }

  static boolean checkKeyExistence(ZkNodeProps message, String key) {
    String value = message.getStr(key);
    if (value == null || value.trim().length() == 0) {
      log.error(
          "Skipping invalid Overseer message because it has no {} specified '{}'", key, message);
      return false;
    }
    return true;
  }
}
