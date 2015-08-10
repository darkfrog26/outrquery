package org.scalarelational.postgresql

import javax.sql.DataSource

import org.postgresql.ds.PGSimpleDataSource
import org.powerscala.log.{Level, Logging}
import org.powerscala.property.Property
import org.scalarelational.column.ColumnPropertyContainer
import org.scalarelational.column.property.{AutoIncrement, Polymorphic, Unique}
import org.scalarelational.datatype._
import org.scalarelational.instruction.ddl.CreateColumn
import org.scalarelational.model._
import org.scalarelational.op.{Condition, RegexCondition}
import org.scalarelational.result.ResultSetIterator

import scala.collection.mutable.ListBuffer

/**
 * @author Robert Djubek <envy1988@gmail.com>
 */
abstract class PostgreSQLDatastore private() extends SQLDatastore with Logging with SQLLogging {
  protected def this(pgConfig: PostgreSQL.Config) = {
    this()
    sqlLogLevel := Level.Warn
    config := pgConfig
  }

  override def supportsMerge = false

  protected def this(dataSource: DataSource) = {
    this()
    dataSourceProperty := dataSource
  }

  Class.forName("org.postgresql.Driver")

  val config = Property[PostgreSQL.Config]()

  init()

  protected def init() = {
    config.change.on {
      case evt => updateDataSource() // Update the data source if the mode changes
    }
  }

  def updateDataSource() = {
    dispose() // Make sure to shut down the previous DataSource if possible

    val source = new PGSimpleDataSource()
    source.setPortNumber(config().port)
    source.setServerName(config().host)
    source.setDatabaseName(config().schema)
    source.setUser(config().user)
    source.setPassword(config().password)
    config().ssl.foreach{s =>
      source.setSsl(true)
      s.sslFactory.foreach { source.setSslfactory }
      s.sslFactoryArg.foreach { source.setSslFactoryArg }
    }
    dataSourceProperty := source
  }

  override protected def columnPropertiesSQL(container: ColumnPropertyContainer): List[String] = {
    val b = ListBuffer.empty[String]
    if (!container.isOptional && !container.has(Polymorphic)) {
      b.append("NOT NULL")
    }
    if (container.has(Unique)) {
      b.append("UNIQUE")
    }
    b.toList
  }

  override protected def columnSQLType(create: CreateColumn[_]) =
    if (create.has(AutoIncrement)) {
      "SERIAL"
    } else if (create.dataType == doubleDataType) {
      "DOUBLE PRECISION"
//    } else if (create.dataType == byteArrayDataType) { //Not sure if this isn't tested or works without?
//      "BYTEA"
    } else if (create.dataType.isInstanceOf[ObjectSerializationConverter[_]]) {
      "BYTEA"
    } else if (create.dataType == blobDataType) {
      "OID"
    } else {
      super.columnSQLType(create)
    }

  override def jdbcTables = {
    val s = session
    val meta = s.connection.getMetaData
    val results = meta.getTables(null, "public", "%", null)
    try {
      new ResultSetIterator(results).map(_.getString("TABLE_NAME")).toSet
    } finally {
      results.close()
    }
  }

  override def jdbcColumns(tableName: String) = {
    val s = session
    val meta = s.connection.getMetaData
    val results = meta.getColumns(null, "public", tableName, null)
    try {
      new ResultSetIterator(results).map(_.getString("COLUMN_NAME")).toSet
    } finally {
      results.close()
    }
  }

  override def doesTableExist(name: String) = {
    val s = session
    val meta = s.connection.getMetaData
    val results = meta.getTables(null, "public", name.toLowerCase, null)
    try {
      results.next()
    } finally {
      results.close()
    }
  }

  override def condition2String(condition: Condition, args: ListBuffer[DataTyped[_]]): String = condition match {
    case c: RegexCondition[_] => {
      args += StringDataType.typed(c.regex.toString())
      s"${c.column.longName} ${if (c.not) "!~ " else ""}~ ?"
    }
    case _ => super.condition2String(condition, args)
  }
}