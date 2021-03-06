package com.geeksville.dapi

import com.geeksville.flight.Waypoint
import java.io.ByteArrayInputStream
import com.geeksville.dataflash.DFReader
import com.geeksville.util.MathTools
import scala.io.Source
import com.geeksville.dataflash.DFMessage
import org.mavlink.messages.ardupilotmega._
import scala.collection.mutable.HashMap
import scala.collection.mutable.ArrayBuffer
import com.geeksville.flight.Location
import org.mavlink.messages.MAV_TYPE
import org.mavlink.messages.MAV_AUTOPILOT
import java.util.Date
import com.geeksville.mavlink.{ErrorCode, TimestampedAbstractMessage}
import java.io.InputStream
import com.geeksville.util.Using._

class DataflashPlaybackModel(val defaultTime: Long) extends PlaybackModel {
  /// A MAV_TYPE vehicle code
  override def vehicleType: Option[Int] =
    buildName.flatMap {
      case "ArduPlane" => Some(MAV_TYPE.MAV_TYPE_FIXED_WING)
      case "ArduCopter" => Some(MAV_TYPE.MAV_TYPE_QUADROTOR)
      case "ArduRover2" => Some(MAV_TYPE.MAV_TYPE_GROUND_ROVER)
      case _ => None
    }

  val modeChanges: ArrayBuffer[(Long, String)] = ArrayBuffer.empty

  val errors: ArrayBuffer[(Long, ErrorCode)] = ArrayBuffer.empty

  val positions: ArrayBuffer[TimestampedLocation] = ArrayBuffer.empty

  val waypoints: ArrayBuffer[Waypoint] = ArrayBuffer.empty

  private val params = HashMap[String, ROParamValue]()

  private var dfMessages: Iterable[DFMessage] = Iterable.empty
  private var numMessages = 0L

  def parameters = params.values

  override def modelType = "Dataflash"

  override def numRecords: Long = numMessages

  // This is our only way of finding the autopilot type without heartbeat msgs
  override def autopilotType = hardwareToAutopilotType

  /// The messages array can be _very_ large and is rarely used, so we reload it as needed by calling loadMessages
  override def abstractMessages = loadMessages(dfMessages)

  // FIXME - these should be hidden inside the loadMessages closure
  private var baseUsec = defaultTime * 1000L
  private var gpsOffsetUsec = 0L
  def nowUsec = gpsOffsetUsec + baseUsec

  private def setStartOfFlight(nowUsec: Long) {
    if (!startTime.isDefined) {
      warn(s"Setting start to $nowUsec / " + (new Date(nowUsec / 1000)))
      startTime = Some(nowUsec)
      startOfFlightTime = Some(nowUsec) // FIXME - not quite correct - should check for flying (like we do with tlogs)
    }
  }

