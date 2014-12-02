package com.viagraphs.idb

import monifu.concurrent.Scheduler
import monifu.reactive.Observable
import monifu.reactive.channels.{AsyncChannel, PublishChannel, ReplayChannel}
import org.scalajs.dom.{ErrorEvent, Event, IDBCursorWithValue, IDBDatabase, IDBObjectStore, IDBOpenDBRequest, IDBVersionChangeEvent, console, window}
import upickle._
import upickle.Aliases.{RW, W, R}

import scala.collection.mutable.ListBuffer
import scala.scalajs.js
import scala.util.control.NonFatal

/**
 * A store key might be :
 *    Number primitive value, String primitive value, Date object, or Array object.
 *    An Array is only a valid key if every item in the array is defined and is a valid key
 *
 * The object store can derive the key from one of three sources :
 *    A key generator. A key generator generates a monotonically increasing numbers every time a key is needed.
 *    Keys can be derived via a key path.
 *    Keys can also be explicitly specified when a value is stored in the object store.

 * A store value might be :
 *    simple types such as String primitive values and Date objects as well as Object,
 *    Array instances, File objects, Blob objects, ImageData objects, and so on
 *
 * MDN
*/
class IndexedDb private(underlying: Observable[IDBDatabase])(implicit s: Scheduler) {
  import IndexedDb._

  def close(): Unit = {
    underlying.map { db =>
      try {
        db.close()
      } catch {
        case NonFatal(ex) => throw new Exception(s"Unable to close database ${db.name}", ex)
      }
    }
  }

  def getStore(name: String, txMode: TxAccessMode): Observable[IDBObjectStore] = {
    underlying.map { db =>
      try {
        db.transaction(name, txMode.value).objectStore(name)
      } catch {
        case NonFatal(ex) => throw new Exception(s"Unable to get store $name", ex)
      }
    }
  }

  def storeNames: Observable[List[String]] = {
    underlying.map { db =>
      val names = db.objectStoreNames
      val result = ListBuffer[String]()
      for (i <- 0 until names.length) {
        result += names.item(i)
      }
      result.toList
    }
  }

  def count(storeName: String): Observable[Int] = {
    getStore(storeName, ReadWrite).flatMap { store =>
      val ch = AsyncChannel[Int]()
      val req = store.count()
      val tx = req.transaction
      req.onsuccess = (e: Event) => {
        ch.pushNext(e.target.asInstanceOf[IDBOpenDBRequest].result.asInstanceOf[Int])
      }
      tx.oncomplete = (e: Event) => {
        ch.pushComplete()
      }
      req.onerror = (e: Event) => {
        ch.pushError(new Exception(s"Database.clear($storeName) failed " + req.error.name))
      }
      tx.onerror = (e: Event) => {
        ch.pushError(new Exception(s"Database.clear($storeName) failed " + tx.error.name))
      }
      ch
    }
  }

  def clear(storeName: String): Observable[Unit] = {
    getStore(storeName, ReadWrite).flatMap { store =>
      Observable.create { observer =>
        val req = store.clear()
        val tx = req.transaction
        tx.oncomplete = (e: Event) => {
          observer.onComplete()
        }
        req.onerror = (e: Event) => {
          observer.onError(new Exception(s"Database.clear($storeName) failed " + req.error.name))
        }
        tx.onerror = (e: Event) => {
          observer.onError(new Exception(s"Database.clear($storeName) failed " + tx.error.name))
        }
      }
    }
  }

