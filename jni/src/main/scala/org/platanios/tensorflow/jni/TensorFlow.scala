/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.tensorflow.jni

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import java.io.{IOException, InputStream}
import java.nio.file.{Files, Path, StandardCopyOption}

import scala.collection.JavaConverters._

/**
  * @author Emmanouil Antonios Platanios
  */
object TensorFlow {
  final class NativeException(message: String) extends RuntimeException(message)

  private[this] val logger: Logger = Logger(LoggerFactory.getLogger("TensorFlow Native"))

  /** TensorFlow native library name. */
  private[this] val LIB_NAME: String = "tensorflow"

  /** TensorFlow JNI bindings library name. */
  private[this] val JNI_LIB_NAME: String = "tensorflow_jni"

  // /** TensorFlow ops library name. */
  // private[this] val OPS_LIB_NAME: String = "tensorflow_ops"

  /** Current platform operating system. */
  private[this] val os = {
    val name = System.getProperty("os.name").toLowerCase
    if (name.contains("linux")) "linux"
    else if (name.contains("os x") || name.contains("darwin")) "darwin"
    else if (name.contains("windows")) "windows"
    else name.replaceAll("\\s", "")
  }

  /** Current platform architecture. */
  private[this] val architecture = {
    val arch = System.getProperty("os.arch").toLowerCase
    if (arch == "amd64") "x86_64"
    else arch
  }

  /** Loads the TensorFlow JNI bindings library along with the TensorFlow native library, if provided as a resource. */
  def load(): Unit = {
    // If either:
    // (1) The native library has already been statically loaded, or
    // (2) The required native code has been statically linked (through a custom launcher), or
    // (3) The native code is part of another library (such as an application-level library) that has already been
    //     loaded (for example, tensorflow/examples/android and tensorflow/contrib/android include the required native
    //     code in differently named libraries).
    // then it seems that the native library has already been loaded and there is nothing else to do.
    if (!checkIfLoaded() && !tryLoadLibrary()) {
      // Native code is not present, perhaps it has been packaged into the JAR file containing this code.
      val tempDirectory = Files.createTempDirectory("tensorflow_scala_native_libraries")
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run(): Unit = {
          Files.walk(tempDirectory).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists)
        }
      })
      val classLoader = Thread.currentThread.getContextClassLoader

      // Check if a TensorFlow native library resource is provided and load it.
      val libResourceStream = Option(classLoader.getResourceAsStream(makeResourceName(LIB_NAME)))
      libResourceStream.map(extractResource(LIB_NAME, _, tempDirectory)).foreach(path => {
        try {
          System.load(path.toAbsolutePath.toString)
        } catch {
          case exception: IOException => throw new UnsatisfiedLinkError(
            s"Unable to load the TensorFlow native library from the extracted file: ${exception.getMessage}.")
        }
      })

      // Load the TensorFlow JNI bindings from the appropriate resource.
      val jniResourceStream = Option(classLoader.getResourceAsStream(makeResourceName(JNI_LIB_NAME)))
      val jniPath = jniResourceStream.map(extractResource(JNI_LIB_NAME, _, tempDirectory))
      if (jniPath.isEmpty)
        throw new UnsatisfiedLinkError(
          s"Cannot find the TensorFlow JNI bindings for OS: $os, and architecture: $architecture. See " +
              "https://github.com/eaplatanios/tensorflow_scala/tree/master/README.md for possible solutions " +
              "(such as building the library from source).")
      jniPath.foreach(path => {
        try {
          System.load(path.toAbsolutePath.toString)
        } catch {
          case exception: IOException => throw new UnsatisfiedLinkError(
            "Unable to load the TensorFlow JNI bindings from the extracted file. This could be due to the TensorFlow " +
                s"native library not being available. Error: ${exception.getMessage}.")
        }
      })

      // Load the TensorFlow ops library from the appropriate resource.
      // val opsResourceStream = Option(classLoader.getResourceAsStream(makeResourceName(OPS_LIB_NAME)))
      // val opsPath = opsResourceStream.map(extractResource(OPS_LIB_NAME, _, tempDirectory))
      // TODO: !!! For some reason this can be called twice.
      // opsPath.foreach(path => loadOpLibrary(path.toAbsolutePath.toString))
    }
  }

  /** Tries to load the TensorFlow JNI bindings library using the system library loader (i.e., not from a resource). */
  private[this] def tryLoadLibrary() = {
    try {
      System.loadLibrary(JNI_LIB_NAME)
      true
    } catch {
      case exception: UnsatisfiedLinkError =>
        logger.info(
          s"Failed to load the TensorFlow native library with error: ${exception.getMessage}. " +
              s"Attempting to load it as a resource.")
        false
    }
  }

  /** Checks if the TensorFlow JNI bindings library has been loaded. */
  private[this] def checkIfLoaded(): Boolean = {
    try {
      TensorFlow.version
      true
    } catch {
      case _: UnsatisfiedLinkError => false
    }
  }

  /** Maps the provided library name to a filename, similar to [[System.mapLibraryName]], but with the only difference
    * being that for the main TensorFlow native library only, the `.so` extension is used for Mac OS X shared libraries,
    * as opposed to the `.dylib` extension. */
  private def mapLibraryName(lib: String): String = {
    var name = System.mapLibraryName(lib)
    if (lib == LIB_NAME && os == "darwin" && name.endsWith(".dylib"))
      name = name.substring(0, name.lastIndexOf(".dylib")) + ".so"
    name
  }

  /** Generates the resource name (including the path) for the specified library. */
  private def makeResourceName(lib: String): String = {
    if (lib == LIB_NAME)
      mapLibraryName(lib)
    else
      s"native/$os-$architecture/${mapLibraryName(lib)}"
  }

  /** Extracts the resource provided by `inputStream` to `directory`. The filename is the mapped library name for the
    * `lib` library. Returns a path pointing to the extracted file. */
  private def extractResource(lib: String, resourceStream: InputStream, directory: Path): Path = {
    val sampleFilename = mapLibraryName(lib)
    val filePath = directory.resolve(sampleFilename)
    logger.info(s"Extracting the '$lib' native library to ${filePath.toAbsolutePath}.")
    try {
      val numBytes = Files.copy(resourceStream, filePath, StandardCopyOption.REPLACE_EXISTING)
      logger.info(String.format(s"Copied $numBytes bytes to ${filePath.toAbsolutePath}."))
    } catch {
      case exception: Exception =>
        throw new UnsatisfiedLinkError(s"Error while extracting the '$lib' native library: $exception")
    }
    filePath
  }

  load()

  lazy val currentJvmPointer: String = jvmPointer
  lazy val currentCallbackRegistryPointer: String = callbackRegistryPointer
  lazy val currentCallbackRegistryCallMethodPointer: String = callbackRegistryCallMethodPointer

  @native private[this] def jvmPointer: String
  @native private[this] def callbackRegistryPointer: String
  @native private[this] def callbackRegistryCallMethodPointer: String
  @native def version: String
  @native def dataTypeSize(dataTypeCValue: Int): Int
  @native def loadOpLibrary(libraryPath: String): Array[Byte]

  // //region Internal API
  //
  // @native private[tensorflow] def updateInput(
  //     graphHandle: Long, inputOpHandle: Long, inputIndex: Int, outputOpHandle: Long, outputIndex: Int): Unit
  // @native private[tensorflow] def addControlInput(graphHandle: Long, opHandle: Long, inputOpHandle: Long): Int
  // @native private[tensorflow] def clearControlInputs(graphHandle: Long, opHandle: Long): Int
  //
  // //endregion Internal API
}
