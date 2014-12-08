package com.viagraphs.idb

import org.scalajs.dom.{IDBObjectStore, IDBDatabase}

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

