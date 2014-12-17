package com.viagraphs.idb

import monifu.reactive.Observable
import scala.language.higherKinds
import scala.collection.mutable.ListBuffer
import scala.scalajs.js

/**
 * Mixin this trait with Store to enable request logging and profiling
 */
trait Spy[K,V] extends Store[K,V] {
  implicit val scheduler = IndexedDb.scheduler

  val stats = ListBuffer[Entry]()

  private def now = new js.Date().getTime()
  private def log(name: String, start: Double): Unit = {
    val entry = Entry(name, new js.Date().getTime() - start)
    stats += entry
    Profiler.totalStats += entry
  }

  abstract override def update[C[_]](keys: C[K], entries: Map[K,V])(implicit e: Tx[C]): Observable[(K,V)] = {
    val start = now
    super.update(keys, entries)(e).dump("update").doOnComplete {
      log("update", start)
    }
  }

  abstract override def get[C[_]](input: C[K])(implicit e: Tx[C]): Observable[(K,V)] = {
    val start = now
    super.get(input)(e).dump("get").doOnComplete {
      log("get", start)
    }
  }

  abstract override def append[C[X] <: Iterable[X]](input: C[V])(implicit e: Tx[C]): Observable[(K,V)] = {
    val start = now
    super.append(input)(e).dump("append").doOnComplete {
      log("append", start)
    }
  }

  abstract override def add(input: Map[K, V])(implicit e: Tx[Iterable]): Observable[Nothing] = {
    val start = now
    super.add(input)(e).dump("add").doOnComplete {
      log("add", start)
    }
  }

  abstract override def delete[C[_]](input: C[K])(implicit e: Tx[C]): Observable[Nothing] = {
    val start = now
    super.delete(input)(e).dump("delete").doOnComplete {
      log("delete", start)
    }
  }

  abstract override def count: Observable[Int] = {
    val start = now
    super.count.dump("count").doOnComplete {
      log("count", start)
    }
  }

  abstract override def clear: Observable[Nothing] = {
    val start = now
    super.clear.dump("clear").doOnComplete {
      log("clear", start)
    }
  }
}

/**
 * Mixin this marker trait with IdbInitMode to enable IDB logging
 */
trait Logging
trait Logger extends IndexedDb {
  implicit val scheduler = IndexedDb.scheduler

  abstract override def close(): Observable[String] = {
    super.close().dump("close")
  }

  abstract override def delete(): Observable[String] = {
    super.delete().dump("delete")
  }

  abstract override def getName: Observable[String] = {
    super.getName.dump("getName")
  }

  abstract override def getStoreNames: Observable[List[String]] = {
    super.getStoreNames.dump("getStoreNames")
  }
}

case class Entry(methodName: String, executionTime: Double)
case class EntrySum(invocationCount: Int, executionTime: Double)

/**
 * Mixin this marker trait with IdbInitMode to enable IDB profiling
 */
trait Profiling
trait Profiler extends IndexedDb {
  implicit val scheduler = IndexedDb.scheduler
  val stats = ListBuffer[Entry]()

  private def now = new js.Date().getTime()
  private def log(name: String, start: Double): Unit = {
    val entry = Entry(name, new js.Date().getTime() - start)
    stats += entry
    Profiler.totalStats += entry
  }

  abstract override def close(): Observable[String] = {
    val start = now
    super.close().doOnComplete {
      log("close", start)
    }
  }

  abstract override def delete(): Observable[String] = {
    val start = now
    super.delete().doOnComplete {
      log("delete", start)
    }
  }

  abstract override def getName: Observable[String] = {
    val start = now
    super.getName.doOnComplete {
      log("getName", start)
    }
  }

  abstract override def getStoreNames: Observable[List[String]] = {
    val start = now
    super.getStoreNames.doOnComplete {
      log("storeNames", start)
    }
  }
}

object Profiler {
  val totalStats = ListBuffer[Entry]()

  private def calculate(stats: ListBuffer[Entry]): Map[String, EntrySum] = stats.groupBy(_.methodName).map {
    case (name, entries) =>
      val (executionTime, invocationCount) = entries.foldLeft((0D, 0)) {
        case ((time,count), entry) => (entry.executionTime + time, count + 1)
      }
      (name, EntrySum(invocationCount, executionTime))
  }

  def printout(stats: ListBuffer[Entry] = totalStats): Unit = {
    calculate(stats).foreach { case (name, result) =>
      def nice(str: String, fixed: Int): String = str + (" " * (fixed - str.length))
      println()
      print(nice(name,20))
      print(nice(result.invocationCount.toString + " times", 15))
      print(result.executionTime + " ms")
    }
    println()
  }
}