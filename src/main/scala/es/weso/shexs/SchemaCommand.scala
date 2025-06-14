package es.weso.shexs

import es.weso.utils.VerboseLevel
import cats.effect.IO
import cats.effect.ExitCode
import com.monovore.decline.Opts
import cats.implicits._
import es.weso.shex.ResolvedSchema
import es.weso.shex.ShapeLabel
import es.weso.shex.ShapeExpr
import es.weso.shex.IRILabel
import es.weso.rdf.nodes.IRI
import es.weso.rdf.PrefixMap
import cats.data.Validated._
import es.weso.shex.implicits.encoderShEx._
import io.circe._
import io.circe.syntax._
import es.weso.shex.Extends
import es.weso.shex.References
import es.weso.shex.Schema

sealed abstract class ShowMethod {
  val name: String
}
object ShowMethod {
  case object ShowQualified extends ShowMethod { val name = "qualified" }
  case object ShowFlat extends ShowMethod { val name = "flat" }
  case object ShowPPrint extends ShowMethod { val name = "pprint" }
  case object ShowJSON extends ShowMethod { val name = "json" }

  val showMethods = List(ShowQualified, ShowFlat, ShowPPrint, ShowJSON)
  val showMethodOpt: Opts[ShowMethod] =
    Opts
      .option[String](
        "method",
        short = "m",
        help =
          s"show-method. Available methods = ${showMethods.map(_.name).mkString("|")}, default = ${showMethods.head.name}"
      )
      .mapValidated(s =>
        showMethods.collect {
          case sm if sm.name.toLowerCase == s.toLowerCase => sm
        }.headOption match {
          case None =>
            invalidNel(
              s"Invalid show method $s. Available methods = ${showMethods.map(_.name).mkString(",")}"
            )
          case Some(sm) => valid(sm)
        }
      )
      .withDefault(showMethods.head)
}

case class SchemaCommand(
    schemaSpec: SchemaSpec,
    showInheritance: Boolean,
    showDependencyGraph: Boolean,
    showMethod: ShowMethod,
    showShape: Option[ShapeLabel],
    verbose: VerboseLevel
) {
  def run(): IO[ExitCode] = for {
    schema <- schemaSpec.getSchema(verbose)
    resolved <- ResolvedSchema.resolve(schema, schemaSpec.baseIRI, verbose)
    _ <- showShapeLabels(resolved)
    _ <- showWellFormed(schema)
    _ <-
      if (showInheritance) runShowInheritance(resolved)
      else IO.pure(())
    _ <-
      if (showDependencyGraph) showDepGraph(resolved)
      else IO.pure(())
    _ <- showShape match {
      case None     => IO.pure(())
      case Some(sl) => runShowShapeLabel(sl, resolved, showMethod)
    }
  } yield ExitCode.Success

  private def showWellFormed(schema: Schema): IO[Unit] = {
    val wellFormed = schema.wellFormed
    wellFormed match {
      case Left(msg) =>
        IO.println(s"Schema is NOT well-formed: ${msg}")
      case Right(_) =>
        IO.println("Schema is well-formed")
    }
  }

  private def showShapeLabels(schema: ResolvedSchema): IO[Unit] = {
    val labels = schema.resolvedMapShapeExprs.keySet
    IO.println(
      s"shape labels: ${if (labels.isEmpty) "[]" else labels.map(schema.qualify(_)).mkString(", ")}"
    )
  }

  private def showDepGraph(schema: ResolvedSchema): IO[Unit] = {
    val eitherDepGraph = schema.source.depGraph
    eitherDepGraph match {
      case Left(err) =>
        IO.println(s"Error obtaining dependency graph: $err")
      case Right(depGraph) =>
        IO.println(s"Dependency graph:\n${depGraph.showEdges(lbl => schema.qualify(lbl.toRDFNode))}")
    }
  }

  private def runShowInheritance(schema: ResolvedSchema): IO[Unit] = for {
    inheritanceStr <-
      schema.inheritanceGraph.show(lbl => schema.qualify(lbl.toRDFNode), r => s" -${r.name}-> ")
    _ <- IO.println(s"""|Inheritance: 
                        |${inheritanceStr}
                        |""".stripMargin)
  } yield ()

  private def runShowShapeLabel(
      sl: ShapeLabel,
      schema: ResolvedSchema,
      showMethod: ShowMethod
  ): IO[Unit] = for {
    se <- schema.getShape(sl).fold(err => IO.raiseError(new RuntimeException(err)), s => IO.pure(s))
    _ <- IO.println(s"""|Shape: ${shape2String(se, schema.prefixMap, showMethod)}""".stripMargin)
    extendeds <- schema.inheritanceGraph.descendantsByEdgtype(sl, Extends)
    references <- schema.inheritanceGraph.descendantsByEdgtype(sl, References)
    _ <- IO.println(s"Extended by shapes: ${extendeds.map(sl => schema.qualify(sl)).mkString(",")}")
    _ <- IO.println(
      s"Referenced by shapes: ${references.map(sl => schema.qualify(sl)).mkString(",")}"
    )
    _ <- IO.println(
      s"Paths: ${se.paths(schema).fold(e => s"Error: $e", ps => ps.map(_.show).mkString("|"))}"
    )
  } yield ()

  private def shape2String(se: ShapeExpr, prefixMap: PrefixMap, showMethod: ShowMethod): String =
    showMethod match {
      case ShowMethod.ShowQualified => se.showQualified(prefixMap)
      case ShowMethod.ShowPPrint =>
        pprint.log(se, "shape")
        ""
      case ShowMethod.ShowFlat => se.toString
      case ShowMethod.ShowJSON => se.asJson.spaces2
    }
}

object SchemaCommand {

  val showInheritance: Opts[Boolean] =
    Opts.flag("show-inheritance", short = "i", help = "show inheritance graph").orFalse

  val showDependencyGraph: Opts[Boolean] =
    Opts.flag("show-dependency-graph", short = "d", help = "show dependency graph").orFalse

  val showShape: Opts[Option[ShapeLabel]] =
    UriOpt.uri("shape", help = "Show shape").map(uri => IRILabel(IRI(uri))).orNone

  val schemaCommand: Opts[SchemaCommand] =
    Opts.subcommand("schema", "Schema processing actions") {
      (
        SchemaSpec.schemaSpec,
        showInheritance,
        showDependencyGraph,
        ShowMethod.showMethodOpt,
        showShape,
        VerboseLevelOpt.verboseLevel
      )
        .mapN(SchemaCommand.apply)
    }

}
