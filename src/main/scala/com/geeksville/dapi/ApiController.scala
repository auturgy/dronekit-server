package com.geeksville.dapi

import org.scalatra._
import org.scalatra.swagger.SwaggerSupport
import org.json4s.{MappingException, DefaultFormats, Formats, Extraction}
import org.scalatra.json._
import org.scalatra.swagger.Swagger
import com.geeksville.util.URLUtil
import com.geeksville.dapi.model.User
import com.geeksville.dapi.model.CRUDOperations
import com.geeksville.json.GeeksvilleFormats
import javax.servlet.http.HttpServletRequest
import org.json4s.JsonAST.JObject
import javax.servlet.http.HttpServletResponse
import java.util.Date
import org.json4s.JsonAST.JValue
import grizzled.slf4j.Logging

case class LogicalBoolean(colName: String, opcode: String, cmpValue: String)

/**
 * A base class for REST endpoints that contain various fields
 *
 * Subclasses can call roField etc... to specify handlers for particular operations
 * T is the primary type
 * JsonT is the type used when serializing as JSON over the wire (might be different)
 */
class ApiController[T <: Product: Manifest, JsonT <: Product: Manifest](val aName: String, val swagger: Swagger, val companion: CRUDOperations[T]) extends DroneHubStack with CorsSupport with SwaggerSupport {

  // This override is necessary for the swagger docgen to make correct paths
  override protected val applicationName = Some("api/v1/" + aName)

  protected lazy val applicationDescription = s"The $aName API. It exposes operations for browsing and searching lists of $aName, and retrieving single $aName."

  /// Utility glue to make easy documentation boilerplate
  def aNames = aName + "s"
  def aCamel = URLUtil.capitalize(aName)
  def aCamels = aCamel + "s"

  // Before every action runs, set the content type to be in JSON format.
  before() {
    contentType = formats("json")
    applyNoCache(response)
  }

  protected def requireReadAllAccess() = {
    requireServiceAuth(aName + "/read")
  }

  /**
   * Filter read access to a potentially protected record.  Subclasses can override if they want to restrict reads based on user or object
   * If not allowed, override should call haltUnauthorized()
   */
  protected final def requireReadAccess(o: T) = {
    requireServiceAuth(aName + "/read")

    filterForReadAccess(o, true).getOrElse(haltUnauthorized("You can not access this record"))
    // Since we have an absolute record ID, we assume the caller has the URL somehow
  }

  /**
   * Return a view of this object that is appropriate for read access viewing.  Or None if no access should be allowed
   * @param isSharedLink is true if we know for sure that the URL came from a particular URL (for semi-private sharing, otherwise false)
   */
  protected def filterForReadAccess(o: T, isSharedLink: Boolean = false): Option[T] =
    Some(o)

  /// Subclasses can overide to limit access for creating new records
  protected def requireCreateAccess() = {
    requireServiceAuth(aName + "/create")
  }

  /**
   * Filter read access to a potentially protected record.  Subclasses can override if they want to restrict reads based on user or object
   * If not allowed, override should call haltUnauthorized()
   */
  protected def requireWriteAccess(o: T): T = {
    requireServiceAuth(aName + "/update")
    o
  }

  protected def requireDeleteAccess(o: T): T = {
    requireWriteAccess(o) // Not quite correct but rare
  }

  /**
   * Throws unauthorized if not owned by the current user (or user is an admin)
   */
  protected def requireBeOwnerOrAdmin(ownerId: Long) {
    val u = tryLogin()
    val isOwner = u.map(_.id == ownerId).getOrElse(false)
    val isAdmin = u.map(_.isAdmin).getOrElse(false)

    if (!isOwner && !isAdmin)
      haltUnauthorized("You do not own this record")
  }

