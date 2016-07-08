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

package org.apache.spark.deploy.yarn.security

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.Text
import org.apache.hadoop.security.Credentials
import org.apache.hadoop.security.token.Token
import org.scalatest.{BeforeAndAfter, Matchers}

import org.apache.spark.{SparkConf, SparkFunSuite}

class ConfigurableCredentialManagerSuite extends SparkFunSuite with Matchers with BeforeAndAfter {
  private var credentialManager: ConfigurableCredentialManager = null
  private var sparkConf: SparkConf = null

  before {
    sparkConf = new SparkConf()
  }

  after {
    if (credentialManager != null) {
      credentialManager.stop()
      credentialManager = null
    }

    if (sparkConf != null) {
      sparkConf = null
    }
  }

  test("Correctly load default credential providers") {
    credentialManager = new ConfigurableCredentialManager(sparkConf)
    credentialManager.initialize()

    credentialManager.getServiceCredentialProvider("hdfs") should not be (None)
    credentialManager.getServiceCredentialProvider("hbase") should not be (None)
    credentialManager.getServiceCredentialProvider("hive") should not be (None)
  }

  test("disable hive credential provider") {
    sparkConf.set("spark.yarn.security.credentials.hive.enabled", "false")
    credentialManager = new ConfigurableCredentialManager(sparkConf)
    credentialManager.initialize()

    credentialManager.getServiceCredentialProvider("hdfs") should not be (None)
    credentialManager.getServiceCredentialProvider("hbase") should not be (None)
    credentialManager.getServiceCredentialProvider("hive") should be (None)
  }

  test("using deprecated configurations") {
    sparkConf.set("spark.yarn.security.tokens.hdfs.enabled", "false")
    sparkConf.set("spark.yarn.security.tokens.hive.enabled", "false")
    credentialManager = new ConfigurableCredentialManager(sparkConf)
    credentialManager.initialize()

    credentialManager.getServiceCredentialProvider("hdfs") should be (None)
    credentialManager.getServiceCredentialProvider("hive") should be (None)
    credentialManager.getServiceCredentialProvider("test") should be (None)
    credentialManager.getServiceCredentialProvider("hbase") should not be (None)
  }

  test("plug in customized credential provider") {
    sparkConf.set("spark.yarn.security.credentials.test.enabled", "true")
    credentialManager = new ConfigurableCredentialManager(sparkConf)
    credentialManager.initialize()

    credentialManager.getServiceCredentialProvider("test") should not be (None)
  }

  test("verify obtaining credentials from provider") {
    sparkConf.set("spark.yarn.security.credentials.test.enabled", "true")
    credentialManager = new ConfigurableCredentialManager(sparkConf)
    credentialManager.initialize()
    val hadoopConf = new Configuration()
    val creds = new Credentials()

    // Tokens can only be obtained from TestTokenProvider, for hdfs, hbase and hive tokens cannot
    // be obtained.
    credentialManager.obtainCredentials(hadoopConf, creds)
    val tokens = creds.getAllTokens
    tokens.size() should be (1)
    tokens.iterator().next().getService should be (new Text("test"))
  }

  test("verify getting credential renewal info") {
    sparkConf.set("spark.yarn.security.tokens.test.enabled", "true")
    credentialManager = new ConfigurableCredentialManager(sparkConf)
    credentialManager.initialize()
    val hadoopConf = new Configuration()
    val creds = new Credentials()

    val testCredentialProvider = credentialManager.getServiceCredentialProvider("test").get
      .asInstanceOf[TestCredentialProvider]
    // Only TestTokenProvider can get the time of next token renewal
    val nextRenewals = credentialManager.obtainCredentials(hadoopConf, creds)
    nextRenewals.length should be (1)
    val testCredsNextRenewal = nextRenewals.head.get
    testCredsNextRenewal should be (testCredentialProvider.timeOfNextTokenRenewal)
  }

  test("Obtain tokens For HiveMetastore") {
    val hadoopConf = new Configuration()
    hadoopConf.set("hive.metastore.kerberos.principal", "bob")
    // thrift picks up on port 0 and bails out, without trying to talk to endpoint
    hadoopConf.set("hive.metastore.uris", "http://localhost:0")

    val hiveCredentialProvider = new HiveCredentialProvider()
    val credentials = new Credentials()
    hiveCredentialProvider.obtainCredentials(hadoopConf, credentials)

    credentials.getAllTokens.size() should be (0)
  }

  test("Obtain tokens For HBase") {
    val hadoopConf = new Configuration()
    hadoopConf.set("hbase.security.authentication", "kerberos")

    val hbaseTokenProvider = new HBaseCredentialProvider()
    val creds = new Credentials()
    hbaseTokenProvider.obtainCredentials(hadoopConf, creds)

    creds.getAllTokens.size should be (0)
  }
}

class TestCredentialProvider extends ServiceCredentialProvider {
  val tokenRenewalInterval = 86400 * 1000L
  var timeOfNextTokenRenewal = 0L

  override def serviceName: String = "test"

  override def isCredentialRequired(conf: Configuration): Boolean = true

  override def obtainCredentials(hadoopConf: Configuration, creds: Credentials): Option[Long] = {
    val emptyToken = new Token()
    emptyToken.setService(new Text("test"))
    creds.addToken(emptyToken.getService, emptyToken)

    val currTime = System.currentTimeMillis()
    timeOfNextTokenRenewal = (currTime - currTime % tokenRenewalInterval) + tokenRenewalInterval

    Some(timeOfNextTokenRenewal)
  }
}
