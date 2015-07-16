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

package org.apache.spark.deploy.yarn

import scala.collection.mutable.ArrayBuffer

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.yarn.util.RackResolver

import org.apache.spark.SparkConf

private[yarn] case class ContainerLocalityPreferences(nodes: Array[String], racks: Array[String])

private[yarn] trait ContainerPlacementStrategy {

  /**
   * Calculate each container's node locality and rack locality
   * @param numContainer number of containers to calculate
   * @param numLocalityAwarePendingTasks number of locality required pending tasks
   * @param hostToLocalTaskCount a map to store the preferred hostname and possible task
   *                             numbers running on it, used as hints for container allocation
   * @return node localities and rack localities, each locality is an array of string,
   *         the length of localities is the same as number of containers
   */
  def localityOfRequestedContainers(
      numContainer: Int,
      numLocalityAwarePendingTasks: Int,
      hostToLocalTaskCount: Map[String, Int]
    ): Array[ContainerLocalityPreferences]
}

/**
 * This strategy is calculating the optimal locality preferences of YARN containers by considering
 * the node ratio of pending tasks, number of required cores/containers and and locality of current
 * existed containers. The target of this algorithm is to maximize the number of tasks that
 * would run locally.
 *
 * The details of this algorithm is described as below, if we have 20 tasks which
 * require (host1, host2, host3) and 10 tasks which require (host1, host2, host4),
 * besides each container has 2 cores and cpus per task is 1, so the required container number is
 * 15, and host ratio is (host1: 30, host2: 30, host3: 20, host4: 10).
 *
 * 1. If requested container number (18) is more than the required container number (15):
 *
 * requests for 5 containers with nodes: (host1, host2, host3, host4)
 * requests for 5 containers with nodes: (host1, host2, host3)
 * requests for 5 containers with nodes: (host1, host2)
 * requests for 3 containers with no locality preferences.
 *
 * The placement ratio is 3 : 3 : 2 : 1, and set the additional containers with no locality
 * preferences.
 *
 * 2. If requested container number (10) is less than or equal to the required container number
 * (15):
 *
 * requests for 4 containers with nodes: (host1, host2, host3, host4)
 * requests for 3 containers with nodes: (host1, host2, host3)
 * requests for 3 containers with nodes: (host1, host2)
 *
 * The placement ratio is 10 : 10 : 7 : 4, close to expected ratio (3 : 3 : 2 : 1)
 *
 * 3. If containers are existed but no matching localities, follow the method of 1 and 2.
 *
 * 4. If containers are existed and some localities are matched. For example if we have 1
 * containers on each node (host1: 1, host2: 1: host3: 1, host4: 1), and the expected containers
 * on each node would be (host1: 5, host2: 5, host3: 4, host4: 2),
 * so the newly requested containers on each node would be updated to (host1: 4, host2: 4,
 * host3: 3, host4: 1), 12 containers by total.
 *
 *   4.1 If requested container number (18) is more than newly required containers (12). Follow
 *   method 1 with updated ratio 4 : 4 : 3 : 1.
 *
 *   4.2 If request container number (10) is more than newly required containers (12). Follow
 *   method 2 with updated ratio 4 : 4 : 3 : 1.
 *
 * 5. If containers are existed and existing localities can fully cover the requested localities.
 * For example if we have 5 containers on each node (host1: 5, host2: 5, host3: 5, host4: 5),
 * which could cover the current requested localities. This algorithm will allocate all the
 * requested containers with no localities.
 */
