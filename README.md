#scalajs-rx-idb


Indexed Database reactive (Rx) wrapper written in [scala.js](1) using [monifu](2), [uPickle](3) and [uTest](4).

Primarily it is trying to be :

##type safe

* thanks to [uPickle](3) and its Reader/Writer type classes user just declares input/return types and uPickle does the rest. It even allows you to deserialize objects in a type safe manner without knowing what type of object you're gonna get from database (See uPickle's tagged values in sealed hierarchies)
* a key validation type class doesn't let you store keys of unsupported types
* there is an abstraction over CRUD operations allowing seamlessly work with both scala collections and idb key ranges

##user friendly because

* there is too much mutability and confusion regarding request result value, versioning, transactions and error handling in IndexedDb API
* no living soul wants to spend 3 hours trying to reliably check whether a database exists
* it should prevent lock starvation that I spent literally days to put up with already
* it should supervise transaction boundaries. There are a few edge cases though I haven't covered yet, [I asked a question on SO](http://stackoverflow.com/questions/27326698/indexeddb-transaction-auto-commit-behavior-in-edge-cases)  
* Rx based API has a clean contract by definition

##handling asynchrony, resilience and back-pressure the Rx way because 

* IndexedDb API imho leads to inevitable callback hell and I couldn't really say when it crashes and why
* it makes it easier to implement new features like profiling
* you get a full control over returned data streams in form of higher-order functions
* thanks to Monifu's back pressure implementation you get a way to asynchronously processing results requested lazily with the possibility to cancel. 

In other words, doing complicated stuff with IndexedDb directly is not that easy as one might expect.

**NOTE** 

* It currently depends on scala-js 0.6.0-SNAPSHOT and unaccepted [utest PR](https://github.com/lihaoyi/utest/pull/40)
  * Before trying, please wait until it depends on scala-js 0.6.0 milestone version otherwise you're gonna spend some time with it !
* Just the basic operations are tested so far, it's a work in progress
* The performance might get a little worse in comparison with direct IDB access
  * But after you spend some time with IDB you'll know that loosing a few milliseconds is always better than lock starvation that might put the entire application down or waste hours of troubleshooting


## Examples

* The best place to look at examples is IndexedDbSuite
* Note that the crud operations accept either anything that is `Iterable` or any `com.viagraphs.idb.Store.Key`

```scala
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

```

```scala
val store = db.openStore[Int, Int](storeName)
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
}

```


  [1]: http://www.scala-js.org
  [2]: http://www.monifu.org
  [3]: https://github.com/lihaoyi/upickle
  [4]: https://github.com/lihaoyi/utest