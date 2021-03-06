package com.geeksville.dapi

import org.scalatra.test.scalatest._
import org.scalatest.FunSuite
import com.github.aselab.activerecord.scalatra.ScalatraConfig
import org.scalatest.BeforeAndAfter
import com.geeksville.dapi.model.Vehicle
import org.json4s.Formats
import org.json4s.DefaultFormats
import com.geeksville.json.GeeksvilleFormats
import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization
import java.io.File
import org.scalatra.test.BytesPart
import com.geeksville.dapi.model.Mission
import grizzled.slf4j.Logging
import org.scalatra.test.Client
import com.geeksville.dapi.auth.SessionsController
import org.scalatest.GivenWhenThen
import scala.util.Random
import com.geeksville.dapi.model.UserJson
import com.geeksville.dapi.model.DroneModelFormats
import com.geeksville.dapi.model.VehicleJson
import java.util.UUID
import com.geeksville.apiproxy.APIConstants
import com.geeksville.util.ThreadTools
import com.geeksville.util.FileTools
import java.io.BufferedInputStream
import java.io.FileInputStream
import com.geeksville.dapi.oauth.OAuthController



case class RequestInfo(method: String, uri: String, queryParams: Iterable[(String, String)], headers: Map[String, String], body: String)


class ServerDependentSuite /* (disabled: Boolean) */ extends FunSuite with ScalatraSuite with Logging with GivenWhenThen {
  implicit val swagger = new ApiSwagger

  lazy val activeRecordTables = new ScalatraConfig().schema

  // Sets up automatic case class to JSON output serialization
  protected implicit def jsonFormats: Formats = DefaultFormats ++ DroneModelFormats ++ GeeksvilleFormats

  // The random ID we will use for this test session
  val random = new Random(System.currentTimeMillis)
  def uniqueSuffix() = random.alphanumeric.take(6).mkString

  val login = "test-" + uniqueSuffix
  val password = random.alphanumeric.take(8).mkString
  val email = s"kevin+$login@3drobotics.com"
  val fullName = "TestUser LongName"

  val apiKey = "eb34bd67.megadroneshare"

  /// Generate a valid authorzation header
  def makeAuthHeader(typ: String, param: String) =  ("Authorization" -> s"$typ $param")