  def getLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    getStore(storeName, ReadOnly).flatMap { store =>
      val ch = AsyncChannel[(K,V)]()
      val req = store.openCursor(null, "prev")
      req.onsuccess = (e: Event) => {
        e.target.asInstanceOf[IDBOpenDBRequest].result match {
          case cursor: IDBCursorWithValue =>
            ch.pushNext((readJs[K](json.readJs(cursor.key)), readJs[V](json.readJs(cursor.value))))
            ch.pushComplete()
          case cursor =>
            ch.pushComplete()
        }
      }
      req.onerror = (e: Event) => {
        ch.pushError(new Exception(s"Database.getLast($storeName) failed " + req.error.name))
      }
      ch
    }
  }

  def get[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    getStore(storeName, ReadOnly).flatMap { store =>
      val ch = PublishChannel[V]()
      def >>(it: Iterator[K]): Unit = {
        if (it.hasNext) {
          val key = json.writeJs(writeJs[K](it.next())).asInstanceOf[js.Any]
          val req = store.get(key)
          req.onsuccess = (e: Event) => {
            ch.pushNext(readJs[V](json.readJs(req.result)))
            >>(it)
          }
          req.onerror = (e: Event) => {
            ch.pushError(new Exception(s"Database.get($storeName, $keys) failed " + req.error.name))
          }
        }
      }
      val it = keys.iterator
      if (it.hasNext) {
        val tx = store.transaction
        tx.oncomplete = (e: Event) => {
          ch.pushComplete()
        }
        tx.onerror = (e: Event) => {
          ch.pushError(new Exception(s"Database.get($storeName, $keys) failed " + tx.error.name))
        }
        >>(it)
        ch
      } else {
        Observable.empty
      }
    }
  }

  def add[K : RW : ValidKey, V : W](storeName: String, optKey: Option[K], values: V*): Observable[K] = {
    getStore(storeName, ReadWrite).flatMap { store =>
      val ch = PublishChannel[K]()
      def >>(it: Iterator[V]): Unit = {
        if (it.hasNext) {
          val value = json.writeJs(writeJs[V](it.next())).asInstanceOf[js.Any]
          val req = optKey.fold(store.add(value))(key => store.add(value, json.writeJs(writeJs[K](key)).asInstanceOf[js.Any]))
          req.onsuccess = (e: Event) => {
            ch.pushNext(readJs[K](json.readJs(req.result))) //TODO what if Some optKey ?
            >>(it)
          }
          req.onerror = (e: Event) => {
            ch.pushError(new Exception(s"Database.add($storeName, $values) failed " + req.error.name))
          }
        }
      }
      val it = values.iterator
      if (it.hasNext) {
        val tx = store.transaction
        tx.oncomplete = (e: Event) => {
          ch.pushComplete()
        }
        tx.onerror = (e: Event) => {
          ch.pushError(new Exception(s"Database.add($storeName, $values) failed " + tx.error.name))
        }
        >>(it)
        ch
      } else {
        Observable.empty
      }
    }
  }

  def delete[K : W : ValidKey](storeName: String, keys: K*): Observable[Unit] = {
    deleteInternally[K,Unit](storeName, ReplayChannel[Unit](), keys:_*)
  }

  def getAndDelete[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    get[K,V](storeName, keys:_*).buffer(keys.length).flatMap { values =>
      val ch = ReplayChannel[V]()
      ch.pushNext(values:_*)
      deleteInternally[K,V](storeName, ch, keys:_*)
    }
  }

  def getAndDeleteLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    getStore(storeName, ReadWrite).flatMap { store =>
      val ch = AsyncChannel[(K,V)]()
      val req = store.openCursor(null, "prev")
      val tx = store.transaction
      tx.oncomplete = (e: Event) => {
        ch.pushComplete()
      }
      tx.onerror = (e: Event) => {
        ch.pushError(new Exception(s"Database.getAndDeleteLast($storeName) failed " + tx.error.name))
      }
      req.onsuccess = (e: Event) => {
        e.target.asInstanceOf[IDBOpenDBRequest].result match {
          case cursor: IDBCursorWithValue =>
            ch.pushNext((readJs[K](json.readJs(cursor.key)), readJs[V](json.readJs(cursor.value))))
            cursor.delete()
          case cursor =>
            ch.pushComplete()
        }
      }
      ch
    }
  }

  private def deleteInternally[K: W : ValidKey, V](storeName: String, ch: ReplayChannel[V], keys: K*): Observable[V] = {
    getStore(storeName, ReadWrite).flatMap { store =>
      def >>(it: Iterator[K]): Unit = {
        if (it.hasNext) {
          val key = json.writeJs(writeJs[K](it.next())).asInstanceOf[js.Any]
          val req = store.delete(key)
          req.onsuccess = (e: Event) => {
            >>(it)
          }
          req.onerror = (e: Event) => {
            ch.pushError(new Exception(s"Database.delete($storeName, $keys) failed " + req.error.name))
          }
        }
      }
      val it = keys.iterator
      if (it.hasNext) {
        val tx = store.transaction
        tx.oncomplete = (e: Event) => {
          ch.pushComplete()
        }
        tx.onerror = (e: Event) => {
          ch.pushError(new Exception(s"Database.delete($storeName, $keys) failed " + tx.error.name))
        }
        >>(it)
        ch
      } else {
        Observable.empty
      }
    }
  }
}

object IndexedDb {
  import scala.collection.immutable.TreeMap
  implicit def TreeMapW[K : W : Ordering, V : W]: W[TreeMap[K, V]] =  W[TreeMap[K, V]](
    x => Js.Arr(x.toSeq.map(writeJs[(K, V)]):_*)
  )

