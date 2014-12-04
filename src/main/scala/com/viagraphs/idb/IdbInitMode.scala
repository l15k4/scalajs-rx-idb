package com.viagraphs.idb

import org.scalajs.dom.{IDBObjectStore, IDBDatabase}

/**
 * Init modes are primarily designed for self explanatory purposes because IndexedDB API is quite ambiguous in this matter
 */
sealed trait IdbInitMode {
  def name: String
  def version: Int
  def defineObjectStores: IDBDatabase => IDBObjectStore
}

trait Profiling

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

