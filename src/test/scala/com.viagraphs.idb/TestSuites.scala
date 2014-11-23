package com.viagraphs.idb

import org.scalajs.dom
import utest._
import utest.framework.{Result, Test, TestSuite}
import utest.util.Tree

import scala.scalajs.concurrent.JSExecutionContext
import scala.util.{Success, Failure}

abstract class TestSuites extends TestSuite {

  def print(tests: Tree[Test], results: Tree[Result]): Unit = {
    println(new DefaultFormatter().format(results))

    val testLeaves = tests.leaves.toList
    val resultLeaves = results.leaves.toList
    val testLeavesCount = testLeaves.length
    val resultLeavesCount = resultLeaves.length
    val successResults = resultLeaves.filter(_.value.isSuccess)
    val failedResults = resultLeaves.filter(_.value.isFailure)

    assert(testLeavesCount == resultLeavesCount)

    println("Total test count " + testLeavesCount)
    println("Total result count " + resultLeavesCount)
    println("Passed test count " + successResults.length)
    println("Failed test count " + failedResults.length)
  }

  def tests = TestSuite {
    "indexedDb" - {
      implicit val qex = JSExecutionContext.queue
      IndexedDbSuite.generalUseCases.runAsync().onComplete {
        case Failure(ex) =>
          println(ex)
          System.exit(0)
        case Success(results) =>
          print(IndexedDbSuite.generalUseCases, results)
          dom.window.setTimeout(() => System.exit(0), 50)
      }
    }
  }
}