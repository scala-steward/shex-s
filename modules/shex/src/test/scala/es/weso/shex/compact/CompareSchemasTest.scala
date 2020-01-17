package es.weso.shex.compact

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import es.weso.utils.json.JsonTest
import es.weso.shex._
import es.weso.shex.implicits.decoderShEx._
import es.weso.utils.FileUtils._
import io.circe.parser._
import org.scalatest.EitherValues
import scala.io._
import cats.effect._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class CompareSchemasTest extends AnyFunSpec with JsonTest with Matchers with EitherValues {

  val conf: Config = ConfigFactory.load()
  val schemasFolder = conf.getString("schemasFolder")

  val ignoreFiles = List("coverage")

  def getCompactFiles(schemasDir: String): IO[List[File]] = {
    getFilesFromFolderWithExt(schemasDir, "shex", ignoreFiles)
  }

  ignore("Parsing Schemas from ShEx") {
    for (file <- getCompactFiles(schemasFolder).unsafeRunSync()) {
      it(s"Should read Schema from file ${file.getName}") {
        val str = Source.fromFile(file)("UTF-8").mkString
        Schema.fromString(str, "SHEXC") match {
          case Right(schema) => {
            val (name, ext) = splitExtension(file.getName)
            val jsonFile = schemasFolder + "/" + name + ".json"
            val jsonStr = Source.fromFile(jsonFile)("UTF-8").mkString
            decode[Schema](jsonStr) match {
              case Left(err) => fail(s"Error parsing $jsonFile: $err")
              case Right(expectedSchema) =>
                if (CompareSchemas.compareSchemas(schema, expectedSchema)) {
                  info("Jsons are equal")
                } else {
                  fail(s"Json's are different. Parsed:\n${schema}\n-----Expected:\n${expectedSchema}")
                }
            }
          }
          case Left(err) => fail(s"Parsing error: $err")
        }
      }
    }
  }
}
