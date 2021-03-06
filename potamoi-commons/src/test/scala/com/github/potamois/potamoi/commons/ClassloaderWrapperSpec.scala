package com.github.potamois.potamoi.commons

import com.github.potamois.potamoi.testkit.STSpec

import scala.util.{Failure, Success}

class ClassloaderWrapperSpec extends STSpec {

  import ClassloaderWrapper._

  "ClassloaderWrapper" should {

    "run with extra dependencies" in {
      val uri = Seq(getClass.getResource("/tiny-1.0.jar"))

      runWithExtraDeps(uri) { cl =>
        val clz = cl.loadClass("com.github.al.assad.tiny.Calculator")
        clz.getName shouldBe "com.github.al.assad.tiny.Calculator"
        val sumMethod = clz.getMethod("sum", classOf[Integer], classOf[Integer])
        sumMethod.invoke(null, Integer.valueOf(1), Integer.valueOf(2)) shouldBe Integer.valueOf(3)
      }
      intercept[ClassNotFoundException] {
        Thread.currentThread.getContextClassLoader.loadClass("com.github.al.assad.tiny.Calculator")
      }
    }

    "run with empty extra dependency" in {
      runWithExtraDeps(Seq.empty) { cl =>
        intercept[ClassNotFoundException] {
          cl.loadClass("com.github.al.assad.tiny.Calculator")
        }
      }
    }

    "try run with extra dependencies" in {
      val uri = Seq(getClass.getResource("/tiny-1.0.jar"))

      tryRunWithExtraDeps(uri) { cl =>
        val clz = cl.loadClass("com.github.al.assad.tiny.Calculator")
        clz.getName shouldBe "com.github.al.assad.tiny.Calculator"
        val sumMethod = clz.getMethod("sum", classOf[Integer], classOf[Integer])
        sumMethod.invoke(null, Integer.valueOf(1), Integer.valueOf(2))
      }.get shouldBe Integer.valueOf(3)

      intercept[ClassNotFoundException] {
        Thread.currentThread.getContextClassLoader.loadClass("com.github.al.assad.tiny.Calculator")
      }
    }

    "try run with extra dependencies with incorrect deps uri" in {
      val uri = Seq(getClass.getResource("/boom.jar"))

      tryRunWithExtraDeps(uri) { cl =>
        val clz = cl.loadClass("com.github.al.assad.tiny.Calculator")
        clz.getName shouldBe "com.github.al.assad.tiny.Calculator"
        val sumMethod = clz.getMethod("sum", classOf[Integer], classOf[Integer])
        sumMethod.invoke(null, Integer.valueOf(1), Integer.valueOf(2))
      } match {
        case Success(_) => fail
        case Failure(e) => e.isInstanceOf[NullPointerException] shouldBe true
      }
    }

    "try run with extra dependencies with error in internal func" in {
      val uri = Seq(getClass.getResource("/tiny-1.0.jar"))

      tryRunWithExtraDeps(uri) { cl =>
        val clz = cl.loadClass("com.github.al.assad.tiny.Calculator")
        clz.getName shouldBe "com.github.al.assad.tiny.Calculator"
        val sumMethod = clz.getMethod("sum2", classOf[Integer], classOf[Integer])
        sumMethod.invoke(null, Integer.valueOf(1), Integer.valueOf(2))
      } match {
        case Success(_) => fail
        case Failure(e) => e.isInstanceOf[NoSuchMethodException] shouldBe true
      }
    }

    "try run with empty extra dependency" in {
      tryRunWithExtraDeps(Seq.empty) { cl =>
        intercept[ClassNotFoundException] {
          cl.loadClass("com.github.al.assad.tiny.Calculator")
        }
      } match {
        case Success(_) =>
        case Failure(e) => fail
      }
    }

  }

}
