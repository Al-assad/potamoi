package com.github.potamois.potamoi.gateway.flink.interact

import com.github.potamois.potamoi.testkit.STSpec

class ExecPropsSpec extends STSpec {

  "ExecConfigSpec to EffectiveExecConfig" should {

    "create remote env correctly" in {
      val props = ExecProps.remoteEnv(
        RemoteAddr("111.111.111.111", 8088),
        Map.empty,
        Seq.empty,
        RsCollectStrategy(EvictStrategy.DROP_TAIL, 500)
      )
      props.toEffectiveExecProps shouldBe EffectiveExecProps(
        ExecProps.DEFAULT_FLINK_CONFIG ++ Map(
          "rest.port" -> "8088",
          "rest.address" -> "111.111.111.111",
          "execution.attached" -> "true",
          "execution.shutdown-on-attached-exit" -> "true",
          "execution.target" -> "remote"),
        Seq.empty,
        RsCollectStrategy(EvictStrategy.DROP_TAIL, 500)
      )
    }

    "create remote local env correctly" in {
      val props = ExecProps.localEnv(
        Map.empty,
        Seq.empty,
        RsCollectStrategy(EvictStrategy.DROP_TAIL, 500)
      )
      props.toEffectiveExecProps shouldBe EffectiveExecProps(
        ExecProps.DEFAULT_FLINK_CONFIG ++ Map(
          "execution.target" -> "local",
          "execution.attached" -> "true",
          "execution.shutdown-on-attached-exit" -> "true"),
        Seq.empty,
        RsCollectStrategy(EvictStrategy.DROP_TAIL, 500)
      )
    }

    "create env with custom flink configs" in {
      val props = ExecProps.remoteEnv(
        RemoteAddr("111.111.111.111", 8088),
        Map(
          "pipeline.auto-generate-uids" -> "false",
          "pipeline.name" -> "test-pipeline",
          "execution.target" -> "local"
        ),
        Seq.empty,
        RsCollectStrategy(EvictStrategy.DROP_TAIL, 500)
      )
      props.toEffectiveExecProps shouldBe EffectiveExecProps(
        ExecProps.DEFAULT_FLINK_CONFIG ++ Map(
          "rest.port" -> "8088",
          "rest.address" -> "111.111.111.111",
          "execution.target" -> "local",
          "pipeline.auto-generate-uids" -> "false",
          "pipeline.name" -> "test-pipeline",
          "execution.attached" -> "true",
          "execution.shutdown-on-attached-exit" -> "true"),
        Seq.empty,
        RsCollectStrategy(EvictStrategy.DROP_TAIL, 500)
      )
    }

  }

}
