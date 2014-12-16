package com.viagraphs.idb

import monifu.concurrent.Scheduler
import org.scalajs.dom.IDBKeyRange
import upickle._
import utest._
import utest.framework.TestSuite

import scala.concurrent.Future
import scala.scalajs.js.Dynamic.{literal => lit}

case class AnInstance(a: String, b: Int, c: Map[Int,String])

object IndexedDbSuite extends TestSuites {

  implicit val scheduler = Scheduler.trampoline()

  def recreateDB(name: String) = new RecreateDb(name, db => db.createObjectStore(name, lit("autoIncrement" -> true))) with Logging

  val generalUseCases = TestSuite {

    "play" - {
      val obj1 = Map("x" -> 0) // store values might be anything that upickle manages to serialize
      val obj2 = Map("y" -> 1)
      val db = IndexedDb( // you may create new db, open, upgrade or recreate existing one
        new NewDb("dbName", db => db.createObjectStore("storeName", lit("autoIncrement" -> true)))
      )
      val store = db.openStore[Int,Map[String, Int]]("storeName") //declare Store's key and value type information
      // db requests should be combined with `flatMapOnComplete` combinator which honors idb transaction boundaries
      store.append(List(obj1, obj2)).flatMapOnComplete { appendTuples =>
        assert(appendTuples.length == 2)
        val (keys, values) = appendTuples.unzip
        assert(values.head == Map("x" -> 0))
        store.get(keys).flatMapOnComplete { getTuples =>
          val (keys2, _) = getTuples.unzip
          store.delete(keys2).flatMapOnComplete { empty =>
            store.count.flatMapOnComplete { counts =>
              assert(counts(0) == 0)
              db.close()
            }
          }
        }
      }
    }

    "get-db-names" - {
      val idb = IndexedDb(recreateDB("get-db-names"))
      idb.underlying.asFuture.flatMap { db =>
        IndexedDb.getDatabaseNames.map { names =>
          assert(names.contains("get-db-names"))
          idb.close()
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

    "append-and-get-object" - {
      val dbName = "append-and-get-object"
      val obj = AnInstance("foo", 1, Map(1 -> "bar"))
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int,AnInstance](dbName)
      store.append(List(obj)).flatMapOnComplete { appendTuples =>
        assert(appendTuples.length == 1)
        val key = appendTuples(0)._1
        val value = appendTuples(0)._2
        assert(value == obj)
        assert(key == 1)
        store.get(key :: Nil).flatMapOnComplete { getTuples =>
          assert(getTuples.length == 1)
          assert(obj == getTuples(0)._2)
          db.close()
        }
      }.asFuture
    }

    "append-and-get" - {
      val dbName = "append-and-get"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int,String](dbName)
      val str = "bl bla bla"
      store.append(List(str)).flatMapOnComplete { appendTuples =>
        assert(appendTuples.length == 1)
        val key = appendTuples(0)._1
        store.get(List(key)).flatMapOnComplete { getTuples =>
          assert(getTuples.length == 1)
          assert(str == getTuples(0)._2.toString)
          db.close()
        }
      }.asFuture
    }


    "append-and-get-then-delete" - {
      val dbName = "append-and-get-then-delete"
      val obj1 = Map("x" -> 0)
      val obj2 = Map("y" -> 1)
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int,Map[String, Int]](dbName)
      store.append(List(obj1, obj2)).flatMapOnComplete { appendTuples =>
        assert(appendTuples.length == 2)
        val (keys, values) = appendTuples.unzip
        assert(values.head == Map("x" -> 0))
        store.get(keys).flatMapOnComplete { getTuples =>
          val (keys2, _) = getTuples.unzip
          store.delete(keys2).flatMapOnComplete { whatever =>
            store.count.flatMapOnComplete { counts =>
              assert(counts(0) == 0)
              db.close()
            }
          }
        }
      }.asFuture
    }

    "count-records" - {
      val dbName = "count-records"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int, Int](dbName)
      store.count.flatMapOnComplete { counts =>
        assert(counts(0) == 0)
        store.append(List(1, 2, 3, 4)).flatMapOnComplete { tuples =>
          val (keys, values) = tuples.unzip
          assert(keys == List(1, 2, 3, 4))
          assert(values == List(1, 2, 3, 4))
          store.count.flatMapOnComplete { counts =>
            assert(counts(0) == 4)
            db.close()
          }
        }
      }.asFuture
    }


