package es.weso.shex

import org.scalatest._
import es.weso.shex.implicits.decoderShEx._
import es.weso.shex.implicits.encoderShEx._
import es.weso.shex.implicits.showShEx._
import es.weso.shex.implicits.eqShEx._
import es.weso.rdf.operations.Comparisons._
import io.circe._
import io.circe.syntax._
import io.circe.parser._
import cats._
import cats.implicits._
import es.weso.rdf.nodes._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class shexCodecTest extends AnyFunSpec with Matchers with EitherValues {

  describe("Prefix parser") {
    it("Should parse prefix") {
      val str = "pepe"
      parsePrefix(str) match {
        case Right(p) => info(s"Parsed ok as $p")
        case Left(e) => fail(s"Error parsing $str: $e")
      }
    }
    it("Should parse lang string") {
      val str = "\"pepe\"@es"
      parseLang(str) match {
        case Right(p) => info(s"Parsed ok as $p")
        case Left(e) => fail(s"Error parsing $str: $e")
      }
    }

  }

  describe("Shape Label") {
    codecValueTest[IRI](IRI("x"))
    codecValueTest[ShapeLabel](IRILabel(IRI("http://example.org/")))
    codecValueTest[ShapeLabel](BNodeLabel(BNode("x")))
  }

  describe("Max codec") {
    codecValueTest[Max](IntMax(5))
    codecValueTest[Max](Star)
    codecStrTest[Max](""""-1"""", "-1")
    codecStrTest[Max](""""5"""", "5")
    codecStrTest[Max]("""5""", "5")
  }

  describe("SemAct codec") {
    codecValueTest[SemAct](SemAct(IRI("x"), None))
    codecValueTest[SemAct](SemAct(IRI("x"), Some("blah")))
  }

  describe("ObjectLiteral codec") {
    codecValueTest[ObjectLiteral](StringValue("hi"))
  }

  describe("ShapeExpr codec") {
    codecValueTest[ShapeExpr](NodeConstraint(Some(IRILabel(IRI("http://example.org/a"))), Some(IRIKind), None, List(), None,None,None))
    codecValueTest[ShapeExpr](NodeConstraint(Some(IRILabel(IRI("http://example.org/a"))), Some(LiteralKind), None, List(), None,None,None))
    codecValueTest[ShapeExpr](NodeConstraint(Some(IRILabel(IRI("http://example.org/a"))), Some(NonLiteralKind), None, List(), None,None,None))
    codecValueTest[ShapeExpr](NodeConstraint(Some(IRILabel(IRI("http://example.org/a"))), Some(BNodeKind), None, List(), None,None,None))
    codecValueTest[ShapeExpr](NodeConstraint(Some(IRILabel(IRI("http://example.org/a"))), None, Some(IRI("http://datatype.org/int")), List(), None,None,None))
    codecValueTest[ShapeExpr](NodeConstraint(Some(IRILabel(IRI("http://example.org/a"))), None, Some(IRI("http://datatype.org/int")), List(Length(0)), None,None,None))
    codecValueTest[ShapeExpr](NodeConstraint(Some(IRILabel(IRI("http://example.org/a"))), None,
      Some(IRI("http://datatype.org/int")), List(Length(0), MinInclusive(NumericDouble(2.3,"2.3"))), None,None,None)
    )
    codecValueTest[ShapeExpr](
      NodeConstraint(
        Some(IRILabel(IRI("http://example.org/a"))),
        Some(BNodeKind), Some(IRI("http://datatype.org/int")),
        List(MinLength(2), MaxLength(5), Pattern("*.ex", None)),
        Some(List(StringValue("x"))),None,None))
    codecValueTest[ShapeExpr](ShapeRef(IRILabel(IRI("x")),None,None))
    codecValueTest[ShapeExpr](ShapeExternal(Some(IRILabel(IRI("http://example.org/a"))),None,None))
    codecValueTest[ShapeExpr](NodeConstraint(Some(IRILabel(IRI("http://example.org/a"))), None, None, List(), Some(List(DatatypeString("x", IRI("http://schema.org/boolean")))),None,None))
  }

  def codecValueTest[A: Encoder: Decoder: Show: Eq](v: A): Unit = {
    it(s"Should encode and decode ${v.show}") {
      val str = v.asJson.spaces4
      val result = decode[A](str)
      if (result === Right(v))
        info(s"Encoded as $str")
      else
        fail(s"Encoded value $v as $str was not equal to ${Right(v)}. Result: ${result}")
    }
  }

  def codecStrTest[A: Encoder: Decoder: Manifest: Eq](str: String, expected: String): Unit = {
    it(s"Should decode $str and obtain $expected through decoder") { // of type ${manifest[A].runtimeClass.getSimpleName}") {
      decode[A](str).fold(
        e => fail(s"Error parsing $str: $e"),
        _.asJson.noSpaces should ===(expected))
    }
  }

}
