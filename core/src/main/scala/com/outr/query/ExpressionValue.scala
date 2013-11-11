package com.outr.query

/**
 * @author Matt Hicks <matt@outr.com>
 */
trait ExpressionValue[T] {
  def expression: SelectExpression
  def value: T
}