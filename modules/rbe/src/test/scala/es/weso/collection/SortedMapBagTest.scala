package es.weso.collection

import org.scalatest._
import org.scalatestplus.scalacheck._

import scala.collection.SortedMap
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class SortedMapBagTest extends AnyFunSpec with Matchers with Checkers {

  describe("A SortedMap Bag") {
    it("Should add one element and have multiplicity 1") {
      val sm: SortedMap[Char, Int] = SortedMap[Char, Int]()
      val bag = BagSortedMap[Char](sm)
      val expected = SortedMap[Char, Int]('b' -> 3)
      bag.add('b', 3).asSortedMap should be(expected)
    }

  }

}