package com.viagraphs.idb

import com.viagraphs.idb.IndexedDb._
import monifu.concurrent.Scheduler
import monifu.reactive.Observable
import utest._

import scala.concurrent.Future
import scala.scalajs.js.Dynamic.{literal => lit}

case class AnInstance(a: String, b: Int, c: Map[Int,String])

object IndexedDbSuite extends TestSuites {

  implicit val scheduler = Scheduler.trampoline() // IndexedDB doesn't like access from multiple event loop tasks !
  def recreateDB(name: String) = RecreateDb(name, db => db.createObjectStore(name, lit("autoIncrement" -> true)))

  val generalUseCases = TestSuite {

    "add-and-getAndDelete-objects"- {
      val dbName = "add-and-getAndDelete-objects"
      val obj1 = Map("x" -> 0)
      val obj2 = Map("y" -> 1)
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int,Map[String,Int]](dbName, None, obj1, obj2).buffer(2).flatMap { keys =>
        assert(keys == Seq(1,2))
        db.getAndDelete[Int,Map[String,Int]](dbName, keys:_*).buffer(2)
      }.asFuture.flatMap {
        case Some(values) =>
          assert(values(0)("x") == 0)
          assert(values(1)("y") == 1)
          db.count(dbName).asFuture.map { count =>
            assert(count.get == 0)
            count
          }
        case x => Future.failed(new Exception(s"$x unexpected from add-and-getAndDelete-objects"))
      }
    }

    "count-records"-{
      val dbName = "count-records"
      val db = IndexedDb(recreateDB(dbName))
      db.count(dbName).asFuture.map { count =>
        assert(count.get == 0)
        count
      }
      db.add[Int, Int](dbName, None, 1, 2, 3, 4).buffer(4).asFuture.flatMap {
        case Some(keys) =>
          assert(keys == Seq(1,2,3,4))
          db.count(dbName).asFuture.map { count =>
            assert(count.get == 4)
            count
          }
        case x => Future.failed(new Exception(s"$x unexpected from count-records"))
      }
    }

    "clear-store"-{
      val dbName = "clear-store"
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, Int](dbName, None, 1, 2, 3, 4).buffer(4).flatMap { keys =>
        assert(keys == Seq(1,2,3,4))
        db.clear(dbName).asInstanceOf[Observable[Int]].ambWith(db.count(dbName))
      }.asFuture.flatMap {
        case Some(count) =>
          assert(count == 0)
          Future.successful(count)
        case x => Future.failed(new Exception(s"$x unexpected from clear-store"))
      }
    }

    "add-and-get-object"-{
      val dbName = "add-and-get-object"
      val obj = AnInstance("trol", 1, Map(1 -> "trol"))
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, AnInstance](dbName, None, obj).flatMap { case key =>
        db.get[Int, AnInstance](dbName, key)
      }.asFuture.flatMap {
        case Some(value) =>
          assert(obj == value)
          Future.successful(value)
        case x => Future.failed(new Exception(s"$x unexpected from add-and-get-object"))
      }
    }

    "add-and-get" - {
      val dbName = "add-and-get"
      val db = IndexedDb(recreateDB(dbName))
      val str = "bl bla bla"
      db.add[Int,String](dbName, None, str).flatMap { case key =>
        db.get[Int, String](dbName, key)
      }.asFuture.flatMap {
        case Some(value) =>
          assert(str == value.toString)
          Future.successful(value)
        case x => Future.failed(new Exception(s"$x unexpected from add-and-get"))
      }
    }

    "add-and-get-multiple" - {
      val dbName = "add-and-get-multiple"
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, String](dbName, None, (0 until 10).map(_.toString):_*).buffer(10).flatMap { keys =>
        assert(keys == (1 to 10).toSeq)
        db.get[Int, String](dbName, keys:_*).buffer(10)
      }.asFuture.flatMap {
        case Some(values) =>
          assert(values == (0 until 10).map(_.toString).toSeq)
          Future.successful(values)
        case x => Future.failed(new Exception(s"$x unexpected from add-and-get-multiple"))
      }
    }

    "get-last" - {
      val dbName = "get-last"
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, String](dbName, None, (0 until 10).map(_.toString):_*).buffer(10).flatMap { keys =>
        assert(keys == (1 to 10).toSeq)
        db.getLast[Int, String](dbName)
      }.asFuture.flatMap {
        case Some((lastKey, lastValue)) =>
          assert(lastValue.toString == 9.toString)
          assert(lastKey == 10)
          Future.successful(lastValue)
        case x => Future.failed(new Exception(s"$x unexpected from get-last"))
      }
    }

    "get-and-delete-last" - {
      val dbName = "get-and-delete-last"
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, String](dbName, None, (0 until 10).map(_.toString):_*).buffer(10).flatMap { keys =>
        assert(keys == (1 to 10).toSeq)
        db.getAndDeleteLast[Int, String](dbName).mergeMap { case (lastKey, lastValue) =>
          assert(lastValue.toString == 9.toString)
          assert(lastKey == 10)
          db.getLast[Int, String](dbName)
        }
      }.asFuture.flatMap {
        case Some((lastKey, lastValue)) =>
          assert(lastValue.toString == 8.toString)
          assert(lastKey == 9)
          Future.successful(lastValue)
        case x => Future.failed(new Exception(s"$x unexpected from get-last"))
      }
    }

    "get-last-on-empty-store"-{
      val dbName = "get-last-on-empty-store"
      val db = IndexedDb(recreateDB(dbName))
      db.getLast[Int, String](dbName).map[Nothing] { res =>
        throw new IllegalStateException("There is supposed to be no record in get-last-on-empty-store")
      }.asFuture
    }
  }

}
