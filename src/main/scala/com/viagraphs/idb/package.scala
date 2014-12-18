package com.viagraphs

import monifu.reactive.Ack.{Cancel, Continue}
import monifu.reactive.{Observable, Observer}
import org.scalajs.dom.IDBDatabase

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

    /**
     * create and define object stores, indices, etc.
     */
    val defineObjectStores: Option[IDBDatabase => Unit]
  }

  /**
   * Create new or open an existing database, use defineObjectStores to define object stores
   * @param defineObjectStores specify in case database might not exist yet
   */
  case class OpenDb(name: String, defineObjectStores: Option[IDBDatabase => Unit]) extends IdbInitMode {
    def version = ???
  }

  /**
   * Delete an existing database of this name and creates new one by defineObjectStores
   */
  case class RecreateDb(name: String, defineObjectStores: Some[IDBDatabase => Unit]) extends IdbInitMode {
    def version = ???
  }

  /**
   * Upgrades an existing database to a new version. Use defineObjectStores to alter existing store definitions
   */
  case class UpgradeDb(name: String, version: Int, defineObjectStores: Some[IDBDatabase => Unit]) extends IdbInitMode

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
