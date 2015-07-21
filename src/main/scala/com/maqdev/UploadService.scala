package com.maqdev

import java.io.{File, OutputStream, InputStream, ByteArrayInputStream}
import java.util.UUID

import akka.actor.Actor
import akka.event.Logging
import spray.http.MediaTypes._
import spray.http.{BodyPart, _}
import spray.routing.HttpService

import scala.util.control.NonFatal
import scala.util.matching.Regex

class UploadService extends Actor with HttpService {
  import com.typesafe.config._
  val conf = ConfigFactory.load()
  val uploadDir = conf.getString("upload-dir")
  val log = Logging(context.system, this)

  import collection.JavaConversions._
  val templateMap = conf.getConfigList("templates").map { x =>
    val r = new Regex(x.getString("name"))
    val cmd = x.getString("command")
    (r, cmd)
  }

  def actorRefFactory = context

  def receive = runRoute(myRoute)

  val myRoute =
    get {
      path("") {
        respondWithMediaType(`text/plain`) {
          complete("POST file to /upload")
        }
      }
    } ~
    path("upload") {
      post {
        entity(as[MultipartFormData]) { formData =>
          val r = formData.fields.headOption.map {
            case (BodyPart(entity, headers)) =>
              val content = entity.data.toByteArray
              val contentType = headers.find(h => h.is("content-type"))
              val fileName = headers.find(h => h.is("content-disposition")).get.value.split("filename=").last

              templateMap.find(_._1.findFirstIn(fileName).isDefined) map { cmd ⇒

                val uuid = UUID.randomUUID()
                val dir = uploadDir + "/" + uuid.toString
                new File(dir).mkdirs()
                val path = dir + "/" + fileName
                saveAttachment(path, content)


                val cmdFull = cmd._2.replace("$FILE_NAME", path)
                val resultLog = new StringBuilder
                val resultErrorLog = new StringBuilder
                import sys.process._
                val logger = ProcessLogger(
                  (o: String) => resultLog.append(o),
                  (e: String) => resultErrorLog.append(e)
                )

                val result =
                  try {
                    sys.process.Process(cmdFull).!(logger)
                    true
                  }
                  catch {
                    case NonFatal(e) ⇒ false
                  }

                val id = UUID.randomUUID().toString
                if (result) {
                  log.info(s"$id $cmdFull Finished with: $resultLog\n$resultErrorLog")
                  HttpResponse(StatusCodes.OK, HttpEntity(ContentType(`text/plain`), s"OK: $id"))
                }
                else {
                  log.error(s"$id $cmdFull Finished with: $resultLog\n$resultErrorLog")
                  HttpResponse(StatusCodes.InternalServerError, HttpEntity(ContentType(`text/plain`), s"Failed: $id"))
                }
              } getOrElse {
                HttpResponse(StatusCodes.Forbidden, HttpEntity(ContentType(`text/plain`), "Forbidden\n"))
              }
            case _ =>
              HttpResponse(StatusCodes.BadRequest, HttpEntity(ContentType(`text/plain`), "Bad request\n"))
          }
          complete {
            r
          }
        }
      }
    }

  private def saveAttachment(fileName: String, content: Array[Byte]) {
    saveAttachment[Array[Byte]](fileName, content, {(is, os) => os.write(is)})
  }

  private def saveAttachment(fileName: String, content: InputStream) {
    saveAttachment[InputStream](fileName, content,
    { (is, os) =>
      val buffer = new Array[Byte](16384)
      Iterator
        .continually (is.read(buffer))
        .takeWhile (-1 !=)
        .foreach (read=>os.write(buffer,0,read))
    }
    )
  }

  private def saveAttachment[T](fileName: String, content: T, writeFile: (T, OutputStream) => Unit) {
    val fos = new java.io.FileOutputStream(fileName)
    writeFile(content, fos)
    fos.close()
  }
}
