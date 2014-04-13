/**
 * *****************************************************************************
 * Copyright 2013 Kevin Hester
 *
 * See LICENSE.txt for license details.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ****************************************************************************
 */
import sbt._
import Keys._
import org.scalatra.sbt._
import org.scalatra.sbt.PluginKeys._
// import com.github.siasia.PluginKeys._
import com.mojolly.scalate.ScalatePlugin._
import ScalateKeys._
import com.bowlingx.sbt.plugins.Wro4jPlugin._
import Wro4jKeys._
import sbtassembly.Plugin._
import AssemblyKeys._ // put this at the top of the file
// import com.typesafe.sbt.SbtAtmos.{ Atmos, atmosSettings, traceAkka }

object NestorBuild extends Build {
  val Organization = "com.geeksville"
  val Name = "apihub"
  val Version = "0.2.0-SNAPSHOT"
  val ScalatraVersion = "2.3.0-SNAPSHOT"

  val assemblyCustomize = mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
    {
      // Pull all of the jansi classes from the offical dist jar, not jline
      case PathList("org", "fusesource", xs @ _*) => MergeStrategy.first
      case PathList("META-INF", "native", xs @ _*) => MergeStrategy.first
      case PathList("org", "slf4j", xs @ _*) => MergeStrategy.first
      //case "application.conf" => MergeStrategy.concat
      case ".project" => MergeStrategy.discard
      case ".classpath" => MergeStrategy.discard
      case "build.xml" => MergeStrategy.discard
      case "about.html" => MergeStrategy.discard
      case "rootdoc.txt" => MergeStrategy.discard
      case x => old(x)
    }
  }

  lazy val common = Project(id = "gcommon",
    base = file("arduleader/common"))

  lazy val threeAkka = Project(id = "three-akka",
    base = file("3scale-akka"))

  lazy val nestorProject = Project(
    "apihub",
    file("."),
    settings = Defaults.defaultSettings ++ ScalatraPlugin.scalatraWithJRebel ++ assemblySettings ++ scalateSettings ++ wro4jSettings ++ Seq(
      // traceAkka("2.2.3"),
      organization := Organization,
      name := Name,
      version := Version,
      assemblyCustomize,
      resolvers += "Sonatype OSS Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
      resolvers += "Maven snapshots" at "http://download.java.net/maven/2",

      // To include source for Takipi
      unmanagedResourceDirectories in Compile <+= baseDirectory(_ / "src" / "main" / "scala"),
      unmanagedResourceDirectories in Compile <+= baseDirectory(_ / "src" / "main" / "java"),

      // Make "test" command work again per https://groups.google.com/forum/#!topic/scalatra-user/Mkx2lHAqQI0

      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion withSources (),
        "org.scalatra" %% "scalatra-scalate" % ScalatraVersion withSources (),
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test" withSources (),
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion withSources (),
        //"org.scalatra" %% "scalatra-specs2" % ScalatraVersion % "test",
        
        // scala-activerecord support
        "com.github.aselab" %% "scala-activerecord" % "0.2.4-SNAPSHOT" withSources (),
        "com.github.aselab" %% "scala-activerecord-scalatra" % "0.2.4-SNAPSHOT" withSources (),

        "mysql" % "mysql-connector-java" % "5.1.22",

        // Turn off h2 for now
        // "com.h2database" % "h2" % "1.3.170",  // See Supported databases

        "org.mindrot" % "jbcrypt" % "0.3m", // For password encryption

        // For swagger - FIXME, the swagger folks are apparently importing the log4j12 lib, which they should not do - causes multiple 
        // bindings for logging
        "org.scalatra" %% "scalatra-swagger" % ScalatraVersion exclude ("org.slf4j", "slf4j-log4j12"),

        // Important to NOT include this: "org.scalatra" %% "scalatra-json" % "2.2.2",
        // Instead use the json4s standalone version - I have to use force() here because it seems that 3.2.5 or later breaks swagger autodoc generation
        "org.json4s" %% "json4s-native" % "3.2.9-SNAPSHOT",
        "org.json4s" %% "json4s-core" % "3.2.9-SNAPSHOT",
        // We want the version from logback
        // "org.slf4j" % "slf4j-log4j12" % "1.7.5",

        //"ch.qos.logback" % "logback-classic" % "1.0.9" % "runtime",
        "com.novus" %% "salat" % "1.9.5",
        "de.micromata.jak" % "JavaAPIforKml" % "2.2.0-SNAPSHOT",
        
        
        //"org.apache.httpcomponents" % "httpclient" % "4.1" % "test", // We need to force this version of http client to keep newer aws libs working
        //"org.apache.httpcomponents" % "httpcore" % "4.1" % "test",         
        //"com.amazonaws" % "aws-java-sdk" % "1.7.5" exclude ("org.apache.httpcomponents", "httpclient"),
        "com.amazonaws" % "aws-java-sdk" % "1.7.5",
        //"commons-logging" % "commons-logging" % "1.1.3" % "compile;container",    
        "commons-codec" % "commons-codec" % "1.6",
        
        "com.google.code.findbugs" % "jsr305" % "2.0.1",
        "com.google.guava" % "guava" % "14.0-rc2",
        "org.eclipse.jetty" % "jetty-webapp" % "8.1.8.v20121106" % "compile;container"),
        //"org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "compile;container;provided;test" artifacts (Artifact("javax.servlet", "jar", "jar"))),

      /* no longer works?/needed?
      warPostProcess in Compile <<= (target) map {
        (target) => { 
          () =>
          val webapp = target / "webapp"
	  val libs = webapp / "WEB-INF" / "lib"
          val notWar = Seq("javax.servlet-3.0.0.v201112011016.jar", "jetty-webapp-8.1.8.v20121106.jar", 
	    "jetty-server-8.1.8.v20121106.jar", "jetty-servlet-8.1.8.v20121106.jar")
          notWar.foreach { f =>
	  IO.delete(libs / f ) }
        }
      },
*/
      scalateTemplateConfig in Compile <<= (sourceDirectory in Compile) { base =>
        Seq(
          TemplateConfig(
            base / "webapp" / "WEB-INF" / "templates",
            Seq.empty, /* default imports should be added here */
            Seq.empty, /* add extra bindings here */
            Some("templates")))
      } // webappResources in Compile <+= (targetFolder in generateResources in Compile)
      )).dependsOn(common, threeAkka)

  // not yet working .settings(atmosSettings: _*).configs(Atmos)
}




