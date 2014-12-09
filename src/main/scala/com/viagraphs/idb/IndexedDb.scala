package com.viagraphs.idb

import monifu.concurrent.{Scheduler, UncaughtExceptionReporter}
import monifu.reactive.Ack.{Cancel, Continue}
import monifu.reactive.Observable
import monifu.reactive.internals.FutureAckExtensions
import org.scalajs.dom._
import upickle.Aliases.{R, RW, W}
import upickle._

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

//TODO returning subjects instead of observables as a way of aborting transaction

class IndexedDb private(val underlying: Observable[IDBDatabase]) {
  implicit val exceptionReporter = UncaughtExceptionReporter.LogExceptionsToStandardErr
  import com.viagraphs.idb.IndexedDb.ObservablePimp

  def openStoreTx(name: String, txMode: TxAccessMode): Observable[StoreTx] =
    Observable.create { observer =>
      underlying.foreachWith(observer) { db =>
        val tx = db.transaction(name, txMode.value)
        val store = tx.objectStore(name)
        observer.onNext(StoreTx(store, tx))
        observer.onComplete()
      }(db => s"Unable to openStoreTx $name in db ${db.name}")
    }
  

  def getName: Observable[String] =
    Observable.create { observer =>
      underlying.foreachWith(observer) { db =>
        observer.onNext(db.name)
        observer.onComplete()
      }(db => s"Unable to get database name")
    }
  

  /**
   * Set the internal closePending flag of connection to true.
   * Wait for all transactions created using connection to complete. Once they are complete, connection is closed.
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

  def count(storeName: String): Observable[Int] = {
    def errorMsg = s"Database.count($storeName) failed"
    Observable.create { observer =>
      openStoreTx(storeName, ReadOnly).foreachWith(observer) { case StoreTx(store, tx) =>
        val req = store.count()
        req.onsuccess = (e: Event) => {
          observer.onNext(e.target.asInstanceOf[IDBOpenDBRequest].result.asInstanceOf[Int])
          observer.onComplete()
        }
        req.onerror = (e: ErrorEvent) => {
          observer.onError(new IDbRequestException(errorMsg, req.error))
        }
      }(storeTx => errorMsg)
    }
  }

  //TODO should be transaction oriented ?
  def clear(storeName: String): Observable[Nothing] = {
    def errorMsg = s"Database.clear($storeName) failed"
    Observable.create { observer =>
      openStoreTx(storeName, ReadWrite).foreachWith(observer) { case StoreTx(store, tx) =>
        store.clear()
        tx.oncomplete = (e: Event) => {
          observer.onComplete()
        }
        tx.onerror = (e: ErrorEvent) => {
          observer.onError(new IDbRequestException(errorMsg, tx.error))
        }
      }(storeTx => errorMsg)
    }
  }

  def getLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    def errorMsg = s"Database.getLast($storeName) failed"
    Observable.create { observer =>
      openStoreTx(storeName, ReadOnly).foreachWith(observer) { case StoreTx(store, tx) =>
        val req = store.openCursor(null, "prev")
        req.onsuccess = (e: Event) => {
          e.target.asInstanceOf[IDBOpenDBRequest].result match {
            case cursor: IDBCursorWithValue =>
              try observer.onNext((readJs[K](json.readJs(cursor.key)), readJs[V](json.readJs(cursor.value)))) catch {
                case NonFatal(ex) =>
                  observer.onError(new IDbException(errorMsg, ex))
              }
            case cursor =>
          }
        }
        req.onerror = (e: ErrorEvent) => {
          observer.onError(new IDbRequestException(errorMsg, req.error))
        }
        tx.oncomplete = (e: Event) => {
          observer.onComplete()
        }
      }(storeTx => errorMsg)
    }
  }

  /**
   * @return observable of values that might be undefined if a key doesn't exist
   */
  def get[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    def errorKey(key: js.Any) = s"get $key from $storeName failed"
    def errorKeys = s"Unexpected error during get($storeName,$keys)"
      Observable.create { observer =>
      openStoreTx(storeName, ReadOnly).foreachWith(observer) { case StoreTx(store, tx) =>
        def >>(it: Iterator[K]): Unit = {
          if (it.hasNext) {
            try {
              val key = json.writeJs(writeJs[K](it.next())).asInstanceOf[js.Any]
              val req = store.get(key)
              req.onsuccess = (e: Event) => {
                observer.onNext(readJs[V](json.readJs(req.result))).onCompleteNow {
                  case Continue.IsSuccess =>
                    >>(it)
                  case _ =>
                }(IndexedDb.scheduler)
              }
              req.onerror = (e: ErrorEvent) => {
                observer.onError(new IDbRequestException(errorKey(key), req.error))
              }
            } catch {
              case NonFatal(ex) =>
                observer.onError(new IDbException(errorKeys, ex))
            }
          }
        }
        val it = keys.iterator
        if (it.hasNext) {
          tx.oncomplete = (e: Event) => {
            observer.onComplete()
          }
          tx.onerror = (e: ErrorEvent) => {
            observer.onError(new IDbTxException(errorKeys, tx.error))
          }
          >>(it)
        } else {
          observer.onComplete()
        }
      }(storeTx => errorKeys)
    }
  }

