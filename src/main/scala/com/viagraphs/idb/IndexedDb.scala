package com.viagraphs.idb

import monifu.concurrent.Scheduler
import monifu.reactive.Observable
import org.scalajs.dom._
import upickle.Aliases.{R, W}

import scala.annotation.implicitNotFound
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * A store key might be :
 *    Number primitive value, String primitive value, Date object, or Array object.
 *    An Array is only a valid key if every item in the array is defined and is a valid key
 *
 * The object store can derive the key from one of three sources :
 *    A key generator. A key generator generates a monotonically increasing numbers every time a key is needed.
 *    Keys can be derived via a key path.
 *    Keys can also be explicitly specified when a value is stored in the object store.
 *
 * A store value might be :
 *    simple types such as String primitive values and Date objects as well as Object,
 *    Array instances, File objects, Blob objects, ImageData objects, and so on
 *
 *
 * Tx is committed when :
 *
 *    - request success callback returns
 *         - that means that multiple requests can be executed within transaction boundaries only when next request is executed from success callback of the previous one
 *    - when your task returns to event loop
 *
 * It means that if no requests are submitted to it, it is not committed until it returns to event loop.
 *
 * These facts pose 2 problematic states :
 *
 *    - enqueuing a new IDB request to event loop from within the success callback of previous request instead of submitting new request synchronously
 *        - in that case the first success callback immediately returns but another IDB request has been scheduled
 *            - ??? are all the asynchronous requests executed within the single initial tx ???
 *
 *
 *    - creating a ReadWrite tx, not placing any requests against it and creating another one before returning to event loop
 *        - ??? does creating a new one implicitly commits the previous tx?  If not, serious write lock starvations might occur, right ???
 *
 */
class IndexedDb private(val underlying: Observable[IDBDatabase]) {

  /**
   *
   * @param name object store name
   * @tparam K type of store keys, it must have uPickle's Reader and Writer evidence and it must be a ValidKey
   * @tparam V type of store values, it must have uPickle's Reader and Writer evidence
   * @return Store that requests are initiated from
   */
  def openStore[K : W : R : ValidKey, V : W : R](name: String) = new Store[K, V](name, underlying)

  /**
   * @return observable of name of this database
   */
  def getName: Observable[String] =
    Observable.create { observer =>
      underlying.foreachWith(observer) { db =>
        observer.onNext(db.name)
        observer.onComplete()
      }(db => s"Unable to get database name")
    }

  /**
   * @return Observable of one element - name of this database that was closed
   * Internally it sets the closePending flag of connection to true.
   * Waits for all transactions created using connection to complete. Once they are complete, connection is closed.
   */
  def close(): Observable[String] = {
    Observable.create { observer =>
      underlying.foreachWith(observer) { db =>
        val dbName = db.name
        db.close()
        observer.onNext(dbName)
        observer.onComplete()
      }(db => s"Unable to close database ${db.name}")
    }
  }

  /**
   * @return Observable of one element - name of this database that was deleted
   * @note IDBDatabase has a delete pending flag which is used during deletion.
   *       When a database is requested to be deleted the flag is set to true and all attempts at opening the database are stalled until the database can be deleted.
   */
  def delete(): Observable[String] = {
    def errorMsg(arg: String) = s"Deleting database $arg failed"
    Observable.create { observer =>
      close().foreachWith(observer) { dbName =>
        val delReq = window.indexedDB.deleteDatabase(dbName)
        delReq.onsuccess = (e: Event) => {
          observer.onNext(dbName)
          observer.onComplete()
        }
        delReq.onerror = (e: Event) => {
          observer.onError(new IDbRequestException(errorMsg(dbName), delReq.error))
        }
      }(dbName => errorMsg(dbName))
    }
  }

  /**
   * @return observable of names of all stores in this database
   */
  def getStoreNames: Observable[List[String]] = {
    def errorMsg(arg: String) = s"Unable to get storeNames of $arg"
    Observable.create { observer =>
      underlying.foreachWith(observer) { db =>
        try {
          val names = db.objectStoreNames
          val result = ListBuffer[String]()
          for (i <- 0 until names.length) {
            result += names.item(i)
          }
          result.toList
        } catch {
          case NonFatal(ex) =>
            observer.onError(new IDbException(errorMsg(db.name), ex))
        }
      }(db => errorMsg(db.name))
    }
  }
}

