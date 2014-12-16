package com.viagraphs.idb

import monifu.reactive.Ack.Continue
import monifu.reactive.internals.FutureAckExtensions
import monifu.reactive.{Ack, Observable, Observer}
import org.scalajs.dom._
import upickle.Aliases.{R, W}
import upickle._

import scala.concurrent.{Promise, Future}
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
 * Type Class that puts a view bound on Key types. Value types are not restricted much so I don't handle that
 */
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

  def openTx(txAccess: TxAccess): Observable[IDBTransaction] =
    Observable.create { observer =>
      underlying.foreachWith(observer) { db =>
        val tx = db.transaction(txAccess.storeNames.toJSArray, txAccess.value)
        observer.onNext(tx)
        observer.onComplete()
      }(db => s"Unable to openStoreTx $name in db ${db.name}")
    }

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
  }

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
   * @note that values might be undefined if a key doesn't exist !
   */
  def get[C[_]](input: C[K])(implicit e: Tx[C]): Observable[(K,V)] = new Request[K, (K, V), C](input, e) {
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

  def append[C[X] <: Iterable[X]](input: C[V])(implicit e: Tx[C]): Observable[(K,V)] = new Request[V, (K, V), C](input, e) {
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
   * @note that structured clones of values are created, beware that structure clone internal idb algorithm may fail
   */
  def add(input: Map[K, V])(implicit e: Tx[Iterable]): Observable[Unit] = new Request[(K, V), Unit, Iterable](input, e) {
    val txAccess = ReadWrite(storeName)

    def execute(store: IDBObjectStore, input: Either[(K, V), Key[(K, V)]]) = input match {
      case Left((key, value)) =>
        val jsKey = json.writeJs(writeJs[K](key)).asInstanceOf[js.Any]
        val jsVal = json.writeJs(writeJs[V](value)).asInstanceOf[js.Any]
        store.add(jsVal, jsKey)
      case x => throw new IllegalStateException("Cannot happen, add doesn't support KeyRanges")
    }

    def onSuccess(result: Either[((K, V), Any), IDBCursorWithValue], observer: Observer[Unit]): Future[Ack] = {
      Continue
    }

    def onError(input: Option[(K, V)] = None) = s"appending ${input.getOrElse("")} to $storeName failed"
  }

  def delete[C[_]](input: C[K])(implicit e: Tx[C]): Observable[Unit] = new Request[K, Unit, C](input, e) {
    val txAccess = ReadWrite(storeName)

    def execute(store: IDBObjectStore, input: Either[K, Key[K]]) = input match {
      case Right(keyRange) =>
        store.openCursor(keyRange.range, keyRange.direction.value)
      case Left(key) =>
        store.delete(json.writeJs(writeJs[K](key)).asInstanceOf[js.Any])
    }

    def onSuccess(result: Either[(K, Any), IDBCursorWithValue], observer: Observer[Unit]): Future[Ack] = {
      result match {
        case Left(_) => Continue
        case Right(cursor) =>
          val promise = Promise[Ack]()
          val req = cursor.delete()
          req.onsuccess = (e: Event) =>
            promise.success(Continue)
          req.onerror = (e: ErrorEvent) =>
            observer.onError(new IDbRequestException("Deleting cursor failed", req.error))
          promise.future
      }
    }

    def onError(input: Option[K] = None) = s"deleting ${input.getOrElse("")} from $storeName failed"
  }

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
