package com.github.potamois.potamoi.commons

/**
 * UUID generator
 *
 * @author Al-assad
 */
object Uuid {

  /**
   * Generate a uuid of length 36
   */
  def genUUID36: String = java.util.UUID.randomUUID().toString

  /**
   * Generate a uuid of length 32
   */
  def genUUID32: String = genUUID36.split("-").mkString

  /**
   * Generate a uuid of length 16
   */
  def genUUID16: String = genUUID32.substring(0, 16)

}
