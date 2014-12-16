package com.viagraphs

import monifu.concurrent.Scheduler
import monifu.reactive.Ack.{Cancel, Continue}
import monifu.reactive.internals.FutureAckExtensions
import monifu.reactive.{Ack, Observable, Observer}
import org.scalajs.dom.{IDBDatabase, IDBObjectStore}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.control.NonFatal

package object idb {

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
    def defineObjectStores: IDBDatabase => IDBObjectStore
  }

  trait Profiling
  trait Logging

  case class NewDb(name: String, defineObjectStores: IDBDatabase => IDBObjectStore) extends IdbInitMode {
    def version = ???
  }

  case class RecreateDb(name: String, defineObjectStores: IDBDatabase => IDBObjectStore) extends IdbInitMode {
    def version = ???
  }

  case class UpgradeDb(name: String, version: Int, defineObjectStores: IDBDatabase => IDBObjectStore) extends IdbInitMode

  case class  OpenDb(name: String) extends IdbInitMode {
    def version: Int = ???
    def defineObjectStores: (IDBDatabase) => IDBObjectStore = ???
  }

  implicit class ObservablePimp[+E](observable: Observable[E]) {
    def foreachWith(delegate: Observer[_])(cb: E => Unit)(msg: E => String): Unit =
      observable.unsafeSubscribe(
        new Observer[E] {
          def onNext(elem: E) =
            try {
              cb(elem); Continue
            } catch {
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
