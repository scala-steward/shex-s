package es.weso.wshex.matcher

import es.weso.rdf.nodes._
import scala.collection.JavaConverters._
import org.wikidata.wdtk.datamodel.implementation._
import org.slf4j.LoggerFactory
import org.wikidata.wdtk.datamodel.interfaces.{
  Statement => WDTKStatement,
  StringValue => WDTKStringValue,
  Value => WDTKValue,
  Snak => WDTKSnak,
  Reference => WDTKReference,
  _
}
import java.nio.file.Path
import cats.effect._
import org.wikidata.wdtk.datamodel.helpers.JsonDeserializer
import org.wikidata.wdtk.datamodel.helpers
import es.weso.utils.internal.CollectionCompat._
import es.weso.wbmodel._
import es.weso.wbmodel.Utils._
import es.weso.wshex.{NotImplemented => _, _}
import es.weso.utils.VerboseLevel
import TermConstraint._
import MatchingError._
import es.weso.rbe.interval.IntOrUnbounded
import cats.implicits._
import ReferencesSpec._
import scala.collection.JavaConverters._
import es.weso.wshex.PropertySpec.EachOfPs
import es.weso.wshex.PropertySpec.EmptySpec
import es.weso.wshex.PropertySpec.OneOfPs
import es.weso.wshex.PropertySpec.PropertyS.PropertyLocal
import es.weso.wshex.PropertySpec.PropertyS.PropertyRef

/** Matcher contains methods to match a WShEx schema with Wikibase entities
  *
  * @param wShEx schema
  * @param site URL that identifies the site. By default: http://www.wikidata.org/entity/
  * @param verbose by default false
  */
