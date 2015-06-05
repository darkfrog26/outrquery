package com.outr.query

import javax.sql.DataSource

import java.sql.ResultSet
import org.powerscala.transactional
import org.powerscala.event.processor.OptionProcessor
import org.powerscala.event.Listenable
import org.powerscala.concurrent.{Time, Executor}
import org.powerscala.log.Logging
import org.powerscala.reflect._

import scala.collection.mutable

/**
 * @author Matt Hicks <matt@outr.com>
 */
trait Datastore extends Listenable with Logging {
  private var _sessions = Map.empty[Thread, DatastoreSession]
  val value2SQL = new OptionProcessor[(ColumnLike[_], Any), Any]("value2SQL")
  val sql2Value = new OptionProcessor[(ColumnLike[_], Any), Any]("sql2Value")

  /**
   * Called when the datastore is being created for the first time. This does not mean the tables are being created but
   * just the datastore.
   */
  def creating(): Unit = {}

  @volatile private var mapInitialized = false
  private var externalTables = List.empty[Table]
  private lazy val tablesMap = synchronized {
    val tables = getClass.methods.collect {
      case m if m.args.isEmpty && m.returnType.`type`.hasType(classOf[Table]) => m[Table](this)
    }
    val map = mutable.ListMap(tables.map(t => t.tableName.toLowerCase -> t): _*)
    externalTables.reverse.foreach {              // Add tables set before initialization
      case t => map += t.tableName -> t
    }
    mapInitialized = true
    map
  }
  protected[query] def add(table: Table) = synchronized {
    if (mapInitialized) {
      tablesMap += (table.tableName.toLowerCase -> table)
    } else {
      externalTables = table :: externalTables
    }
  }
  def tables = tablesMap.values

  def tableByName(tableName: String) = tablesMap.get(tableName.toLowerCase)

  private var lastUpdated = System.nanoTime()
  val updater = Executor.scheduleWithFixedDelay(1.0, 1.0) {
    val current = System.nanoTime()
    val delta = Time.fromNanos(current - lastUpdated)
    update(delta)
    lastUpdated = current
  }

  def sessions = _sessions.values
  def session = synchronized {
    _sessions.get(Thread.currentThread()) match {
      case Some(s) => s
      case None => {
        val s = createSession()
        _sessions += Thread.currentThread() -> s
        s
      }
    }
  }
  def clearSessions() = synchronized {
    _sessions.values.foreach {
      case session => session.dispose()
    }
    _sessions = Map.empty[Thread, DatastoreSession]
  }

  def select(expressions: SelectExpression*) = Query(expressions.toList)
  def select(expressions: List[SelectExpression]) = Query(expressions)
  def insert(values: ColumnValue[_]*) = {
    val results = exec(Insert(values.toList))
    if (results.hasNext) {
      Some(results.next())
    } else {
      None
    }
  }
  def insertInto(table: Table, values: Any*) = insert(values.zip(table.columns).map {
    case (value, column) => column.asInstanceOf[Column[Any]](value)
  }: _*)
  def merge(key: Column[_], values: ColumnValue[_]*) = {
    exec(Merge(key, values.toList))
  }
  def update(values: ColumnValue[_]*) = Update(values.toList, values.head.column.table)
  def delete(table: Table) = Delete(table)

  def dataSource: DataSource
  def sessionTimeout = 5.0

  def jdbcTables = active {
    val s = session
    val meta = s.connection.getMetaData
    val results = meta.getTables(null, "PUBLIC", "%", null)
    try {
      new ResultSetIterator(results).map(_.getString("TABLE_NAME")).toSet
    } finally {
      results.close()
    }
  }

  def empty() = jdbcTables.isEmpty

  def create(ifNotExist: Boolean = true) = {
    val s = session
    val sql = ddl(ifNotExist)
    transaction {
      s.execute(sql)
    }
  }

  def ddl(ifNotExist: Boolean = true) = {
    val b = new StringBuilder

    val existingTables = jdbcTables

    tables.foreach {
      case t => if (!existingTables.contains(t.tableName.toUpperCase)) {
        b.append(createTableSQL(ifNotExist, t))
        b.append("\r\n")
      } else {
        debug(s"Table already exists: ${t.tableName}")
      }
    }

    tables.foreach {
      case t => if (!existingTables.contains(t.tableName.toUpperCase)) {
        createTableExtras(t, b)
      }
    }

    createExtras(b)

    b.toString()
  }

