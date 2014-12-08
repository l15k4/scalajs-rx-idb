package com.viagraphs.idb

//TODO these constants are typed as java.lang.String in scala-js-dom which throws an error if not implemented by browsers
sealed trait TxAccessMode {
  def value: String
}

/**
 * If multiple "readwrite" transactions are attempting to access the same object store (i.e. if they have overlapping scope),
 * the transaction that was created first must be the transaction which gets access to the object store first.
 * Due to the requirements in the previous paragraph, this also means that it is the only transaction which has access to the object store until the transaction is finished.
 */
object ReadWrite extends TxAccessMode {
  val value = "readwrite" /*(IDBTransaction.READ_WRITE : UndefOr[String]).getOrElse("readwrite")*/
}

object ReadOnly extends TxAccessMode {
  val value = "readonly" /*(IDBTransaction.READ_ONLY : UndefOr[String]).getOrElse("readonly")*/
}

object VersionChange extends TxAccessMode {
  val value = "versionchange" /*(IDBTransaction.VERSION_CHANGE : UndefOr[String]).getOrElse("versionchange")*/
}