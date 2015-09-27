package com.viagraphs

import monifu.concurrent.Scheduler
import monifu.reactive.Ack.{Cancel, Continue}
import monifu.reactive.{Ack, Observable, Observer}

import scala.concurrent.Future
import scala.util.{Try, Failure}
import scala.util.control.NonFatal

package object idb {

  implicit class ObservablePimp[+E](observable: Observable[E]) {
    def foreachWith(delegate: Observer[_])(cb: E => Unit)(msg: E => String)(implicit s: Scheduler): Unit =
      observable.subscribe(
        new Observer[E] {
          def onNext(elem: E) =
            try {
              cb(elem); Continue
            } catch {
              case NonFatal(ex) =>
                delegateError(ex, elem)
                Cancel
            }

          def onComplete() = ()

          def onError(ex: Throwable) = ???

          private def delegateError(ex: Throwable, elem: E) = {
            delegate.onError(new IDbException(msg(elem), ex))
          }
        }
      )
  }

  implicit class FuturePimp(val source: Future[Ack]) extends AnyVal {
    /**
     * Triggers execution of the given callback, once the source terminates either
     * with a `Cancel` or with a failure.
     */
    def onCancel(cb: => Unit)(implicit s: Scheduler): Future[Ack] =
      source match {
        case Continue => source
        case Cancel =>
          try cb catch {
            case NonFatal(ex) => s.reportFailure(ex)
          }
          source
        case sync if sync.isCompleted =>
          sync.value.get match {
            case Continue.IsSuccess => source
            case Cancel.IsSuccess | Failure(_) =>
              try cb catch {
                case NonFatal(ex) => s.reportFailure(ex)
              }
              source
            case other =>
              // branch not necessary, but Scala's compiler emits warnings if missing
              s.reportFailure(new MatchError(other.toString))
              source
          }
        case async =>
          source.onComplete {
            case Cancel.IsSuccess | Failure(_) => cb
            case _ => // nothing
          }
          source
      }

    /**
     * Unsafe version of `onComplete` that triggers execution synchronously
     * in case the source is already completed.
     */
    def onCompleteNow(f: Try[Ack] => Unit)(implicit s: Scheduler): Future[Ack] =
      source match {
        case sync if sync.isCompleted =>
          try f(sync.value.get) catch {
            case NonFatal(ex) =>
              s.reportFailure(ex)
          }
          source
        case async =>
          source.onComplete(f)
          source
      }

    def onContinueCompleteWith[T](observer: Observer[T], lastElem: T)(implicit s: Scheduler): Unit =
      source match {
        case sync if sync.isCompleted =>
          if (sync == Continue || ((sync != Cancel) && sync.value.get == Continue.IsSuccess)) {
            try {
              observer.onNext(lastElem)
              observer.onComplete()
            }
            catch {
              case NonFatal(err) =>
                observer.onError(err)
            }
          }
        case async =>
          async.onSuccess {
            case Continue =>
              try {
                observer.onNext(lastElem)
                observer.onComplete()
              }
              catch {
                case NonFatal(err) =>
                  observer.onError(err)
              }
          }
      }
  }
}
