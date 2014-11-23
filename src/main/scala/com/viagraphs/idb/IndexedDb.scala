package com.viagraphs.idb

import monifu.concurrent.Scheduler
import monifu.reactive.Observable
import monifu.reactive.channels.{AsyncChannel, PublishChannel, ReplayChannel}
import org.scalajs.dom.{ErrorEvent, Event, IDBCursorWithValue, IDBDatabase, IDBObjectStore, IDBOpenDBRequest, IDBVersionChangeEvent, console, window}

import scala.collection.mutable.ListBuffer
import scala.scalajs.js
import scala.util.control.NonFatal

class IndexedDb private(underlying: Observable[IDBDatabase])(implicit s: Scheduler) {
  import com.viagraphs.idb.IndexedDb._

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
  sealed trait Compatible[T]
  object Compatible {
    implicit object StringOk extends Compatible[String]
    implicit object IntOk extends Compatible[Int]
    implicit object DateOk extends Compatible[js.Date]
    implicit object ArrayOk extends Compatible[js.Array[_]]
    implicit object DynamicOk extends Compatible[js.Dynamic]
    implicit object ObjDynamicOk extends Compatible[DynamicObject]
    implicit object UnitOk extends Compatible[Unit]
    //TODO support more types
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
    getStore(storeName, RW).flatMap { store =>
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
    getStore(storeName, RW).flatMap { store =>
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

  def getLast[K : Compatible, V : Compatible](storeName: String): Observable[(K,V)] = {
    getStore(storeName, RO).flatMap { store =>
      val ch = AsyncChannel[(K,V)]()
      val req = store.openCursor(null, "prev")
      req.onsuccess = (e: Event) => {
        e.target.asInstanceOf[IDBOpenDBRequest].result match {
          case cursor: IDBCursorWithValue =>
            ch.pushNext((cursor.key.asInstanceOf[K], cursor.value.asInstanceOf[V]))
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

  def get[K : Compatible, V : Compatible](storeName: String, keys: K*): Observable[V] = {
    getStore(storeName, RO).flatMap { store =>
      val ch = PublishChannel[V]()
      def >>(it: Iterator[K]): Unit = {
        if (it.hasNext) {
          val req = store.get(it.next().asInstanceOf[js.Any])
          req.onsuccess = (e: Event) => {
            ch.pushNext(req.result.asInstanceOf[V])
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

  def add[K : Compatible, V : Compatible](storeName: String, optKey: Option[K], values: V*): Observable[K] = {
    getStore(storeName, RW).flatMap { store =>
      val ch = PublishChannel[K]()
      def >>(it: Iterator[V]): Unit = {
        if (it.hasNext) {
          val value = it.next().asInstanceOf[js.Any]
          val req = optKey.fold(store.add(value))(key => store.add(value, key.asInstanceOf[js.Any]))
          req.onsuccess = (e: Event) => {
            ch.pushNext(req.result.asInstanceOf[K])
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

  def delete[K : Compatible, V : Compatible](storeName: String, keys: K*): Observable[Unit] = {
    deleteInternally[K,Unit](storeName, ReplayChannel[Unit](), keys:_*)
  }

  def getAndDelete[K : Compatible, V : Compatible](storeName: String, keys: K*): Observable[V] = {
    get[K,V](storeName, keys:_*).buffer(keys.length).flatMap { values =>
      val ch = ReplayChannel[V]()
      ch.pushNext(values:_*)
      deleteInternally(storeName, ch, keys:_*)
    }
  }

  private def deleteInternally[K: Compatible, V : Compatible](storeName: String, ch: ReplayChannel[V], keys: K*): Observable[V] = {
    getStore(storeName, RW).flatMap { store =>
      def >>(it: Iterator[K]): Unit = {
        if (it.hasNext) {
          val req = store.delete(it.next().asInstanceOf[js.Any])
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
  type DynamicObject = js.Object with js.Dynamic
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

  object RW extends TxAccessMode {
    val value = "readwrite" /*(IDBTransaction.READ_WRITE : UndefOr[String]).getOrElse("readwrite")*/
  }

  object RO extends TxAccessMode {
    val value = "readonly" /*(IDBTransaction.READ_ONLY : UndefOr[String]).getOrElse("readonly")*/
  }

  object VC extends TxAccessMode {
    val value = "versionchange" /*(IDBTransaction.VERSION_CHANGE : UndefOr[String]).getOrElse("versionchange")*/
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