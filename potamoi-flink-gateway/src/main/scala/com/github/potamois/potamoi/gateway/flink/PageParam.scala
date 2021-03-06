package com.github.potamois.potamoi.gateway.flink

/**
 * Paging query parameters.
 *
 * @param index page index
 * @param size  number of rows per page
 * @author Al-assad
 */
case class PageReq(index: Int, size: Int)

/**
 * Paging statistics.
 *
 * @param index      current page index
 * @param size       current number of rows per page
 * @param totalPages total page count
 * @param totalRows   total row count
 * @param hasNext    whether has next page
 * @param data       data payload
 * @author Al-assad
 */
case class PageRsp[T](index: Int, size: Int, totalPages: Int, totalRows: Int, hasNext: Boolean, data: T)