  /**
   * Check if the specified owned resource can be accessed by the current user given an AccessCode
   */
  protected def isAccessAllowed(ownerId: Long, privacyCode: Int, defaultPrivacy: Int, isSharedLink: Boolean) = {
    val u = tryLogin()
    val isOwner = u.map(_.id == ownerId).getOrElse(false)
    val isResearcher = u.map(_.isResearcher).getOrElse(false)
    val isAdmin = u.map(_.isAdmin).getOrElse(false)

    ApiController.isAccessAllowed(privacyCode, isOwner || isAdmin, isResearcher, defaultPrivacy, isSharedLink)
  }

  private def requireAccessCode(ownerId: Long, privacyCode: Int, defaultPrivacy: Int, isSharedLink: Boolean) {
    if (!isAccessAllowed(ownerId, privacyCode, defaultPrivacy, isSharedLink))
      haltUnauthorized("You do not have adequate permissions")
  }

  /// Generate a ro attribute on this rest endpoint of the form /:id/name.
  /// call getter as needed
  /// FIXME - move this great utility somewhere else
  def roField[R: Manifest](name: String)(getter: T => R) {
    val getInfo =
      (apiOperation[R]("get" + URLUtil.capitalize(name))
        summary s"Get the $name for the specified $aName"
        parameters (
          pathParam[String]("id").description(s"Id of $aName to be read")))

    get("/:id/" + name, operation(getInfo)) {
      getter(findById)
    }
  }

  /**
   * FIXME - remove after release 1 - temp hack to allow kmz/tlog fetches to not need API keys
   */
  def unsafeROField[R: Manifest](name: String)(getter: T => R) {
    val getInfo =
      (apiOperation[R]("get" + URLUtil.capitalize(name))
        summary s"Get the $name for the specified $aName"
        parameters (
          pathParam[String]("id").description(s"Id of $aName to be read")))

    get("/:id/" + name, operation(getInfo)) {
      getter(unprotectedFindById)
    }
  }

  /// Generate a wo attribute on this rest endpoint of the form /:id/name.
  /// call getter and setter as needed
  /// FIXME - move this great utility somewhere else
  def woField[R: Manifest](name: String, setter: (T, R) => Any) {
    val putInfo =
      (apiOperation[String]("set" + URLUtil.capitalize(name))
        summary s"Set the $name on specified $aName"
        parameters (
          bodyParam[R],
          pathParam[String]("id").description(s"Id of $aName to be changed"),
          bodyParam[R](name).description(s"New value for the $name")))

    put("/:id/" + name, operation(putInfo)) {
      val o = findById
      requireWriteAccess(o)

      setter(o, parsedBodyAs[R])
    }
  }

  private lazy val createDynamicallyOp =
    (apiOperation[String]("createDynamically")
      summary "Create a new object with a dynamically constructed ID"
      parameters (
        bodyParam[JsonT]))

  put("/", operation(createDynamicallyOp)) {
    requireCreateAccess()

    val json = bodyAsJSON
    val o = createDynamically(json)
    // Update the created object based on any payload that was provided
    updateObject(o, json)
  }

  /// Subclasses can provide suitable behavior if they want to allow PUTs to / to result in creating new objects.  implementations should return the new ID
  protected def createDynamically(payload: JObject): T = {
    haltMethodNotAllowed("creation without IDs not allowed")
  }

  /// Subclasses can provide suitable behavior if they want to allow DELs to /:id to result in deleting objects
  protected def doDelete(o: T): Any = {
    haltMethodNotAllowed("deletion not allowed")
  }

  /// Generate an append only attribute on this rest endpoint of the form /:id/name.
  def aoField[R: Manifest](name: String, appender: (T, R) => Any) {
    val postInfo =
      (apiOperation[String]("add" + URLUtil.capitalize(name))
        summary s"Set the $name on specified $aName"
        parameters (
          pathParam[String]("id").description(s"Id of $aName to be appended"),
          bodyParam[R](name).description(s"New value for the $name")))

    post("/:id/" + name, operation(postInfo)) {
      val o = findById
      requireWriteAccess(o)

      appender(o, parsedBodyAs[R])
    }
  }

