package es.weso.shextest.manifest

import java.nio.file.Paths

import com.typesafe.config.{Config, ConfigFactory}
import es.weso.shex._
import es.weso.shex.compact.CompareSchemas
import io.circe.parser._
import io.circe.syntax._
import es.weso.shex.implicits.decoderShEx._
import es.weso.shex.implicits.encoderShEx._
import Utils._
// import es.weso.utils.UriUtils._
//import cats._
import cats.data._
import cats.effect.IO
import cats.implicits._

class SchemasManifestTest extends ValidateManifest {

  val nameIfSingle: Option[String] =
     None
     // Some("_all")

  val conf: Config = ConfigFactory.load()
  val shexFolder = conf.getString("schemasFolder")
//  val shexFolder = conf.getString("shexLocalFolder")
  val shexFolderURI = Paths.get(shexFolder).normalize.toUri

  describe("RDF2ManifestLocal") {
    val r = RDF2Manifest.read(shexFolder + "/" + "manifest.ttl", "Turtle", Some(shexFolderURI.toString), false)
    r.fold(e => fail(s"Error reading manifest: $e"),
      mf => {
        for (e <- mf.entries) {
          if (nameIfSingle == None || nameIfSingle.getOrElse("") == e.name) {
            it(s"Should pass test ${e.name}") {
              println(s"Testing: ${e.name}")
              e match {
                case r: RepresentationTest => {
                  val schemaUri = mkLocal(r.shex, schemasBase, shexFolderURI)
                  val jsonUri   = mkLocal(r.json, schemasBase, shexFolderURI)
                  val either: EitherT[IO, String, String] = for {
                    schemaStr      <- derefUriIO(schemaUri)
                    jsonStr        <- derefUriIO(jsonUri)
                    schema         <- fromIO(Schema.fromString(schemaStr, "SHEXC", None))
                    _ <- fromIO (IO { println(s"Schema: ${schema}") })
                    _ <- fromIO (IO { println(s"Checking if it is well formed...") })
                    // b              <- schema.wellFormed
                    // _ <- { println(s"Schema well formed?: ${b.toString}"); Right(()) }

                    expectedSchema <- fromEitherS(decode[Schema](jsonStr).leftMap(_.getMessage()))
                    _ <- fromIO (IO { println(s"Expected schema: ${expectedSchema}") })
                    _ <- if (CompareSchemas.compareSchemas(schema, expectedSchema)) ok("Schemas are ")
                         else err(s"Schemas are different. Parsed:\n${schema}\n-----Expected:\n${expectedSchema}")
                    json <- fromEitherS(parse(jsonStr).leftMap(_.message))
                    check <- if (json.equals(schema.asJson)) ok(s"Schemas are equal")
                             else err(s"Json's are different\nSchema:${schema}\nJson generated: ${schema.asJson.spaces2}\nExpected: ${json.spaces2}")
                  } yield check
                  either.fold(e => fail(s"Error: $e"), msg => info(msg))
                }
              }
            }
          }
        }
        info(s"Manifest read OK: ${mf.entries.length} entries")
      }
    )
   }


}