case class Matcher(
    wShEx: WSchema,
    site: String = "http://www.wikidata.org/entity/",
    verbose: VerboseLevel = VerboseLevel.Nothing
) {

  private lazy val jsonDeserializer = new helpers.JsonDeserializer(site)

  private lazy val logger = LoggerFactory.getLogger(this.getClass().getCanonicalName());

  private def info(msg: String): Unit = if (verbose.asBoolean) logger.info(msg)

  /** Checks if an entityDocument matches the start shape of a WShEx schema
    * If the schema doesn't have a start declaration, it tries to match
    * the first shape expression declared
    *
    * @param entityDocument
    * @return a matching report
    */
  def matchStart(entityDocument: EntityDocument): MatchingStatus =
    wShEx.startShapeExpr match {
      case None => NoMatching(List(NoShapeExprs(wShEx)))
      case Some(se) =>
        matchShapeExpr(
          se,
          EntityDoc(entityDocument),
          EntityDoc.emptyFrom(entityDocument)
        )
    }

  /** Match a JSON string that represents an Entity document against the start shape or the first shape
    *
    * @param jsonStr
    * @return a matching stsatus
    */
  def matchJsonStart(jsonStr: String): MatchingStatus = {
    val entityDocument = jsonDeserializer.deserializeEntityDocument(jsonStr)
    matchStart(entityDocument)
  }

  private def matchShapeExpr(
      shapeExpr: WShapeExpr,
      entity: EntityDoc,
      current: EntityDoc
  ): MatchingStatus =
    shapeExpr match {

      case s: WShape =>
        matchWShape(s, entity, current)

      case sand: WShapeAnd =>
        val ls: LazyList[MatchingStatus] =
          sand.exprs.toLazyList.map(matchShapeExpr(_, entity, current))
        MatchingStatus.combineAnds(current, ls)

      case sor: WShapeOr =>
        val ls: LazyList[MatchingStatus] =
          sor.exprs.toLazyList.map(matchShapeExpr(_, entity, current))
        MatchingStatus.combineOrs(ls)

      case snot: WShapeNot =>
        val ms = matchShapeExpr(snot.shapeExpr, entity, current)
        if (ms.matches) NoMatching(List(NotShapeFail(snot.shapeExpr, entity)))
        else
          Matching(shapeExprs = List(shapeExpr), entity = current, dependencies = ms.dependencies)

      case _ =>
        // TODO: Pending
        val notImplemented: MatchingError = NotImplemented(s"matchShape: $shapeExpr")
        NoMatching(List(notImplemented))

    }

  private def matchWShape(s: WShape, entity: EntityDoc, current: EntityDoc): MatchingStatus = {
    val matchExpr = s.expression match {
      case Some(te) => matchTripleExpr(te, entity, s, current)
      case None     => MatchingStatus.matchEmpty(current)
    }
    s.termConstraints.foldLeft(matchExpr) { case (current, tc) =>
      current match {
        case nm: NoMatching => nm // TODO: Maybe we could provide more info...
        case m: Matching    => matchTermConstraint(tc, entity, s, m.entity)
      }
    }
  }

  private def matchTermConstraint(
      tc: TermConstraint,
      entity: EntityDoc,
      s: WShape,
      current: EntityDoc
  ): MatchingStatus = tc
    .matchTerm(entity, current)
    .fold(
      err => NoMatching(List(err)),
      matched => Matching(List(s), matched)
    )

  private def matchTripleExpr(
      te: TripleExpr,
      entity: EntityDoc,
      se: WShape,
      current: EntityDoc
  ): MatchingStatus =
    te match {
      case tc: TripleConstraint => {
        matchTripleConstraint(tc, entity, se, current)
      }
      case eo: EachOf if eo.exprs.forall(_.isInstanceOf[TripleConstraint]) =>
        val tcs: LazyList[TripleConstraint] =
          eo.exprs.map(_.asInstanceOf[TripleConstraint]).toLazyList
        MatchingStatus.combineAnds(
          current,
          tcs.map(matchTripleConstraint(_, entity, se, current))
        )
      case eo: EachOf => err(NotImplemented(s"EachOf contains complex expressions. Only tripleConstraints are accepted by now"))  
      case oo: OneOf if oo.exprs.forall(_.isInstanceOf[TripleConstraint]) =>
        val tcs: LazyList[TripleConstraint] =
          oo.exprs.map(_.asInstanceOf[TripleConstraint]).toLazyList
        MatchingStatus.combineOrs(
          tcs.map(tc => matchTripleConstraint(tc, entity, se, current))
        )
      case oo: OneOf => err(NotImplemented(s"OneOf contains complex expressions. Only tripleConstraints are accepted by now"))  
      case _ =>
        NoMatching(List(NotImplemented(s"matchTripleExpr: $te")))
    }

  private def matchTripleConstraint(
      tc: TripleConstraint,
      e: EntityDoc,
      se: WShape,
      current: EntityDoc
  ): MatchingStatus = {
    tc match {
      case tcr: TripleConstraintRef => 
        err(NotImplemented(s"tripleConstraintRef: $tcr"))
      case tcl: TripleConstraintLocal => {
        val allowExtras = se.extras.contains(tc.property)
        matchTripleConstraintLocal_Entity(tcl, e, se, current, allowExtras)
      }
      case tcg: TripleConstraintGeneral =>
        err(NotImplemented(s"tripleConstraintGeneral: $tcg"))
    }
  }

  private def matchTripleConstraintLocal_Entity(
    tcl: TripleConstraintLocal, 
    e: EntityDoc, 
    se: WShapeExpr, 
    current: EntityDoc, 
    allowExtras: Boolean
    ): MatchingStatus = {
   val predicate = tcl.property.iri
   val pidValue: PropertyIdValue = predicate2propertyIdValue(predicate)
   val statements = e.getStatementsForProperty(pidValue)
   val (oks, errs) = 
    statements
    .map(matchTripleConstraintLocal_Statement(tcl, _, se, current))
    .partition(_.matches)
   val oksCounter = oks.length
   if (oksCounter < tcl.min) {
     err(StatementsPropertyFailMin(predicate, e, oksCounter, tcl.min))
    } 
    else if (tcl.max < oksCounter) 
     err(StatementsPropertyFailMax(predicate, e, oksCounter, tcl.max))
    else if (errs.nonEmpty && !allowExtras)
              err(StatementsFailTripleConstraint(predicate, tcl, errs))
    else MatchingStatus.combineAnds(current, oks.toLazyList)  
  }

  private def matchTripleConstraintLocal_Statement(
    tcl: TripleConstraintLocal, 
    wdtkStatement: WDTKStatement, 
    se: WShapeExpr, 
    current: EntityDoc): MatchingStatus = {
    val s: Statement = Statement.fromWDTKStatement(wdtkStatement)
    val eitherStatement = for {
      snak <- matchSnakWNodeConstraint(s.snak, tcl.value)
      qs <- tcl.qs.fold(Qualifiers.empty.asRight)(quals => matchQs(s.qualifiers, quals))
      refs <- tcl.refs.fold(References.empty.asRight)(refSpec => matchRefs(s.references, refSpec))
    } yield Statement(tcl.property, snak, qs, refs)
    eitherStatement.fold(
      errs => err(errs),
      s => Matching(List(se), current.addStatement(s))
    )
  }


  private def matchRefs(
    refs: References, 
    refSpec: ReferencesSpec
  ): Either[MatchingError, References] =
    refSpec match {
      case rs: ReferencesSpecSingle =>
        matchReferencesSpecSingle(refs, rs)
        
      case roo: ReferencesOneOf  => NotImplemented(s"ReferencesOneOf: $roo").asLeft
      case reo: ReferencesEachOf => NotImplemented(s"ReferencesEachOf: $reo").asLeft
    }

  def getReferences(st: WDTKStatement): LazyList[WDTKReference] =
    st.getReferences().asScala.toLazyList

  def matchReferencesSpecSingle(
      rs: References,
      refSingle: ReferencesSpecSingle,
  ): Either[MatchingError, References] = {
    val (oks, errs) =
      rs.refs.map(matchReferencePropertySpec(_, refSingle.ps)).partition(_.isRight)
    val numOks = oks.length
    if (numOks < refSingle.min)
      ReferencesNumLessMin(numOks, refSingle.min, refSingle).asLeft
    else if (refSingle.max < numOks)
      ReferencesNumGreaterMax(numOks, refSingle.max, refSingle).asLeft
    else 
      oks.sequence.fold(
        e => InternalError(s"matchReferencesSpecSingle. Error: $e").asLeft, 
        rs => References(rs).asRight
        )
  }

  def matchReferencePropertySpec(
      ref: Reference,
      ps: PropertySpec
  ): Either[MatchingError, Reference] =
    matchSnaksPropertySpec(ref.getSnaks(), ps)
    .map(Reference.fromSnaks(_))

  def matchSnaksPropertySpec(
      snaks: List[Snak],
      ps: PropertySpec,
  ): Either[MatchingError, List[Snak]] =
    ps match {
      case EachOfPs(ps) => 
        // ps.map(matchSnaksPropertyLocal(snaks,_)).sequence // 
        NotImplemented(s"matchSnaksPropertySpec: EachPs: $ps").asLeft
      case EmptySpec =>
        if (snaks.isEmpty)
          List().asRight
        else NoMatchingEmptyPropertySpec(snaks).asLeft
      case OneOfPs(ps) => NotImplemented(s"matchSnaksPropertySpec: OneOfPropertySpec: $ps").asLeft
      case pl: PropertyLocal =>
        matchSnaksPropertyLocal(snaks, pl)
      case pr: PropertyRef => NotImplemented(s"matchSnaksPropertySpec: PropertyRef ps=$ps").asLeft
    }

  private def matchSnaksPropertyLocal(
      snaks: List[Snak],
      pl: PropertyLocal
  ): Either[MatchingError, List[Snak]] = {
    val rs = snaks
      .filter(hasPropertyId(pl.p, _))
      .map(matchSnakWNodeConstraint(_, pl.nc))
    val (oks, errs) = rs.partition(_.isRight)
     if (pl.min > oks.length)  
      PropertySpecLocalNumLessMin(oks.length, pl.min, pl, snaks).asLeft[List[Snak]]
     else if (pl.max < oks.length) 
      PropertySpecLocalNumGreaterMax(oks.length, pl.max, pl, snaks).asLeft[List[Snak]]
     else 
      oks.sequence.fold(
        err => InternalError(s"matchSnaksPropertyLocal: $err, Should all be right...").asLeft,
        ls => ls.asRight)
  }

  private def matchSnakWNodeConstraint(
      snak: Snak,
      nc: WNodeConstraint
  ): Either[MatchingError, Snak] = {
    nc.matchLocal(snak).bimap(
      reason => WNodeConstraintSnakError(reason,nc,snak),
      _ => snak
    )
  }

  private def hasPropertyId(p: PropertyId, snak: Snak): Boolean =
    snak.propertyId == p

  private def err(merr: MatchingError): MatchingStatus =
    NoMatching(List(merr))

  def withPropertyId(property: PropertyId)(st: WDTKStatement): Boolean =
    st.getMainSnak().getPropertyId().getId() == property.id
  
  private def matchQs(
    qualifiers: Qualifiers, 
    qualifierSpec: QualifierSpec,
  ): Either[MatchingError, Qualifiers] =
    // TODO...add matching on qualifiers
    qualifiers.asRight

/*  private def matchPropertyIdValueExpr(
      propertyId: PropertyId,
      valueExpr: Option[WShapeExpr],
      e: EntityDoc,
      se: WShapeExpr,
      current: EntityDoc,
      min: Int,
      max: IntOrUnbounded,
      allowExtras: Boolean,
      qs: Option[QualifierSpec],
      refs: Option[ReferencesSpec]
  ): MatchingStatus = {
    val predicate = propertyId.iri
    val pidValue: PropertyIdValue = predicate2propertyIdValue(predicate)
    val values = e.getValues(pidValue)
    println(s"## MatchPropertyIdValueExpr: $predicate value: $pidValue")
    val resValueExpr = valueExpr match {
      case None =>
        val valuesCounter = values.length
        if (min > valuesCounter) {
          NoMatching(List(ValuesPropertyFailMin(predicate, e, valuesCounter, min)))
        } else if (max < valuesCounter) {
          NoMatching(List(ValuesPropertyFailMax(predicate, e, valuesCounter, max)))
        } else
          Matching(
            shapeExprs = List(se),
            entity = current.addPropertyValues(pidValue, values)
          )

      case Some(nse) =>
        nse match {
          case wnc: WNodeConstraint =>
            // println(s"Matching WNodeConstraint: $wnc with predicate $predicate")
            matchPredicateWNodeConstraintValues(
              wnc,
              values,
              current,
              se,
              pidValue,
              min,
              max,
              allowExtras,
              qs,
              refs
            )
          case _ =>
            NoMatching(
              List(
                NotImplemented(s"matchPropertyIdValueExpr: ${predicate}, valueExpr: ${valueExpr}")
              )
            )
        }
    }
    val resQs =
      qs.fold(resValueExpr)(quals => 
          resValueExpr.and(matchQs(quals, e, current, propertyId)))
    val resRefs = 
       refs.fold(resQs)(refs => 
          resQs.and(matchRefs(refs, e, propertyId, se, current)))
    println(s"After checking references: $resRefs")
    resRefs
  } */

  private def matchPredicateWNodeConstraintValues(
      wnc: WNodeConstraint,
      values: LazyList[WDTKValue],
      current: EntityDoc,
      se: WShapeExpr,
      pidValue: PropertyIdValue,
      min: Int,
      max: IntOrUnbounded,
      allowExtras: Boolean,
      qs: Option[QualifierSpec],
      refs: Option[ReferencesSpec]
  ): MatchingStatus = {
    val (matched, noMatched) =
      values
        .map(matchPredicateWNodeConstraintValue(wnc, _, current, se, pidValue, qs, refs))
        .partition(_.matches)
    val matchedCount = matched.length
    if (matchedCount < min) {
      NoMatching(
        List(
          ValuesPropertyFailNodeConstraintMin(pidValue, matchedCount, min, wnc, noMatched, matched)
        )
      )
    } else if (max < matchedCount) {
      NoMatching(
        List(ValuesPropertyFailNodeConstraintMax(pidValue, matchedCount, max, wnc, matched))
      )
    } else {
      if (noMatched.nonEmpty) {
        if (!allowExtras)
          NoMatching(List(ValuesPropertyFailNodeConstraint(pidValue, wnc, noMatched)))
        else
          // TODO. noMatched contains values but EXTRA is allowed...should we add those extra values?
          MatchingStatus.combineAnds(current, matched)
      } else {
        MatchingStatus.combineAnds(current, matched)
      }
    }
  }

  private def matchPredicateWNodeConstraintValue(
      wnc: WNodeConstraint,
      value: WDTKValue,
      current: EntityDoc,
      se: WShapeExpr,
      pidValue: PropertyIdValue,
      qs: Option[QualifierSpec],
      refs: Option[ReferencesSpec]
  ): MatchingStatus = {
    // TODO: Add Qs and referencesSpec
    val wbValue = Value.fromWDTKValue(value)
    wnc
      .matchLocal(wbValue)
      .fold(
        reason => NoMatching(List(WNodeConstraintError(reason, value, wbValue))),
        _ => Matching(List(se), 
        current.addPropertyValues(pidValue, LazyList(value)))
      )
  }

  private def predicate2propertyIdValue(predicate: IRI): PropertyIdValue = {
    val (localName, base) = splitIri(predicate)
    val propertyId = new PropertyIdValueImpl(localName, base)
    propertyId
  }

}

