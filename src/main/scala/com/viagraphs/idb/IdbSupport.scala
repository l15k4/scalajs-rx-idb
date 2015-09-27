package com.viagraphs.idb

import monifu.concurrent.Scheduler
import monifu.concurrent.atomic.Atomic
import monifu.reactive.Ack.Continue
import monifu.reactive.{Subscriber, Ack, Observer, Observable}
import org.scalajs.dom.raw._
import upickle.Aliases._
import upickle._
import scala.annotation.implicitNotFound
import scala.collection.mutable.{ListBuffer, ArrayBuffer}
import scala.concurrent.{Promise, Future}
import scala.scalajs.js
import scala.util.control.NonFatal
import scala.language.higherKinds
import scala.concurrent.duration._

class IDbException(msg: String, cause: Throwable) extends Exception(msg, cause)
case class IDbRequestException(message: String, error: DOMError) extends IDbException(message, new Exception(error.name))
case class IDbTxException(message: String, error: DOMError) extends IDbException(message, new Exception(error.name))

/**
 * If multiple "readwrite" transactions are attempting to access the same object store (i.e. if they have overlapping scope),
 * the transaction that was created first must be the transaction which gets access to the object store first.
 * Due to the requirements in the previous paragraph, this also means that it is the only transaction which has access to the object store until the transaction is finished.
 */
//TODO these constants are typed as java.lang.String in scala-js-dom which throws an error if not implemented by browsers
sealed trait TxAccess {
  def value: String
  def storeNames: Seq[String]
}
case class ReadWrite(storeNames: String*) extends TxAccess {
  val value = "readwrite" /*(IDBTransaction.READ_WRITE : UndefOr[String]).getOrElse("readwrite")*/
}
case class ReadOnly(storeNames: String*) extends TxAccess {
  val value = "readonly" /*(IDBTransaction.READ_ONLY : UndefOr[String]).getOrElse("readonly")*/
}
case class VersionChange(storeNames: String*) extends TxAccess {
  val value = "versionchange" /*(IDBTransaction.VERSION_CHANGE : UndefOr[String]).getOrElse("versionchange")*/
}

/**
 * KeyRange might be iterated even descendingly (prev)
 */
sealed trait Direction {
  def value: String
}
object Direction {
  case object Next extends Direction {
    def value: String = "next"
  }
  case object Prev extends Direction {
    def value: String = "prev"
  }
}

/**
 * Init modes are primarily designed for self explanatory purposes because IndexedDB API is quite ambiguous in this matter
 *
 * If db was found, wait until the following conditions are all fulfilled:
 *    No already existing connections to db, have non-finished "versionchange" transaction.
 *    If db has its delete pending flag set, wait until db has been deleted.
 *
 */
sealed trait IdbInitMode {
  def name: String

  /**
   * @note If the version of db is higher than version, return a DOMError of type VersionError.
   */
  def version: Int

  /**
   * create and define object stores, indices, etc.
   */
  val defineObjectStores: Option[(IDBDatabase, IDBVersionChangeEvent) => Unit]
}

/**
 * Create new or open an existing database, use defineObjectStores to define object stores
 * @param defineObjectStores specify in case database might not exist yet
 */
case class OpenDb(name: String, defineObjectStores: Option[(IDBDatabase, IDBVersionChangeEvent) => Unit]) extends IdbInitMode {
  def version = ???
}

/**
 * Delete an existing database of this name and creates new one by defineObjectStores
 */
case class RecreateDb(name: String, defineObjectStores: Some[(IDBDatabase, IDBVersionChangeEvent) => Unit]) extends IdbInitMode {
  def version = ???
}

/**
 * Upgrades an existing database to a new version. Use defineObjectStores to alter existing store definitions
 */
case class UpgradeDb(name: String, version: Int, defineObjectStores: Some[(IDBDatabase, IDBVersionChangeEvent) => Unit]) extends IdbInitMode


/**
 * Type Class that puts a view bound on key types. Value types are not restricted much so I don't handle that
 */
@implicitNotFound("No implicit ValidKey defined for ${T}, thus it is not a valid Store Key type")
sealed trait ValidKey[T]
object ValidKey {
  implicit object StringOk extends ValidKey[String]
  implicit object IntOk extends ValidKey[Int]
  implicit object DoubleOk extends ValidKey[Double]
  implicit object IntSeqOk extends ValidKey[Seq[Int]]
  implicit object DoubleSeqOk extends ValidKey[Seq[Double]]
  implicit object IntArrayOk extends ValidKey[Array[Int]]
  implicit object DoubleArrayOk extends ValidKey[Array[Double]]
  implicit object StringSeqOk extends ValidKey[Seq[String]]
  implicit object StringArrayOk extends ValidKey[Array[String]]
  implicit object JsDateOk extends ValidKey[js.Date]
}