  implicit def TreeMapR[K : R : Ordering, V : R] : R[TreeMap[K, V]] = R[TreeMap[K, V]](
    Internal.validate("Array(n)"){
      case x: Js.Arr => TreeMap(x.value.map(readJs[(K, V)]):_*)
    }
  )

  /**
   * Init modes are primarily designed for self explanatory purposes because IndexedDB API is quite ambiguous in this matter
   */
  sealed trait DbInitMode {
    def name: String
    def version: Int
    def defineObjectStores: IDBDatabase => IDBObjectStore
  }

  case class NewDb(name: String, defineObjectStores: IDBDatabase => IDBObjectStore) extends DbInitMode {
    def version = ???
  }

  case class RecreateDb(name: String, defineObjectStores: IDBDatabase => IDBObjectStore) extends DbInitMode {
    def version = ???
  }

  case class UpgradeDb(name: String, version: Int, defineObjectStores: IDBDatabase => IDBObjectStore) extends DbInitMode

  case class  OpenDb(name: String) extends DbInitMode {
    def version: Int = ???
    def defineObjectStores: (IDBDatabase) => IDBObjectStore = ???
  }

  //TODO these constants are typed as java.lang.String in scala-js-dom which throws an error if not implemented by browsers
  sealed trait TxAccessMode {
    def value: String
  }

  object ReadWrite extends TxAccessMode {
    val value = "readwrite" /*(IDBTransaction.READ_WRITE : UndefOr[String]).getOrElse("readwrite")*/
  }

  object ReadOnly extends TxAccessMode {
    val value = "readonly" /*(IDBTransaction.READ_ONLY : UndefOr[String]).getOrElse("readonly")*/
  }

  object VersionChange extends TxAccessMode {
    val value = "versionchange" /*(IDBTransaction.VERSION_CHANGE : UndefOr[String]).getOrElse("versionchange")*/
  }

  /**
   * Type Class that puts a view bound on Key types. Value types are not restricted much so I don't handle that
   */
  sealed trait ValidKey[T]
  object ValidKey {
    implicit object StringOk extends ValidKey[String]
    implicit object IntOk extends ValidKey[Int]
    implicit object IntSeqOk extends ValidKey[Seq[Int]]
    implicit object IntArrayOk extends ValidKey[Array[Int]]
    implicit object StringSeqOk extends ValidKey[Seq[String]]
    implicit object StringArrayOk extends ValidKey[Array[String]]
    implicit object JsDateOk extends ValidKey[js.Date]
  }

  def apply(mode: DbInitMode)(implicit s: Scheduler): IndexedDb = {
    val channel = AsyncChannel[Event]()
    val dbObservable = channel.map { event =>
      event.target.asInstanceOf[IDBOpenDBRequest].result.asInstanceOf[IDBDatabase]
    }.replay()
    dbObservable.connect()

    def registerOpenCallbacks(req: IDBOpenDBRequest, upgradeOpt: Option[IDBDatabase => IDBObjectStore]): Unit = {
      upgradeOpt.foreach { upgrade =>
        req.onupgradeneeded = (ve: IDBVersionChangeEvent) => {
          upgrade(ve.target.asInstanceOf[IDBOpenDBRequest].result.asInstanceOf[IDBDatabase])
        }
      }
      req.onsuccess = (e: Event) => {
        channel.pushNext(e)
        channel.pushComplete()
      }
      req.onerror = (e: ErrorEvent) => {
        console.info("Trying open DB but error " + req.error.name)
      }
      req.onblocked = (e: Event) => {
        console.info("Trying open DB but blocked " + req.error.name)
      }
    }

    val factory = window.indexedDB
    mode match {
      case NewDb(name, defineObjectStores) =>
        registerOpenCallbacks(factory.open(name), Some(defineObjectStores))
      case UpgradeDb(name, version, defineObjectStores) =>
        registerOpenCallbacks(factory.open(name, version), Some(defineObjectStores))
      case OpenDb(name) =>
        registerOpenCallbacks(factory.open(name), None)
      case RecreateDb(name, defineObjectStores) =>
        val delReq = factory.deleteDatabase(name)
        delReq.onsuccess = (e: Event) => {
          registerOpenCallbacks(factory.open(name), Some(defineObjectStores))
        }
        delReq.onerror = (e: Event) => {
          console.info("Trying delete DB but error " + delReq.error.name)
        }
    }

    new IndexedDb(dbObservable)
  }

}