  /**
   * @param optKey //TODO what if Some optKey ?
   * @param values  Structured clones of values is created, beware that structure clone algorithm may fail
   */
  def add[K : RW : ValidKey, V : W](storeName: String, optKey: Option[K], values: V*): Observable[K] = {
    def errorValue(value: js.Any) =s"add $value to $storeName failed"
    def errorValues = s"Unexpected error during add($storeName,$optKey,$values)"
    Observable.create { observer =>
      openStoreTx(storeName, ReadWrite).foreachWith(observer) { case StoreTx(store, tx) =>
        def >>(it: Iterator[V]): Unit = {
          if (it.hasNext) {
            try {
              val value = json.writeJs(writeJs[V](it.next())).asInstanceOf[js.Any]
              val req = optKey.fold(store.add(value))(key => store.add(value, json.writeJs(writeJs[K](key)).asInstanceOf[js.Any]))
              req.onsuccess = (e: Event) => {
                observer.onNext(readJs[K](json.readJs(req.result))).onCompleteNow {
                  case Continue.IsSuccess =>
                    >>(it)
                  case _ =>
                }(IndexedDb.scheduler)
              }
              req.onerror = (e: Event) => {
                observer.onError(new IDbRequestException(errorValue(value), req.error))
              }
            } catch {
              case NonFatal(ex) =>
                observer.onError(new IDbException(errorValues, ex))
            }
          }
        }
        val it = values.iterator
        if (it.hasNext) {
          tx.oncomplete = (e: Event) => {
            observer.onComplete()
          }
          tx.onerror = (e: Event) => {
            observer.onError(new IDbTxException(errorValues, tx.error))
          }
          >>(it)
        } else {
          observer.onComplete()
        }
      }(storeTx => errorValues)
    }
  }

  //TODO The IDBKeyRange interface defines a key range.

  def getAndDeleteLast[K : R : ValidKey, V : R](storeName: String): Observable[(K,V)] = {
    def errorMsg = s"Database.getAndDeleteLast($storeName) failed"
    Observable.create { observer =>
      openStoreTx(storeName, ReadWrite).foreachWith(observer) { case StoreTx(store, tx) =>
        val req = store.openCursor(null, "prev")
        tx.oncomplete = (e: Event) => {
          observer.onComplete()
        }
        tx.onerror = (e: Event) => {
          observer.onError(new IDbTxException(errorMsg, tx.error))
        }
        req.onsuccess = (e: Event) => {
          e.target.asInstanceOf[IDBOpenDBRequest].result match {
            case cursor: IDBCursorWithValue =>
              try {
                observer.onNext((readJs[K](json.readJs(cursor.key)), readJs[V](json.readJs(cursor.value))))
                cursor.delete()
              } catch {
                case NonFatal(ex) =>
                  observer.onError(new IDbException(errorMsg, ex))
              }
            case cursor =>
          }
        }
      }(storeTx => errorMsg)
    }
  }

  def getAndDelete[K : W : ValidKey, V : R](storeName: String, keys: K*): Observable[V] = {
    implicit val s = IndexedDb.scheduler
    get[K,V](storeName, keys:_*).flatMapOnComplete { values =>
      delete[K](storeName, keys:_*).endWith(values:_*)
    }
  }