object IndexedDb {
  implicit val scheduler = Scheduler.trampoline()

  /* the only way to find out whether a database exists */
  val WebkitGetDatabaseNames = "webkitGetDatabaseNames"

  def getDatabaseNames: Future[DOMStringList] = {
    val req = window.indexedDB.asInstanceOf[js.Dynamic].applyDynamic(WebkitGetDatabaseNames)().asInstanceOf[IDBRequest]
    val promise = Promise[DOMStringList]()
    req.onsuccess = (e: Event) => {
      promise.success(e.target.asInstanceOf[IDBRequest].result.asInstanceOf[DOMStringList])
    }
    req.onerror = (e: ErrorEvent) => {
      promise.failure(new IDbRequestException(s"Unable to get db names", req.error))
    }
    promise.future
  }

  /**
   * @note Do not delete database that is currently open, it is your responsibility to close it prior deletion
   */
  def deleteIfPresent(dbName: String): Future[Boolean] = {
    getDatabaseNames.flatMap { databaseNames =>
      val promise = Promise[Boolean]()
      if (databaseNames.contains(dbName)) {
        val delReq = window.indexedDB.deleteDatabase(dbName)
        delReq.onsuccess = (e: Event) => {
          promise.success(true)
        }
        delReq.onerror = (e: Event) => {
          promise.failure(new IDbRequestException(s"Unable to delete db $dbName", delReq.error))
        }
      } else {
        promise.success(false)
      }
      promise.future
    }
  }

  /**
   * @note The IDBDatabase interface represents a connection to a database, there might be multiple connections within one origin
   */
  def apply(mode: IdbInitMode): IndexedDb = {

    val asyncDbObs = Observable.create[IDBDatabase] { observer =>

      /**
       * IDBFactory.open call doesn't create transaction !
       */
      def registerOpenCallbacks(req: IDBOpenDBRequest, upgradeOpt: Option[IDBDatabase => Unit]): Unit = {
        upgradeOpt.foreach { upgrade =>
          req.onupgradeneeded = (ve: IDBVersionChangeEvent) => {
            upgrade(ve.target.asInstanceOf[IDBOpenDBRequest].result.asInstanceOf[IDBDatabase])
          }
        }
        req.onsuccess = (e: Event) => {
          observer.onNext(e.target.asInstanceOf[IDBOpenDBRequest].result.asInstanceOf[IDBDatabase])
          observer.onComplete()
        }
        req.onerror = (e: ErrorEvent) => {
          observer.onError(new IDbRequestException("Openning db connection failed", req.error))
        }
        req.onblocked = (e: Event) => {
          console.warn("Trying open DB but blocked " + req.error.name)
        }
      }

      val factory = window.indexedDB
      mode match {
        case NewDb(dbName, defineObjectStores) =>
          registerOpenCallbacks(factory.open(dbName), Some(defineObjectStores))
        case UpgradeDb(dbName, version, defineObjectStores) =>
          registerOpenCallbacks(factory.open(dbName, version), Some(defineObjectStores))
        case OpenDb(dbName) =>
          registerOpenCallbacks(factory.open(dbName), None)
        case RecreateDb(dbName, defineObjectStores) =>
          deleteIfPresent(dbName).onComplete {
            case Success(deleted) =>
              registerOpenCallbacks(factory.open(dbName), Some(defineObjectStores))
            case Failure(ex) =>
              observer.onError(ex)
          }(Scheduler.trampoline())
      }
    }.publishLast()
    asyncDbObs.connect()

    mode match {
      case m: Profiling =>
        new IndexedDb(asyncDbObs) with Profiler
      case m: Logging =>
        new IndexedDb(asyncDbObs) with Logger
      case _ =>
        new IndexedDb(asyncDbObs)
    }

  }
}