  /// An old school pre oauth 2.0 API auth header
  def simpleAuthHeader = makeAuthHeader("DroneApi", s"""apikey="$apiKey"""")

  def makeOAuthHeader(accessToken: String) = makeAuthHeader("Bearer", accessToken)

  val acceptJsonHeader = "Accept" -> "application/json"
  val contentJsonHeader = "Content-Type" -> "application/json"
  val refererHeader = "Referer" -> "http://www.droneshare.com/"  // Pretend to come from droneshare server - because we are using its api key

  // Send this in all cases
  val commonHeaders = Map(
    simpleAuthHeader,
    refererHeader)

  val jsonHeaders = commonHeaders ++ Map(
    acceptJsonHeader,
    contentJsonHeader)

  val loginInfo = Map("login" -> login, "password" -> password)

  private var currentRequest: Option[RequestInfo] = None

  // Instead of using before we use beforeAll so that we don't tear down the DB for each test (speeds run at risk of side effect - FIXME)
  override def beforeAll() {
    println("**************************** STARTING TESTS ************************************")
    System.setProperty("run.mode", "test") // So we use the correct testing DB
    Global.setConfig()

    super.beforeAll()

    activeRecordTables.initialize

    addServlet(new SessionsController, "/api/v1/session/*")
    addServlet(new UserController, "/api/v1/user/*")
    addServlet(new VehicleController, "/api/v1/vehicle/*")
    addServlet(new SharedMissionController, "/api/v1/mission/*")
    addServlet(new SessionsController, "/api/v1/auth/*")
    addServlet(new OAuthController, "/api/v1/oauth/*")
  }

  override def submit[A](
                 method: String,
                 uri: String,
                 queryParams: Iterable[(String, String)] = Map.empty,
                 headers: Map[String, String] = Map.empty,
                 body: Array[Byte] = null)(f: => A): A = {
    val bodyStr = if(body == null)
      "(null)"
    else
      body.map(_.toChar).mkString

    currentRequest = Some(RequestInfo(method, uri, queryParams, headers, bodyStr))

    super.submit(method, uri, queryParams, headers, body)(f)
  }

  override def afterAll() {
    super.afterAll()

    ThreadTools.catchIgnore {
      activeRecordTables.cleanup
    }
  }

  def bodyGet(uri: String, params: Iterable[(String, String)] = Seq.empty) =
    get(uri, params, headers = jsonHeaders) {
      checkStatusOk()
      body
    }

  def jsonGet(uri: String, params: Iterable[(String, String)] = Seq.empty) =
    parse(bodyGet(uri, params))

  /// Post the req as JSON in the body
  def jsonPost(uri: String, req: AnyRef, headers: Map[String, String] = jsonHeaders) =
    post(uri, toJSON(req), headers) {
      checkStatusOk()
      parse(body)
    }

  def jsonPut(uri: String, req: AnyRef, headers: Map[String, String] = jsonHeaders) =
    put(uri, toJSON(req), headers) {
      checkStatusOk()
      parse(body)
    }

  /// Post as form post and receive json response
  def jsonParamPost(uri: String, params: Iterable[(String, String)], headers: Map[String, String] = commonHeaders) =
    parse(paramPost(uri, params, headers))

  /// Post the request as form params
  def paramPost(uri: String, params: Iterable[(String, String)], headers: Map[String, String] = commonHeaders) =
    post(uri, params, headers) {
      checkStatusOk()
      body
    }

  def checkStatus(expected: Int) {
    if (status != expected) { // If not okay then show the error msg from server
      error(s"While handling request: ${currentRequest.get}")
      error("Unexpected status: " + response.statusLine.message)
      error("Error body: " + response.body)
      //Thread.dumpStack()
    }
    status should equal(expected)
  }

  def checkStatusOk() = checkStatus(200)
  def parseRedirect() = {
    checkStatus(302)
    val dest = response.headers("Location")(0)
    debug("Received redirect resp: " + dest)
    dest
  }

  def toJSON(x: AnyRef) = {
    val r = Serialization.write(x)
    debug(s"Sending $r")
    r.getBytes
  }

  /// Do a session logged in as our test user
  def userSession[A](f: => A): A = session {
    post("/api/v1/session/login", loginInfo, commonHeaders) {
      checkStatusOk()
    }

    f
  }

  def testEasyUpload(params: Map[String, String], payload: BytesPart) {
    // Set the payload
    val vehicleId = UUID.randomUUID.toString

    post(s"/api/v1/mission/upload/$vehicleId", params, Map("payload" -> payload), headers = commonHeaders) {
      // checkStatusOk()
      // status should equal(406) // The tlog we are sending should be considered uninteresting by the server
      info("View URL is " + body)
    }
  }
}

object ServerDependentSuite {
  /**
   * Get test tlog data that can be posted to the server
   */
  lazy val tlogPayload = readLog("test.tlog", APIConstants.tlogMimeType)
  lazy val logPayload = readLog("test.log", APIConstants.flogMimeType)
  lazy val blogPayload = readLog("test.bin", APIConstants.blogMimeType)

  // From resources
  def readLog(name: String, mime: String) = {
    val is = getClass.getResourceAsStream(name)
    val bytes = FileTools.toByteArray(is)
    is.close()
    BytesPart(name, bytes, mime)
  }

  // A blog file on the filesystem
  def filesystemBlog(path: String) = {
    val mime = APIConstants.blogMimeType
    val is = new BufferedInputStream(new FileInputStream(path))
    val bytes = FileTools.toByteArray(is)
    BytesPart("px4.bin", bytes, mime)
  }
}