  /// Generate a rw attribute on this rest endpoint of the form /:id/name.
  /// call getter and setter as needed
  def rwField[R: Manifest](name: String, getter: T => R, setter: (T, R) => Unit) {
    roField(name)(getter)
    woField(name, setter)
  }

  /// Read an appendable field
  def raField[R: Manifest](name: String, getter: T => List[R], appender: (T, R) => Unit) {
    roField(name)(getter)
    aoField(name, appender)
  }

  protected def getOp =
    (apiOperation[List[JsonT]]("get")
      summary s"Show all $aNames"
      notes s"Shows all the $aNames. You can search it too."
      parameters (
        queryParam[Option[Int]]("page_offset").description("If paging, the record # to start with (use 0 at start)"),
        queryParam[Option[Int]]("page_size").description("If paging, the # of records in the page"),
        queryParam[Option[String]]("order_by").description("To get sorted response, the field name to sort on"),
        queryParam[Option[String]]("order_dir").description("If sorting, the optional direction.  either asc or desc")))

  /*
   * Retrieve a list of instances
   */
  get("/", operation(getOp)) {
    //dumpRequest()
    requireReadAllAccess()

    var offset = params.get("page_offset").map(_.toInt)
    val pagesize = params.get("page_size").map(_.toInt)
    val numdesired = pagesize

    // We do the json conversion here - so that it happens inside of our try/catch block

    var results = Seq[JValue]()
    var needMore = false

    // We might need to do a series of queries if all the data is getting filtered by permissions
    // FIXME - probably better to add the filter rules to the sql expression
    do {
      val unfiltered = getAll(offset, pagesize)

      val filtered = unfiltered.flatMap { m =>
        try {
          filterForReadAccess(m, false).map(toJSON)
        } catch {
          case ex: Exception =>
            error(s"getall error on record - skipping: $ex")
            None
        }
      }

      // Adjust for new offset
      offset = Some(offset.getOrElse(0) + unfiltered.size)

      // If the user wanted a particular number of records and  we deleted some items we need to keep working
      needMore = numdesired.isDefined && (filtered.size < unfiltered.size)
      if (needMore)
        debug(s"need extra read offset=$offset, pagesize=$pagesize")

      // Keep only what we need
      results ++= (if (numdesired.isDefined)
        filtered.take(numdesired.get - results.size)
      else
        filtered)

    } while (needMore)

    // We convert each record indivually so that if we barf while generating JSON we can at least make the others (i.e. don't let
    // a single bad flight break the mission list.
    toJSON(results)
  }

  /// A regex that matches fieldname[NE] or fieldname[EQ] etc...
  val OpRegex = """(\S+)\[(\w+)\]""".r

  /**
   * Using the current query parameters, return all matching records (paging and ordering is supported as well.
   *
   * NOTE: These are raw records - unfiltered by user permissions
   */
  final protected def getAll(pageOffset: Option[Int] = None, pagesizeOpt: Option[Int] = None): Iterable[T] = {
    val offset = pageOffset.orElse(params.get("page_offset").map(_.toInt))
    val pagesize = pagesizeOpt.orElse(params.get("page_size").map(_.toInt))

    val fieldPrefix = "field_"
    val filters = params.filterKeys(_.startsWith(fieldPrefix)).map {
      case (k, v) =>
        var fieldName = k.substring(fieldPrefix.length)
        var opcode = "EQ"

        fieldName match {
          case OpRegex(name, op) =>
            fieldName = name
            opcode = op
          case _ =>
          // assume that everything was in the fieldname
        }
        LogicalBoolean(fieldName, opcode.toUpperCase, v)
    }
    getWithQuery(offset, pagesize, params.get("order_by"), params.get("order_dir"), filters)
  }

