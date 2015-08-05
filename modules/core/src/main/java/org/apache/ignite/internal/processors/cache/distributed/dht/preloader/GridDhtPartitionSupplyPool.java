/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed.dht.preloader;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.internal.cluster.*;
import org.apache.ignite.internal.managers.deployment.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.jsr166.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.*;

import static org.apache.ignite.internal.GridTopic.*;
import static org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionState.*;

/**
 * Thread pool for supplying partitions to demanding nodes.
 */
class GridDhtPartitionSupplyPool {
    /** */
    private final GridCacheContext<?, ?> cctx;

    /** */
    private final IgniteLogger log;

    /** */
    private GridDhtPartitionTopology top;

    /** */
    private final boolean depEnabled;

    /** Preload predicate. */
    private IgnitePredicate<GridCacheEntryInfo> preloadPred;

    /** Supply context map. */
    private ConcurrentHashMap8<T2, SupplyContext> scMap = new ConcurrentHashMap8<>();

    /** Done map. */
    private ConcurrentHashMap8<T2, Boolean> doneMap = new ConcurrentHashMap8<>();//Todo: refactor

    /**
     * @param cctx Cache context.
     * @param busyLock Shutdown lock.
     */
    GridDhtPartitionSupplyPool(GridCacheContext<?, ?> cctx, ReadWriteLock busyLock) {
        assert cctx != null;
        assert busyLock != null;

        this.cctx = cctx;

        log = cctx.logger(getClass());

        top = cctx.dht().topology();

        if (!cctx.kernalContext().clientNode()) {
            for (int p = 0; p <= cctx.affinity().partitions(); p++)
                cctx.io().addOrderedHandler(topic(p, cctx.cacheId()), new CI2<UUID, GridDhtPartitionDemandMessage>() {
                    @Override public void apply(UUID id, GridDhtPartitionDemandMessage m) {
                        processMessage(m, id);
                    }
                });
        }

        depEnabled = cctx.gridDeploy().enabled();
    }

    /**
     * @param p Partition.
     * @param id Node id.
     * @return topic
     */
    static Object topic(int p, int id) {
        return TOPIC_CACHE.topic("SupplyPool", id, p);
    }

    /**
     *
     */
    void start() {
    }

    /**
     *
     */
    void stop() {
        top = null;
    }

    /**
     * Sets preload predicate for supply pool.
     *
     * @param preloadPred Preload predicate.
     */
    void preloadPredicate(IgnitePredicate<GridCacheEntryInfo> preloadPred) {
        this.preloadPred = preloadPred;
    }