abstract class IdbSupport[K : W : R : ValidKey, V : W : R](var storeName: String, dbRef: Atomic[Observable[IDBDatabase]]) {

  abstract class IndexRequest[I, O, C[_]](input: C[I], tx: Tx[C]) extends Request[I, O, C](input, tx) {
    def executeOnIndex(store: IDBIndex, input: Either[I, Key[I]]): IDBRequest
  }

  /**
   * Request is an observable of Output/results
   * @param input an [[scala.collection.Iterable]] or [[com.viagraphs.idb.IdbSupport.Key]] of keys or values transaction is created over
   * @param tx transaction strategy for either [[scala.collection.Iterable]] or [[com.viagraphs.idb.IdbSupport.Key]]
   */
  abstract class Request[I, O, C[_]](val input: C[I], tx: Tx[C]) extends Observable[O] {
    def txAccess: TxAccess
    def execute(store: IDBObjectStore, input: Either[I, Key[I]]): IDBRequest
    def onSuccess(result: Either[(I, js.Any), IDBCursorWithValue], observer: Observer[O]): Future[Ack]
    def onError(input: Option[I] = None): String
    def onSubscribe(subscriber: Subscriber[O]): Unit = {
      import scala.scalajs.js.JSConverters._
      dbRef.get.foreachWith(subscriber) { db =>
        val transaction = db.transaction(txAccess.storeNames.toJSArray, txAccess.value)
        tx.execute[I, O](this, transaction, subscriber)
      }(db => s"Unable to open transaction for request $this")(IndexedDb.scheduler)
    }
  }

  /**
   * IDB is requested by providing store keys as scala [[scala.collection.Iterable]] or this [[com.viagraphs.idb.IdbSupport.Key]].
   * As [[scala.collection.Iterable]] is a type constructor, [[com.viagraphs.idb.IdbSupport.Key]] must have become type constructor too to make abstraction over both
   * @note that in case of Update operation you need to provide entries with new values
   */
  sealed trait Key[_] {
    def range: IDBKeyRange
    def direction: Direction
    def entries: Map[K,V]
  }
  case class rangedKey(range: IDBKeyRange, direction: Direction, entries: Map[K,V] = Map()) extends Key[K]
  case class allKeys(direction: Direction, entries: Map[K,V] = Map()) extends Key[K] {
    def range: IDBKeyRange = null
  }
  case class lastKey(entries: Map[K,V] = Map()) extends Key[K] {
    def range: IDBKeyRange = null
    def direction: Direction = Direction.Prev
  }
  case class firstKey(entries: Map[K,V] = Map()) extends Key[K] {
    def range: IDBKeyRange = null
    def direction: Direction = Direction.Next
  }

  /**
   * Type class representing an abstraction over store's ability having key on keypath or autogenerated
   * @tparam I either K or (K,V)
   */
  @implicitNotFound("No implicit StoreKeyPolicy defined for ${I}, only V or (K,V) types are supported")
  trait StoreKeyPolicy[I] {
    def add(input: I, store: IDBObjectStore): IDBRequest
    def put(input: I, store: IDBObjectStore): IDBRequest
    def value(input: I): V
  }
  object StoreKeyPolicy {
    implicit object implicitly extends StoreKeyPolicy[V] {
      def value(input: V): V = input
      def add(input: V, store: IDBObjectStore): IDBRequest = {
        store.add(json.writeJs(writeJs[V](input)).asInstanceOf[js.Any])
      }
      def put(input: V, store: IDBObjectStore): IDBRequest = {
        store.put(json.writeJs(writeJs[V](input)).asInstanceOf[js.Any])
      }
    }
    implicit object explicitly extends StoreKeyPolicy[(K,V)] {
      def value(input: (K,V)): V = input._2
      def add(input: (K,V), store: IDBObjectStore): IDBRequest = {
        store.add(
          json.writeJs(writeJs[V](input._2)).asInstanceOf[js.Any],
          json.writeJs(writeJs[K](input._1)).asInstanceOf[js.Any]
        )
      }
      def put(input: (K,V), store: IDBObjectStore): IDBRequest = {
        store.put(
          json.writeJs(writeJs[V](input._2)).asInstanceOf[js.Any],
          json.writeJs(writeJs[K](input._1)).asInstanceOf[js.Any]
        )
      }
    }
  }

