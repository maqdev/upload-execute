package com.maqdev

import scala.concurrent.duration._

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http

object Boot extends App {
  implicit val system = ActorSystem("on-spray-can")

  val service = system.actorOf(Props[UploadService], "upload-execute")

  import com.typesafe.config._
  val conf = ConfigFactory.load()
  val serverPort = conf.getInt("port")
  val serverInterface = conf.getString("interface")

  implicit val timeout = Timeout(5.seconds)
  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http) ? Http.Bind(service, interface = serverInterface, port = serverPort)
}
