scalajs-rx-idb
==============

Indexed Database reactive (Rx) wrapper written in [scala.js](1) using [monifu](2), [uPickle](3) and [uTest](4).

Primarily it is trying to :
* be type safe 
    * thanks to [uPickle](3) and its Reader/Writer type classes user just declares input/return types and uPickle does the rest
    * a key validation type class doesn't let you store keys of unsupported types
* be user friendly 
    * because there is too much mutability and confusion regarding request result value, versioning, transactions and error handling in IndexedDb API
    * Rx based API has a clean contract by definition
* handling asynchrony and resilience the Rx way 
    * because IndexedDb API imho leads to inevitable callback hell and I couldn't really say when it crashes and why

In other words, working with IndexedDb directly is not that easy as one might expect.

NOTE: 

* It currently depends on scala-js 0.6.0-SNAPSHOT and unaccepted [utest PR](https://github.com/lihaoyi/utest/pull/40)
* Just the basic operations are implemented and tested so far, it's a work in progress

    [1]: http://www.scala-js.org
    [2]: http://www.monifu.org
    [3]: https://github.com/lihaoyi/upickle
    [4]: https://github.com/lihaoyi/utest