  /**
   * Type class representing a transaction strategy over request input that is either [[scala.collection.Iterable]] or [[com.viagraphs.idb.IdbSupport.Key]]
   * @tparam C either [[scala.collection.Iterable]] or [[com.viagraphs.idb.IdbSupport.Key]] type constructor
   */
  @implicitNotFound("No implicit Tx defined for ${C}, only Key and Iterable types are supported")
  abstract class Tx[C[_]] {
    def execute[I, O](request: Request[I, O, C], tx: IDBTransaction, observer: Observer[O]): Unit
  }

  object Tx {
    implicit def iterable[C[X] <: Iterable[X]]: Tx[C] = new Tx[C] {
      def execute[I,O](request: Request[I, O, C], tx: IDBTransaction, observer: Observer[O]): Unit = {
        val target = IdbSupport.this match {
          case s: Store[K,V] => Left(tx.objectStore(storeName))
          case i: Index[K,V] => Right(tx.objectStore(storeName).index(i.indexName))
        }
        def >>(it: Iterator[I]): Unit = {
          if (it.hasNext) {
            val next = it.next()
            try {
              val req = target match {
                case Right(index) => request.asInstanceOf[IndexRequest[I,O,C]].executeOnIndex(index, Left(next))
                case Left(store) => request.execute(store, Left(next))
              }
              req.onsuccess = (e: Event) => {
                request.onSuccess(Left((next, req.result)), observer).onCompleteNow {
                  case Continue.IsSuccess =>
                    >>(it)
                  case _ =>
                }(IndexedDb.scheduler)
              }
              req.onerror = (e: ErrorEvent) => {
                observer.onError(new IDbRequestException(request.onError(Some(next)), req.error))
              }
            } catch {
              case NonFatal(ex) =>
                observer.onError(new IDbException(request.onError(Some(next)), ex))
            }
          }
        }
        val it = request.input.iterator
        if (it.hasNext) {
          tx.oncomplete = (e: Event) => {
            observer.onComplete()
          }
          tx.onerror = (e: ErrorEvent) => {
            observer.onError(new IDbTxException(request.onError(), tx.error))
          }
          >>(it)
        } else {
          observer.onComplete()
        }
      }
    }
    implicit def range[C[X] <: Key[X]]: Tx[C] = new Tx[C] {
      def execute[I, O](request: Request[I, O, C], tx: IDBTransaction, observer: Observer[O]): Unit = {
        val target = IdbSupport.this match {
          case s: Store[K,V] => Left(tx.objectStore(storeName))
          case i: Index[K,V] => Right(tx.objectStore(storeName).index(i.indexName))
        }
        val keyRange = request.input
        val oneTimer = keyRange.isInstanceOf[lastKey] || keyRange.isInstanceOf[firstKey]
        try {
          val req = target match {
            case Right(index) => request.asInstanceOf[IndexRequest[I,O,C]].executeOnIndex(index, Right(keyRange))
            case Left(store) => request.execute(store, Right(keyRange))
          }
          req.onsuccess = (e: Event) => {
            e.target.asInstanceOf[IDBRequest].result match {
              case cursor: IDBCursorWithValue =>
                request.onSuccess(Right(cursor), observer).onCompleteNow {
                  case Continue.IsSuccess if !oneTimer =>
                    cursor.continue()
                  case _ =>
                }(IndexedDb.scheduler)
              case _ => // rangedKey sequence ended
            }
          }
          req.onerror = (e: ErrorEvent) => {
            observer.onError(new IDbRequestException(request.onError(), req.error))
          }
          tx.oncomplete = (e: Event) => {
            observer.onComplete()
          }
          tx.onerror = (e: ErrorEvent) => {
            observer.onError(new IDbTxException(request.onError(), tx.error))
          }
        } catch {
          case NonFatal(ex) =>
            observer.onError(new IDbException(request.onError(), ex))
        }
      }
    }
  }
}

