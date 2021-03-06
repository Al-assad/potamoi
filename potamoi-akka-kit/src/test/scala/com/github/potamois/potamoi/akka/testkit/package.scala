package com.github.potamois.potamoi.akka

import com.github.potamois.potamoi.commons.PotaConfig.RichConfig
import com.typesafe.config.{Config, ConfigFactory}

package object testkit {

  // default akka config for scalatest
  val defaultConfig: Config = ConfigFactory.load().ensurePotamoi

  // default akka cluster config for scalatest
  val defaultClusterConfig: Config =
    ConfigFactory.parseString(
      """
        |akka {
        |   actor.provider = "cluster"
        |   cluster {
        |    downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
        |    jmx.multi-mbeans-in-same-jvm = on
        |  }
        | }
        |""".stripMargin)
      .withFallback(ConfigFactory.load())
      .ensurePotamoi

}
