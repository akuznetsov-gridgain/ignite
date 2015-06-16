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

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.cache.store.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import javax.cache.configuration.*;
import javax.cache.integration.*;

/**
 *
 */
class GridCacheLoaderWriterStoreFactory<K, V> implements Factory<CacheStore<K, V>> {
    /** */
    private static final long serialVersionUID = 0L;

    /** */
    private final GridKernalContext cctx;

    /** */
    private final Factory<CacheLoader<K, V>> ldrFactory;

    /** */
    private final Factory<CacheWriter<K, V>> writerFactory;

    /**
     * @param ldrFactory Loader factory.
     * @param writerFactory Writer factory.
     */
    GridCacheLoaderWriterStoreFactory(GridKernalContext cctx, @Nullable Factory<CacheLoader<K, V>> ldrFactory,
        @Nullable Factory<CacheWriter<K, V>> writerFactory) {
        this.cctx = cctx;
        this.ldrFactory = ldrFactory;
        this.writerFactory = writerFactory;

        assert ldrFactory != null || writerFactory != null;
    }

    /** {@inheritDoc} */
    @Override public CacheStore<K, V> create() {
        CacheLoader<K, V> ldr = CU.create(cctx, ldrFactory);
        CacheWriter<K, V> writer = CU.create(cctx, writerFactory);

        return new GridCacheLoaderWriterStore<>(ldr, writer);
    }
}
