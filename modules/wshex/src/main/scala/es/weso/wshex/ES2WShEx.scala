package es.weso.wshex

import cats._
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import es.weso._
import es.weso.rbe.interval.{IntLimit, Unbounded}
import es.weso.rdf.nodes._
import es.weso.wbmodel.{Property => _, _}
import es.weso.wshex.esconvert._
import es.weso.rbe.interval.IntOrUnbounded
import scala.collection.compat._ // Required for partitionMap

case class ESConvertOptions(
    entityIri: IRI,
    directPropertyIri: IRI,
    propIri: IRI,
    propStatementIri: IRI,
    propQualifierIri: IRI
)

object ESConvertOptions {
  val default = ESConvertOptions(
    entityIri = IRI("http://www.wikidata.org/entity/"),
    directPropertyIri = IRI("http://www.wikidata.org/prop/direct/"),
    propIri = IRI("http://www.wikidata.org/prop/"),
    propStatementIri = IRI("http://www.wikidata.org/prop/statement/"),
    propQualifierIri = IRI("http://www.wikidata.org/prop/qualifier/")
  )
}

case class ES2WShEx(convertOptions: ESConvertOptions) extends LazyLogging {

  /** Convert an entity schema in ShEx to WShEx
    */
  def convertSchema(
      shexSchema: shex.AbstractSchema
  ): Either[ConvertError, WSchema] =
    for {
      shapes <-
        shexSchema.shapesMap.toList.map { case (l, se) =>
          convertLabelShapeExpr(l, se, shexSchema)
        }.sequence

      start <- shexSchema.start match {
        case None     => none.asRight
        case Some(se) => convertShapeExpr(se, shexSchema).flatMap(se => Right(Some(se)))
      }
    } yield WSchema(shapes.toMap, start, shexSchema.prefixMap)

  private def convertLabelShapeExpr(
      label: shex.ShapeLabel,
      se: shex.ShapeExpr,
      shexSchema: shex.AbstractSchema
  ): Either[ConvertError, (ShapeLabel, WShapeExpr)] = for {
    cse <- convertShapeExpr(se, shexSchema)
    lbl = convertShapeLabel(label)
  } yield (lbl, cse)

  private def convertShapeExpr(
      se: shex.ShapeExpr,
      schema: shex.AbstractSchema
  ): Either[ConvertError, WShapeExpr] =
    se match {
      case nc: shex.NodeConstraint => convertNodeConstraint(nc)
      case s: shex.Shape           => convertShape(s, schema)
      case sand: shex.ShapeAnd =>
        for {
          ss <- sand.shapeExprs.map(convertShapeExpr(_, schema)).sequence
        } yield WShapeAnd(id = convertId(sand.id), exprs = ss)
      case sor: shex.ShapeOr =>
        for {
          ss <- sor.shapeExprs.map(convertShapeExpr(_, schema)).sequence
        } yield WShapeOr(id = convertId(sor.id), exprs = ss)
      case snot: shex.ShapeNot =>
        convertShapeExpr(snot.shapeExpr, schema)
          .map(se => WShapeNot(id = convertId(snot.id), shapeExpr = se))
      case sref: shex.ShapeRef =>
        WShapeRef(convertShapeLabel(sref.reference)).asRight
      case _ => UnsupportedShapeExpr(se).asLeft
    }

  private def convertId(id: Option[shex.ShapeLabel]): Option[ShapeLabel] =
    id.map(convertShapeLabel)

  private def convertNodeConstraint(
      nc: shex.NodeConstraint
  ): Either[ConvertError, WNodeConstraint] =
    nc match {
      case shex.NodeConstraint(id, None, None, List(), Some(values), None, None) =>
        convertValueSet(convertId(id), values)
      // convertValueSet(values.getOrElse(List())).map(ValueSet(id, _))
      case _ => UnsupportedNodeConstraint(nc).asLeft
    }

  private def convertValueSet(
      id: Option[ShapeLabel],
      values: List[shex.ValueSetValue]
  ): Either[ConvertError, ValueSet] =
    convertValueSetValues(values)
      .map(vs => ValueSet(id, vs))

  private def convertValueSetValues(
      values: List[shex.ValueSetValue]
  ): Either[ConvertError, List[ValueSetValue]] =
    values
      .map(convertValueSetValue)
      .sequence

  private def convertValueSetValue(
      value: shex.ValueSetValue
  ): Either[ConvertError, ValueSetValue] =
    value match {
      case shex.IRIValue(i) =>
        val (name1, base1) = Utils.splitIri(i)
        logger.trace(s"""|convertValueSetValue:
              |name1: $name1
              |base1: $base1
              |""".stripMargin)
        if (IRI(base1) == convertOptions.entityIri) {
          Right(EntityIdValueSetValue(EntityId.fromIri(i)))
        } else {
          Right(IRIValueSetValue(i))
        }
      case _ => UnsupportedValueSetValue(value).asLeft
    }

  private def convertShape(
      s: shex.Shape,
      schema: shex.AbstractSchema
  ): Either[ConvertError, WShape] =
    for {
      te <- optConvert(s.expression, convertTripleExpr(schema))
    } yield WShape(
      id = convertId(s.id),
      closed = s.closed.getOrElse(false),
      extra = s.extra.getOrElse(List()).map(PropertyId.fromIRI(_)),
      expression = te,
      List()
    )

  private def optConvert[A, B](
      v: Option[A],
      cnv: A => Either[ConvertError, B]
  ): Either[ConvertError, Option[B]] =
    v.fold(none[B].asRight[ConvertError])(a => cnv(a).map(Some(_)))

  private def convertTripleExpr(
      schema: shex.AbstractSchema
  )(te: shex.TripleExpr): Either[ConvertError, TripleExpr] =
    te match {
      case eo: shex.EachOf =>
        // TODO: generalize to handle triple expressions
        for {
          tes <- eo.expressions
            .map(convertTripleExpr(schema))
            .sequence
          tcs <- tes.map(castToTripleConstraint(_)).sequence
        } yield EachOf(tcs)
      case oo: shex.OneOf =>
        // TODO: generalize to handle triple expressions
        for {
          tes <- oo.expressions
            .map(convertTripleExpr(schema))
            .sequence
          tcs <- tes.map(castToTripleConstraint(_)).sequence
        } yield OneOf(tcs)
      case tc: shex.TripleConstraint =>
        convertTripleConstraint(tc, schema)
      case _ =>
        logger.warn(s"Unsupported triple expression: $te")
        Left(UnsupportedTripleExpr(te))
    }

  private def castToTripleConstraint(
      te: TripleExpr
  ): Either[ConvertError, TripleConstraint] = te match {
    case tc: TripleConstraint => Right(tc)
    case _                    => Left(CastTripleConstraintError(te))
  }

  private def makeTripleConstraint(
      pred: PropertyId,
      min: Int,
      max: IntOrUnbounded,
      se: Option[shex.ShapeExpr],
      schema: shex.AbstractSchema
  ): Either[ConvertError, TripleConstraint] =
    se match {
      case None =>
        TripleConstraintLocal(pred, EmptyExpr, min, max).asRight
      case Some(se) =>
        convertShapeExpr(se, schema).flatMap(s =>
          s match {
            case s @ WShapeRef(lbl) =>
              TripleConstraintRef(pred, s, min, max, None).asRight
            case v @ ValueSet(id, vs) =>
              TripleConstraintLocal(pred, v, min, max).asRight
            case _ =>
              UnsupportedShapeExpr(se, s"Making tripleConstraint for pred: $pred").asLeft
          }
        )
    }

  private def convertTripleConstraint(
      tc: shex.TripleConstraint,
      schema: shex.AbstractSchema
  ): Either[ConvertError, TripleConstraint] = {
    val iriParsed = IRIConvert.parseIRI(tc.predicate, convertOptions)
    iriParsed match {
      case Some(DirectProperty(n)) =>
        val pred = PropertyId.fromIRI(tc.predicate)
        val (min, max) = convertMinMax(tc)
        makeTripleConstraint(pred, min, max, tc.valueExpr, schema)
      case Some(Property(p)) =>
        tc.valueExpr match {
          case Some(ve) => convertTripleConstraintProperty(p, ve, schema)
          case None     => NoValueForPropertyConstraint(p, tc).asLeft
        }
      case Some(PropertyQualifier(_)) =>
        val pred = PropertyId.fromIRI(tc.predicate)
        val (min, max) = convertMinMax(tc)
        makeTripleConstraint(pred, min, max, tc.valueExpr, schema)
      case Some(PropertyStatement(_)) =>
        val pred = PropertyId.fromIRI(tc.predicate)
        val (min, max) = convertMinMax(tc)
        makeTripleConstraint(pred, min, max, tc.valueExpr, schema)
      case _ => UnsupportedPredicate(tc.predicate, s"Parsing direct tripleConstraint $tc").asLeft
    }
  }

  private def convertMinMax(tc: shex.TripleConstraint): (Int, IntOrUnbounded) = {
    val min = tc.min
    val max = tc.max match {
      case shex.Star      => Unbounded
      case shex.IntMax(m) => IntLimit(m)
    }
    (min, max)
  }

  private def convertTripleConstraintProperty(
      n: Int,
      t: shex.ShapeExpr,
      schema: shex.AbstractSchema
  ): Either[ConvertError, TripleConstraint] =
    t match {
      case s: shex.Shape => convertTripleConstraintPropertyShape(n, s, schema)
      case ref: shex.ShapeRef =>
        schema.getShape(ref.reference) match {
          case Left(msg) => NotFoundShape(ref.reference, msg).asLeft
          case Right(se) =>
            se match {
              case s: shex.Shape =>
                convertTripleConstraintPropertyShape(n, s, schema)
              case _ =>
                UnsupportedShapeExpr(se, s"Parsing property $n with ref ${ref.reference}").asLeft
            }
        }

      case _ => UnsupportedShapeExpr(t, s"Parsing property $n").asLeft
    }

  private def convertTripleConstraintPropertyShape(
      n: Int,
      s: shex.Shape,
      schema: shex.AbstractSchema
  ): Either[ConvertError, TripleConstraint] =
    s.expression match {
      case None => NoExprForTripleConstraintProperty(n, s).asLeft
      case Some(te) =>
        te match {
          case tc: shex.TripleConstraint =>
            val iriParsed = IRIConvert.parseIRI(tc.predicate, convertOptions)
            iriParsed match {
              case Some(PropertyStatement(ns)) =>
                if (n == ns) {
                  val pred = PropertyId.fromNumber(n, convertOptions.directPropertyIri)
                  val (min, max) = convertMinMax(tc)
                  makeTripleConstraint(pred, min, max, tc.valueExpr, schema)
                } else DifferentPropertyPropertyStatement(n, ns).asLeft
              case _ =>
                UnsupportedPredicate(
                  tc.predicate,
                  s"Parsing shape for property $n\nShape: ${s}"
                ).asLeft
            }
          case s: shex.EachOf =>
            parseEachOfForProperty(n, s, schema)
          case _ => UnsupportedTripleExpr(te, s"Parsing property $n").asLeft
        }
    }

  private def convertShapeLabel(label: shex.ShapeLabel): ShapeLabel =
    label match {
      case shex.IRILabel(iri)     => IRILabel(iri)
      case shex.BNodeLabel(bnode) => BNodeLabel(bnode)
      case shex.Start             => Start
    }

  private def parseEachOfForProperty(
      n: Int,
      s: shex.EachOf,
      schema: shex.AbstractSchema
  ): Either[ConvertError, TripleConstraint] =
    getPropertyStatement(n, s.expressions, schema).flatMap(tc =>
      getQualifiers(s.expressions, n, schema).flatMap(qs => tc.withQs(qs).asRight)
    )

  private def getPropertyStatement(
      n: Int,
      es: List[shex.TripleExpr],
      schema: shex.AbstractSchema
  ): Either[ConvertError, TripleConstraint] =
    es.collectFirstSome(checkPropertyStatement(n, schema)) match {
      case None     => NoValueForPropertyStatementExprs(n, es).asLeft
      case Some(tc) => tc.asRight
    }

  private def checkPropertyStatement(n: Int, schema: shex.AbstractSchema)(
      te: shex.TripleExpr
  ): Option[TripleConstraint] =
    te match {
      case tc: shex.TripleConstraint =>
        val iriParsed = IRIConvert.parseIRI(tc.predicate, convertOptions)
        iriParsed match {
          case Some(PropertyStatement(ns)) =>
            if (n == ns) {
              val pred = PropertyId.fromNumber(n, convertOptions.directPropertyIri)
              val (min, max) = convertMinMax(tc)
              makeTripleConstraint(pred, min, max, tc.valueExpr, schema).toOption
            } else None
          case _ => None
        }
      case _ => None
    }

  private def getQualifiers(
      es: List[shex.TripleExpr],
      n: Int,
      schema: shex.AbstractSchema
  ): Either[ConvertError, Option[QualifierSpec]] = {
    val (errs, oks) = es.map(getQualifier(n, schema)).partitionMap(x => x)
    if (errs.isEmpty) {
      val vs = oks.flatten
      if (vs.isEmpty) none.asRight
      else
        QualifierSpec(EachOfPs(vs), false).some.asRight
    } else {
      ConvertErrors(errs).asLeft
    }
  }

  private def getQualifier(n: Int, schema: shex.AbstractSchema)(
      te: shex.TripleExpr
  ): Either[ConvertError, Option[QualifierS]] =
    te match {
      case tc: shex.TripleConstraint =>
        val iriParsed = IRIConvert.parseIRI(tc.predicate, convertOptions)
        iriParsed match {
          case Some(PropertyStatement(ns)) =>
            if (n == ns) none.asRight
            else DifferentPropertyPropertyStatement(n, ns, s"Parsing qualifiers").asLeft
          case Some(PropertyQualifier(nq)) =>
            val pq = PropertyId.fromNumber(nq, convertOptions.propQualifierIri)
            val (min, max) = convertMinMax(tc)
            tc.valueExpr match {
              case None => QualifierLocal(pq, EmptyExpr, min, max).some.asRight
              case Some(se) =>
                convertShapeExpr(se, schema).flatMap(s =>
                  s match {
                    case s @ WShapeRef(lbl)   => QualifierRef(pq, s, min, max).some.asRight
                    case v @ ValueSet(id, vs) => QualifierLocal(pq, v, min, max).some.asRight
                    case _ =>
                      UnsupportedShapeExpr(se, s"Parsing qualifiers for property $n").asLeft
                  }
                )
            }
          case _ => UnsupportedPredicate(tc.predicate, s"Parsing qualifiers for property $n").asLeft
        }
      case _ => UnsupportedTripleExpr(te, s"Parsing qualifiers of property $n").asLeft
    }
}

object ES2WShEx {
  def apply(
      convertOptions: ConvertOptions = ConvertOptions.default
  ): ShEx2WShEx =
    // Note: 'new' is needed to avoid infinite loop
    new ShEx2WShEx(convertOptions)

}