    /**
     * @param d Demand message.
     * @param id Node uuid.
     */
    private void processMessage(GridDhtPartitionDemandMessage d, UUID id) {
        assert d != null;
        assert id != null;

        GridDhtPartitionSupplyMessage s = new GridDhtPartitionSupplyMessage(d.workerId(),
            d.updateSequence(), cctx.cacheId());

        long preloadThrottle = cctx.config().getRebalanceThrottle();

        long maxBatchesCnt = 3;//Todo: param

        ClusterNode node = cctx.discovery().node(id);

        boolean ack = false;

        T2<UUID, Object> scId = new T2<>(id, d.topic());

        try {
            SupplyContext sctx = scMap.remove(scId);

            if (doneMap.get(scId) != null)//Todo: refactor
                return;

            long bCnt = 0;

            int phase = 0;

            if (sctx != null)
                phase = sctx.phase;

            Iterator<Integer> partIt = sctx != null ? sctx.partIt : d.partitions().iterator();

            while (sctx != null || partIt.hasNext()) {
                int part = sctx != null ? sctx.part : partIt.next();

                GridDhtLocalPartition loc = top.localPartition(part, d.topologyVersion(), false);

                if (loc == null || loc.state() != OWNING || !loc.reserve()) {
                    // Reply with partition of "-1" to let sender know that
                    // this node is no longer an owner.
                    s.missed(part);

                    if (log.isDebugEnabled())
                        log.debug("Requested partition is not owned by local node [part=" + part +
                            ", demander=" + id + ']');

                    continue;
                }

                GridCacheEntryInfoCollectSwapListener swapLsnr = null;

                try {
                    if (phase == 0 && cctx.isSwapOrOffheapEnabled()) {
                        swapLsnr = new GridCacheEntryInfoCollectSwapListener(log);

                        cctx.swap().addOffHeapListener(part, swapLsnr);
                        cctx.swap().addSwapListener(part, swapLsnr);
                    }

                    boolean partMissing = false;

                    if (phase == 0)
                        phase = 1;

                    if (phase == 1) {
                        Iterator<GridDhtCacheEntry> entIt = sctx != null ?
                            (Iterator<GridDhtCacheEntry>)sctx.entryIt : loc.entries().iterator();

                        while (entIt.hasNext()) {
                            if (!cctx.affinity().belongs(node, part, d.topologyVersion())) {
                                // Demander no longer needs this partition, so we send '-1' partition and move on.
                                s.missed(part);

                                if (log.isDebugEnabled())
                                    log.debug("Demanding node does not need requested partition [part=" + part +
                                        ", nodeId=" + id + ']');

                                partMissing = true;

                                break;
                            }

                            if (s.messageSize() >= cctx.config().getRebalanceBatchSize()) {
                                ack = true;

                                if (!reply(node, d, s))
                                    return;

                                // Throttle preloading.
                                if (preloadThrottle > 0)
                                    U.sleep(preloadThrottle);

                                if (++bCnt >= maxBatchesCnt) {
                                    saveSupplyContext(scId, phase, partIt, part, entIt, swapLsnr);

                                    swapLsnr = null;

                                    return;
                                }
                                else {
                                    s = new GridDhtPartitionSupplyMessage(d.workerId(), d.updateSequence(),
                                        cctx.cacheId());
                                }
                            }

                            GridCacheEntryEx e = entIt.next();

                            GridCacheEntryInfo info = e.info();

                            if (info != null && !info.isNew()) {
                                if (preloadPred == null || preloadPred.apply(info))
                                    s.addEntry(part, info, cctx);
                                else if (log.isDebugEnabled())
                                    log.debug("Rebalance predicate evaluated to false (will not sender cache entry): " +
                                        info);
                            }
                        }

                        if (partMissing)
                            continue;

                    }

                    if (phase == 1)
                        phase = 2;

                    if (phase == 2 && cctx.isSwapOrOffheapEnabled()) {
                        GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>> iter = sctx != null ?
                            (GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>>)sctx.entryIt :
                            cctx.swap().iterator(part);

                        // Iterator may be null if space does not exist.
                        if (iter != null) {
                            try {
                                boolean prepared = false;

                                while (iter.hasNext()) {
                                    if (!cctx.affinity().belongs(node, part, d.topologyVersion())) {
                                        // Demander no longer needs this partition,
                                        // so we send '-1' partition and move on.
                                        s.missed(part);

                                        if (log.isDebugEnabled())
                                            log.debug("Demanding node does not need requested partition " +
                                                "[part=" + part + ", nodeId=" + id + ']');

                                        partMissing = true;

                                        break; // For.
                                    }

                                    if (s.messageSize() >= cctx.config().getRebalanceBatchSize()) {
                                        ack = true;

                                        if (!reply(node, d, s))
                                            return;

                                        // Throttle preloading.
                                        if (preloadThrottle > 0)
                                            U.sleep(preloadThrottle);

                                        if (++bCnt >= maxBatchesCnt) {
                                            saveSupplyContext(scId, phase, partIt, part, iter, swapLsnr);

                                            swapLsnr = null;

                                            return;
                                        }
                                        else {
                                            s = new GridDhtPartitionSupplyMessage(d.workerId(), d.updateSequence(),
                                                cctx.cacheId());
                                        }
                                    }

                                    Map.Entry<byte[], GridCacheSwapEntry> e = iter.next();

                                    GridCacheSwapEntry swapEntry = e.getValue();

                                    GridCacheEntryInfo info = new GridCacheEntryInfo();

                                    info.keyBytes(e.getKey());
                                    info.ttl(swapEntry.ttl());
                                    info.expireTime(swapEntry.expireTime());
                                    info.version(swapEntry.version());
                                    info.value(swapEntry.value());

                                    if (preloadPred == null || preloadPred.apply(info))
                                        s.addEntry0(part, info, cctx);
                                    else {
                                        if (log.isDebugEnabled())
                                            log.debug("Rebalance predicate evaluated to false (will not send " +
                                                "cache entry): " + info);

                                        continue;
                                    }

                                    // Need to manually prepare cache message.
                                    if (depEnabled && !prepared) {
                                        ClassLoader ldr = swapEntry.keyClassLoaderId() != null ?
                                            cctx.deploy().getClassLoader(swapEntry.keyClassLoaderId()) :
                                            swapEntry.valueClassLoaderId() != null ?
                                                cctx.deploy().getClassLoader(swapEntry.valueClassLoaderId()) :
                                                null;

                                        if (ldr == null)
                                            continue;

                                        if (ldr instanceof GridDeploymentInfo) {
                                            s.prepare((GridDeploymentInfo)ldr);

                                            prepared = true;
                                        }
                                    }
                                }

                                if (partMissing)
                                    continue;
                            }
                            finally {
                                iter.close();
                            }
                        }
                    }

                    if (swapLsnr == null && sctx != null)
                        swapLsnr = sctx.swapLsnr;

                    // Stop receiving promote notifications.
                    if (swapLsnr != null) {
                        cctx.swap().removeOffHeapListener(part, swapLsnr);
                        cctx.swap().removeSwapListener(part, swapLsnr);
                    }

                    if (phase == 2)
                        phase = 3;

                    if (phase == 3 && swapLsnr != null) {
                        Collection<GridCacheEntryInfo> entries = swapLsnr.entries();

                        swapLsnr = null;

                        Iterator<GridCacheEntryInfo> lsnrIt = sctx != null ?
                            (Iterator<GridCacheEntryInfo>)sctx.entryIt : entries.iterator();

                        while (lsnrIt.hasNext()) {
                            if (!cctx.affinity().belongs(node, part, d.topologyVersion())) {
                                // Demander no longer needs this partition,
                                // so we send '-1' partition and move on.
                                s.missed(part);

                                if (log.isDebugEnabled())
                                    log.debug("Demanding node does not need requested partition " +
                                        "[part=" + part + ", nodeId=" + id + ']');

                                // No need to continue iteration over swap entries.
                                break;
                            }

                            if (s.messageSize() >= cctx.config().getRebalanceBatchSize()) {
                                ack = true;

                                if (!reply(node, d, s))
                                    return;

                                // Throttle preloading.
                                if (preloadThrottle > 0)
                                    U.sleep(preloadThrottle);

                                if (++bCnt >= maxBatchesCnt) {
                                    saveSupplyContext(scId, phase, partIt, part, lsnrIt, swapLsnr);

                                    return;
                                }
                                else {
                                    s = new GridDhtPartitionSupplyMessage(d.workerId(), d.updateSequence(),
                                        cctx.cacheId());
                                }
                            }

                            GridCacheEntryInfo info = lsnrIt.next();

                            if (preloadPred == null || preloadPred.apply(info))
                                s.addEntry(part, info, cctx);
                            else if (log.isDebugEnabled())
                                log.debug("Rebalance predicate evaluated to false (will not sender cache entry): " +
                                    info);
                        }
                    }

                    // Mark as last supply message.
                    s.last(part);

                    if (ack) {
                        s.markAck();

                        break; // Partition for loop.
                    }
                }
                finally {
                    loc.release();

                    if (swapLsnr != null) {
                        cctx.swap().removeOffHeapListener(part, swapLsnr);
                        cctx.swap().removeSwapListener(part, swapLsnr);
                    }
                }
            }

            reply(node, d, s);

            doneMap.put(scId, true);
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send partition supply message to node: " + id, e);
        }
    }

