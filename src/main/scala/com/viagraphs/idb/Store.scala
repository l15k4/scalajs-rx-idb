package com.viagraphs.idb

import monifu.concurrent.Scheduler
import monifu.reactive.Ack.{Cancel, Continue}
import monifu.reactive.internals.FutureAckExtensions
import monifu.reactive.{Ack, Observable, Observer}
import org.scalajs.dom._
import upickle.Aliases.{R, W}
import upickle._

import scala.annotation.implicitNotFound
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, Promise}
import scala.language.higherKinds
import scala.scalajs.js
import scala.scalajs.js.UndefOr
import scala.util.control.NonFatal

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

class IDbException(msg: String, cause: Throwable) extends Exception(msg, cause)
case class IDbRequestException(message: String, error: DOMError) extends IDbException(message, new Exception(error.name))
case class IDbTxException(message: String, error: DOMError) extends IDbException(message, new Exception(error.name))

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
 * Type Class that puts a view bound on key types. Value types are not restricted much so I don't handle that
 */
@implicitNotFound("No implicit ValidKey defined for ${K}, thus it is not a valid Store Key type")
sealed trait ValidKey[K]
object ValidKey {
  implicit object StringOk extends ValidKey[String]
  implicit object IntOk extends ValidKey[Int]
  implicit object IntSeqOk extends ValidKey[Seq[Int]]
  implicit object IntArrayOk extends ValidKey[Array[Int]]
  implicit object StringSeqOk extends ValidKey[Seq[String]]
  implicit object StringArrayOk extends ValidKey[Array[String]]
  implicit object JsDateOk extends ValidKey[js.Date]
}

