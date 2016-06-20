/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.executor

import org.apache.spark.SparkEnv
import org.apache.spark.smstorage.worker.ProcfsBasedGetter
import org.apache.spark.util.Utils

import scala.collection.JavaConversions._

import com.codahale.metrics.{Gauge, MetricRegistry}
import org.apache.hadoop.fs.FileSystem

import org.apache.spark.metrics.source.Source

private[spark] class ExecutorSource(val executor: Executor, executorId: String) extends Source {

  val MB = 1024 * 1024

  val pid = Utils.getJvmId()

  /**
   * Runtime.getRuntime().maxMemory不一定准确，所以修改为从配置中进行读取
   */
  val maxMemoryMb =
    (Utils.memoryStringToMb(SparkEnv.get.conf.get("spark.executor.memory")).toLong * MB).toDouble

  private def fileStats(scheme: String) : Option[FileSystem.Statistics] =
    FileSystem.getAllStatistics().filter(s => s.getScheme.equals(scheme)).headOption

  private def registerFileSystemStat[T](
        scheme: String, name: String, f: FileSystem.Statistics => T, defaultValue: T) = {
    metricRegistry.register(MetricRegistry.name("filesystem", scheme, name), new Gauge[T] {
      override def getValue: T = fileStats(scheme).map(f).getOrElse(defaultValue)
    })
  }

  override val metricRegistry = new MetricRegistry()

  override val sourceName = "executor"

  // 增加了Executor实际内存使用率输出
  metricRegistry.register(MetricRegistry.name("memory", "memoryUsedRate"), new Gauge[Int] {
    override def getValue: Double =
      ProcfsBasedGetter.getProcessRss(pid) / maxMemoryMb
  })

  // Gauge for executor thread pool's actively executing task counts
  metricRegistry.register(MetricRegistry.name("threadpool", "activeTasks"), new Gauge[Int] {
    override def getValue: Int = executor.threadPool.getActiveCount()
  })

  // Gauge for executor thread pool's approximate total number of tasks that have been completed
  metricRegistry.register(MetricRegistry.name("threadpool", "completeTasks"), new Gauge[Long] {
    override def getValue: Long = executor.threadPool.getCompletedTaskCount()
  })

  // Gauge for executor thread pool's current number of threads
  metricRegistry.register(MetricRegistry.name("threadpool", "currentPool_size"), new Gauge[Int] {
    override def getValue: Int = executor.threadPool.getPoolSize()
  })

  // Gauge got executor thread pool's largest number of threads that have ever simultaneously
  // been in th pool
  metricRegistry.register(MetricRegistry.name("threadpool", "maxPool_size"), new Gauge[Int] {
    override def getValue: Int = executor.threadPool.getMaximumPoolSize()
  })

  // Gauge for file system stats of this executor
  for (scheme <- Array("hdfs", "file")) {
    registerFileSystemStat(scheme, "read_bytes", _.getBytesRead(), 0L)
    registerFileSystemStat(scheme, "write_bytes", _.getBytesWritten(), 0L)
    registerFileSystemStat(scheme, "read_ops", _.getReadOps(), 0)
    registerFileSystemStat(scheme, "largeRead_ops", _.getLargeReadOps(), 0)
    registerFileSystemStat(scheme, "write_ops", _.getWriteOps(), 0)
  }
}
