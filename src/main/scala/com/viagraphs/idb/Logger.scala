package com.viagraphs.idb

import monifu.reactive.Observable
import upickle.Aliases._

trait Logger extends IndexedDb {
  implicit val scheduler = IndexedDb.scheduler

  abstract override def close(): Observable[String] = {
    super.close().dump("close")
  }

  abstract override def openStoreTx(name: String, txMode: TxAccessMode): Observable[StoreTx] = {
    super.openStoreTx(name, txMode).dump("openStoreTx")
  }

  abstract override def getStoreNames: Observable[List[String]] = {
    super.getStoreNames.dump("getStoreNames")
  }

  abstract override def count(storeName: String): Observable[Int] = {
    super.count(storeName).dump("count")
  }

  abstract override def clear(storeName: String): Observable[Nothing] = {
    super.clear(storeName).dump("clear")
  }

  abstract override def getLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    super.getLast[K,V](storeName).dump("getLast")
  }

  abstract override def get[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    super.get[K,V](storeName, keys:_*).dump("get")
  }

  abstract override def add[K : RW : ValidKey, V : W](storeName: String, optKey: Option[K], values: V*): Observable[K] = {
    super.add[K,V](storeName, optKey, values:_*).dump("add")
  }

  abstract override def delete[K : W : ValidKey](storeName: String, keys: K*): Observable[Nothing] = {
    super.delete[K](storeName, keys:_*).dump("delete")
  }

  abstract override def getAndDelete[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    super.getAndDelete[K,V](storeName, keys:_*).dump("getAndDelete")
  }

  abstract override def getAndDeleteLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    super.getAndDeleteLast[K,V](storeName).dump("getAndDeleteLast")
  }
}