  def delete[K: W : ValidKey](storeName: String, keys: K*): Observable[Nothing] = {
    def errorKey(key: js.Any) = s"delete $key in $storeName failed"
    def errorKeys = s"Unable to delete($storeName, $keys)"
    Observable.create[Nothing] { observer =>
      openStoreTx(storeName, ReadWrite).foreachWith(observer) { case StoreTx(store, tx) =>
        def >>(it: Iterator[K]): Unit = {
          if (it.hasNext) {
            try {
              val key = json.writeJs(writeJs[K](it.next())).asInstanceOf[js.Any]
              val req = store.delete(key)
              req.onsuccess = (e: Event) => {
                >>(it)
              }
              req.onerror = (e: Event) => {
                observer.onError(new IDbRequestException(errorKey(key), req.error))
              }
            } catch {
              case NonFatal(ex) =>
                observer.onError(new IDbException(errorKeys, ex))
            }
          }
        }
        val it = keys.iterator
        if (it.hasNext) {
          tx.oncomplete = (e: Event) => {
            observer.onComplete()
          }
          tx.onerror = (e: Event) => {
            observer.onError(new IDbTxException(errorKeys, tx.error))
          }
          >>(it)
        }
      }(storeTx => errorKeys)
    }
  }

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

class IDbException(msg: String, cause: Throwable) extends Exception(msg, cause)
case class IDbRequestException(message: String, error: DOMError) extends IDbException(message, new Exception(error.name))
case class IDbTxException(message: String, error: DOMError) extends IDbException(message, new Exception(error.name))

case class StoreTx(store: IDBObjectStore, tx: IDBTransaction)

object IndexedDb {
  val scheduler = Scheduler.trampoline()

  val WebkitGetDatabaseNames = "webkitGetDatabaseNames"

  import scala.collection.immutable.TreeMap
  implicit def TreeMapW[K : W : Ordering, V : W]: W[TreeMap[K, V]] =  W[TreeMap[K, V]](
    x => Js.Arr(x.toSeq.map(writeJs[(K, V)]):_*)
  )

  implicit def TreeMapR[K : R : Ordering, V : R] : R[TreeMap[K, V]] = R[TreeMap[K, V]](
    Internal.validate("Array(n)"){
      case x: Js.Arr => TreeMap(x.value.map(readJs[(K, V)]):_*)
    }
  )

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
   * Do not delete database that is currently open, it is your responsibility to close it prior deletion
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
    }(scheduler)
  }

  /**
   * @note The IDBDatabase interface represents a connection to a database, there might be multiple connections within one origin
   */
  def apply(mode: IdbInitMode): IndexedDb = {

    val asyncDbObs = Observable.create[IDBDatabase] { observer =>

      /**
       * IDBFactory.open call doesn't create transaction !
       */
      def registerOpenCallbacks(req: IDBOpenDBRequest, upgradeOpt: Option[IDBDatabase => IDBObjectStore]): Unit = {
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
    }.publishLast()(IndexedDb.scheduler)
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

  implicit class ObservablePimp[E](observable: Observable[E]) {
    import monifu.reactive.Ack.Continue
    import monifu.reactive.{Observable, Observer}

    def foreachWith(delegate: Observer[_])(cb: E => Unit)(msg: E => String)(implicit r: UncaughtExceptionReporter): Unit =
      observable.unsafeSubscribe(
        new Observer[E] {
          def onNext(elem: E) =
            try { cb(elem); Continue } catch {
              case NonFatal(ex) =>
                onError(ex, elem)
                Cancel
            }

          def onComplete() = ()
          def onError(ex: Throwable) = ???
          def onError(ex: Throwable, elem: E) = {
            delegate.onError(new IDbException(msg(elem), ex))
          }
        }
      )

    def flatMapOnComplete[U](f: Seq[E] => Observable[U]): Observable[U] =
      flatMapOnComplete(observable.buffer(Integer.MAX_VALUE)(IndexedDb.scheduler).map(f))

    private def flatMapOnComplete[U,T](source: Observable[T])(implicit ev: T <:< Observable[U]): Observable[U] =
      Observable.create[U] { observerU =>
        source.unsafeSubscribe(new Observer[T] {
          private[this] var childObservable: T = _

          def onNext(elem: T) = {
            childObservable  = elem
            Continue
          }

          def onError(ex: Throwable) = {
            observerU.onError(ex)
          }

          def onComplete() = {
            Option(childObservable).fold(observerU.onComplete()) { obs =>
              obs.unsafeSubscribe(new Observer[U] {
                def onNext(elem: U) = {
                  observerU.onNext(elem)
                }
                def onError(ex: Throwable): Unit = {
                  observerU.onError(ex)
                }

                def onComplete(): Unit = {
                  observerU.onComplete()
                }
              })
            }
          }
        })
      }
  }
}