  private def loadMessages(messages: Iterable[DFMessage]) = {
    import DFMessage._


    debug(s"Decoding dataflash messages")

    dfMessages = messages // keep this around in case someone calls abstractMessages
    numMessages = 0L

    // It is important to use a view here so we don't build up a HUGE list of asbtract message results
    val abstractMsgs = messages.view.map { m =>

      def dumpMessage() = debug(s"Considering $m")

      def updateTime(newUsec: Long) {
        baseUsec = newUsec
        currentTime = Some(nowUsec)
        endOfFlightTime = Some(nowUsec) // FIXME - not quite correct - should check for flying (like we do with tlogs)
      }

      numMessages += 1

      // dumpMessage()
      m.messageType match {
        case TIME =>
          m.startTimeOpt.foreach { t =>
            updateTime(t)
          }

        case GPS =>
          // dumpMessage()

          // Cancel out the last known msec offset, using GPS time as the new zero
          m.gpsTimeUsec.foreach { t =>
            // If this GPS msg included a msec timestamp update our offset
            m.tOpt.foreach { ms => updateTime(ms * 1000) }
            gpsOffsetUsec = t - baseUsec
            setStartOfFlight(nowUsec)
          }

          for {
            lat <- m.latOpt
            lon <- m.lngOpt
          } yield {
            val loc = Location(lat, lon, m.altOpt)
            if (loc.isValid) {
              m.altOpt.foreach { a =>
                maxAltitude = MathTools.saneMax(maxAltitude, a)
              }
              m.spdOpt.foreach { a =>
                maxGroundSpeed = MathTools.saneMax(maxGroundSpeed, a)
              }

              val tm = TimestampedLocation(nowUsec, loc)
              //debug(s"Adding location $tm")
              positions.append(tm)
              endPosition = Some(loc)
            }
          }

        case NTUN =>
          m.arspdOpt.foreach { a =>
            maxAirSpeed = math.max(maxAirSpeed, a)
          }

        case CMD =>
          //dumpMessage()
          for {
            // TimeMS,CTot,CNum,CId,Prm1,Prm2,Prm3,Prm4,Lat,Lng,Alt
            // 11725, 5, 1, 16, 0.000000, 0.000000, 0.000000, 0.000000, 39.98196, -87.85937, 80.00000
            ctot <- m.ctotOpt
            cnum <- m.cnumOpt

            cid <- m.cidOpt

            prm1 <- m.prm1Opt
            prm2 <- m.prm2Opt
            prm3 <- m.prm3Opt
            prm4 <- m.prm4Opt
            lat <- m.latOpt
            lon <- m.lngOpt
            alt <- m.altOpt
          } yield {
            val msg = new msg_mission_item(0, 0)
            msg.param1 = prm1.toFloat
            msg.param2 = prm2.toFloat
            msg.param3 = prm3.toFloat
            msg.param4 = prm4.toFloat
            msg.seq = cnum.toInt
            msg.command = cid.toInt
            msg.x = lat.toFloat
            msg.y = lon.toFloat
            msg.z = alt.toFloat
            val w = new Waypoint(msg)

            // We might have multiple wpt mentions in the log file - just replace old defs with new ones based on seq #
            //debug(s"Adding $w")
            val seq = msg.seq
            while (waypoints.size < seq + 1)
              waypoints.append(w)
            waypoints(seq) = w
          }

        case IMU =>
          m.timeMSopt.foreach { ms => updateTime(ms * 1000) }

        case ATT =>
          m.timeMSopt.foreach { ms => updateTime(ms * 1000) }

        case MODE =>
          dumpMessage()
          modeChanges.append(nowUsec -> m.mode)
        case ERR =>
          dumpMessage()
          errors.append(nowUsec -> ErrorCode(m))

        case MSG =>
          //dumpMessage()
          filterMessage(m.message)

        case VER => // A PX4 style version record
          buildName = m.archOpt
          buildGit = m.fwGitOpt

        case PARM =>
          //dumpMessage()

          val msg = new msg_param_value(0, 0) // FIXME - params shouldn't assume mavlink msgs, but for now...
          val name = m.name
          msg.setParam_id(name)
          msg.param_value = m.value.toFloat
          msg.param_type = MAVLINK_TYPE_FLOAT // Dataflash logs always store params as floats
          params(name) = new ROParamValue(msg)

        case _ =>
        // Ignore
      }

      TimestampedAbstractMessage(nowUsec, m)
    }

    abstractMsgs
  }

  /**
   * Load messages from a raw dflash file
   */
  private def loadBytes(bytes: Array[Byte], isTextFormat: Boolean) {

    val reader = new DFReader
    warn(s"Parsing dataflash text=$isTextFormat")
    if (isTextFormat)
      reader.parseText(Source.fromRawBytes(bytes))
    else
      reader.parseBinary(bytes)
    loadMessages(reader.messages).foreach { m => } // FIXME - crufty way to make sure we walk through all msgs

    // If we never found a GPS timestamp, just assume server time
    setStartOfFlight(nowUsec)
  }

  /**
   * Load messages from a raw dflash file (we will close the input stream)
   * (disabled until we support rewind on these streams)
   * private def loadBytes(isin: InputStream, isTextFormat: Boolean) {
   * using(isin) { is =>
   * val reader = new DFReader
   * warn(s"Parsing dataflash text=$isTextFormat")
   * if (isTextFormat)
   * reader.parseText(Source.fromInputStream(is))
   * else
   * reader.parseBinary(is)
   * loadMessages(reader.messages)
   * }
   * }
   */
}

object DataflashPlaybackModel {
  /**
   * Fully populate a model from bytes, or return None if bytes not available
   */
  def fromBytes(b: Array[Byte], isTextFormat: Boolean, defaultTime: Long = System.currentTimeMillis) = {
    val model = new DataflashPlaybackModel(defaultTime)
    model.loadBytes(b, isTextFormat)
    model
  }

  /*
  def fromInputStream(b: InputStream, isTextFormat: Boolean, defaultTime: Long = System.currentTimeMillis) = {
    val model = new DataflashPlaybackModel(defaultTime)
    model.loadBytes(b, isTextFormat)
    model
  } */
}
