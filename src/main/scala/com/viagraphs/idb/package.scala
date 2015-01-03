package com.viagraphs

import monifu.concurrent.Scheduler
import monifu.reactive.Ack.{Cancel, Continue}
import monifu.reactive.{Observable, Observer}

import scala.util.control.NonFatal

package object idb {

  implicit class ObservablePimp[+E](observable: Observable[E]) {
    def foreachWith(delegate: Observer[_])(cb: E => Unit)(msg: E => String)(implicit s: Scheduler): Unit =
      observable.unsafeSubscribe(
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
}
