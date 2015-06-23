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

package org.apache.ignite.internal.processors.rest.request;

/**
 * Compute request.
 */
public class RestComputeRequest extends GridRestRequest {
    /** Java script function. */
    private String func;

    /** Cache name. */
    private String cacheName;

    /** Key. */
    private Object key;

    /**
     * @return Java script function.
     */
    public String function() {
        return func;
    }

    /**
     * @param func Java script function.
     */
    public void function(String func) {
        this.func = func;
    }

    /**
     * @return Cache name.
     */
    public String cacheName() {
        return cacheName;
    }

    /**
     * @param cacheName Cache name.
     */
    public void cacheName(String cacheName) {
        this.cacheName = cacheName;
    }

    /**
     * @param key Key.
     */
    public void key(Object key) {
        this.key = key;
    }

    /**
     * @return Key.
     */
    public Object key() {
        return key;
    }
}