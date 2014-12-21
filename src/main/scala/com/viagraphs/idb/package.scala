package com.viagraphs

import monifu.reactive.Ack.{Cancel, Continue}
import monifu.reactive.{Observable, Observer}

import scala.util.control.NonFatal

package object idb {

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
  }
}
