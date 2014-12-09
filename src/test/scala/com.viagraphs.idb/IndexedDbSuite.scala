package com.viagraphs.idb

import com.viagraphs.idb.IndexedDb.ObservablePimp
import monifu.concurrent.Scheduler
import monifu.reactive.Observable
import utest._

import scala.concurrent.Future
import scala.scalajs.js.Dynamic.{literal => lit}

case class AnInstance(a: String, b: Int, c: Map[Int,String])

object IndexedDbSuite extends TestSuites {

  implicit val scheduler = Scheduler.trampoline()

  def recreateDB(name: String) = new RecreateDb(name, db => db.createObjectStore(name, lit("autoIncrement" -> true))) with Logging

val generalUseCases = TestSuite {

    "get-db-names" - {
      IndexedDb(recreateDB("get-db-names")).underlying.asFuture.flatMap { db =>
        IndexedDb.getDatabaseNames.map { names =>
          assert(names.contains("get-db-names"))
          (0 until names.length).foldLeft(List[String]()) { case (acc, i) => names(i) :: acc}
        }
      }
    }

    "delete-db-if-present" - {
      val dbName = "delete-db-if-present"
      IndexedDb(recreateDB(dbName)).close().asFuture.flatMap {
        case Some(closedDbName) =>
          IndexedDb.deleteIfPresent(closedDbName).flatMap { deleted =>
            assert(deleted)
            IndexedDb.getDatabaseNames.map { names =>
              val deleted = !names.contains(closedDbName)
              assert(deleted)
              deleted
            }
          }
        case _ =>
          Future.failed(new Exception("deleteIfPresent should certainly return something!"))
      }
    }

    "add-and-getAndDelete" - {
      val dbName = "add-and-getAndDelete"
      val obj1 = Map("x" -> 0)
      val obj2 = Map("y" -> 1)
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, Map[String, Int]](dbName, None, obj1, obj2).flatMapOnComplete { keys =>
          assert(keys == Seq(1, 2))
          db.getAndDelete[Int, Map[String, Int]](dbName, keys: _*)
      }.flatMapOnComplete { values =>
          assert(values(0)("x") == 0)
          assert(values(1)("y") == 1)
          db.count(dbName).map { count =>
            assert(count == 0)
            db.close()
            count
          }
      }.asFuture
    }

    "count-records" - {
      val dbName = "count-records"
      val db = IndexedDb(recreateDB(dbName))
      db.count(dbName).asFuture.map { count =>
        assert(count.get == 0)
        count
      }
      db.add[Int, Int](dbName, None, 1, 2, 3, 4).flatMapOnComplete { keys =>
          assert(keys == Seq(1, 2, 3, 4))
          db.count(dbName).map { count =>
            assert(count == 4)
            db.close()
            count
          }
      }.asFuture
    }

    "clear-store" - {
      val dbName = "clear-store"
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, Int](dbName, None, 1, 2, 3, 4).flatMapOnComplete { keys =>
        assert(keys == Seq(1, 2, 3, 4))
        (db.clear(dbName) ++ db.count(dbName)).map { count: Int =>
          assert(count == 0)
          db.close()
          count
        }
      }.asFuture
    }

    "add-and-get-object" - {
      val dbName = "add-and-get-object"
      val obj = AnInstance("trol", 1, Map(1 -> "trol"))
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, AnInstance](dbName, None, obj).flatMapOnComplete { keys =>
        assert(keys.length == 1)
        db.get[Int, AnInstance](dbName, keys(0)).flatMapOnComplete { values =>
          assert(values.length == 1)
          assert(obj == values(0))
          db.close()
          Observable.from(values)
        }
      }.asFuture
    }

    "add-and-get" - {
      val dbName = "add-and-get"
      val db = IndexedDb(recreateDB(dbName))
      val str = "bl bla bla"
      db.add[Int, String](dbName, None, str).flatMapOnComplete { keys =>
        assert(keys.length == 1)
        db.get[Int, String](dbName, keys(0)).flatMapOnComplete { values =>
          assert(values.length == 1)
          assert(str == values(0).toString)
          db.close()
          Observable.from(values)
        }
      }.asFuture
    }

    "add-and-get-multiple" - {
      val dbName = "add-and-get-multiple"
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, String](dbName, None, (0 until 10).map(_.toString): _*).flatMapOnComplete { keys =>
          assert(keys == (1 to 10).toSeq)
          db.get[Int, String](dbName, keys: _*).flatMapOnComplete { values =>
            assert(values == (0 until 10).map(_.toString).toSeq)
            db.close()
            Observable.from(values)
          }
      }.asFuture
    }

    "get-last" - {
      val dbName = "get-last"
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, String](dbName, None, (0 until 10).map(_.toString): _*).flatMapOnComplete { keys =>
          assert(keys == (1 to 10).toSeq)
          db.getLast[Int, String](dbName).flatMapOnComplete { tuples =>
            assert(tuples.length == 1)
            val (lastKey, lastValue) = tuples(0)
            assert(lastValue.toString == 9.toString)
            assert(lastKey == 10)
            db.close()
            Observable.from(tuples)
          }
      }.asFuture
    }

    "get-and-delete-last" - {
      val dbName = "get-and-delete-last"
      val db = IndexedDb(recreateDB(dbName))
      db.add[Int, String](dbName, None, (1 to 10).map(_.toString): _*).flatMapOnComplete { keys =>
        assert(keys == (1 to 10).toSeq)
        db.getAndDeleteLast[Int, String](dbName).flatMapOnComplete { tuples =>
          assert(tuples.length == 1)
          val (deletedKey, deletedValue) = tuples(0)
          assert(deletedKey == 10)
          assert(deletedValue.toString == 10.toString)
          db.getLast[Int, String](dbName).flatMapOnComplete { lastTuples =>
            assert(lastTuples.length == 1)
            val (lastKey, lastValue) = lastTuples(0)
            assert(lastValue.toString == 9.toString)
            assert(lastKey == 9)
            db.close()
            Observable.from(lastTuples)
          }
        }
      }.asFuture
    }

    "get-last-on-empty-store" - {
      val dbName = "get-last-on-empty-store"
      val db = IndexedDb(recreateDB(dbName))
      db.getLast[Int, String](dbName).map[Nothing] { res =>
        throw new IllegalStateException("There is supposed to be no record in get-last-on-empty-store")
      }.doOnComplete(db.close()).asFuture
    }
  }

}