  /**
   * Creates a transaction for the contents of the supplied function. If an exception is thrown the contents will be
   * rolled back to the savepoint created before the function was invoked. If no exception occurs the transaction
   * will be committed (but only if it is not a nested transaction). Layering of transactions is supported and
   * will defer commit until the last transaction is ended.
   *
   * @param f the function to execute within the transaction
   * @tparam R the return value from the function
   * @return R
   */
  def transaction[R](f: => R): R = {
    val autoCommit = session.autoCommit
    val depth = transactional.transaction.depth
    val root = depth == 0
    active {
      transactional.transaction {
        try {
          val result = f
          if (root) {
            try {
              session.commit()
            } catch {
              case exc: Throwable => error("Exception trying to commit transaction.", exc)
            }
          }
          result
        } catch {
          case t: Throwable => {
            if (root) session.rollback()
            throw t
          }
        } finally {
          if (root) {
            session.autoCommit(autoCommit)
          }
        }
      }
    }
  }
  // TODO: test this again after I figure out why savepoints aren't working like expected
  /*def transaction[R](f: => R): R = {
    val autoCommit = session.autoCommit
    val depth = transactional.transaction.depth
    val root = depth == 0
    val savePointName = s"savePoint$depth"
    active {
      transactional.transaction {
        val savePoint = session.savePoint(savePointName)
        try {
          val result = f
          if (root) {
            try {
              session.commit()
            } catch {
              case exc: Throwable => error("Exception trying to commit transaction.", exc)
            }
          }
          result
        } catch {
          case t: Throwable => {
            session.rollback(savePoint)
            throw t
          }
        } finally {
          session.releaseSavePoint(savePoint)
          if (root) {
            session.autoCommit(autoCommit)
          }
        }
      }
    }
  }*/

  def sqlFromQuery(query: Query): (String, List[Any])

  def exec(query: Query): QueryResultsIterator
  def exec(insert: Insert): Iterator[Int]
  def exec(merge: Merge): Int
  def exec(update: Update): Int
  def exec(delete: Delete): Int

  def createTableSQL(ifNotExist: Boolean, table: Table): String

  def createTableExtras(table: Table, b: StringBuilder): Unit

  def createExtras(b: StringBuilder): Unit

  protected def createSession() = new DatastoreSession(this, sessionTimeout, Thread.currentThread())

  protected[query] def cleanup(thread: Thread, session: DatastoreSession) = synchronized {
    _sessions -= thread
  }

  protected def update(delta: Double) = {
    sessions.foreach {
      case session => session.update(delta)
    }
  }
  def active[T](f: => T): T = {
    val s = session
    s.checkIn()
    s.activeQueries.incrementAndGet()
    try {
      f
    } finally {
      s.activeQueries.decrementAndGet()
    }
  }
  def dispose(): Unit = {
    clearSessions()
  }
}

object Datastore {
  private val instance = new ThreadLocal[Datastore]

  protected[query] def current(d: Datastore) = instance.set(d)
  def apply() = instance.get()
}

class GeneratedKeysIterator(rs: ResultSet) extends Iterator[Int] {
  def hasNext = rs.next()
  def next() = rs.getInt(1)
}

case class QueryResult(table: Table, values: List[ExpressionValue[_]]) {
  def apply[T](column: Column[T]) = values.collectFirst {
    case cv: ColumnValue[_] if cv.column == column => cv.value.asInstanceOf[T]
  }.getOrElse(throw new RuntimeException(s"Unable to find column: ${column.name} in result."))

  def apply[T](function: SQLFunction[T]) = values.collectFirst {
    case fv: SQLFunctionValue[_] if fv.function == function => fv.value.asInstanceOf[T]
  }.getOrElse(throw new RuntimeException(s"Unable to find function value: $function in result."))

  override def toString = s"${table.tableName}: ${values.mkString(", ")}"
}

class QueryResultsIterator(rs: ResultSet, val query: Query) extends Iterator[QueryResult] {
  def hasNext = rs.next()
  def next() = {
    query.table.datastore.session.checkIn()       // Keep the session alive
    val values = query.expressions.zipWithIndex.map {
      case (expression, index) => expression match {
        case column: ColumnLike[_] => ColumnValue[Any](column.asInstanceOf[ColumnLike[Any]], column.converter.fromSQLType(column, rs.getObject(index + 1)), None)
        case function: SQLFunction[_] => SQLFunctionValue[Any](function.asInstanceOf[SQLFunction[Any]], rs.getObject(index + 1))
      }
    }
    QueryResult(query.table, values)
  }

  def one = if (hasNext) {
    val n = next()
    if (hasNext) throw new RuntimeException("More than one result for query!")
    n
  } else {
    throw new RuntimeException("No results for the query!")
  }
}

class ResultSetIterator(results: ResultSet) extends Iterator[ResultSet] {
  def hasNext = results.next()
  def next() = results
}