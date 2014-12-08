package com.viagraphs.idb

import monifu.reactive.Observable
import upickle.Aliases._


trait Logger extends IndexedDb {
  implicit val scheduler = IndexedDb.scheduler

  private def logNice(prefix: String, suffix: String, fixed: Int = 30): Unit = {
    val result = prefix + (" " * (fixed - prefix.length)) + suffix
    println(result)
  }

  abstract override def close(): Observable[String] = {
    logNice("close", "start")
    val obs = super.close()
    logNice("close", "end")
    obs
  }

  abstract override def openStoreTx(name: String, txMode: TxAccessMode): Observable[StoreTx] = {
    logNice("openStoreTx", "start")
    super.openStoreTx(name, txMode).doOnComplete {
      logNice("openStoreTx", "end")
    }
  }

  abstract override def getStoreNames: Observable[List[String]] = {
    logNice("getStoreNames", "start")
    super.getStoreNames.doOnComplete {
      logNice("getStoreNames", "end")
    }
  }

  abstract override def count(storeName: String): Observable[Int] = {
    logNice("count", "start")
    super.count(storeName).doOnComplete {
      logNice("count", "end")
    }
  }

  abstract override def clear(storeName: String): Observable[Nothing] = {
    logNice("clear", "start")
    super.clear(storeName).doOnComplete {
      logNice("clear", "end")
    }
  }

  abstract override def getLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    logNice("getLast", "start")
    super.getLast[K,V](storeName).doOnComplete {
      logNice("getLast", "end")
    }
  }

  abstract override def get[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    logNice("get", "start")
    super.get[K,V](storeName, keys:_*).doOnComplete {
      logNice("get", "end")
    }
  }

  abstract override def add[K : RW : ValidKey, V : W](storeName: String, optKey: Option[K], values: V*): Observable[K] = {
    logNice("add", "start")
    super.add[K,V](storeName, optKey, values:_*).doOnComplete {
      logNice("add", "end")
    }
  }

  abstract override def delete[K : W : ValidKey](storeName: String, keys: K*): Observable[Nothing] = {
    logNice("delete", "start")
    super.delete[K](storeName, keys:_*).doOnComplete {
      logNice("delete", "end")
    }
  }

  abstract override def getAndDelete[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    logNice("getAndDelete", "start")
    super.getAndDelete[K,V](storeName, keys:_*).doOnComplete {
      logNice("getAndDelete", "end")
    }
  }

  abstract override def getAndDeleteLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    logNice("getAndDeleteLast", "start")
    super.getAndDeleteLast[K,V](storeName).doOnComplete {
      logNice("getAndDeleteLast", "end")
    }
  }
}