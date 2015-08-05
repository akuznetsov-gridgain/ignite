/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.*;
import org.apache.ignite.cache.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.spi.discovery.tcp.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.*;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.*;
import org.apache.ignite.testframework.junits.common.*;

import java.util.concurrent.atomic.*;

/**
 *
 */
public class GridCacheMassiveRebalancingSelfTest extends GridCommonAbstractTest {
    /** */
    private static TcpDiscoveryIpFinder ipFinder = new TcpDiscoveryVmIpFinder(true);

    private static int TEST_SIZE = 1_024_000;

    /** cache name. */
    protected static String CACHE_NAME_DHT = "cache";

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return Long.MAX_VALUE;
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration iCfg = super.getConfiguration(gridName);

        CacheConfiguration<Integer, Integer> cacheCfg = new CacheConfiguration<>();

        ((TcpDiscoverySpi)iCfg.getDiscoverySpi()).setIpFinder(ipFinder);
        ((TcpDiscoverySpi)iCfg.getDiscoverySpi()).setForceServerMode(true);

        if (getTestGridName(3).equals(gridName))
            iCfg.setClientMode(true);

        cacheCfg.setName(CACHE_NAME_DHT);
        cacheCfg.setCacheMode(CacheMode.PARTITIONED);
        cacheCfg.setRebalanceBatchSize(100 * 1024);
        cacheCfg.setRebalanceMode(CacheRebalanceMode.SYNC);
        cacheCfg.setRebalanceThreadPoolSize(4);
        //cacheCfg.setRebalanceTimeout(1000000);
        cacheCfg.setBackups(1);

        iCfg.setCacheConfiguration(cacheCfg);
        return iCfg;
    }

    /**
     * @param ignite Ignite.
     */
    private void generateData(Ignite ignite) {
        try (IgniteDataStreamer<Integer, Integer> stmr = ignite.dataStreamer(CACHE_NAME_DHT)) {
            for (int i = 0; i < TEST_SIZE; i++) {
                if (i % 1_000_000 == 0)
                    log.info("Prepared " + i / 1_000_000 + "m entries.");

                stmr.addData(i, i);
            }
        }
    }

    /**
     * @param ignite Ignite.
     * @throws IgniteCheckedException
     */
    private void checkData(Ignite ignite) throws IgniteCheckedException {
        for (int i = 0; i < TEST_SIZE; i++) {
            if (i % 1_000_000 == 0)
                log.info("Checked " + i / 1_000_000 + "m entries.");

            assert ignite.cache(CACHE_NAME_DHT).get(i).equals(i) : "keys " + i + " does not match";
        }
    }

    /**
     * @throws Exception
     */
    public void testMassiveRebalancing() throws Exception {
        Ignite ignite = startGrid(0);

        generateData(ignite);

        log.info("Preloading started.");

        long start = System.currentTimeMillis();

        //startGrid(1);

        startGrid(2);

        long spend = (System.currentTimeMillis() - start) / 1000;

        stopGrid(0);

        //Thread.sleep(20000);

        //stopGrid(1);

        checkData(grid(2));

        log.info("Spend " + spend + " seconds to preload entries.");

        stopAllGrids();
    }

    /**
     * @throws Exception
     */
    public void testOpPerSecRebalancingTest() throws Exception {
        startGrid(0);

        final AtomicBoolean cancelled = new AtomicBoolean(false);

        generateData(grid(0));

        startGrid(1);
        startGrid(2);
        startGrid(3);

        Thread t = new Thread(new Runnable() {
            @Override public void run() {

                long spend = 0;

                long ops = 0;

                while (!cancelled.get()) {
                    try {
                        long start = System.currentTimeMillis();

                        int size = 1000;

                        for (int i = 0; i < size; i++)
                            grid(3).cachex(CACHE_NAME_DHT).remove(i);

                        for (int i = 0; i < size; i++)
                            grid(3).cachex(CACHE_NAME_DHT).put(i, i);

                        spend += System.currentTimeMillis() - start;

                        ops += size * 2;
                    }
                    catch (IgniteCheckedException e) {
                        e.printStackTrace();
                    }

                    log.info("Ops. per ms: " + ops / spend);
                }
            }
        });
        t.start();

        stopGrid(0);
        startGrid(0);

        stopGrid(0);
        startGrid(0);

        stopGrid(0);
        startGrid(0);

        cancelled.set(true);
        t.join();

        checkData(grid(3));

        //stopAllGrids();
    }
}