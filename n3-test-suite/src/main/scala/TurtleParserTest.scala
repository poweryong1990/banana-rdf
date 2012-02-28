/*
* Copyright (c) 2012 Henry Story
* under the Open Source MIT Licence http://www.opensource.org/licenses/MIT
*/


package org.w3.rdf.n3

import java.io._
import org.w3.rdf._
import nomo.Accumulator
import scala.util.Random
import org.scalatest.matchers.ShouldMatchers
import java.net.URI
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FailureOf, PropSpec}


/**
 * Test parser with official tests from w3c using another turtle parser as a reference point
 *
 * @author bblfish
 * @created 27/02/2012
 */
abstract class TurtleParserTest[Rdf <: RDF, F, E, X, Rdf2 <: RDF](val ops: RDFOperations[Rdf],
                                                     val testedParser: TurtleParser[Rdf, F, E, X, Listener[Rdf]],
                                                     val referenceParser: TurtleReader[Rdf2])
  extends PropSpec with PropertyChecks with ShouldMatchers with FailureOf {

  val morpheus: GraphIsomorphism[Rdf2]

  import ops._

  lazy val prime: Stream[Int] = 2 #:: Stream.from(3).filter(i =>
    prime.takeWhile(j => j * j <= i).forall(i % _ > 0))

  object rdfTransformer extends RDFTransformer[Rdf,Rdf2](ops,referenceParser.ops)

  def randomSz = {
    prime(5+Random.nextInt(248))
  }

  val tstDir = new File(this.getClass.getResource("/www.w3.org/TR/turtle/tests/").toURI)
  val ttlFiles = tstDir.listFiles().filter(_.getName.endsWith(".ttl"))
  val bad= ttlFiles.filter(_.getName.startsWith("bad"))
  val good = ttlFiles.diff(bad).filter(!_.getName.contains("manifest"))

  def parseTurtleFile(testFile: File, base: String="") = {
    implicit def U = new Listener(ops, new URI(base))
    var chunk = ParsedChunk(testedParser.turtleDoc, testedParser.P.annotator(U))
    var inOpen = true
    val in = new FileInputStream(testFile)
    val bytes = new Array[Byte](randomSz)
    info("setting Turtle parser to reading with chunk size of " + bytes.size + " bytes")

    while (inOpen) {
      try {
        val length = in.read(bytes)
        if (length > -1) {
          chunk = chunk.parse(new String(bytes, 0, length, "ASCII"))
        } else inOpen = false
      } catch {
        case e: IOException => inOpen = false
      }
    }
    chunk.parser.result(chunk.acc)
  }

  def isomorphicTest(result: List[Rdf#Triple], referenceResult: Rdf2#Graph) {
    val g = Graph(result)
    val gAsOther = rdfTransformer.transform(g)

    val isomorphic = morpheus.isomorphism(gAsOther, referenceResult)

    if (!isomorphic) {
      info("graphs were not isomorphic - trying to narrow down on problematic statement")

      import referenceParser.ops.graphAsIterable
      val referenceAsSet = graphAsIterable(referenceResult).toIterable.toSet
      info("read ntriples file with " +testedParser+" found "+referenceAsSet.size +" triples")

      if (result.size!=referenceAsSet.size) {
        info("The number of triples read was "+result.size+". The reference number was "+referenceAsSet.size)
        info("But perhaps that is due to there being duplicates. The result size with obvious duplicates removed is "+Set(result:_*).size)
        if (result.size>0) info("the last triple read was "+result.last)
      }

      //test each statement
      //this does not work very well yet. We need a contains method on a graph that works for each implementation
      result.foreach {
        var counter = 0
        triple =>
          counter += 1
          val res = referenceAsSet.contains(rdfTransformer.transformTriple(triple))
          assert(res, "the reference graph does not contain triple no " + counter + ": " + triple)
      }

      assert(false, "graphs were not isomorphic")
    }
  }

  property("the Turtle parser should parse the official NTriples test case") {
    val testFile =  new File(this.getClass.getResource("/www.w3.org/2000/10/rdf-tests/rdfcore/ntriples/test.nt").toURI)
    info("testing file "+testFile.getName + " at "+testFile.getPath)

    val otherReading = referenceParser.read(testFile,"")
    assert(otherReading.isRight === true,referenceParser +" could not read the "+testFile+" returned "+otherReading)

    val result = parseTurtleFile(testFile)

    val res = result.user.queue.toList.map(_.asInstanceOf[Triple])

    isomorphicTest(res, otherReading.right.get)
  }

  property("The Turtle Parser should parse each of the good files") {
    val base: String = "http://www.w3.org/2001/sw/DataAccess/df1/tests/"
    info("all these files are in "+tstDir)
    val res = for(f <- good) yield {
      val resFileName = f.getName.substring(0,f.getName.length()-"ttl".length())+"out"
      val resultFile = new File(f.getParentFile,resFileName)
      info("testing file "+f.getName+" against result in "+resultFile.getName)
      val otherReading = referenceParser.read(resultFile,base)
      failureOf {
        assert(otherReading.isRight === true, referenceParser + " could not read the " + f + " returned " + otherReading)
        val result = parseTurtleFile(f, base)
        val res = result.user.queue.toList.map(_.asInstanceOf[Triple])
        isomorphicTest(res, otherReading.right.get)
      }
    }
    val errs = res.filter(_!= None)

    assert(errs.size==0,"Not all tests passed. See info for details")


  }

  import testedParser.P._
  case class ParsedChunk( val parser: Parser[Unit],
                          val acc: Accumulator[Char, X, Listener[Rdf]] ) {
    def parse(buf: Seq[Char]) = {
      if (!buf.isEmpty) {
        val (tripleParser, newAccu) = parser.feedChunked(buf, acc, buf.size)
        ParsedChunk(tripleParser, newAccu)
      } else {
        this
      }
    }
  }

}