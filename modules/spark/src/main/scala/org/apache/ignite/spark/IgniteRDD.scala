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
package org.apache.ignite.spark

import javax.cache.Cache

import org.apache.ignite.cache.query._
import org.apache.ignite.cluster.ClusterNode
import org.apache.ignite.configuration.CacheConfiguration
import org.apache.ignite.internal.processors.cache.query.QueryCursorEx
import org.apache.ignite.internal.processors.query.GridQueryFieldMetadata
import org.apache.ignite.lang.IgniteUuid
import org.apache.ignite.spark.impl._
import org.apache.ignite.spi.discovery.tcp.internal.TcpDiscoveryNode
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.spark.sql._
import org.apache.spark._

import scala.collection.JavaConversions._

/**
 * Ignite RDD. Represents Ignite cache as Spark RDD abstraction.
 *
 * @param ic Ignite context to use.
 * @param cacheName Cache name.
 * @param cacheCfg Cache configuration.
 * @tparam K Key type.
 * @tparam V Value type.
 */
class IgniteRDD[K, V] (
    val ic: IgniteContext[K, V],
    val cacheName: String,
    val cacheCfg: CacheConfiguration[K, V]
) extends IgniteAbstractRDD[(K, V), K, V] (ic, cacheName, cacheCfg) {
    /**
     * Computes iterator based on given partition.
     *
     * @param part Partition to use.
     * @param context Task context.
     * @return Partition iterator.
     */
    override def compute(part: Partition, context: TaskContext): Iterator[(K, V)] = {
        val cache = ensureCache()

        val qry: ScanQuery[K, V] = new ScanQuery[K, V](part.index)

        val partNodes = ic.ignite().affinity(cache.getName).mapPartitionToPrimaryAndBackups(part.index)

        val it: java.util.Iterator[Cache.Entry[K, V]] = cache.query(qry).iterator()

        new IgniteQueryIterator[Cache.Entry[K, V], (K, V)](it, entry ⇒ {
            (entry.getKey, entry.getValue)
        })
    }

    /**
     * Gets partitions for the given cache RDD.
     *
     * @return Partitions.
     */
    override protected[spark] def getPartitions: Array[Partition] = {
        ensureCache()

        val parts = ic.ignite().affinity(cacheName).partitions()

        (0 until parts).map(new IgnitePartition(_)).toArray
    }

    /**
     * Gets prefferred locations for the given partition.
     *
     * @param split Split partition.
     * @return
     */
    override protected[spark] def getPreferredLocations(split: Partition): Seq[String] = {
        ensureCache()

        ic.ignite().affinity(cacheName).mapPartitionToPrimaryAndBackups(split.index)
            .map(_.asInstanceOf[TcpDiscoveryNode].socketAddresses()).flatten.map(_.getHostName).toList
    }

    def objectSql(typeName: String, sql: String, args: Any*): RDD[(K, V)] = {
        val qry: SqlQuery[K, V] = new SqlQuery[K, V](typeName, sql)

        qry.setArgs(args.map(_.asInstanceOf[Object]):_*)

        new IgniteSqlRDD[(K, V), Cache.Entry[K, V], K, V](ic, cacheName, cacheCfg, qry, entry ⇒ (entry.getKey, entry.getValue))
    }

    def sql(sql: String, args: Any*): DataFrame = {
        val qry = new SqlFieldsQuery(sql)

        qry.setArgs(args.map(_.asInstanceOf[Object]):_*)

        val schema = buildSchema(ensureCache().query(qry).asInstanceOf[QueryCursorEx[java.util.List[_]]].fieldsMeta())

        val rowRdd = new IgniteSqlRDD[Row, java.util.List[_], K, V](ic, cacheName, cacheCfg, qry, list ⇒ Row.fromSeq(list))

        ic.sqlContext.createDataFrame(rowRdd, schema)
    }

    def saveValues(rdd: RDD[V], overwrite: Boolean = false) = {
        rdd.foreachPartition(it ⇒ {
            val ig = ic.ignite()

            ensureCache()

            val locNode = ig.cluster().localNode()

            val node: Option[ClusterNode] = ig.cluster().forHost(locNode).nodes().find(!_.eq(locNode))

            val streamer = ig.dataStreamer[Object, V](cacheName)

            try {
                streamer.allowOverwrite(overwrite)

                it.foreach(value ⇒ {
                    val key = affinityKeyFunc(value, node.orNull)

                    streamer.addData(key, value)
                })
            }
            finally {
                streamer.close()
            }
        })
    }

    def savePairs(rdd: RDD[(K, V)], overwrite: Boolean = false) = {
        rdd.foreachPartition(it ⇒ {
            val ig = ic.ignite()

            // Make sure to deploy the cache
            ensureCache()

            val streamer = ig.dataStreamer[K, V](cacheName)

            try {
                streamer.allowOverwrite(overwrite)

                it.foreach(tup ⇒ {
                    streamer.addData(tup._1, tup._2)
                })
            }
            finally {
                streamer.close()
            }
        })
    }

    def clear(): Unit = {
        ensureCache().removeAll()
    }

    /**
     * Builds spark schema from query metadata.
     *
     * @param fieldsMeta Fields metadata.
     * @return Spark schema.
     */
    private def buildSchema(fieldsMeta: java.util.List[GridQueryFieldMetadata]): StructType = {
        new StructType(fieldsMeta.map(i ⇒ new StructField(i.fieldName(), dataType(i.fieldTypeName()), nullable = true))
            .toArray)
    }

    /**
     * Gets Spark data type based on type name.
     *
     * @param typeName Type name.
     * @return Spark data type.
     */
    private def dataType(typeName: String): DataType = typeName match {
        case "java.lang.Boolean" ⇒ BooleanType
        case "java.lang.Byte" ⇒ ByteType
        case "java.lang.Short" ⇒ ShortType
        case "java.lang.Integer" ⇒ IntegerType
        case "java.lang.Long" ⇒ LongType
        case "java.lang.Float" ⇒ FloatType
        case "java.lang.Double" ⇒ DoubleType
        case "java.lang.String" ⇒ StringType
        case "java.util.Date" ⇒ DateType
        case "java.sql.Timestamp" ⇒ TimestampType
        case "[B" ⇒ BinaryType

        case _ ⇒ StructType(new Array[StructField](0)) // TODO Do we need to fill user types?
    }

    /**
     * Generates affinity key for given cluster node.
     *
     * @param value Value to generate key for.
     * @param node Node to generate key for.
     * @return Affinity key.
     */
    private def affinityKeyFunc(value: V, node: ClusterNode): IgniteUuid = {
        val aff = ic.ignite().affinity[IgniteUuid](cacheName)

        Stream.continually(IgniteUuid.randomUuid()).find(node == null || aff.mapKeyToNode(_).eq(node)).get
    }
}