package com.viagraphs

import monifu.reactive.Ack.{Cancel, Continue}
import monifu.reactive.{Observable, Observer}
import org.scalajs.dom.{DOMError, IDBDatabase}

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
}
