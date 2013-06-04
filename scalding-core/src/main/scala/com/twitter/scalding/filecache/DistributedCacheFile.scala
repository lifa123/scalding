package com.twitter.scalding.filecache

import java.io.File
import java.net.URI
import org.apache.hadoop.conf.Configuration
import com.twitter.algebird.MurmurHash128
import java.nio.ByteBuffer


object URIHasher {
  private[this] final val HashFunc = MurmurHash128(1L)

  private[this] def deSign(b: Byte): Int =
    if (b < 0) b + 0xff else b

  def apply(stringUri: String): String =
    apply(new URI(stringUri))

  /**
   * generates hashes of hdfs URIs using algebird's MurmurHash128
   * @param uri the URI to generate a hash for
   * @return a hex-encoded string of the bytes of the 128 bit hash. The results are zero padded on the left, so
   *         this string will always be 32 characters long.
   */
  def apply(uri: URI): String = {
    val (h1, h2) = HashFunc(uri.toASCIIString)
    val bytes = ByteBuffer.allocate(16).putLong(h1).putLong(h2).array()
    bytes.map(deSign).map("%02x".format(_)).reduceLeft(_ + _) // lifted gently from com.twitter.util.U64
  }
}

object DistributedCacheFile {
  /**
   * Create an object that can be used to register a given URI (representing an hdfs file)
   * that should be added to the DistributedCache.
   *
   * @param uri The fully qualified URI that points to the hdfs file to add
   * @return A DistributedCacheFile that must have its add() method called with the current
   *         Configuration before use.
   */
  def apply(uri: URI)(implicit distCache: DistributedCache): UncachedFile =
    UncachedFile(Right(uri))

  def apply(path: String)(implicit distCache: DistributedCache): UncachedFile =
    UncachedFile(Left(path))

  def symlinkNameFor(uri: URI): String = {
    val hexsum = URIHasher(uri)
    val fileName = new File(uri.toString).getName

    Seq(fileName, hexsum).mkString("-")
  }

  def symlinkedUriFor(sourceUri: URI): URI =
    new URI(sourceUri.getScheme, sourceUri.getSchemeSpecificPart, symlinkNameFor(sourceUri))
}


/**
 * The distributed cache is simply hadoop's method for allowing each node local access to a
 * specific file. The registration of that file must be called with the Configuration of the job,
 * and not when it's on a mapper or reducer. Additionally, a unique name for the node-local access
 * path must be used to prevent collisions in the cluster. This class provides this functionality.
 *
 * In the configuration phase, the file URI is used to construct an UncachedFile instance. The name
 * of the symlink to use on the mappers is only available after calling the add() method, which
 * registers the file and computes the unique symlink name and returns a CachedFile instance.
 * The CachedFile instance is Serializable, it's designed to be assigned to a val and accessed later.
 *
 * The local symlink is available thorugh .file or .path depending on what type you need.
 */
sealed abstract class DistributedCacheFile {
  def isDefined: Boolean

  def add(conf: Configuration): CachedFile
}

// the reason we use an implicit here is that we don't want to concern our users with
// the DistributedCache class, which is a hack for wrapping the actual Hadoop DistributedCache
// object to allow for stubbing during tests.
//
final case class UncachedFile private[scalding] (source: Either[String, URI])(implicit cache: DistributedCache)
    extends DistributedCacheFile {

  import DistributedCacheFile._

  def isDefined = false

  def add(conf: Configuration): CachedFile = {
    cache.createSymlink(conf)

    val sourceUri =
      source match {
        case Left(strPath) => cache.makeQualified(strPath, conf)
        case Right(uri) => cache.makeQualified(uri, conf)
      }

    cache.addCacheFile(symlinkedUriFor(sourceUri), conf)
    CachedFile(sourceUri)
  }
}

final case class CachedFile private[scalding] (sourceUri: URI) extends DistributedCacheFile {

  import DistributedCacheFile._

  def path: String =
    Seq("./", symlinkNameFor(sourceUri)).mkString("")

  def file: File =
    new File(path)

  def isDefined = true
  def add(conf: Configuration) = this
}
