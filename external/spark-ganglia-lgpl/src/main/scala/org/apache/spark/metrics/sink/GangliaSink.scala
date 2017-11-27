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

package org.apache.spark.metrics.sink

import java.util.{Locale, Properties}
import java.util.concurrent.TimeUnit

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.ganglia.GangliaReporter
import info.ganglia.gmetric4j.gmetric.GMetric
import info.ganglia.gmetric4j.gmetric.GMetric.UDPAddressingMode

import org.apache.spark.SecurityManager
import org.apache.spark.metrics.MetricsSystem

class GangliaSink(
    property: Properties,
    registry: MetricRegistry,
    securityMgr: SecurityManager) extends Sink(property, registry) {
  val GANGLIA_KEY_MODE = "mode"
  val GANGLIA_DEFAULT_MODE: UDPAddressingMode = GMetric.UDPAddressingMode.MULTICAST

  // TTL for multicast messages. If listeners are X hops away in network, must be at least X.
  val GANGLIA_KEY_TTL = "ttl"
  val GANGLIA_DEFAULT_TTL = 1

  val GANGLIA_KEY_HOST = "host"
  val GANGLIA_KEY_PORT = "port"

  val GANGLIA_KEY_DMAX = "dmax"
  val GANGLIA_DEFAULT_DMAX = 0

  def propertyToOption(prop: String): Option[String] = Option(property.getProperty(prop))

  if (!propertyToOption(GANGLIA_KEY_HOST).isDefined) {
    throw new Exception("Ganglia sink requires 'host' property.")
  }

  if (!propertyToOption(GANGLIA_KEY_PORT).isDefined) {
    throw new Exception("Ganglia sink requires 'port' property.")
  }

  private val host = propertyToOption(GANGLIA_KEY_HOST).get
  private val port = propertyToOption(GANGLIA_KEY_PORT).get.toInt
  private val ttl = propertyToOption(GANGLIA_KEY_TTL).map(_.toInt).getOrElse(GANGLIA_DEFAULT_TTL)
  private val dmax = propertyToOption(GANGLIA_KEY_DMAX).map(_.toInt).getOrElse(GANGLIA_DEFAULT_DMAX)
  private val mode = propertyToOption(GANGLIA_KEY_MODE)
    .map(u => GMetric.UDPAddressingMode.valueOf(u.toUpperCase(Locale.ROOT)))
    .getOrElse(GANGLIA_DEFAULT_MODE)

  private val ganglia = new GMetric(host, port, mode, ttl)
  private val reporter = GangliaReporter.forRegistry(registry)
    .convertDurationsTo(TimeUnit.MILLISECONDS)
    .convertRatesTo(TimeUnit.SECONDS)
    .withDMax(dmax)
    .build(ganglia)

  override def start() {
    reporter.start(pollPeriod, pollUnit)
  }

  override def stop() {
    reporter.stop()
  }

  override def report() {
    reporter.report()
  }
}