case class Store[K : W : R : ValidKey, V : W : R](storeName: String, underlying: Observable[IDBDatabase]) {
  import scala.scalajs.js.JSConverters._

  private[this] def openTx(txAccess: TxAccess): Observable[IDBTransaction] =
    Observable.create { observer =>
      underlying.foreachWith(observer) { db =>
        val tx = db.transaction(txAccess.storeNames.toJSArray, txAccess.value)
        observer.onNext(tx)
        observer.onComplete()
      }(db => s"Unable to openStoreTx $name in db ${db.name}")
    }

  /**
   * IDB is requested by providing store keys as scala [[Iterable]] or this [[Key]].
   * As [[Iterable]] is a type constructor, [[Key]] must have become type constructor too to make abstraction over both
   */
  sealed trait Key[_] {
    def range: IDBKeyRange
    def direction: Direction
  }
  case class rangedKey(range: IDBKeyRange, direction: Direction) extends Key[K]
  case object lastKey extends Key[K] {
    def range: IDBKeyRange = null
    def direction: Direction = Direction.Prev
  }
  case object firstKey extends Key[K] {
    def range: IDBKeyRange = null
    def direction: Direction = Direction.Next
  }

  /**
   * Type class representing a transaction strategy over request input that is either [[Iterable]] or [[Key]]
   * @tparam C either [[Iterable]] or [[Key]] type constructor
   */
  @implicitNotFound("No implicit Tx defined for ${C}, only [[Key]] and [[Iterable]] types are supported")
  trait Tx[C[_]] {
    def execute[I, O](request: Request[I, O, C], tx: IDBTransaction, observer: Observer[O]): Unit
  }

  object Tx {
    implicit def range[C[X] <: Key[X]]: Tx[C] = new Tx[C] {
      override def execute[I, O](request: Request[I, O, C], tx: IDBTransaction, observer: Observer[O]): Unit = {
        val store = tx.objectStore(storeName)
        val keyRange = request.input
        try {
          val req = request.execute(store, Right(keyRange))
          req.onsuccess = (e: Event) => {
            e.target.asInstanceOf[IDBRequest].result match {
              case cursor: IDBCursorWithValue =>
                request.onSuccess(Right(cursor), observer).onCompleteNow {
                  case Continue.IsSuccess if keyRange.isInstanceOf[rangedKey] =>
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

    implicit def iterable[C[X] <: Iterable[X]]: Tx[C] = new Tx[C] {
      override def execute[I, O](request: Request[I, O, C], tx: IDBTransaction, observer: Observer[O]): Unit = {
        val store = tx.objectStore(storeName)
        def >>(it: Iterator[I]): Unit = {
          if (it.hasNext) {
            val next = it.next()
            try {
              val req = request.execute(store, Left(next))
              req.onsuccess = (e: Event) => {
                request.onSuccess(Left(next, req.result), observer).onCompleteNow {
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
  }

  /**
   * Request is an observable of Output/results
   * @param input an [[Iterable]] or [[Key]] of keys or values transaction is created over
   * @param tx transaction strategy for either [[Iterable]] or [[Key]]
   */
  abstract class Request[I, O, C[_]](val input: C[I], tx: Tx[C]) extends Observable[O] {
    def txAccess: TxAccess
    def execute(store: IDBObjectStore, input: Either[I, Key[I]]): IDBRequest
    def onSuccess(result: Either[(I, Any), IDBCursorWithValue], observer: Observer[O]): Future[Ack]
    def onError(input: Option[I] = None): String
    def subscribeFn(observer: Observer[O]): Unit = {
      import scala.scalajs.js.JSConverters._
      underlying.foreachWith(observer) { db =>
        val transaction = db.transaction(txAccess.storeNames.toJSArray, txAccess.value)
        tx.execute[I, O](this, transaction, observer)
      }(db => s"Unable to open transaction for request $this")
    }
  }

  /**
   * Updates store records matching keys with entries
   * @param keys keys of records to update - either [[Iterable]] or [[Key]]
   * @param entries map of key-value pairs to update store with
   * @return observable of the updated key-value pairs (the old values)
   * @note providing both keys and entries[key,value] is necessary due to IDB's cursor internal workings
   */
  def update[C[_]](keys: C[K], entries: Map[K,V])(implicit e: Tx[C]): Observable[(K,V)] = new Request[K, (K,V), C](keys, e) {
    def txAccess = ReadWrite(storeName)

    def execute(store: IDBObjectStore, input: Either[K, Key[K]]) = input match {
      case Right(keyRange) =>
        store.openCursor(keyRange.range, keyRange.direction.value)
      case Left(key) =>
        val value = entries.getOrElse(key, throw new IllegalArgumentException(s"Key $key is not present in update entries !"))
        store.put(
          json.writeJs(writeJs[V](value)).asInstanceOf[js.Any],
          json.writeJs(writeJs[K](key)).asInstanceOf[js.Any]
        )
    }

    def onSuccess(result: Either[(K, Any), IDBCursorWithValue], observer: Observer[(K, V)]): Future[Ack] = {
      result match {
        case Right(cursor) =>
          val promise = Promise[Ack]()
          val key = readJs[K](json.readJs(cursor.key))
          val oldVal = readJs[V](json.readJs(cursor.value))
          val newVal = entries.getOrElse(key, throw new IllegalArgumentException(s"Key $key is not present in update entries !"))
          val req = cursor.update(json.writeJs(writeJs[V](newVal)).asInstanceOf[js.Any])
          req.onsuccess = (e: Event) =>
            observer.onNext((key,oldVal))
            promise.success(Continue)
          req.onerror = (e: ErrorEvent) => {
            observer.onError(new IDbRequestException(s"Updating cursor '$key' with '$newVal' failed", req.error))
            promise.success(Cancel)
          }
          promise.future
        case Left((key,value)) =>
          (value : UndefOr[Any]).fold[Future[Ack]](Continue) { anyVal =>
            observer.onNext(
              key -> readJs[V](json.readJs(anyVal))
            )
          }
      }
    }
    def onError(input: Option[K]) = s"updating ${input.getOrElse("")} from $storeName failed"
  }

  /**
   * Gets records by keys
   * @param keys keys of records to get - either [[Iterable]] or [[Key]]
   * @return observable of key-value pairs
   * @note that values might be undefined if a key doesn't exist !
   */
  def get[C[_]](keys: C[K])(implicit e: Tx[C]): Observable[(K,V)] = new Request[K, (K, V), C](keys, e) {
    val txAccess = ReadOnly(storeName)

    def execute(store: IDBObjectStore, input: Either[K, Key[K]]) = input match {
      case Right(keyRange) =>
        store.openCursor(keyRange.range, keyRange.direction.value)
      case Left(key) =>
        store.get(json.writeJs(writeJs[K](key)).asInstanceOf[js.Any])
    }

    def onSuccess(result: Either[(K, Any), IDBCursorWithValue], observer: Observer[(K, V)]): Future[Ack] = {
      result match {
        case Right(cursor) =>
          observer.onNext(
            readJs[K](json.readJs(cursor.key)) -> readJs[V](json.readJs(cursor.value))
          )
        case Left((key,value)) =>
          (value : UndefOr[Any]).fold[Future[Ack]](Continue) { anyVal =>
            observer.onNext(
              key -> readJs[V](json.readJs(anyVal))
            )
          }
      }
    }

    def onError(input: Option[K] = None) = s"getting ${input.getOrElse("")} from $storeName failed"
  }

  /**
   * @param values to append to the object store with autogenerated key
   * @return observable of appended key-value pairs
   */
  def append[C[X] <: Iterable[X]](values: C[V])(implicit e: Tx[C]): Observable[(K,V)] = new Request[V, (K, V), C](values, e) {
    val txAccess = ReadWrite(storeName)

    def execute(store: IDBObjectStore, input: Either[V, Key[V]]) = input match {
      case Left(value) =>
        store.add(json.writeJs(writeJs[V](value)).asInstanceOf[js.Any])
      case _ =>
        throw new IllegalStateException("Cannot happen, append doesn't support KeyRanges")
    }

    def onSuccess(result: Either[(V, Any), IDBCursorWithValue], observer: Observer[(K, V)]): Future[Ack] = {
      result match {
        case Left((key,value)) =>
          observer.onNext(readJs[K](json.readJs(value)) -> key)
        case _ =>
          throw new IllegalStateException("Cannot happen, append doesn't support KeyRanges")
      }
    }

    def onError(input: Option[V] = None) = s"appending ${input.getOrElse("")} to $storeName failed"
  }

  /**
   * @param entries key-value pairs to insert to object store
   * @return An empty observable that just completes or errors out
   * @note that structured clones of values are created, beware that structure clone internal idb algorithm may fail
   */
  def add(entries: Map[K, V])(implicit e: Tx[Iterable]): Observable[Nothing] = new Request[(K, V), Nothing, Iterable](entries, e) {
    val txAccess = ReadWrite(storeName)

    def execute(store: IDBObjectStore, input: Either[(K, V), Key[(K, V)]]) = input match {
      case Left((key, value)) =>
        val jsKey = json.writeJs(writeJs[K](key)).asInstanceOf[js.Any]
        val jsVal = json.writeJs(writeJs[V](value)).asInstanceOf[js.Any]
        store.add(jsVal, jsKey)
      case x => throw new IllegalStateException("Cannot happen, add doesn't support KeyRanges")
    }

    def onSuccess(result: Either[((K, V), Any), IDBCursorWithValue], observer: Observer[Nothing]): Future[Ack] = {
      Continue
    }

    def onError(input: Option[(K, V)] = None) = s"appending ${input.getOrElse("")} to $storeName failed"
  }

  /**
   * @param keys of records to delete
   * @return empty observable that either completes or errors out when records are deleted
   */
  def delete[C[_]](keys: C[K])(implicit e: Tx[C]): Observable[Nothing] = new Request[K, Nothing, C](keys, e) {
    val txAccess = ReadWrite(storeName)

    def execute(store: IDBObjectStore, input: Either[K, Key[K]]) = input match {
      case Right(keyRange) =>
        store.openCursor(keyRange.range, keyRange.direction.value)
      case Left(key) =>
        store.delete(json.writeJs(writeJs[K](key)).asInstanceOf[js.Any])
    }

    def onSuccess(result: Either[(K, Any), IDBCursorWithValue], observer: Observer[Nothing]): Future[Ack] = {
      result match {
        case Left(_) => Continue
        case Right(cursor) =>
          val key = cursor.key
          val promise = Promise[Ack]()
          val req = cursor.delete()
          req.onsuccess = (e: Event) =>
            promise.success(Continue)
          req.onerror = (e: ErrorEvent) => {
            observer.onError(new IDbRequestException(s"Deleting cursor '$key' failed", req.error))
            promise.success(Cancel)
          }
          promise.future
      }
    }

    def onError(input: Option[K] = None) = s"deleting ${input.getOrElse("")} from $storeName failed"
  }

  /**
   * @return observable of one element - count of records in this store
   */
  def count: Observable[Int] = {
    def errorMsg = s"Database.count($storeName) failed"
    Observable.create { observer =>
      openTx(ReadOnly(storeName)).foreachWith(observer) { tx =>
        val req = tx.objectStore(storeName).count()
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

  /**
   * Deletes all records from this store
   */
  def clear: Observable[Nothing] = {
    def errorMsg = s"Database.clear($storeName) failed"
    Observable.create { observer =>
      openTx(ReadWrite(storeName)).foreachWith(observer) { tx =>
        tx.objectStore(storeName).clear()
        tx.oncomplete = (e: Event) => {
          observer.onComplete()
        }
        tx.onerror = (e: ErrorEvent) => {
          observer.onError(new IDbRequestException(errorMsg, tx.error))
        }
      }(storeTx => errorMsg)
    }
  }
}

object Store {
  import scala.collection.immutable.TreeMap
  implicit def TreeMapW[K: W : Ordering, V: W]: W[TreeMap[K, V]] = W[TreeMap[K, V]](
    x => Js.Arr(x.toSeq.map(writeJs[(K, V)]): _*)
  )
  implicit def TreeMapR[K: R : Ordering, V: R]: R[TreeMap[K, V]] = R[TreeMap[K, V]](
    Internal.validate("Array(n)") {
      case x: Js.Arr => TreeMap(x.value.map(readJs[(K, V)]): _*)
    }
  )
  implicit class RequestPimp[+E](observable: Observable[E]) {
    def flatMapOnComplete[U](f: Seq[E] => Observable[U]): Observable[U] = {
      def emptyYieldingBuffer[T](source: Observable[T], count: Int)(implicit s: Scheduler): Observable[Seq[T]] =
        Observable.create { observer =>
          source.unsafeSubscribe(new Observer[T] {
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
              lastAck.onContinueCompleteWith(observer, buffer)
              buffer = null
            }
          })
        }
      flatMapOnComplete(emptyYieldingBuffer(observable, Integer.MAX_VALUE)(IndexedDb.scheduler).map(f))
    }

    private def flatMapOnComplete[U, T](source: Observable[T])(implicit ev: T <:< Observable[U]): Observable[U] = {
      Observable.create[U] { observerU =>
        source.unsafeSubscribe(new Observer[T] {
          private[this] var childObservable: T = _

          def onNext(elem: T) = {
            childObservable = elem
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
}
