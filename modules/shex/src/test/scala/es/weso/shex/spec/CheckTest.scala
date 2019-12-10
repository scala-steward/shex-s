package es.weso.shex.spec

import Check._
import cats._
import es.weso.rdf.jena.RDFAsJenaModel
import es.weso.rdf.nodes.IRI
import es.weso.shapeMaps.{IRILabel, ShapeMapLabel}
import es.weso.shex.Schema
import org.scalatest._
import es.weso.utils.internal.CollectionCompat._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class CheckTest extends AnyFunSpec with Matchers with EitherValues {

  val emptyEnv = Env(Schema.empty,TypingMap.empty,RDFAsJenaModel.empty)

  describe(s"satisfy all") {

    it(s"Should satisfy a list of values when all true") {
      val opt1 = pure(true)
      val opt2 = pure(true)
      runCheck(emptyEnv, satisfyAll(List(opt1, opt2))).fold(
        e => fail(s"Error"),
        v => v should be(true)
      )
    }

    it(s"Should not satisfy a list of values when one is false") {
      val opt1 = pure(true)
      val opt2 = pure(false)
      runCheck(emptyEnv, satisfyAll(List(opt1, opt2))).fold(
        e => fail(s"Error"),
        v => v should be(false)
      )
    }
    it(s"Should fail when one fails") {
      val opt1: Check[Boolean] = pure(true)
      val opt2: Check[Boolean] = err(s"Error")
      runCheck(emptyEnv, satisfyAll(List(opt1, opt2))).fold(
        e => info(s"Fails as expected $e"),
        v => fail(s"Should fail but returned $v")
      )
    }
  }

  describe(s"optSatisfy") {
    def check(x: Int): Check[Boolean] =
      if (x == 0) pure(true)
      else pure(false)

    it(s"Should satisfy a none") {
      runCheck(emptyEnv, optSatisfy(None, check)).
        fold(e => fail(s"Failed with none but it should be true"),
          v => v should be(true)
        )
    }
    it(s"Should satisfy an ok value (0)") {
      runCheck(emptyEnv, optSatisfy(Some(0), check)).
        fold(e => fail(s"Failed with none but it should be true"),
          v => v should be(true)
        )
    }
    it(s"Should not satisfy an incorrect value (non 0") {
      runCheck(emptyEnv, optSatisfy(Some(1), check)).
        fold(e => fail(s"Failed with none but it should be true"),
          v => v should be(false)
        )
    }
  }

  describe(s"satisfySome") {

    it(s"Should satisfy a list of values when all true") {
      val opt1: Check[Boolean] = pure(true)
      val opt2: Check[Boolean] = pure(true)
      runCheck(emptyEnv, satisfySome(List(opt1, opt2))).fold(
        e => fail(s"Error"),
        v => v should be(true)
      )
    }

    it(s"Should satisfy a list of values when one is false but another is true") {
      val opt1: Check[Boolean] = pure(false)
      val opt2: Check[Boolean] = pure(true)
      runCheck(emptyEnv, satisfySome(List(opt1, opt2))).fold(
        e => fail(s"Error"),
        v => v should be(true)
      )
    }

    it(s"Should fail when one fails") {
      val opt1: Check[Boolean] = pure(true)
      val opt2: Check[Boolean] = err(s"Error")
      runCheck(emptyEnv, satisfySome(List(opt1, opt2))).fold(
        e => info(s"Fails as expected $e"),
        v => fail(s"Should fail but returned $v")
      )
    }
    it(s"Should fail when all fail") {
      val opt1: Check[Boolean] = pure(false)
      val opt2: Check[Boolean] = pure(false)
      runCheck(emptyEnv, satisfySome(List(opt1, opt2))).fold(
        e => fail(s"Error $e"),
        v => v should be(false)
      )
    }
  }

  describe(s"satisfyNot") {

    it(s"Should satisfy a failing check") {
      val opt: Check[Boolean] = pure(true)
      runCheck(emptyEnv, satisfyNot(opt)).fold(
        e => fail(s"Error"),
        v => v should be(false)
      )
    }

    it(s"Should not satisfy a failing check") {
      val opt: Check[Boolean] = pure(false)
      runCheck(emptyEnv, satisfyNot(opt)).fold(
        e => fail(s"Error"),
        v => v should be(true)
      )
    }
    it(s"Should err an error check") {
      val opt: Check[Boolean] = err("Error")
      runCheck(emptyEnv, satisfyNot(opt)).fold(
        e => info(s"Error as expected"),
        v => fail(s"Should return error and returned $v")
      )
    }

  }

  describe(s"satisfyFirst") {

    it(s"Should satisfyFirst on 1,2,3") {
      val ls = LazyList(1,2,3)
      def even(n: Int): Check[Boolean] = {
        if (n < 0) err(s"Negative")
        else pure(n % 2 == 0)
      }
      runCheck(emptyEnv, satisfyFirst(ls, even)).fold(
        e => fail(s"Error"),
        v => v should be(true)
      )
    }

    it(s"Should not satisfyFirst on 1,3,5") {
      val ls = LazyList(1,3,5)
      def even(n: Int): Check[Boolean] = {
        if (n < 0) err(s"Negative")
        else pure(n % 2 == 0)
      }
      runCheck(emptyEnv, satisfyFirst(ls, even)).fold(
        e => fail(s"Error"),
        v => v should be(false)
      )
    }

    it(s"Should work on infinite list") {
      type E[A] = Id[A]
      val s: LazyList[Int] = LazyList.from(1)
      def check(x: Int): E[Boolean] = Monad[E].pure(x == 3)
      satisfyFirst(s,check)  should be(true)
    }

    it(s"Should work on finite list and return false") {
      type E[A] = Id[A]
      val s: LazyList[Int] = LazyList.from(1).take(100)
      def check(x: Int): E[Boolean] = Monad[E].pure(x < 0)
      satisfyFirst(s,check)  should be(false)
    }


    it(s"Should satisfyFirst on infinite") {
      val ls = LazyList.from(1)
      def even(n: Int): Check[Boolean] = {
        // println(s"Checking $n")
        if (n < 0) err(s"Negative")
        else {
          val b = n % 2 == 0
          // println(s"Returning $b")
          pure(b)
        }
      }
      runCheck(emptyEnv, satisfyFirst(ls, even)).fold(
        e => fail(s"Error"),
        v => v should be(true)
      )
    }

  }

  describe("satisfyOr") {
    it(s"Should check or(true,false) = true") {
      var global = 0
      val c1: Check[Boolean] = pure(true)
      lazy val c2: Check[Boolean] = {
        global = global + 1
        pure(false)
      }
      val c = satisfyOr(c1, c2)
      runCheck(emptyEnv, c).fold(e => fail(s"Error $e"),
        n => n should be(true))

      global should be(0) // checks that or is short circuited
    }

    it(s"Should check or(false,true) = true") {
      var global = 0
      val c1: Check[Boolean] = pure(false )
      lazy val c2: Check[Boolean] = {
        global = global + 1
        pure(true)
      }
      val c = satisfyOr(c1, c2)

      runCheck(emptyEnv, c).fold(e => fail(s"Error $e"),
        n => n should be(true))

      global should be(1) // checks that or is short circuited
    }

    it(s"Should check or(false,false) = false") {
      var global = 0
      val c1: Check[Boolean] = pure(false )
      lazy val c2: Check[Boolean] = {
        global = global + 1
        pure(false)
      }
      val c = satisfyOr(c1, c2)
      val env = Env(Schema.empty,TypingMap.empty,RDFAsJenaModel.empty)
      runCheck(env, c).fold(e => fail(s"Error $e"),
        n => n should be(false))

      global should be(1) // checks that or is short circuited
    }
  }

  describe("satisfyChain") {
    val ex = IRI("http://example.org/")
    def mkLabel(x: String): ShapeMapLabel = IRILabel(ex + x)
    val x = ex + "x"
    it("satisfy a chain of updates") {
      val ls = List("1","2","3")
      def check(v: String): Check[ShapeTyping] = for {
        typing <- getTyping
        newTyping <- fromEither(typing.addConformant(x, mkLabel(v), List()))
      } yield newTyping
      val c = satisfyChain(ls,check)
      val env = Env(Schema.empty,TypingMap.empty,RDFAsJenaModel.empty)
      runCheck(env,c).fold(
        e => fail(s"Error: $e"),
        typing => {
          typing.conformingValues(x) should contain theSameElementsAs(List(mkLabel("1"),mkLabel("2"),mkLabel("3")))
        }
      )
    }

    it("error when adding a type to a non-conforming node") {
      val ls = List("1","2")
      def check(v: String): Check[ShapeTyping] = for {
        typing <- getTyping
        newTyping <- fromEither(typing.addConformant(x, mkLabel(v), List()))
      } yield newTyping
      val c = satisfyChain(ls,check)
      val r : Either[String,ShapeTyping] = for {
        typing <- emptyTyping.addNonConformant(x,mkLabel("2"), List())
        env = Env(Schema.empty,typing,RDFAsJenaModel.empty)
        r <- runCheck(env,c)
      } yield r
      r.fold(
        e => info(s"Fails as expected"),
        typing => {
          fail(s"It returned a good typing $typing but should return an error")
        }
      )
    }
  }
}
