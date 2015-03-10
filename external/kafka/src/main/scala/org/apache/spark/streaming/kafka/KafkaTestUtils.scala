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

package org.apache.spark.streaming.kafka

import java.io.File
import java.lang.{Integer => JInt}
import java.net.InetSocketAddress
import java.util.{Map => JMap}
import java.util.Properties
import java.util.concurrent.TimeoutException

import scala.annotation.tailrec
import scala.language.postfixOps
import scala.util.Random

import kafka.admin.AdminUtils
import kafka.common.KafkaException
import kafka.producer.{KeyedMessage, Producer, ProducerConfig}
import kafka.serializer.StringEncoder
import kafka.server.{KafkaConfig, KafkaServer}
import kafka.utils.ZKStringSerializer
import org.apache.zookeeper.server.{NIOServerCnxnFactory, ZooKeeperServer}
import org.I0Itec.zkclient.ZkClient

import org.apache.spark.Logging
import org.apache.spark.streaming.Time
import org.apache.spark.util.Utils

/**
 * This is a helper class for Kafka test suites. This has the functionality to set up
 * and tear down local Kafka servers, and to push data using Kafka producers.
 */
private class KafkaTestUtils extends Logging {

  // Zookeeper related configurations
  private val zkHost = "localhost"
  private var zkPort: Int = 0
  private val zkConnectionTimeout = 6000
  private val zkSessionTimeout = 6000

  private var zookeeper: EmbeddedZookeeper = _

  var zkClient: ZkClient = _

  // Kafka broker related configurations
  private val brokerHost = "localhost"
  private var brokerPort = 9092
  private var brokerConf: KafkaConfig = _

  // Kafka broker server
  private var server: KafkaServer = _

  // Kafka producer
  private var producer: Producer[String, String] = _

  // Flag to test whether the system is correctly started
  private var zkReady = false
  private var brokerReady = false

  def zkAddress: String = {
    assert(zkReady, "Zookeeper not setup yet or already torn down, cannot get zookeeper address")
    s"$zkHost:$zkPort"
  }

  def brokerAddress: String = {
    assert(brokerReady, "Kafka not setup yet or already torn down, cannot get broker address")
    s"$brokerHost:$brokerPort"
  }

  /** Set up the Embedded Zookeeper server and get the proper Zookeeper port */
  def setupEmbeddedZookeeper(): Unit = {
    // Zookeeper server startup
    zookeeper = new EmbeddedZookeeper(s"$zkHost:$zkPort")
    // Get the actual zookeeper binding port
    zkPort = zookeeper.actualPort
    zkReady = true
    zkClient = new ZkClient(zkAddress, zkSessionTimeout, zkConnectionTimeout, ZKStringSerializer)
  }

  /** Set up the Embedded Kafka server */
  def setupEmbeddedKafkaServer(): Unit = {
    assert(zkReady, "Zookeeper should be set up beforehand")
    // Kafka broker startup
    var bindSuccess: Boolean = false
    while(!bindSuccess) {
      try {
        brokerConf = new KafkaConfig(brokerConfigure)
        server = new KafkaServer(brokerConf)
        server.startup()
        bindSuccess = true
      } catch {
        case e: KafkaException =>
          if (e.getMessage != null && e.getMessage.contains("Socket server failed to bind to")) {
            brokerPort += 1
          }
        case e: Exception => throw new Exception("Kafka server create failed", e)
      }
    }

    Thread.sleep(2000)
    brokerReady = true
  }

  /** Tear down the whole servers, including Kafka broker and Zookeeper */
  def tearDownEmbeddedServers(): Unit = {
    brokerReady = false
    zkReady = false

    if (producer != null) {
      producer.close()
      producer = null
    }

    if (server != null) {
      server.shutdown()
      server = null
    }

    brokerConf.logDirs.foreach { f => Utils.deleteRecursively(new File(f)) }

    if (zkClient != null) {
      zkClient.close()
      zkClient = null
    }

    if (zookeeper != null) {
      zookeeper.shutdown()
      zookeeper = null
    }
  }