private[yarn] class LocalityPreferredContainerPlacementStrategy(
    val sparkConf: SparkConf,
    val yarnConf: Configuration,
    val yarnAllocator: YarnAllocator) extends ContainerPlacementStrategy {

  // Number of CPUs per task
  private val CPUS_PER_TASK = sparkConf.getInt("spark.task.cpus", 1)

  /**
   * Calculate the number of executors need to satisfy the given number of pending tasks.
   */
  private def numExecutorsPending(numTasksPending: Int): Int = {
    val coresPerExecutor = yarnAllocator.resource.getVirtualCores
    (numTasksPending * CPUS_PER_TASK + coresPerExecutor -1) / coresPerExecutor
  }

  /**
   * Update the expected host to number of containers by considering with allocated containers.
   * @param localityAwarePendingTasks number of locality aware pending tasks
   * @param hostToLocalTaskCount a map to store the preferred hostname and possible task
   *                             numbers running on it, used as hints for container allocation
   * @return a map with hostname as key and required number of containers on this host as value
   */
  private def updateExpectedHostToContainerCount(
      localityAwarePendingTasks: Int,
      hostToLocalTaskCount: Map[String, Int]
    ): Map[String, Int] = {
    val totalLocalTaskNum = hostToLocalTaskCount.values.sum
    hostToLocalTaskCount.map { case (host, count) =>
      val expectedCount =
        count.toDouble * numExecutorsPending(localityAwarePendingTasks) / totalLocalTaskNum
      val existedCount = yarnAllocator.allocatedHostToContainersMap.get(host)
        .map(_.size)
        .getOrElse(0)

      // If existing container can not fully satisfy the expected number of container,
      // the required container number is expected count minus existed count. Otherwise the
      // required container number is 0.
      (host, math.max(0, (expectedCount - existedCount).ceil.toInt))
    }
  }

  /**
   * Calculate each container's node locality and rack locality
   * @param numContainer number of containers to calculate
   * @param numLocalityAwarePendingTasks number of locality required pending tasks
   * @param hostToLocalTaskCount a map to store the preferred hostname and possible task
   *                             numbers running on it, used as hints for container allocation
   * @return node localities and rack localities, each locality is an array of string,
   *         the length of localities is the same as number of containers
   */
  override def localityOfRequestedContainers(
      numContainer: Int,
      numLocalityAwarePendingTasks: Int,
      hostToLocalTaskCount: Map[String, Int]
    ): Array[ContainerLocalityPreferences] = {
    val updatedHostToContainerCount =
      updateExpectedHostToContainerCount(numLocalityAwarePendingTasks, hostToLocalTaskCount)
    val updatedLocalityAwareContainerNum = updatedHostToContainerCount.values.sum

    // The number of containers to allocate, divided into two groups, one with preferred locality,
    // and the other without locality preference.
    val requiredLocalityFreeContainerNum =
      math.max(0, numContainer - updatedLocalityAwareContainerNum)
    val requiredLocalityAwareContainerNum = numContainer - requiredLocalityFreeContainerNum

    val containerLocalityPreferences = ArrayBuffer[ContainerLocalityPreferences]()
    if (requiredLocalityFreeContainerNum > 0) {
      for (i <- 0 until requiredLocalityFreeContainerNum) {
        containerLocalityPreferences += ContainerLocalityPreferences(
          null.asInstanceOf[Array[String]], null.asInstanceOf[Array[String]])
      }
    }

    if (requiredLocalityAwareContainerNum > 0) {
      val largestRatio = updatedHostToContainerCount.values.max
      // Round the ratio of preferred locality to the number of locality required container
      // number, which is used for locality preferred host calculating.
      var preferredLocalityRatio = updatedHostToContainerCount.mapValues { ratio =>
        val adjustedRatio = ratio.toDouble * requiredLocalityAwareContainerNum / largestRatio
        adjustedRatio.ceil.toInt
      }

      for (i <- 0 until requiredLocalityAwareContainerNum) {
        // Only filter out the ratio which is larger than 0, which means the current host can
        // still be allocated with new container request.
        val hosts = preferredLocalityRatio.filter(_._2 > 0).keys.toArray
        val racks = hosts.map { h =>
          RackResolver.resolve(yarnConf, h).getNetworkLocation
        }.toSet
        containerLocalityPreferences += ContainerLocalityPreferences(hosts, racks.toArray)

        // Minus 1 each time when the host is used. When the current ratio is 0,
        // which means all the required ratio is satisfied, this host will not be allocated again.
        preferredLocalityRatio = preferredLocalityRatio.mapValues(_ - 1)
      }
    }

    containerLocalityPreferences.toArray
  }
}
