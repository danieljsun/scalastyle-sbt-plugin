// Copyright (C) 2011-2012 the original author or authors.
// See the LICENCE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scalastyle.sbt

import java.util.Date
import java.util.jar.JarEntry
import java.util.jar.JarFile

import scala.io.Codec

import org.scalastyle.Directory
import org.scalastyle.FileSpec
import org.scalastyle.Message
import org.scalastyle.OutputResult
import org.scalastyle.ScalastyleChecker
import org.scalastyle.ScalastyleConfiguration
import org.scalastyle.TextOutput
import org.scalastyle.XmlOutput

import sbt.Compile
import sbt.ConfigKey.configurationToKey
import sbt.File
import sbt.IO
import sbt.InputKey
import sbt.Keys.scalaSource
import sbt.Keys.streams
import sbt.Keys.target
import sbt.Logger
import sbt.Plugin
import sbt.Project
import sbt.Scoped.t3ToTable3
import sbt.Scoped.t6ToTable6
import sbt.SettingKey
import sbt.TaskKey
import sbt.file
import sbt.inputTask
import sbt.richFile
import sbt.richInitialize

object ScalastylePlugin extends Plugin {
  import PluginKeys._ // scalastyle:ignore import.grouping underscore.import

  val Settings = Seq(
    scalastyle <<= Tasks.scalastyle,
    generateConfig <<= Tasks.generateConfig,
    scalastyleTarget <<= (target).map(_ / "scalastyle-result.xml"),
    config := file("scalastyle-config.xml"),
    failOnError := true)
}

object PluginKeys {
  lazy val scalastyle = InputKey[Unit]("scalastyle")
  lazy val scalastyleTarget = TaskKey[File]("scalastyle-target")
  lazy val config = SettingKey[File]("scalastyle-config")
  lazy val failOnError = SettingKey[Boolean]("scalastyle-fail-on-error")
  lazy val generateConfig = InputKey[Unit]("scalastyle-generate-config")
}

object Tasks {
  import PluginKeys._ // scalastyle:ignore import.grouping underscore.import

  val scalastyle: Project.Initialize[sbt.InputTask[Unit]] = inputTask {
    (_, config, failOnError, scalaSource in Compile, scalastyleTarget, streams) map {
      case (args, config, failOnError, sourceDir, target, streams) => {
        val logger = streams.log
        if (config.exists) {
          val messages = runScalastyle(config, sourceDir)

          saveToXml(messages, target.absolutePath)

          val result = printResults(messages, args.exists(_ == "q"))
          logger.success("created: %s".format(target))

          def onHasErrors(message: String): Unit = {
            if (failOnError) {
              error(message)
            } else {
              logger.error(message)
            }
          }

          if (result.errors > 0) {
            onHasErrors("exists error")
          } else if (args.exists(_ == "w") && result.warnings > 0) {
            onHasErrors("exists warning")
          }
        } else {
          sys.error("not exists: %s".format(config))
        }
      }
    }
  }

  private[this] def runScalastyle(config: File, sourceDir: File) = {
    val configuration = ScalastyleConfiguration.readFromXml(config.absolutePath)
    new ScalastyleChecker().checkFiles(configuration, Directory.getFiles(None, List(sourceDir)))
  }

  private[this] def printResults(messages: List[Message[FileSpec]], quiet: Boolean = false): OutputResult = {
    def now: Long = new Date().getTime
    val start = now
    val outputResult = new TextOutput().output(messages)
    // scalastyle:off regex
    if (!quiet) {
      println("Processed " + outputResult.files + " file(s)")
      println("Found " + outputResult.errors + " errors")
      println("Found " + outputResult.warnings + " warnings")
      println("Finished in " + (now - start) + " ms")
    }
    // scalastyle:on regex

    outputResult
  }

  private[this] def saveToXml(messages: List[Message[FileSpec]], path: String)(implicit codec: Codec): Unit = {
    XmlOutput.save(path, codec.charSet.toString, messages)
  }

  val generateConfig: Project.Initialize[sbt.InputTask[Unit]] = inputTask {
    (_, config, streams) map {
      case (args, to, streams) =>
        getFileFromJar(getClass.getResource("/scalastyle-config.xml"), to.absolutePath, streams.log)
    }
  }

  private[this] implicit def enumToIterator[A](e: java.util.Enumeration[A]): Iterator[A] = new Iterator[A] {
    def next: A = e.nextElement
    def hasNext: Boolean = e.hasMoreElements
  }

  private[this] def getFileFromJar(url: java.net.URL, destination: String, logger: Logger): Unit = {
    def createFile(jarFile: JarFile, e: JarEntry): Unit = {
      val target = file(destination)

      if (safeToCreateFile(target)) {
        IO.transfer(jarFile.getInputStream(e), target)
        logger.success("created: " + target)
      }
    }

    url.openConnection match {
      case connection: java.net.JarURLConnection => {
        val entryName = connection.getEntryName
        val jarFile = connection.getJarFile

        jarFile.entries.filter(_.getName == entryName).foreach { createFile(jarFile, _) }
      }
      case _ => // nothing
    }
  }

  private[this] def safeToCreateFile(file: File): Boolean = {
    def askUser: Boolean = {
      val question = "The file %s exists, do you want to overwrite it? (y/n): ".format(file.getPath)
      scala.Console.readLine(question).toLowerCase.headOption match {
        case Some('y') => true
        case Some('n') => false
        case _ => askUser
      }
    }

    if (file.exists) askUser else true
  }
}
