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

package org.apache.ignite.internal;

import org.apache.ignite.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.plugin.*;
import org.jetbrains.annotations.*;
import org.jsr166.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Marshaller context adapter.
 */
public abstract class MarshallerContextAdapter implements MarshallerContext {
    /** */
    private static final String CLS_NAMES_FILE = "META-INF/classnames.properties";

    /** */
    private static final String JDK_CLS_NAMES_FILE = "META-INF/classnames-jdk.properties";

    /** */
    private final ConcurrentMap<Integer, String> map = new ConcurrentHashMap8<>();

    /** */
    private final Set<String> registeredSystemTypes = new HashSet<>();

    /**
     * Initializes context.
     *
     * @param plugins Plugins.
     */
    public MarshallerContextAdapter(@Nullable List<PluginProvider> plugins) {
        try {
            ClassLoader ldr = U.gridClassLoader();

            Enumeration<URL> urls = ldr.getResources(CLS_NAMES_FILE);

            boolean foundClsNames = false;

            while (urls.hasMoreElements()) {
                processResource(urls.nextElement());

                foundClsNames = true;
            }

            if (!foundClsNames)
                throw new IgniteException("Failed to load class names properties file packaged with ignite binaries " +
                    "[file=" + CLS_NAMES_FILE + ", ldr=" + ldr + ']');

            URL jdkClsNames = ldr.getResource(JDK_CLS_NAMES_FILE);

            if (jdkClsNames == null)
                throw new IgniteException("Failed to load class names properties file packaged with ignite binaries " +
                    "[file=" + JDK_CLS_NAMES_FILE + ", ldr=" + ldr + ']');

            processResource(jdkClsNames);

            checkHasClassName(GridDhtPartitionFullMap.class.getName(), ldr, CLS_NAMES_FILE);
            checkHasClassName(GridDhtPartitionMap.class.getName(), ldr, CLS_NAMES_FILE);
            checkHasClassName(HashMap.class.getName(), ldr, JDK_CLS_NAMES_FILE);

            if (plugins != null && !plugins.isEmpty()) {
                for (PluginProvider plugin : plugins) {
                    URL pluginClsNames = ldr.getResource("META-INF/" + plugin.name().toLowerCase()
                        + ".classnames.properties");

                    if (pluginClsNames != null)
                        processResource(pluginClsNames);
                }
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to initialize marshaller context.", e);
        }
    }

    /**
     * @param clsName Class name.
     * @param ldr Class loader used to get properties file.
     * @param fileName File name.
     */
    public void checkHasClassName(String clsName, ClassLoader ldr, String fileName) {
        if (!map.containsKey(clsName.hashCode()))
            throw new IgniteException("Failed to read class name from class names properties file. " +
                "Make sure class names properties file packaged with ignite binaries is not corrupted " +
                "[clsName=" + clsName + ", fileName=" + fileName + ", ldr=" + ldr + ']');
    }

    /**
     * @param url Resource URL.
     * @throws IOException In case of error.
     */
    private void processResource(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            BufferedReader rdr = new BufferedReader(new InputStreamReader(in));

            String line;

            while ((line = rdr.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                String clsName = line.trim();

                int typeId = clsName.hashCode();

                String oldClsName;

                if ((oldClsName = map.put(typeId, clsName)) != null) {
                    if (!oldClsName.equals(clsName))
                        throw new IgniteException("Duplicate type ID [id=" + typeId + ", clsName=" + clsName +
                        ", oldClsName=" + oldClsName + ']');
                }

                registeredSystemTypes.add(clsName);
            }
        }
    }

    /** {@inheritDoc} */
    @Override public boolean registerClass(int id, Class cls) throws IgniteCheckedException {
        boolean registered = true;

        String clsName = map.get(id);

        if (clsName == null) {
            registered = registerClassName(id, cls.getName());

            if (registered)
                map.putIfAbsent(id, cls.getName());
        }
        else if (!clsName.equals(cls.getName()))
            throw new IgniteCheckedException("Duplicate ID [id=" + id + ", oldCls=" + clsName +
                ", newCls=" + cls.getName());

        return registered;
    }

    /** {@inheritDoc} */
    @Override public Class getClass(int id, ClassLoader ldr) throws ClassNotFoundException, IgniteCheckedException {
        String clsName = map.get(id);

        if (clsName == null) {
            clsName = className(id);

            if (clsName == null)
                throw new ClassNotFoundException("Unknown type ID: " + id);

            String old = map.putIfAbsent(id, clsName);

            if (old != null)
                clsName = old;
        }

        return U.forName(clsName, ldr);
    }

    /** {@inheritDoc} */
    @Override public boolean isSystemType(String typeName) {
        return registeredSystemTypes.contains(typeName);
    }

    /**
     * Registers class name.
     *
     * @param id Type ID.
     * @param clsName Class name.
     * @return Whether class name was registered.
     * @throws IgniteCheckedException In case of error.
     */
    protected abstract boolean registerClassName(int id, String clsName) throws IgniteCheckedException;

    /**
     * Gets class name by type ID.
     *
     * @param id Type ID.
     * @return Class name.
     * @throws IgniteCheckedException In case of error.
     */
    protected abstract String className(int id) throws IgniteCheckedException;
}