  protected def getWithQuery(pageOffset: Option[Int] = None, pagesizeOpt: Option[Int] = None,
    orderBy: Option[String] = None,
    orderDir: Option[String] = None,
    whereExp: Iterable[LogicalBoolean] = Iterable.empty): Iterable[T] = {
    haltMethodNotAllowed("This endpoint does not support this operation")
  }

  private lazy val findByIdOp =
    (apiOperation[JsonT]("findById")
      summary "Find by id"
      parameters (
        pathParam[String]("id").description(s"Id of $aName that needs to be fetched")))

  /**
   * Find an object
   */
  get("/:id", operation(findByIdOp)) {
    //dumpRequest()
    toSingletonJSON(findById)
  }

  /**
   * This is _only_ used in the case of a direct get of a record.  Subclasses might override to provide extra information in that case (as opposed to the
   * getAll function that might be briefer)
   */
  protected def toSingletonJSON(o: T): JValue = toJSON(o)

  /**
   * Get the object associated with the provided id param (or fatally end the request with a 404)
   */
  protected def findById(implicit request: HttpServletRequest) = {
    requireReadAccess(unprotectedFindById(request))
  }

  /**
   * This accessor does not confirm that the user has read access
   */
  protected def unprotectedFindById(implicit request: HttpServletRequest) = {
    val id = params("id")
    companion.find(id).getOrElse(haltNotFound(s"$id not found"))
  }

  private lazy val createByIdOp =
    (apiOperation[String]("createById")
      summary "Create by id"
      parameters (
        bodyParam[JsonT],
        pathParam[String]("id").description(s"Id of $aName that needs to be created")))

  post("/:id", operation(createByIdOp)) {
    haltMethodNotAllowed("This endpoint does not support this operation")
  }

  private lazy val updateByIdOp =
    (apiOperation[String]("uodateById")
      summary "Update by id"
      parameters (
        bodyParam[JsonT],
        pathParam[String]("id").description(s"Id of $aName that needs to be updated")))

  put("/:id", operation(updateByIdOp)) {
    val o = findById
    requireWriteAccess(o)
    updateObject(o, bodyAsJSON)
  }

  /// Subclasses can provide suitable behavior if they want to allow PUTs to /:id to result in updating objects.
  /// Implementations should return the updated object
  protected def updateObject(o: T, payload: JObject): T = {
    haltMethodNotAllowed("update by ID not allowed")
  }

  private lazy val deleteByIdOp =
    (apiOperation[String]("deleteById")
      summary "Delete by id"
      parameters (
        pathParam[String]("id").description(s"Id of $aName that needs to be deleted")))

  delete("/:id", operation(deleteByIdOp)) {
    val o = findById
    requireDeleteAccess(o)
    doDelete(o)
  }
}

object ApiController extends Logging {
  /**
   * Does the user have appropriate access to see the specified AccessCode?
   * @param isSharedLink if true we are resolving a _specific_ URL which was passed between users (so not something like a google
   * search or top level map view)
   */
  def isAccessAllowed(requiredIn: Int, isOwner: Boolean, isResearcher: Boolean, default: Int, isSharedLink: Boolean) = {
    val required = if (requiredIn == AccessCode.DEFAULT_VALUE) {
      //debug(s"Checking for access, default sharing so using ${AccessCode.valueOf(default)}")
      default
    } else {
      //debug(s"Checking for access, using ${AccessCode.valueOf(requiredIn)}")
      requiredIn
    }

    required match {
      case AccessCode.DEFAULT_VALUE =>
        throw new Exception("Bug: Can't check against default access code")
      case AccessCode.PRIVATE_VALUE =>
        isOwner
      case AccessCode.PUBLIC_VALUE =>
        true
      case AccessCode.SHARED_VALUE =>
        isSharedLink || isOwner
      case AccessCode.RESEARCHER_VALUE =>
        isOwner || isResearcher
    }
  }

  val defaultVehicleViewAccess = AccessCode.PUBLIC_VALUE
  val defaultVehicleControlAccess = AccessCode.PRIVATE_VALUE
}