object Matcher {

  val defaultEntityIRI = es.weso.wbmodel.Value.defaultIRI

  /** Read a WShEx from a path
    *
    * @param schemaPath: Path where the WShEx schema can be found
    * @param verbose: Print more messages during validation
    * @param format: WShEx format
    * @return an IO action that returns a matcher
    */
  def fromPath(
      schemaPath: Path,
      format: WShExFormat = WShExFormat.ESCompactFormat,
      base: Option[IRI] = None,
      entityIRI: IRI = defaultEntityIRI,
      verbose: VerboseLevel = VerboseLevel.Nothing
  ): IO[Matcher] =
    WSchema
      .fromPath(schemaPath, format, base, entityIRI, verbose)
      .map(s => Matcher(wShEx = s, verbose = verbose))

  /** Read a WShEx from a path
    * This is the synchronous and unsafe version
    * An IO-based version is also available
    *
    * @param schemaPath: Path where the WShEx schema can be found
    * @param verbose: Print more messages during validation
    * @param format: WShEx format
    * @return a matcher
    */
  def unsafeFromPath(
      schemaPath: Path,
      format: WShExFormat = WShExFormat.CompactWShExFormat,
      base: Option[IRI] = None,
      entityIRI: IRI = defaultEntityIRI,
      verbose: VerboseLevel = VerboseLevel.Nothing
  ): Matcher = {
    import cats.effect.unsafe.implicits.global
    fromPath(schemaPath, format, base, entityIRI, verbose).unsafeRunSync()
  }

  def unsafeFromString(
      str: String,
      verbose: VerboseLevel = VerboseLevel.Nothing,
      base: Option[IRI] = None,
      entityIRI: IRI = defaultIRI,
      format: WShExFormat = WShExFormat.CompactWShExFormat
  ): Either[ParseError, Matcher] =
    WSchema
      .unsafeFromString(str, format, base, entityIRI, verbose)
      .map(s => Matcher(wShEx = s, verbose = verbose))

  val defaultIRI = es.weso.wbmodel.Value.defaultIRI

}
