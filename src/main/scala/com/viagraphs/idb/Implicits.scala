package com.viagraphs.idb


import upickle.{Js, Writer, Reader}

import scala.scalajs.js


trait Implicits {

  /**
   * Type Class that puts a view bound on Key types. Value types are not restricted much so I don't handle that
   */
  sealed trait ValidKey[T]
  object ValidKey {
    implicit object StringOk extends ValidKey[String]
    implicit object IntOk extends ValidKey[Int]
    implicit object IntSeqOk extends ValidKey[Seq[Int]]
    implicit object IntArrayOk extends ValidKey[Array[Int]]
    implicit object StringSeqOk extends ValidKey[Seq[String]]
    implicit object StringArrayOk extends ValidKey[Array[String]]
    implicit object JsDateOk extends ValidKey[js.Date]
  }

  def DateWriter = Writer[js.Date] (date => Js.Num(date.getTime()))

  def DateReader = Reader[js.Date] {
    case Js.Num(time) => new js.Date(time)
  }

}
