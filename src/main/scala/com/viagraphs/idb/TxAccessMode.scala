package com.viagraphs.idb

//TODO these constants are typed as java.lang.String in scala-js-dom which throws an error if not implemented by browsers
sealed trait TxAccessMode {
  def value: String
}

object ReadWrite extends TxAccessMode {
  val value = "readwrite" /*(IDBTransaction.READ_WRITE : UndefOr[String]).getOrElse("readwrite")*/
}

object ReadOnly extends TxAccessMode {
  val value = "readonly" /*(IDBTransaction.READ_ONLY : UndefOr[String]).getOrElse("readonly")*/
}

object VersionChange extends TxAccessMode {
  val value = "versionchange" /*(IDBTransaction.VERSION_CHANGE : UndefOr[String]).getOrElse("versionchange")*/
}