  /** Create a Kafka topic and wait until it propagated to the whole cluster */
  def createTopic(topic: String): Unit = {
    AdminUtils.createTopic(zkClient, topic, 1, 1)
    // wait until metadata is propagated
    waitUntilMetadataIsPropagated(topic, 0)
  }

  /** Java function for sending messages to the Kafka broker */
  def sendMessages(topic: String, messageToFreq: JMap[String, JInt]): Unit = {
    import scala.collection.JavaConversions._
    sendMessages(topic, Map(messageToFreq.mapValues(_.intValue()).toSeq: _*))
  }

  /** Send the messages to the Kafka broker */
  def sendMessages(topic: String, messageToFreq: Map[String, Int]): Unit = {
    val messages = messageToFreq.flatMap { case (s, freq) => Seq.fill(freq)(s) }.toArray
    sendMessages(topic, messages)
  }

  /** Send the array of messages to the Kafka broker */
  def sendMessages(topic: String, messages: Array[String]): Unit = {
    producer = new Producer[String, String](new ProducerConfig(producerConfigure))
    producer.send(messages.map { new KeyedMessage[String, String](topic, _ ) }: _*)
    producer.close()
    producer = null
  }

  private def brokerConfigure: Properties = {
    val props = new Properties()
    props.put("broker.id", "0")
    props.put("host.name", "localhost")
    props.put("port", brokerPort.toString)
    props.put("log.dir", Utils.createTempDir().getAbsolutePath)
    props.put("zookeeper.connect", zkAddress)
    props.put("log.flush.interval.messages", "1")
    props.put("replica.socket.timeout.ms", "1500")
    props
  }

  private def producerConfigure: Properties = {
    val props = new Properties()
    props.put("metadata.broker.list", brokerAddress)
    props.put("serializer.class", classOf[StringEncoder].getName)
    props
  }

  private def waitUntilMetadataIsPropagated(topic: String, partition: Int): Unit = {
    eventually(Time(10000), Time(100)) {
      assert(
        server.apis.metadataCache.containsTopicAndPartition(topic, partition),
        s"Partition [$topic, $partition] metadata not propagated after timeout"
      )
    }
  }

  // A simplified version of scalatest eventually, rewrite here is to avoid adding extra test
  // dependency
  private def eventually[T](timeout: Time, interval: Time)(func: => T): T = {
    def makeAttempt(): Either[Throwable, T] = {
      try {
        Right(func)
      } catch {
        case e: Throwable => Left(e)
      }
    }

    val startTime = System.currentTimeMillis()
    @tailrec
    def tryAgain(attempt: Int): T = {
      makeAttempt() match {
        case Right(result) => result
        case Left(e) =>
          val duration = System.currentTimeMillis() - startTime
          if (duration < timeout.milliseconds) {
            Thread.sleep(interval.milliseconds)
          } else {
            throw new TimeoutException(e.getMessage)
          }

          tryAgain(attempt + 1)
      }
    }

    tryAgain(1)
  }

  class EmbeddedZookeeper(val zkConnect: String) {
    val random = new Random()
    val snapshotDir = Utils.createTempDir()
    val logDir = Utils.createTempDir()

    val zookeeper = new ZooKeeperServer(snapshotDir, logDir, 500)
    val (ip, port) = {
      val splits = zkConnect.split(":")
      (splits(0), splits(1).toInt)
    }
    val factory = new NIOServerCnxnFactory()
    factory.configure(new InetSocketAddress(ip, port), 16)
    factory.startup(zookeeper)

    val actualPort = factory.getLocalPort

    def shutdown() {
      factory.shutdown()
      Utils.deleteRecursively(snapshotDir)
      Utils.deleteRecursively(logDir)
    }
  }
}