    /**
     * @param n Node.
     * @param d Demand message.
     * @param s Supply message.
     * @return {@code True} if message was sent, {@code false} if recipient left grid.
     * @throws IgniteCheckedException If failed.
     */
    private boolean reply(ClusterNode n, GridDhtPartitionDemandMessage d, GridDhtPartitionSupplyMessage s)
        throws IgniteCheckedException {
        try {
            if (log.isDebugEnabled())
                log.debug("Replying to partition demand [node=" + n.id() + ", demand=" + d + ", supply=" + s + ']');

            cctx.io().sendOrderedMessage(n, d.topic(), s, cctx.ioPolicy(), d.timeout());

            return true;
        }
        catch (ClusterTopologyCheckedException ignore) {
            if (log.isDebugEnabled())
                log.debug("Failed to send partition supply message because node left grid: " + n.id());

            return false;
        }
    }


    /**
     * Demand message wrapper.
     */
    private static class DemandMessage extends IgniteBiTuple<UUID, GridDhtPartitionDemandMessage> {
        /** */
        private static final long serialVersionUID = 0L;

        /**
         * @param sndId Sender ID.
         * @param msg Message.
         */
        DemandMessage(UUID sndId, GridDhtPartitionDemandMessage msg) {
            super(sndId, msg);
        }

        /**
         * Empty constructor required for {@link Externalizable}.
         */
        public DemandMessage() {
            // No-op.
        }

        /**
         * @return Sender ID.
         */
        UUID senderId() {
            return get1();
        }

        /**
         * @return Message.
         */
        public GridDhtPartitionDemandMessage message() {
            return get2();
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "DemandMessage [senderId=" + senderId() + ", msg=" + message() + ']';
        }
    }


    /**
     * @param t T.
     * @param phase Phase.
     * @param partIt Partition it.
     * @param entryIt Entry it.
     * @param swapLsnr Swap listener.
     */
    private void saveSupplyContext(
        T2 t,
        int phase,
        Iterator<Integer> partIt,
        int part,
        Iterator<?> entryIt, GridCacheEntryInfoCollectSwapListener swapLsnr){
        scMap.put(t, new SupplyContext(phase, partIt, entryIt, swapLsnr, part));
    }

    private static class SupplyContext{
        private int phase;

        private Iterator<Integer> partIt;

        private Iterator<?> entryIt;

        private GridCacheEntryInfoCollectSwapListener swapLsnr;

        int part;

        public SupplyContext(int phase, Iterator<Integer> partIt, Iterator<?> entryIt,
            GridCacheEntryInfoCollectSwapListener swapLsnr, int part) {
            this.phase = phase;
            this.partIt = partIt;
            this.entryIt = entryIt;
            this.swapLsnr = swapLsnr;
            this.part = part;
        }
    }
}
