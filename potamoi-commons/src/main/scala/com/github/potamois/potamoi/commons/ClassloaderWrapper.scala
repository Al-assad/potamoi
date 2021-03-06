package com.github.potamois.potamoi.commons

import java.net.URL
import scala.reflect.internal.util.ScalaClassLoader
import scala.util.Try

/**
 * JDK ClassLoader wrapper tool.
 *
 * @author Al-assad
 */
object ClassloaderWrapper {

  /**
   * Run func process with extra dependency jars.
   * When the extraDeps is empty, it would use the current classloader.
   *
   * @param extraDeps extra dependencies
   * @param func      process function
   * @return result of func
   * @throws java.lang.SecurityException    if a security manager exists and its checkCreateClassLoader
   *                                        method doesn't allow creation of a class loader.
   * @throws java.lang.NullPointerException if urls or any of its elements is null.
   */
  @throws[SecurityException]
  @throws[NullPointerException]
  def runWithExtraDeps[T](extraDeps: Seq[URL])(func: ClassLoader => T): T = {
    if (extraDeps.isEmpty)
      func(Thread.currentThread.getContextClassLoader)
    else {
      val oriCl = Thread.currentThread.getContextClassLoader
      val cl = ScalaClassLoader.fromURLs(extraDeps, oriCl)
      Thread.currentThread.setContextClassLoader(cl)
      try {
        func(cl)
      } finally {
        Thread.currentThread.setContextClassLoader(oriCl)
      }
    }
  }

  /**
   * Same as [[runWithExtraDeps]] but return a Try partition function as result.
   */
  def tryRunWithExtraDeps[T](extraDeps: Seq[URL])(func: ClassLoader => T): Try[T] = Try(runWithExtraDeps(extraDeps)(func))


}