    "clear-store" - {
      val dbName = "clear-store"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int, Int](dbName)
      store.add(Map(1->1, 2->2, 3->3, 4->4)).flatMapOnComplete { tuples =>
        store.clear.flatMapOnComplete { empty =>
          store.count.flatMapOnComplete { counts =>
            assert(counts(0) == 0)
            db.close()
          }
        }
      }.asFuture
    }

    "add-and-get-multiple" - {
      val dbName = "add-and-get-multiple"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int, String](dbName)
      store.add((1 to 10).map(k => k -> k.toString).toMap).flatMapOnComplete { tuples =>
        store.get((1 to 10).toSeq).flatMapOnComplete { tuples =>
          val (keys, values) = tuples.unzip
          assert(values == (1 to 10).map(_.toString).toSeq)
          db.close()
        }
      }.asFuture
    }

    "get-key-range" - {
      val dbName = "get-key-range"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int, Int](dbName)
      store.add((1 to 10).map(k => k -> k).toMap).flatMapOnComplete { tuples =>
        store.get(store.lastKey).flatMapOnComplete { lastTuples =>
          assert(lastTuples.length == 1)
          val (lastKey, lastValue) = lastTuples(0)
          assert(lastValue == 10)
          assert(lastKey == 10)
          store.get(store.firstKey).flatMapOnComplete { firstTuples =>
            assert(firstTuples.length == 1)
            val (firstKey, firstVal) = firstTuples(0)
            assert(firstVal == 1)
            assert(firstKey == 1)
            store.get(store.rangedKey(IDBKeyRange.bound(3,7), Direction.Next)).flatMapOnComplete { ascTuples =>
              assert(ascTuples.length == 5)
              val (keys, vals) = ascTuples.unzip
              assert(keys == Seq(3,4,5,6,7))
              assert(vals == Seq(3,4,5,6,7))
              store.get(store.rangedKey(IDBKeyRange.bound(3,7), Direction.Prev)).flatMapOnComplete { descTuples =>
                assert(descTuples.length == 5)
                val (keys, vals) = descTuples.unzip
                assert(keys == Seq(7,6,5,4,3))
                assert(vals == Seq(7,6,5,4,3))
                db.close()
              }
            }
          }
        }
      }.asFuture
    }

    "delete-key-range" - {
      val dbName = "delete-key-range"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int, Int](dbName)
      store.append(1 to 10).flatMapOnComplete { tuples =>
        store.delete(store.lastKey).flatMapOnComplete { empty =>
          store.count.map { count =>
            assert(count == 9)
          }
          store.delete(store.firstKey).flatMapOnComplete { empty =>
            store.count.map { count =>
              assert(count == 8)
            }
            store.delete(store.rangedKey(IDBKeyRange.bound(3,5), Direction.Prev)).flatMapOnComplete { empty =>
              store.count.map { count =>
                assert(count == 5)
              }
              db.close()
            }
          }
        }
      }.asFuture
    }

    "get-last-on-empty-store" - {
      val dbName = "get-last-on-empty-store"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int, String](dbName)
      store.get(store.lastKey).flatMapOnComplete { res =>
        assert(res.length == 0)
        db.close()
      }.asFuture
    }

    "get-nonexistent-key" - {
      val dbName = "get-nonexistent-key"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int, String](dbName)
      store.get(List(1)).flatMapOnComplete { res =>
        assert(res.length == 0)
        db.close()
      }.asFuture
    }

    "update-entries" - {
      val dbName = "update-entries"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int, Int](dbName)
      store.append(1 to 10).flatMapOnComplete { tuples =>
        store.update(9 to 10, Map(9->90, 10->100)).flatMapOnComplete { tuples =>
          store.get(9 to 10).flatMapOnComplete { result =>
            val (_, values) = result.unzip
            assert(values == Seq(90,100))
            db.close()
          }
        }
      }.asFuture
    }

    "update-entries-with-range" - {
      val dbName = "update-entries"
      val db = IndexedDb(recreateDB(dbName))
      val store = db.openStore[Int, Int](dbName)
      store.append(1 to 10).flatMapOnComplete { tuples =>
        val range = store.rangedKey(IDBKeyRange.bound(9,10), Direction.Next)
        store.update(range, Map(9->90, 10->100)).flatMapOnComplete { tuples =>
          store.get(range).flatMapOnComplete { result =>
            val (_, values) = result.unzip
            assert(values == Seq(90,100))
            db.close()
          }
        }
      }.asFuture
    }
  }
}