object IdbSupport {
  import scala.collection.immutable.TreeMap
  implicit def TreeMapW[K: W : Ordering, V: W]: W[TreeMap[K, V]] = W[TreeMap[K, V]](
    x => Js.Arr(x.toSeq.map(writeJs[(K, V)]): _*)
  )
  implicit def TreeMapR[K: R : Ordering, V: R]: R[TreeMap[K, V]] = R[TreeMap[K, V]](
    Internal.validate("Array(n)") {
      case x: Js.Arr => TreeMap(x.value.map(readJs[(K, V)]): _*)
    }
  )
  implicit class RequestPimp[+E](source: Observable[E]) {

    /**
     * Observable#asFuture completes after first element, this one when at the end with the result buffered
     */
    def asCompletedFuture(implicit s: Scheduler): Future[List[E]] = {
      val promise = Promise[List[E]]()
      val buffer = ListBuffer[E]()
      source.subscribe(new Observer[E] {
        def onNext(elem: E) = {
          buffer += elem
          Continue
        }

        def onComplete() = {
          promise.trySuccess(buffer.toList)
        }

        def onError(ex: Throwable) = {
          promise.tryFailure(ex)
        }
      })
      promise.future
    }

    def finallyDo(success: => Unit = (), error: => Unit = (), cancel: => Unit = ()): Observable[E] =
      Observable.create[E] { observer =>
        implicit val s = observer.scheduler

        source.subscribe(new Observer[E] {
          private[this] val wasExecuted = Atomic(false)

          private[this] def execute(callback: => Unit) = {
            if (wasExecuted.compareAndSet(expect = false, update = true))
              try callback catch {
                case NonFatal(ex) =>
                  s.reportFailure(ex)
              }
          }

          def onNext(elem: E) = {
            val f = observer.onNext(elem)
            f.onCancel(execute(cancel))
            f
          }

          def onError(ex: Throwable): Unit = {
            try observer.onError(ex) finally
              s.scheduleOnce(0.second) {
                execute(error)
              }
          }

          def onComplete(): Unit = {
            try observer.onComplete() finally
              s.scheduleOnce(0.second) {
                execute(success)
              }
          }
        })
      }

    def doWorkOnSuccess(f: Seq[E] => Unit)(implicit s: Scheduler): Observable[E] =
      Observable.create { observer =>
        source.subscribe(new Observer[E] {
          private[this] var buffer = ArrayBuffer.empty[E]
          private[this] val wasExecuted = Atomic(false)

          private[this] def execute(): Unit = {
            if (wasExecuted.compareAndSet(expect=false, update=true))
              try f(buffer) catch {
                case NonFatal(ex) =>
                  s.reportFailure(ex)
              } finally {
                buffer = null
              }
          }

          def onNext(elem: E): Future[Ack] = {
            buffer.append(elem)
            val f = observer.onNext(elem)
            f.onCancel(execute())
            f
          }

          def onError(ex: Throwable): Unit = {
            observer.onError(ex)
            buffer = null
          }

          def onComplete(): Unit = {
            try observer.onComplete() finally {
              s.scheduleOnce(0.second) {
                execute()
              }
              ()
            }
          }
        })
      }

    private def emptyYieldingBuffer[T](obs: Observable[T], count: Int)(implicit s: Scheduler): Observable[Seq[T]] =
      Observable.create { observer =>
        obs.subscribe(new Observer[T] {
          private[this] var buffer = ArrayBuffer.empty[T]
          private[this] var lastAck = Continue : Future[Ack]
          private[this] var size = 0

          def onNext(elem: T): Future[Ack] = {
            size += 1
            buffer.append(elem)
            if (size >= count) {
              val oldBuffer = buffer
              buffer = ArrayBuffer.empty[T]
              size = 0

              lastAck = observer.onNext(oldBuffer)
              lastAck
            }
            else
              Continue
          }

          def onError(ex: Throwable): Unit = {
            observer.onError(ex)
            buffer = null
          }

          def onComplete(): Unit = {
            // classic buffer emits either non-empty sequence or completes, this buffer emits even empty sequence before complete
            lastAck.onContinueCompleteWith(observer, buffer)
            buffer = null
          }
        })
      }

    def onCompleteNewTx[U](f: Seq[E] => Observable[U])(implicit s: Scheduler): Observable[U] = {
      onCompleteNewTx(emptyYieldingBuffer(source, Integer.MAX_VALUE)(IndexedDb.scheduler).map(f))
    }

    private def onCompleteNewTx[U, T](obs: Observable[T])(implicit ev: T <:< Observable[U], s: Scheduler): Observable[U] = {
      Observable.create[U] { observer =>
        obs.subscribe(new Observer[T] {
          private[this] var childObservable: T = _

          def onNext(elem: T) = {
            childObservable = elem
            Continue
          }

          def onError(ex: Throwable) = {
            observer.onError(ex)
          }

          def onComplete() = {
            Option(childObservable).fold(observer.onComplete()) { obs =>
              obs.subscribe(new Observer[U] {
                def onNext(elem: U) = {
                  observer.onNext(elem)
                }

                def onError(ex: Throwable): Unit = {
                  observer.onError(ex)
                }

                def onComplete(): Unit = {
                  observer.onComplete()
                }
              })
            }
          }
        })
      }
    }
  }
}