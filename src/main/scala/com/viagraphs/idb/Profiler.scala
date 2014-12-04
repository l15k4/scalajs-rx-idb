package com.viagraphs.idb

import monifu.reactive.Observable
import org.scalajs.dom.IDBObjectStore
import upickle.Aliases._

import scala.collection.mutable.ListBuffer
import scala.scalajs.js

case class Entry(methodName: String, executionTime: Double)
case class EntrySum(invocationCount: Int, executionTime: Double)

trait Profiler extends IndexedDb {

  val stats = ListBuffer[Entry]()
  
  private def now = new js.Date().getTime()
  private def log(name: String, start: Double): Unit = {
    val entry = Entry(name, new js.Date().getTime() - start)
    stats += entry
    Profiler.totalStats += entry
  }

  abstract override def close(): Unit = {
    val start = now
    super.close()
    log("close", start)
    Profiler.printout(stats)
  }

  abstract override def getStore(name: String, txMode: TxAccessMode): Observable[IDBObjectStore] = {
    val start = now
    super.getStore(name, txMode).doOnComplete {
      log("getStore", start)
    }
  }

  abstract override def storeNames: Observable[List[String]] = {
    val start = now
    super.storeNames.doOnComplete {
      log("storeNames", start)
    }
  }

  abstract override def count(storeName: String): Observable[Int] = {
    val start = now
    super.count(storeName).doOnComplete {
      log("count", start)
    }
  }

  abstract override def clear(storeName: String): Observable[Unit] = {
    val start = now
    super.clear(storeName).doOnComplete {
      log("clear", start)
    }
  }

  abstract override def getLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    val start = now
    super.getLast[K,V](storeName).doOnComplete {
      log("getLast", start)
    }
  }

  abstract override def get[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    val start = now
    super.get[K,V](storeName, keys:_*).doOnComplete {
      log("get", start)
    }
  }

  abstract override def add[K : RW : ValidKey, V : W](storeName: String, optKey: Option[K], values: V*): Observable[K] = {
    val start = now
    super.add[K,V](storeName, optKey, values:_*).doOnComplete {
      log("add", start)
    }
  }

  abstract override def delete[K : W : ValidKey](storeName: String, keys: K*): Observable[Unit] = {
    val start = now
    super.delete[K](storeName, keys:_*).doOnComplete {
      log("delete", start)
    }
  }

  abstract override def getAndDelete[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    val start = now
    super.getAndDelete[K,V](storeName, keys:_*).doOnComplete {
      log("getAndDelete", start)
    }
  }

  abstract override def getAndDeleteLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    val start = now
    super.getAndDeleteLast[K,V](storeName).doOnComplete {
      log("getAndDeleteLast", start)
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